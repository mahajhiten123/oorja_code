package com.oorja.backend.service;

import com.oorja.backend.dto.response.SlotAvailabilityResponse;
import com.oorja.backend.entity.BookingSlot;
import com.oorja.backend.exception.ApiException;
import com.oorja.backend.repository.BookingSlotRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SlotServiceTest {

    @Mock BookingSlotRepository repo;

    SlotService slots;

    final LocalDate nextWeek = LocalDate.now().plusDays(7);
    final LocalTime nine = LocalTime.of(9, 0);

    @BeforeEach
    void setUp() {
        slots = new SlotService(repo);
        ReflectionTestUtils.setField(slots, "defaultCapacity", 4);
        ReflectionTestUtils.setField(slots, "minLeadHours", 2);
        ReflectionTestUtils.setField(slots, "maxAdvanceDays", 180);
    }

    private BookingSlot row(LocalDate d, LocalTime t, int capacity, int booked) {
        return BookingSlot.builder().slotDate(d).slotTime(t).capacity(capacity).bookedCount(booked).build();
    }

    // ---------------------------------------------------------------- grid & availability

    @Test
    void onlyTheFivePublishedTwoHourWindowsAreOnTheGrid() {
        for (int h : new int[]{9, 11, 13, 15, 17}) {
            assertTrue(slots.isOnGrid(LocalTime.of(h, 0)), h + ":00");
        }
        assertFalse(slots.isOnGrid(LocalTime.of(10, 0)));
        assertFalse(slots.isOnGrid(LocalTime.of(9, 30)));
        assertFalse(slots.isOnGrid(LocalTime.of(19, 0)));
    }

    @Test
    void anUntouchedDayShowsTheFullGridAtDefaultCapacity() {
        when(repo.findBySlotDate(nextWeek)).thenReturn(List.of());

        List<SlotAvailabilityResponse> grid = slots.listAvailability(nextWeek);

        assertEquals(5, grid.size());
        assertEquals(List.of(LocalTime.of(9, 0), LocalTime.of(11, 0), LocalTime.of(13, 0), LocalTime.of(15, 0), LocalTime.of(17, 0)),
                grid.stream().map(SlotAvailabilityResponse::time).toList());
        grid.forEach(s -> {
            assertEquals(4, s.capacity());
            assertEquals(0, s.booked());
            assertEquals(4, s.remaining());
            assertTrue(s.available());
            assertFalse(s.full());
        });
        assertEquals("9 AM – 11 AM", grid.get(0).label());
        assertEquals("5 PM – 7 PM", grid.get(4).label());
    }

    @Test
    void aFullSlotIsNotAvailableAndNeverShowsNegativeSeats() {
        when(repo.findBySlotDate(nextWeek)).thenReturn(List.of(row(nextWeek, nine, 4, 6)));

        SlotAvailabilityResponse s = slots.listAvailability(nextWeek).get(0);

        assertTrue(s.full());
        assertFalse(s.available());
        assertEquals(0, s.remaining(), "an over-booked slot (admin override) must not report negative seats");
    }

    @Test
    void aSlotInsideTheLeadTimeIsTooSoon() {
        LocalDate today = LocalDate.now();

        // Yesterday's grid is entirely in the past, whatever time the test runs.
        LocalDate yesterday = today.minusDays(1);
        when(repo.findBySlotDate(yesterday)).thenReturn(List.of());
        slots.listAvailability(yesterday).forEach(s -> {
            assertTrue(s.tooSoon());
            assertFalse(s.available());
        });
    }

    // ---------------------------------------------------------------- reserve

    @Test
    void reserveClaimsASeatWhenOneIsFree() {
        when(repo.tryClaimSeat(nextWeek, nine)).thenReturn(1);

        slots.reserve(nextWeek, nine);

        verify(repo, never()).createIfAbsent(any(), any(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void reserveCreatesTheSlotRowOnFirstUseThenClaims() {
        when(repo.tryClaimSeat(nextWeek, nine)).thenReturn(0, 1);

        slots.reserve(nextWeek, nine);

        verify(repo).createIfAbsent(nextWeek, nine, 4);
        verify(repo, times(2)).tryClaimSeat(nextWeek, nine);
    }

    @Test
    void reserveOnAFullSlotIsA409SlotFull() {
        when(repo.tryClaimSeat(nextWeek, nine)).thenReturn(0, 0);

        ApiException e = assertThrows(ApiException.class, () -> slots.reserve(nextWeek, nine));

        assertEquals(409, e.getStatus().value());
        assertEquals("SLOT_FULL", e.getCode());
        verify(repo, never()).forceClaimSeat(any(), any());
    }

    @Test
    void reserveRejectsOffGridTimesWithoutTouchingTheDatabase() {
        ApiException e = assertThrows(ApiException.class, () -> slots.reserve(nextWeek, LocalTime.of(10, 30)));
        assertEquals(400, e.getStatus().value());
        verify(repo, never()).tryClaimSeat(any(), any());
    }

    @Test
    void reserveRejectsASlotInsideTheMinimumLeadTime() {
        ApiException e = assertThrows(ApiException.class, () -> slots.reserve(LocalDate.now().minusDays(1), nine));
        assertEquals(400, e.getStatus().value());
        verify(repo, never()).tryClaimSeat(any(), any());
    }

    @Test
    void reserveRejectsDatesBeyondTheBookingHorizon() {
        ApiException e = assertThrows(ApiException.class, () -> slots.reserve(LocalDate.now().plusDays(181), nine));
        assertEquals(400, e.getStatus().value());
        verify(repo, never()).createIfAbsent(any(), any(), org.mockito.ArgumentMatchers.anyInt());
    }

    // ---------------------------------------------------------------- assertBookable (advisory pre-flight)

    @Test
    void assertBookablePassesForAnUntouchedSlotAndClaimsNothing() {
        when(repo.findBySlotDateAndSlotTime(nextWeek, nine)).thenReturn(Optional.empty());

        slots.assertBookable(nextWeek, nine);

        verify(repo, never()).tryClaimSeat(any(), any());
    }

    @Test
    void assertBookableRefusesAFullSlot() {
        when(repo.findBySlotDateAndSlotTime(nextWeek, nine)).thenReturn(Optional.of(row(nextWeek, nine, 4, 4)));

        ApiException e = assertThrows(ApiException.class, () -> slots.assertBookable(nextWeek, nine));

        assertEquals("SLOT_FULL", e.getCode());
    }

    @Test
    void assertBookablePassesWhenASeatIsLeft() {
        when(repo.findBySlotDateAndSlotTime(nextWeek, nine)).thenReturn(Optional.of(row(nextWeek, nine, 4, 3)));
        slots.assertBookable(nextWeek, nine);
    }

    // ---------------------------------------------------------------- reserveBestEffort (paid bookings / admin edits)

    @Test
    void bestEffortWithinCapacityDoesNotForce() {
        when(repo.tryClaimSeat(nextWeek, nine)).thenReturn(1);

        slots.reserveBestEffort(nextWeek, nine);

        verify(repo, never()).forceClaimSeat(any(), any());
    }

    @Test
    void bestEffortOnAFullSlotStillCountsTheSeatSoTheCounterStaysTruthful() {
        when(repo.tryClaimSeat(nextWeek, nine)).thenReturn(0, 0);
        when(repo.forceClaimSeat(nextWeek, nine)).thenReturn(1);

        slots.reserveBestEffort(nextWeek, nine);

        verify(repo).forceClaimSeat(nextWeek, nine);
    }

    @Test
    void bestEffortIgnoresOffGridTimes() {
        slots.reserveBestEffort(nextWeek, LocalTime.of(10, 30));
        verify(repo, never()).tryClaimSeat(any(), any());
        verify(repo, never()).forceClaimSeat(any(), any());
    }

    // ---------------------------------------------------------------- release

    @Test
    void releaseGivesTheSeatBack() {
        slots.release(nextWeek, nine);
        verify(repo).releaseSeat(nextWeek, nine);
    }

    @Test
    void releaseOfAnOffGridTimeIsANoOp() {
        slots.release(nextWeek, LocalTime.of(10, 30));
        verify(repo, never()).releaseSeat(any(), any());
    }
}
