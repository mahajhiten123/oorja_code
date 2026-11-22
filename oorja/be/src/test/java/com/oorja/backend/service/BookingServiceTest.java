package com.oorja.backend.service;

import com.oorja.backend.dto.request.CancelBookingRequest;
import com.oorja.backend.dto.request.CreateBookingRequest;
import com.oorja.backend.dto.request.UpdateBookingRequest;
import com.oorja.backend.dto.response.BookingResponse;
import com.oorja.backend.entity.Booking;
import com.oorja.backend.entity.Customer;
import com.oorja.backend.entity.Payment;
import com.oorja.backend.entity.Plant;
import com.oorja.backend.entity.Refund;
import com.oorja.backend.entity.Technician;
import com.oorja.backend.entity.enums.BookingStatus;
import com.oorja.backend.entity.enums.RefundReasonCode;
import com.oorja.backend.entity.enums.SubjectType;
import com.oorja.backend.exception.ApiException;
import com.oorja.backend.repository.BookingAttachmentRepository;
import com.oorja.backend.repository.BookingRepository;
import com.oorja.backend.repository.CustomerRepository;
import com.oorja.backend.repository.PlantRepository;
import com.oorja.backend.repository.RefundRepository;
import com.oorja.backend.repository.TechnicianRepository;
import com.oorja.backend.storage.StorageProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link BookingService}: every collaborator is mocked, so these pin down the
 * service's own decisions (ownership, state machine, seat accounting, refund hand-off).
 * Real-database behaviour is covered by the *IT classes.
 */
@ExtendWith(MockitoExtension.class)
class BookingServiceTest {

    @Mock BookingRepository bookingRepository;
    @Mock CustomerRepository customerRepository;
    @Mock PlantRepository plantRepository;
    @Mock TechnicianRepository technicianRepository;
    @Mock BookingAttachmentRepository bookingAttachmentRepository;
    @Mock CodeGeneratorService codeGeneratorService;
    @Mock PlantService plantService;
    @Mock StorageProvider storageProvider;
    @Mock QuotationService quotationService;
    @Mock RefundService refundService;
    @Mock PaymentService paymentService;
    @Mock SlotService slotService;
    @Mock RefundRepository refundRepository;

    @InjectMocks BookingService service;

    static final LocalDate DAY = LocalDate.now().plusDays(10);
    static final LocalDate OTHER_DAY = LocalDate.now().plusDays(11);
    static final LocalTime NINE = LocalTime.of(9, 0);
    static final LocalTime ELEVEN = LocalTime.of(11, 0);

    Customer owner;
    Customer stranger;
    Plant plant;

    @BeforeEach
    void setUp() {
        owner = Customer.builder().id(1L).name("Owner").email("owner@x.com").build();
        stranger = Customer.builder().id(2L).name("Stranger").email("s@x.com").build();
        plant = Plant.builder().id(7L).name("Rooftop").capacityKw(new BigDecimal("5.00")).customer(owner).build();
        lenient().when(bookingRepository.save(any(Booking.class))).thenAnswer(i -> i.getArgument(0));
        lenient().when(bookingAttachmentRepository.findByBookingIdAndDeletedAtIsNull(any())).thenReturn(List.of());
    }

    private Booking booking(long id, BookingStatus status, boolean slotReserved) {
        return Booking.builder().id(id).token("BK-" + id).customer(owner).plant(plant)
                .serviceType("Cleaning").scheduledDate(DAY).scheduledTime(NINE)
                .status(status).slotReserved(slotReserved).build();
    }

    private void locked(Booking b) {
        when(bookingRepository.findByIdForUpdate(b.getId())).thenReturn(Optional.of(b));
    }

    private static ApiException thrown(Runnable r) {
        try {
            r.run();
        } catch (ApiException e) {
            return e;
        }
        throw new AssertionError("expected ApiException");
    }

    // ------------------------------------------------------------------ reschedule

    @Test
    void rescheduleByAnotherCustomerIsForbiddenAndTouchesNoSeats() {
        Booking b = booking(10, BookingStatus.PENDING, true);
        locked(b);

        ApiException e = thrown(() -> service.rescheduleForCustomer(stranger.getId(), 10L, OTHER_DAY, ELEVEN));

        assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
        verifyNoInteractions(slotService);
    }

    @Test
    void rescheduleOfCompletedBookingIsInvalidState() {
        Booking b = booking(10, BookingStatus.COMPLETED, false);
        locked(b);

        ApiException e = thrown(() -> service.rescheduleForCustomer(1L, 10L, OTHER_DAY, ELEVEN));

        assertThat(e.getCode()).isEqualTo("INVALID_STATE");
        verifyNoInteractions(slotService);
    }

    @Test
    void rescheduleToPastDateIsRejected() {
        Booking b = booking(10, BookingStatus.PENDING, true);
        locked(b);

        ApiException e = thrown(() -> service.rescheduleForCustomer(1L, 10L, LocalDate.now().minusDays(1), ELEVEN));

        assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
        verifyNoInteractions(slotService);
    }

    @Test
    void rescheduleToFullSlotLeavesBookingAndOldSeatUntouched() {
        Booking b = booking(10, BookingStatus.PENDING, true);
        locked(b);
        org.mockito.Mockito.doThrow(ApiException.conflict("full", "SLOT_FULL"))
                .when(slotService).reserve(OTHER_DAY, ELEVEN);

        ApiException e = thrown(() -> service.rescheduleForCustomer(1L, 10L, OTHER_DAY, ELEVEN));

        assertThat(e.getCode()).isEqualTo("SLOT_FULL");
        assertThat(b.getScheduledDate()).isEqualTo(DAY);
        assertThat(b.getScheduledTime()).isEqualTo(NINE);
        verify(slotService, never()).release(any(), any());
        verify(bookingRepository, never()).save(any());
    }

    @Test
    void successfulRescheduleClaimsNewSeatThenReleasesOld() {
        Booking b = booking(10, BookingStatus.ASSIGNED, true);
        locked(b);

        BookingResponse r = service.rescheduleForCustomer(1L, 10L, OTHER_DAY, ELEVEN);

        var inOrder = org.mockito.Mockito.inOrder(slotService);
        inOrder.verify(slotService).reserve(OTHER_DAY, ELEVEN);
        inOrder.verify(slotService).release(DAY, NINE);
        assertThat(b.getScheduledDate()).isEqualTo(OTHER_DAY);
        assertThat(b.getScheduledTime()).isEqualTo(ELEVEN);
        assertThat(b.isSlotReserved()).isTrue();
        assertThat(r.getScheduledDate()).isEqualTo(OTHER_DAY);
    }

    @Test
    void rescheduleOfBookingWithoutHeldSeatDoesNotReleaseAnything() {
        Booking b = booking(10, BookingStatus.PENDING, false);
        locked(b);

        service.rescheduleForCustomer(1L, 10L, OTHER_DAY, ELEVEN);

        verify(slotService).reserve(OTHER_DAY, ELEVEN);
        verify(slotService, never()).release(any(), any());
        assertThat(b.isSlotReserved()).isTrue();
    }

    @Test
    void rescheduleToSameSlotIsANoOpOnSeats() {
        Booking b = booking(10, BookingStatus.PENDING, true);
        locked(b);

        service.rescheduleForCustomer(1L, 10L, DAY, NINE);

        verifyNoInteractions(slotService);
    }

    // ------------------------------------------------------------------ cancel

    @Test
    void cancelByAnotherCustomerIsForbidden() {
        Booking b = booking(10, BookingStatus.PENDING, true);
        locked(b);

        ApiException e = thrown(() -> service.cancelForCustomer(stranger.getId(), 10L, (CancelBookingRequest) null));

        assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(b.getStatus()).isEqualTo(BookingStatus.PENDING);
        verifyNoInteractions(refundService);
    }

    @Test
    void cancelReleasesSeatAndRaisesCustomerRefund() {
        Booking b = booking(10, BookingStatus.PENDING, true);
        locked(b);
        Refund refund = Refund.builder().id(99L).build();
        when(refundService.requestForCancelledBooking(eq(b), eq(RefundReasonCode.CUSTOMER_REQUEST), eq("Cancelled by customer"),
                eq(SubjectType.CUSTOMER), eq(1L), eq("customer"))).thenReturn(refund);

        BookingService.CancellationOutcome out = service.cancelForCustomer(1L, 10L, (CancelBookingRequest) null);

        assertThat(b.getStatus()).isEqualTo(BookingStatus.CANCELLED);
        assertThat(b.isSlotReserved()).isFalse();
        verify(slotService).release(DAY, NINE);
        assertThat(out.refundId()).isEqualTo(99L);
        assertThat(out.rebooked()).isFalse();
    }

    @Test
    void cancelUsesCustomersReasonText() {
        Booking b = booking(10, BookingStatus.PENDING, false);
        locked(b);

        service.cancelForCustomer(1L, 10L, new CancelBookingRequest("Moved house", null, null, null, null));

        verify(refundService).requestForCancelledBooking(eq(b), eq(RefundReasonCode.CUSTOMER_REQUEST), eq("Moved house"),
                eq(SubjectType.CUSTOMER), eq(1L), eq("customer"));
        verify(slotService, never()).release(any(), any());
    }

    @Test
    void customerCannotSelectProviderFaultReasonCode() {
        Booking b = booking(10, BookingStatus.PENDING, false);
        locked(b);

        service.cancelForCustomer(1L, 10L,
                new CancelBookingRequest("x", RefundReasonCode.PROVIDER_CANCELLED, null, null, null));

        verify(refundService).requestForCancelledBooking(eq(b), eq(RefundReasonCode.CUSTOMER_REQUEST), anyString(),
                eq(SubjectType.CUSTOMER), eq(1L), anyString());
    }

    @Test
    void secondCancelReturnsExistingRefundAndRaisesNoNewOne() {
        Booking b = booking(10, BookingStatus.CANCELLED, false);
        locked(b);
        when(refundRepository.findByBookingIdOrderByRequestedAtDesc(10L))
                .thenReturn(List.of(Refund.builder().id(55L).build()));

        BookingService.CancellationOutcome out = service.cancelForCustomer(1L, 10L, (CancelBookingRequest) null);

        assertThat(out.refundId()).isEqualTo(55L);
        verifyNoInteractions(refundService);
        verifyNoInteractions(slotService);
    }

    @Test
    void cancelOfCompletedBookingIsInvalidState() {
        Booking b = booking(10, BookingStatus.COMPLETED, false);
        locked(b);

        ApiException e = thrown(() -> service.cancelForCustomer(1L, 10L, (CancelBookingRequest) null));

        assertThat(e.getCode()).isEqualTo("INVALID_STATE");
        assertThat(b.getStatus()).isEqualTo(BookingStatus.COMPLETED);
        verifyNoInteractions(refundService);
    }

    @Test
    void rebookIntentWithoutTargetIsBadRequest() {
        Booking b = booking(10, BookingStatus.PENDING, true);
        locked(b);

        ApiException e = thrown(() -> service.cancelForCustomer(1L, 10L,
                new CancelBookingRequest(null, null, CancelBookingRequest.Intent.REBOOK, null, null)));

        assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
        verifyNoInteractions(refundService);
    }

    @Test
    void rebookIntentMovesBookingAndRaisesNoRefund() {
        Booking b = booking(10, BookingStatus.PENDING, true);
        locked(b);

        BookingService.CancellationOutcome out = service.cancelForCustomer(1L, 10L,
                new CancelBookingRequest(null, null, CancelBookingRequest.Intent.REBOOK, OTHER_DAY, ELEVEN));

        assertThat(out.rebooked()).isTrue();
        assertThat(out.refundId()).isNull();
        assertThat(b.getScheduledDate()).isEqualTo(OTHER_DAY);
        assertThat(b.getStatus()).isEqualTo(BookingStatus.PENDING);
        verifyNoInteractions(refundService);
    }

    // ------------------------------------------------------------------ create

    @Test
    void createReplaysExistingBookingForSameIdempotencyKey() {
        Booking existing = booking(10, BookingStatus.PENDING, true);
        when(bookingRepository.findByCustomerIdAndIdempotencyKey(1L, "key-1")).thenReturn(Optional.of(existing));

        BookingResponse r = service.createForCustomer(1L,
                new CreateBookingRequest(null, "Cleaning", DAY, NINE, null, "Pune", null, null, null), null, "key-1");

        assertThat(r.getId()).isEqualTo(10L);
        verifyNoInteractions(slotService);
        verify(bookingRepository, never()).save(any());
    }

    @Test
    void createWithoutPaymentReservesSeatStrictly() {
        when(customerRepository.findById(1L)).thenReturn(Optional.of(owner));
        when(plantService.findOrCreateDefaultForCustomer(owner, "Pune")).thenReturn(plant);
        when(slotService.isOnGrid(NINE)).thenReturn(true);
        when(codeGeneratorService.nextBookingToken()).thenReturn("BK-NEW");

        BookingResponse r = service.createForCustomer(1L,
                new CreateBookingRequest(null, "Cleaning", DAY, NINE, null, "Pune", null, null, null));

        verify(slotService).reserve(DAY, NINE);
        assertThat(r.getToken()).isEqualTo("BK-NEW");
        assertThat(r.getStatus()).isEqualTo(BookingStatus.PENDING.name());
        verify(paymentService, never()).linkToBooking(any(), any());
    }

    @Test
    void createOnFullSlotFailsBeforeSaving() {
        when(customerRepository.findById(1L)).thenReturn(Optional.of(owner));
        when(plantService.findOrCreateDefaultForCustomer(any(), any())).thenReturn(plant);
        org.mockito.Mockito.doThrow(ApiException.conflict("full", "SLOT_FULL")).when(slotService).reserve(DAY, NINE);

        ApiException e = thrown(() -> service.createForCustomer(1L,
                new CreateBookingRequest(null, "Cleaning", DAY, NINE, null, "Pune", null, null, null)));

        assertThat(e.getCode()).isEqualTo("SLOT_FULL");
        verify(bookingRepository, never()).save(any());
    }

    @Test
    void createWithSomeoneElsesPlantIsForbidden() {
        Plant foreign = Plant.builder().id(8L).name("X").capacityKw(BigDecimal.ONE).customer(stranger).build();
        when(customerRepository.findById(1L)).thenReturn(Optional.of(owner));
        when(plantRepository.findById(8L)).thenReturn(Optional.of(foreign));

        ApiException e = thrown(() -> service.createForCustomer(1L,
                new CreateBookingRequest(8L, "Cleaning", DAY, NINE, null, null, null, null, null)));

        assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
        verifyNoInteractions(slotService);
    }

    @Test
    void createWithRemovedPlantIsBadRequest() {
        plant.setDeleted(true);
        when(customerRepository.findById(1L)).thenReturn(Optional.of(owner));
        when(plantRepository.findById(7L)).thenReturn(Optional.of(plant));

        ApiException e = thrown(() -> service.createForCustomer(1L,
                new CreateBookingRequest(7L, "Cleaning", DAY, NINE, null, null, null, null, null)));

        assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void createWithCapturedPaymentCopiesAmountFromPaymentNotRequestAndLinksIt() {
        Payment payment = new Payment();
        payment.setAmountPaise(250_000L);
        payment.setRazorpayPaymentId("pay_abc");
        when(customerRepository.findById(1L)).thenReturn(Optional.of(owner));
        when(paymentService.consumeCapturedPayment(1L, "order_1")).thenReturn(payment);
        when(plantService.findOrCreateDefaultForCustomer(any(), any())).thenReturn(plant);
        when(paymentService.takeOverSlotHold(payment, DAY, NINE)).thenReturn(true);
        when(codeGeneratorService.nextBookingToken()).thenReturn("BK-PAID");

        BookingResponse r = service.createForCustomer(1L,
                new CreateBookingRequest(null, "Cleaning", DAY, NINE, null, "Pune", "order_1", null, null));

        assertThat(r.getAmountPaise()).isEqualTo(250_000L);
        assertThat(r.getPaymentRef()).isEqualTo("pay_abc");
        verify(paymentService).linkToBooking(eq(payment), any(Booking.class));
        // the seat was already held at create-order: it is handed over, never claimed twice
        verify(slotService, never()).reserve(any(), any());
    }

    @Test
    void createWithAlreadySpentPaymentReturnsThatBookingAndCreatesNoSecond() {
        Booking spent = booking(77, BookingStatus.PENDING, true);
        Payment payment = new Payment();
        payment.setBooking(spent);
        when(customerRepository.findById(1L)).thenReturn(Optional.of(owner));
        when(paymentService.consumeCapturedPayment(1L, "order_1")).thenReturn(payment);

        BookingResponse r = service.createForCustomer(1L,
                new CreateBookingRequest(null, "Cleaning", DAY, NINE, null, "Pune", "order_1", null, null));

        assertThat(r.getId()).isEqualTo(77L);
        verify(bookingRepository, never()).save(any());
        verifyNoInteractions(slotService);
    }

    @Test
    void createWithRequestQuoteCreatesDraftQuotation() {
        when(customerRepository.findById(1L)).thenReturn(Optional.of(owner));
        when(plantService.findOrCreateDefaultForCustomer(any(), any())).thenReturn(plant);
        when(codeGeneratorService.nextBookingToken()).thenReturn("BK-Q");

        service.createForCustomer(1L,
                new CreateBookingRequest(null, "Installation", DAY, NINE, null, "Pune", null, null, true));

        verify(quotationService).createDraftForBooking(any(Booking.class));
    }

    // ------------------------------------------------------------------ admin

    @Test
    void markCompletedIsIdempotent() {
        Booking b = booking(10, BookingStatus.COMPLETED, false);
        locked(b);

        BookingResponse r = service.markCompleted(10L, "again");

        assertThat(r.getStatus()).isEqualTo(BookingStatus.COMPLETED.name());
        verify(bookingRepository, never()).save(any());
    }

    @Test
    void markCompletedFromCancelledIsInvalidState() {
        Booking b = booking(10, BookingStatus.CANCELLED, false);
        locked(b);

        ApiException e = thrown(() -> service.markCompleted(10L, null));

        assertThat(e.getCode()).isEqualTo("INVALID_STATE");
    }

    @Test
    void markCompletedStoresNotes() {
        Booking b = booking(10, BookingStatus.ASSIGNED, true);
        locked(b);

        service.markCompleted(10L, "All good");

        assertThat(b.getStatus()).isEqualTo(BookingStatus.COMPLETED);
        assertThat(b.getNotes()).isEqualTo("All good");
    }

    @Test
    void adminCancelRaisesFullProviderFaultRefundAndReleasesSeat() {
        Booking b = booking(10, BookingStatus.PENDING, true);
        locked(b);

        service.cancelByAdmin(10L, "Site fell through");

        assertThat(b.getStatus()).isEqualTo(BookingStatus.CANCELLED);
        assertThat(b.getNotes()).contains("Cancelled: Site fell through");
        verify(slotService).release(DAY, NINE);
        verify(refundService).requestForCancelledBooking(eq(b), eq(RefundReasonCode.PROVIDER_CANCELLED),
                eq("Site fell through"), eq(SubjectType.ADMIN), eq(null), eq("admin console"));
    }

    @Test
    void adminCancelTwiceDoesNotRaiseSecondRefund() {
        Booking b = booking(10, BookingStatus.CANCELLED, false);
        locked(b);

        service.cancelByAdmin(10L, "dup");

        verifyNoInteractions(refundService);
        verifyNoInteractions(slotService);
    }

    @Test
    void adminCancelOfCompletedBookingIsInvalidState() {
        Booking b = booking(10, BookingStatus.COMPLETED, false);
        locked(b);

        ApiException e = thrown(() -> service.cancelByAdmin(10L, "x"));

        assertThat(e.getCode()).isEqualTo("INVALID_STATE");
        verifyNoInteractions(refundService);
    }

    @Test
    void assignTechnicianMovesPendingToAssigned() {
        Booking b = booking(10, BookingStatus.PENDING, true);
        locked(b);
        Technician t = Technician.builder().id(5L).name("Tech").build();
        when(technicianRepository.findById(5L)).thenReturn(Optional.of(t));

        BookingResponse r = service.assignTechnician(10L, 5L);

        assertThat(b.getStatus()).isEqualTo(BookingStatus.ASSIGNED);
        assertThat(r.getTechnicianId()).isEqualTo(5L);
    }

    @Test
    void assignTechnicianToClosedBookingIsInvalidState() {
        Booking b = booking(10, BookingStatus.CANCELLED, false);
        locked(b);
        when(technicianRepository.findById(5L)).thenReturn(Optional.of(Technician.builder().id(5L).build()));

        ApiException e = thrown(() -> service.assignTechnician(10L, 5L));

        assertThat(e.getCode()).isEqualTo("INVALID_STATE");
    }

    @Test
    void adminCannotCancelThroughStatusEdit() {
        Booking b = booking(10, BookingStatus.PENDING, true);
        locked(b);

        ApiException e = thrown(() -> service.update(10L,
                new UpdateBookingRequest(BookingStatus.CANCELLED, null, null, null, null)));

        assertThat(e.getCode()).isEqualTo("INVALID_STATE");
        assertThat(b.getStatus()).isEqualTo(BookingStatus.PENDING);
    }

    @Test
    void deleteRefusesBookingThatHasMoneyOnIt() {
        Booking b = booking(10, BookingStatus.PENDING, true);
        b.setPaymentRef("pay_1");
        locked(b);

        ApiException e = thrown(() -> service.delete(10L));

        assertThat(e.getCode()).isEqualTo("INVALID_STATE");
        verify(bookingRepository, never()).delete(any());
    }

    @Test
    void deleteReleasesSeatOfUnpaidBooking() {
        Booking b = booking(10, BookingStatus.PENDING, true);
        locked(b);
        when(refundRepository.findByBookingIdOrderByRequestedAtDesc(10L)).thenReturn(List.of());

        service.delete(10L);

        verify(slotService).release(DAY, NINE);
        verify(bookingRepository).delete(b);
    }

    @Test
    void unknownBookingIsNotFound() {
        when(bookingRepository.findByIdForUpdate(404L)).thenReturn(Optional.empty());

        ApiException e = thrown(() -> service.markCompleted(404L, null));

        assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // ------------------------------------------------------------------ rebook from refund

    @Test
    void rebookFromRefundRequiresCancelledBooking() {
        Booking b = booking(10, BookingStatus.PENDING, true);
        Refund refund = Refund.builder().id(3L).booking(b).build();
        when(refundRepository.findById(3L)).thenReturn(Optional.of(refund));
        locked(b);

        ApiException e = thrown(() -> service.rebookFromRefund(1L, 3L, OTHER_DAY, ELEVEN));

        assertThat(e.getCode()).isEqualTo("INVALID_STATE");
        verifyNoInteractions(slotService);
    }

    @Test
    void rebookFromRefundByAnotherCustomerIsForbidden() {
        Booking b = booking(10, BookingStatus.CANCELLED, false);
        Refund refund = Refund.builder().id(3L).booking(b).build();
        when(refundRepository.findById(3L)).thenReturn(Optional.of(refund));
        locked(b);

        ApiException e = thrown(() -> service.rebookFromRefund(2L, 3L, OTHER_DAY, ELEVEN));

        assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
        verify(refundService, never()).withdrawForRebooking(any(), any(), any());
    }

    @Test
    void rebookFromRefundRevivesBookingAtNewSeat() {
        Booking b = booking(10, BookingStatus.CANCELLED, false);
        b.setTechnician(Technician.builder().id(5L).build());
        Refund refund = Refund.builder().id(3L).booking(b).build();
        when(refundRepository.findById(3L)).thenReturn(Optional.of(refund));
        locked(b);

        BookingResponse r = service.rebookFromRefund(1L, 3L, OTHER_DAY, ELEVEN);

        verify(refundService).withdrawForRebooking(eq(3L), eq(1L), anyString());
        verify(slotService).reserve(OTHER_DAY, ELEVEN);
        assertThat(b.getStatus()).isEqualTo(BookingStatus.PENDING);
        assertThat(b.isSlotReserved()).isTrue();
        assertThat(b.getTechnician()).isNull();
        assertThat(r.getScheduledDate()).isEqualTo(OTHER_DAY);
    }
}
