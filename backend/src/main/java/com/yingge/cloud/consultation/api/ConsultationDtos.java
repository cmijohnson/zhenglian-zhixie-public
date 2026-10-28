package com.yingge.cloud.consultation.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class ConsultationDtos {
    private ConsultationDtos() {}

    public record Settings(
            LocalTime businessStart,
            LocalTime businessEnd,
            int minimumLeadMinutes,
            List<Integer> allowedDurations,
            int dailyCapacity,
            int feedbackDueHours,
            long version
    ) {}

    public record UpdateSettings(
            @NotNull LocalTime businessStart,
            @NotNull LocalTime businessEnd,
            @Min(0) @Max(10080) int minimumLeadMinutes,
            @NotNull @Size(min = 1, max = 10) List<@Min(15) @Max(480) Integer> allowedDurations,
            @Min(1) @Max(100) int dailyCapacity,
            @Min(1) @Max(720) int feedbackDueHours,
            @NotNull Long expectedVersion
    ) {}

    public record FailureReason(UUID id, String code, String label, boolean active, int sortOrder) {}
    public record ConsultantCandidate(UUID membershipId, String displayName, List<String> roles,
                                      List<String> teams) {}

    public record SaveFailureReason(
            @NotBlank @Size(max = 80) String code,
            @NotBlank @Size(max = 160) String label,
            boolean active,
            int sortOrder
    ) {}

    public record CreateRequest(
            @NotNull UUID customerId,
            @NotNull UUID consultantMembershipId,
            @NotNull @Size(min = 1, max = 50) List<UUID> sourceInteractionIds,
            @NotBlank @Size(max = 6000) String consultationReason,
            @NotBlank @Size(max = 10000) String customerQuotes,
            @NotBlank @Size(max = 6000) String goalsMotivation,
            @NotBlank @Size(max = 6000) String actionsTaken,
            @NotBlank @Size(max = 6000) String mainConcerns,
            @Size(max = 4000) String decisionParticipants,
            boolean decisionParticipantConsent,
            @NotBlank @Size(max = 6000) String productFit,
            @NotBlank @Size(max = 6000) String suggestedFocus,
            @NotBlank @Size(max = 6000) String explicitTaboos,
            @NotBlank @Size(max = 4000) String expectedResult,
            @NotNull Instant scheduledStart,
            @NotNull Instant scheduledEnd
    ) {}

    public record RejectRequest(@NotNull Long expectedVersion, @NotBlank @Size(max = 2000) String reason) {}
    public record CancelRequest(@NotNull Long expectedVersion, @NotBlank @Size(max = 2000) String reason) {}
    public record VersionRequest(@NotNull Long expectedVersion) {}
    public record EndRequest(@NotNull Long expectedVersion, Instant actualEndedAt) {}

    public record CompleteRequest(
            @NotNull Long expectedVersion,
            @NotBlank @Size(max = 10000) String resultSummary,
            boolean continueFollowUp,
            @NotBlank @Size(max = 6000) String nextStep,
            @NotNull UUID nextOwnerMembershipId,
            Instant nextFollowUpAt,
            UUID failureReasonId,
            @Size(max = 2000) String failureReasonOther
    ) {}

    public record Response(
            UUID id,
            UUID customerId,
            String customerName,
            Map<String, Object> customerSnapshot,
            UUID createdByMembershipId,
            String createdByName,
            UUID consultantMembershipId,
            String consultantName,
            List<UUID> sourceInteractionIds,
            String consultationReason,
            String customerQuotes,
            String goalsMotivation,
            String actionsTaken,
            String mainConcerns,
            String decisionParticipants,
            boolean decisionParticipantConsent,
            String productFit,
            String suggestedFocus,
            String explicitTaboos,
            String expectedResult,
            Instant scheduledStart,
            Instant scheduledEnd,
            String status,
            String rejectionReason,
            Instant acceptedAt,
            Instant actualEndedAt,
            Instant feedbackDueAt,
            String resultSummary,
            Boolean continueFollowUp,
            String nextStep,
            UUID nextOwnerMembershipId,
            Instant nextFollowUpAt,
            UUID failureReasonId,
            String failureReasonOther,
            UUID resultInteractionId,
            Instant createdAt,
            Instant updatedAt,
            long version
    ) {}
}
