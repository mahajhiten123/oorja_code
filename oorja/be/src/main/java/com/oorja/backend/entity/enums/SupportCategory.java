package com.oorja.backend.entity.enums;

/**
 * What the ticket is about. Category drives routing, the default priority
 * and the promised first-response time, so it's a closed set rather than a
 * free-text field the customer invents.
 */
public enum SupportCategory {
    BOOKING("Booking or scheduling", SupportPriority.NORMAL, 8),
    PAYMENT("Payment or invoice", SupportPriority.HIGH, 4),
    REFUND("Refund status", SupportPriority.HIGH, 4),
    SERVICE_QUALITY("Service quality", SupportPriority.HIGH, 4),
    TECHNICIAN("Technician or visit", SupportPriority.NORMAL, 8),
    PLANT("My plant or site", SupportPriority.NORMAL, 24),
    ACCOUNT("Account and login", SupportPriority.NORMAL, 8),
    AMC("AMC or quotation", SupportPriority.NORMAL, 24),
    OTHER("Something else", SupportPriority.LOW, 24);

    private final String label;
    private final SupportPriority defaultPriority;
    private final int firstResponseHours;

    SupportCategory(String label, SupportPriority defaultPriority, int firstResponseHours) {
        this.label = label;
        this.defaultPriority = defaultPriority;
        this.firstResponseHours = firstResponseHours;
    }

    public String label() { return label; }
    public SupportPriority defaultPriority() { return defaultPriority; }
    /** Hours we commit to a first human reply for this category. */
    public int firstResponseHours() { return firstResponseHours; }
}
