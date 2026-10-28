package com.yingge.cloud.mobile.application;

import com.yingge.cloud.assistant.application.AssistantFacade;
import com.yingge.cloud.auth.application.CurrentActorProvider;
import com.yingge.cloud.interaction.application.InteractionService;
import com.yingge.cloud.mobile.api.MobileDtos;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class MobileAssistantService {
    static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    private static final String FALLBACK_SUBTITLE = "今天也从最重要的一次真实沟通开始。";

    private final AssistantFacade assistant;
    private final InteractionService interactions;
    private final CurrentActorProvider actors;
    private final Map<UUID, InsightCache> insightCache = new ConcurrentHashMap<>();
    private final Map<GreetingKey, GreetingCache> greetingCache = new ConcurrentHashMap<>();

    public MobileAssistantService(AssistantFacade assistant, InteractionService interactions,
                                  CurrentActorProvider actors) {
        this.assistant = assistant;
        this.interactions = interactions;
        this.actors = actors;
    }

    public MobileDtos.CustomerInsight insight(UUID customerId, String traceId) {
        assistant.requireCustomerAccess(customerId);
        Instant timelineUpdatedAt = interactions.list(customerId).stream()
                .map(row -> row.createdAt() == null ? row.contactedAt() : row.createdAt())
                .max(Instant::compareTo)
                .orElse(Instant.EPOCH);
        InsightCache cached = insightCache.get(customerId);
        if (cached != null && cached.timelineUpdatedAt().equals(timelineUpdatedAt)) {
            return new MobileDtos.CustomerInsight(cached.content(), cached.generatedAt(),
                    cached.timelineUpdatedAt(), traceId);
        }
        String content = assistant.summarizeCustomerTimeline(customerId);
        InsightCache saved = new InsightCache(content, Instant.now(), timelineUpdatedAt);
        insightCache.put(customerId, saved);
        return new MobileDtos.CustomerInsight(saved.content(), saved.generatedAt(), saved.timelineUpdatedAt(), traceId);
    }

    public MobileDtos.DashboardGreeting greeting(String traceId) {
        var actor = actors.require();
        ZonedDateTime now = ZonedDateTime.now(BUSINESS_ZONE);
        TimeBand band = TimeBand.at(now.getHour());
        GreetingKey key = new GreetingKey(actor.organizationId(), actor.membershipId(), now.toLocalDate(), band);
        GreetingCache cached = greetingCache.get(key);
        if (cached == null) {
            String subtitle;
            boolean generatedByAi;
            try {
                subtitle = assistant.generateMobileGreeting(band.salutation());
                generatedByAi = !subtitle.isBlank();
                if (!generatedByAi) subtitle = FALLBACK_SUBTITLE;
            } catch (RuntimeException exception) {
                subtitle = FALLBACK_SUBTITLE;
                generatedByAi = false;
            }
            cached = new GreetingCache(subtitle, generatedByAi, band.validUntil(now).toInstant());
            greetingCache.put(key, cached);
        }
        return new MobileDtos.DashboardGreeting(band.salutation(), cached.subtitle(), cached.generatedByAi(),
                cached.validUntil(), traceId);
    }

    enum TimeBand {
        MORNING("早上好"), NOON("中午好"), AFTERNOON("下午好"), EVENING("晚上好"), LATE_NIGHT("夜深了");

        private final String salutation;
        TimeBand(String salutation) { this.salutation = salutation; }
        String salutation() { return salutation; }

        static TimeBand at(int hour) {
            if (hour >= 5 && hour <= 10) return MORNING;
            if (hour >= 11 && hour <= 13) return NOON;
            if (hour >= 14 && hour <= 17) return AFTERNOON;
            if (hour >= 18 && hour <= 22) return EVENING;
            return LATE_NIGHT;
        }

        ZonedDateTime validUntil(ZonedDateTime now) {
            return switch (this) {
                case MORNING -> now.withHour(11).withMinute(0).withSecond(0).withNano(0);
                case NOON -> now.withHour(14).withMinute(0).withSecond(0).withNano(0);
                case AFTERNOON -> now.withHour(18).withMinute(0).withSecond(0).withNano(0);
                case EVENING -> now.withHour(23).withMinute(0).withSecond(0).withNano(0);
                case LATE_NIGHT -> now.getHour() < 5
                        ? now.withHour(5).withMinute(0).withSecond(0).withNano(0)
                        : now.plusDays(1).withHour(5).withMinute(0).withSecond(0).withNano(0);
            };
        }
    }

    private record InsightCache(String content, Instant generatedAt, Instant timelineUpdatedAt) {}
    private record GreetingKey(UUID organizationId, UUID membershipId, LocalDate date, TimeBand band) {}
    private record GreetingCache(String subtitle, boolean generatedByAi, Instant validUntil) {}
}
