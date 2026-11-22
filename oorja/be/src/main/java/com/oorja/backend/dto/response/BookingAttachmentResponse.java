package com.oorja.backend.dto.response;

import com.oorja.backend.entity.BookingAttachment;
import com.oorja.backend.entity.enums.BookingStatus;
import com.oorja.backend.entity.enums.SubjectType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BookingAttachmentResponse {
    private Long id;
    private String fileName;
    private String contentType;
    private Long sizeBytes;
    /** Admin-authenticated download/view URL, relative to the API base. */
    private String url;
    /** Customer-authenticated download/view URL for the same file, relative to the API base. */
    private String customerUrl;
    /** True for ops-generated service reports / completion documents, which customers can never delete. */
    private boolean officialDocument;
    /**
     * Whether the owning customer is allowed to delete this file, so the UI can
     * hide the delete control instead of offering an action that will 400.
     * The server re-checks on the actual delete regardless.
     */
    private boolean customerDeletable;

    public static BookingAttachmentResponse from(BookingAttachment a) {
        return BookingAttachmentResponse.builder()
                .id(a.getId())
                .fileName(a.getFileName())
                .contentType(a.getContentType())
                .sizeBytes(a.getSizeBytes())
                .url("/api/admin/bookings/attachments/" + a.getId())
                .customerUrl("/api/customer/bookings/attachments/" + a.getId())
                .officialDocument(a.isOfficialDocument())
                .customerDeletable(customerDeletable(a))
                .build();
    }

    /** Mirrors the rules enforced in AttachmentService.deleteAsCustomer. */
    private static boolean customerDeletable(BookingAttachment a) {
        if (a.isOfficialDocument()) return false;
        if (a.getUploadedByType() != SubjectType.CUSTOMER) return false;
        return a.getBooking().getStatus() != BookingStatus.COMPLETED;
    }
}
