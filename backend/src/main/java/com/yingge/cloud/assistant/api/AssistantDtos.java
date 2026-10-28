package com.yingge.cloud.assistant.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class AssistantDtos {
    private AssistantDtos() {}

    public record ParseRequest(@NotBlank @Size(max = 20000) String text) {}
    public record CustomerDraft(
            String fullName, String nickname, String phone, String wechatId, String qqId,
            String school, String major, String grade, String city, String identityType,
            List<String> focusDirections, String demandSummary, String intentionLevel,
            String urgencyLevel, String verificationStatus, String sourceChannel,
            String currentStatus, String ownerNotes, Instant nextFollowUpAt,
            List<String> uncertainties
    ) {}
    public record InteractionDraft(
            Instant contactedAt, String channel, String summary, String details, String outcome,
            String verificationTodo, Instant nextFollowUpAt, String currentStatus,
            String intentionLevel, String verificationStatus, List<String> uncertainties
    ) {}
    public record Availability(boolean available, String provider, String model) {}
    public record QqConversationDraft(
            @NotBlank @Size(max = 10000) String summary,
            @NotNull @Size(max = 50) List<@Size(max = 2000) String> customerQuotes,
            @Size(max = 10000) String goalsMotivation,
            @Size(max = 10000) String actionsTaken,
            @Size(max = 10000) String mainConcerns,
            @Size(max = 6000) String decisionParticipants,
            @Size(max = 10000) String productFit,
            @Size(max = 10000) String suggestedFocus,
            @Size(max = 10000) String explicitTaboos,
            @Size(max = 10000) String outcome,
            @Size(max = 10000) String nextStep,
            Instant nextFollowUpAt,
            @NotNull @Size(max = 50) List<@Size(max = 2000) String> uncertainties
    ) {}
    public record InteractionCitation(UUID interactionId, String evidence) {}
    public record ConsultationDraft(
            String consultationReason,
            String customerQuotes,
            String goalsMotivation,
            String actionsTaken,
            String mainConcerns,
            String decisionParticipants,
            String productFit,
            String suggestedFocus,
            String explicitTaboos,
            String expectedResult,
            List<InteractionCitation> citations,
            List<String> uncertainties
    ) {}
}
