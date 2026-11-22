package com.oorja.backend.service;

import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class QuotationExpiryJob {

    private final QuotationService quotationService;

    /** Runs once an hour to flip quotations past their validUntil date to EXPIRED. */
    @Scheduled(fixedRate = 60 * 60 * 1000)
    public void expireOverdueQuotations() {
        quotationService.expireOverdue();
    }
}
