package com.oorja.backend.dto.response;

import com.oorja.backend.entity.BookingAttachment;
import com.oorja.backend.entity.ServiceReport;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/** The completion report, as shown on screen before it's downloaded as a PDF. */
@Data
@Builder
public class ServiceReportResponse {

    private Long id;
    private String reportNumber;
    private Long bookingId;
    private String bookingToken;
    private String serviceLabel;
    private String customerName;
    private String technicianName;

    private LocalDate serviceDate;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;

    private String workPerformed;
    private String observations;
    private String partsReplaced;
    private String recommendations;
    private String checklistJson;

    private Integer panelsCleaned;
    private Double outputBeforeKw;
    private Double outputAfterKw;
    private Double improvementKw;
    private LocalDate nextServiceDue;

    private String customerSignatureName;
    private String customerRemarks;

    private boolean published;
    private LocalDateTime publishedAt;
    private String preparedBy;
    private String fileName;

    private List<BookingAttachmentResponse> attachments;

    public static ServiceReportResponse from(ServiceReport r, List<BookingAttachment> attachments) {
        return ServiceReportResponse.builder()
                .id(r.getId())
                .reportNumber(r.getReportNumber())
                .bookingId(r.getBooking().getId())
                .bookingToken(r.getBooking().getToken())
                .serviceLabel(r.getBooking().getServiceLabel() != null
                        ? r.getBooking().getServiceLabel() : r.getBooking().getServiceType())
                .customerName(r.getCustomer().getName())
                .technicianName(r.getTechnician() != null ? r.getTechnician().getName() : null)
                .serviceDate(r.getServiceDate())
                .startedAt(r.getStartedAt())
                .finishedAt(r.getFinishedAt())
                .workPerformed(r.getWorkPerformed())
                .observations(r.getObservations())
                .partsReplaced(r.getPartsReplaced())
                .recommendations(r.getRecommendations())
                .checklistJson(r.getChecklistJson())
                .panelsCleaned(r.getPanelsCleaned())
                .outputBeforeKw(r.getOutputBeforeKw() != null ? r.getOutputBeforeKw().doubleValue() : null)
                .outputAfterKw(r.getOutputAfterKw() != null ? r.getOutputAfterKw().doubleValue() : null)
                .improvementKw(r.improvementKw() != null ? r.improvementKw().doubleValue() : null)
                .nextServiceDue(r.getNextServiceDue())
                .customerSignatureName(r.getCustomerSignatureName())
                .customerRemarks(r.getCustomerRemarks())
                .published(Boolean.TRUE.equals(r.getPublished()))
                .publishedAt(r.getPublishedAt())
                .preparedBy(r.getPreparedBy())
                .fileName(r.fileName())
                .attachments(attachments == null ? List.of()
                        : attachments.stream().map(BookingAttachmentResponse::from).toList())
                .build();
    }
}
