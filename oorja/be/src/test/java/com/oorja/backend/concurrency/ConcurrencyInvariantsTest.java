package com.oorja.backend.concurrency;

import com.oorja.backend.dto.request.CreateBookingRequest;
import com.oorja.backend.dto.request.CreateOrderRequest;
import com.oorja.backend.dto.request.VerifyPaymentRequest;
import com.oorja.backend.dto.response.BookingResponse;
import com.oorja.backend.dto.response.CreateOrderResponse;
import com.oorja.backend.exception.ApiException;
import com.oorja.backend.service.BookingService;
import com.oorja.backend.service.PaymentService;
import com.oorja.backend.service.RazorpayService;
import com.oorja.backend.service.SlotHoldExpiryJob;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.function.IntFunction;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * The business invariants, proven under real concurrency against a real PostgreSQL (all Flyway migrations
 * applied, ddl-auto=validate). Only the Razorpay gateway is faked.
 *
 * Requires Docker (Testcontainers); skipped automatically when Docker isn't available.
 * The same guarantees at the raw-SQL level can be checked without Docker: verify/db_concurrency_check.py.
 */
@SpringBootTest(properties = {"booking.slots.capacity=3", "booking.slots.min-lead-hours=0"})
@Testcontainers(disabledWithoutDocker = true)
class ConcurrencyInvariantsTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @MockBean RazorpayService razorpay;

    @Autowired PaymentService paymentService;
    @Autowired BookingService bookingService;
    @Autowired SlotHoldExpiryJob expiryJob;
    @Autowired JdbcTemplate jdbc;

    LocalDate day;
    final LocalTime nine = LocalTime.of(9, 0);

    @BeforeEach
    void setUp() {
        // A fresh date per test = a fresh slot row, so tests never see each other's seats.
        day = LocalDate.now().plusDays(10 + ThreadLocalRandom.current().nextInt(100_000) % 150);
        when(razorpay.createOrder(anyLong(), anyString())).thenAnswer(inv -> CreateOrderResponse.builder()
                .orderId("order_" + UUID.randomUUID().toString().replace("-", "").substring(0, 14))
                .amount(inv.getArgument(0)).currency("INR").keyId("key").build());
        when(razorpay.verifySignature(any())).thenReturn(true);
        when(razorpay.getPublicKeyId()).thenReturn("key");
    }

    // ---------------------------------------------------------------- helpers

    long customer() {
        String u = UUID.randomUUID().toString().substring(0, 8);
        Long id = jdbc.queryForObject("insert into customers(customer_code,name,email) values (?,?,?) returning id",
                Long.class, "C" + u, "Test " + u, u + "@test.io");
        jdbc.update("insert into plants(plant_code,name,type,capacity_kw,location,customer_id) values (?,?,?,?,?,?)",
                "P" + u, "Plant", "RESIDENTIAL", 5, "Pune", id);
        return id;
    }

    CreateOrderRequest orderRequest() {
        return new CreateOrderRequest("cleaning", null, null, "rcpt_" + System.nanoTime(), null, day, nine, "Cleaning", null, "Pune");
    }

    CreateBookingRequest bookingRequest(String orderId) {
        return new CreateBookingRequest(null, "Cleaning", day, nine, null, "Pune", orderId, "Cleaning", false);
    }

    int booked() {
        List<Integer> r = jdbc.queryForList("select booked_count from booking_slots where slot_date=? and slot_time=?",
                Integer.class, day, java.sql.Time.valueOf(nine));
        return r.isEmpty() ? 0 : r.get(0);
    }

    /** Runs {@code n} copies of the task at the same instant; returns each one's result or thrown exception. */
    static List<Object> parallel(int n, IntFunction<Object> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CyclicBarrier start = new CyclicBarrier(n);
        List<Future<Object>> futures = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            final int idx = i;
            futures.add(pool.submit(() -> {
                start.await();
                try { return task.apply(idx); } catch (Throwable t) { return t; }
            }));
        }
        List<Object> out = new ArrayList<>();
        for (Future<Object> f : futures) out.add(f.get(60, TimeUnit.SECONDS));
        pool.shutdown();
        return out;
    }

    static long count(List<Object> results, Class<?> type) {
        return results.stream().filter(type::isInstance).count();
    }

    String capturedOrderFor(long customerId) {
        CreateOrderResponse order = paymentService.createOrder(customerId, orderRequest());
        var ok = paymentService.verify(customerId, new VerifyPaymentRequest(order.getOrderId(), "pay_" + UUID.randomUUID().toString().substring(0, 12), "sig"));
        assertTrue(ok.verified());
        return order.getOrderId();
    }

    // ---------------------------------------------------------------- the invariants

    @Test
    void lastSeatCannotBeSoldTwice() throws Exception {
        long[] customers = new long[10];
        for (int i = 0; i < customers.length; i++) customers[i] = customer();

        List<Object> results = parallel(10, i -> paymentService.createOrder(customers[i], orderRequest()));

        assertEquals(3, count(results, CreateOrderResponse.class), "exactly capacity (3) customers may get an order to pay for");
        assertEquals(7, count(results, ApiException.class), "everyone else is told the slot is full");
        results.stream().filter(ApiException.class::isInstance).forEach(r -> assertEquals("SLOT_FULL", ((ApiException) r).getCode()));
        assertEquals(3, booked(), "seat counter never exceeds capacity");
    }

    @Test
    void verifyIsIdempotentUnderConcurrentRetries() throws Exception {
        long c = customer();
        CreateOrderResponse order = paymentService.createOrder(c, orderRequest());
        VerifyPaymentRequest verify = new VerifyPaymentRequest(order.getOrderId(), "pay_same_" + UUID.randomUUID().toString().substring(0, 8), "sig");

        List<Object> results = parallel(10, i -> paymentService.verify(c, verify));

        assertEquals(10, count(results, com.oorja.backend.dto.response.VerifyPaymentResponse.class), "no call may error");
        Integer captured = jdbc.queryForObject("select count(*) from payment_transactions t join payments p on p.id=t.payment_id " +
                "where p.razorpay_order_id=? and t.type='PAYMENT_CAPTURED'", Integer.class, order.getOrderId());
        assertEquals(1, captured, "one capture ledger row no matter how many retries");
    }

    @Test
    void clickingPayTwiceCreatesOneBookingAndOneSeat() throws Exception {
        long c = customer();
        String orderId = capturedOrderFor(c);

        // Different idempotency keys on purpose: the guarantee must come from the payment itself, not just the key.
        List<Object> results = parallel(10, i -> bookingService.createForCustomer(c, bookingRequest(orderId), null, "key-" + i));

        assertEquals(10, count(results, BookingResponse.class), "retries get the booking back, never an error");
        long distinct = results.stream().map(r -> ((BookingResponse) r).getId()).distinct().count();
        assertEquals(1, distinct, "all retries resolve to the SAME booking");
        assertEquals(1, jdbc.queryForObject("select count(*) from bookings where customer_id=?", Integer.class, c));
        assertEquals(1, booked(), "the booking took over the checkout hold; the seat is counted once");
    }

    @Test
    void retryAfterBookingExistsReturnsIt() {
        long c = customer();
        String orderId = capturedOrderFor(c);
        BookingResponse first = bookingService.createForCustomer(c, bookingRequest(orderId), null, "k1");
        BookingResponse retry = bookingService.createForCustomer(c, bookingRequest(orderId), null, "k2");
        assertEquals(first.getId(), retry.getId());
    }

    @Test
    void sameIdempotencyKeyOnCreateOrderYieldsOneOrderAndOneSeat() throws Exception {
        long c = customer();
        String key = "order-key-" + UUID.randomUUID();
        List<Object> results = parallel(6, i -> paymentService.createOrder(c, orderRequest(), key));

        long ok = count(results, CreateOrderResponse.class);
        assertTrue(ok >= 1);
        // A racing duplicate may lose on the unique index (the controller turns that into a replay); it must never
        // leave a second row or a second seat behind.
        results.stream().filter(Throwable.class::isInstance).forEach(t ->
                assertTrue(t instanceof org.springframework.dao.DataIntegrityViolationException || t instanceof ApiException, "unexpected: " + t));
        assertEquals(1, jdbc.queryForObject("select count(*) from payments where customer_id=?", Integer.class, c));
        assertEquals(1, booked());
    }

    @Test
    void abandonedCheckoutGivesTheSeatBackButPaidOnesKeepIt() {
        long abandoned = customer(), paid = customer();
        CreateOrderResponse a = paymentService.createOrder(abandoned, orderRequest());
        String paidOrder = capturedOrderFor(paid);
        assertEquals(2, booked());

        jdbc.update("update payments set slot_hold_expires_at = now() - interval '1 minute' where razorpay_order_id in (?, ?)", a.getOrderId(), paidOrder);
        expiryJob.sweep();

        assertEquals("RELEASED", jdbc.queryForObject("select slot_hold_state from payments where razorpay_order_id=?", String.class, a.getOrderId()));
        assertEquals("HELD", jdbc.queryForObject("select slot_hold_state from payments where razorpay_order_id=?", String.class, paidOrder),
                "a captured payment never loses its seat");
        assertEquals(1, booked());
    }

    @Test
    void aBadSignatureDoesNotPoisonTheOrder() {
        long c = customer();
        CreateOrderResponse order = paymentService.createOrder(c, orderRequest());
        when(razorpay.verifySignature(any())).thenReturn(false);
        assertFalse(paymentService.verify(c, new VerifyPaymentRequest(order.getOrderId(), "pay_x", "forged")).verified());
        assertEquals("CREATED", jdbc.queryForObject("select status from payments where razorpay_order_id=?", String.class, order.getOrderId()));

        when(razorpay.verifySignature(any())).thenReturn(true);
        assertTrue(paymentService.verify(c, new VerifyPaymentRequest(order.getOrderId(), "pay_real_" + UUID.randomUUID().toString().substring(0, 6), "ok")).verified());
    }

    @Test
    void someoneElsesPaymentIsForbiddenNotUnauthorized() {
        long owner = customer(), thief = customer();
        String orderId = capturedOrderFor(owner);
        ApiException e = assertThrows(ApiException.class, () -> bookingService.createForCustomer(thief, bookingRequest(orderId), null, "x"));
        assertEquals(403, e.getStatus().value(), "401 would make the frontend log the user out");
    }
}
