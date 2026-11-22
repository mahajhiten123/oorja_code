package com.oorja.backend.service;

import com.oorja.backend.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Gives back the seats of abandoned checkouts.
 * <p>
 * A seat is claimed when a Razorpay order is created (see {@link PaymentService#createOrder}), so a customer
 * who opens Checkout and walks away would otherwise hold it forever. This sweep releases holds whose
 * {@code slot_hold_expires_at} has passed <b>and whose payment has not been captured</b>.
 * <p>
 * Safe to run on several app servers at once: each release happens in its own transaction under the payment's
 * row lock, re-checking state first ({@link PaymentService#releaseHoldIfExpired}), so a capture or booking that
 * lands while the sweep is running always wins and its seat is never released from under it.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SlotHoldExpiryJob {

    private static final int BATCH = 200;

    private final PaymentRepository paymentRepository;
    private final PaymentService paymentService;

    @Scheduled(fixedDelayString = "${booking.slots.hold-sweep-interval-ms:60000}")
    public void sweep() {
        List<Long> ids = paymentRepository.findExpiredHoldIds(LocalDateTime.now(), PageRequest.of(0, BATCH));
        int released = 0;
        for (Long id : ids) {
            try {
                if (paymentService.releaseHoldIfExpired(id)) {
                    released++;
                }
            } catch (RuntimeException e) {
                // One bad row must not stop the rest; it will be retried on the next sweep.
                log.warn("Could not release slot hold for payment {}: {}", id, e.getMessage());
            }
        }
        if (released > 0) {
            log.info("Slot hold sweep released {} lapsed hold(s)", released);
        }
    }
}
