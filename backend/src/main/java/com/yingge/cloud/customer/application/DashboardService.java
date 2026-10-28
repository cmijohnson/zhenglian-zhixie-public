package com.yingge.cloud.customer.application;

import com.yingge.cloud.auth.application.CurrentActorProvider;
import com.yingge.cloud.customer.api.CustomerDtos;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;

@Service
public class DashboardService {
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");

    private final CustomerService customers;
    private final CurrentActorProvider actors;

    public DashboardService(CustomerService customers, CurrentActorProvider actors) {
        this.customers = customers;
        this.actors = actors;
    }

    @Transactional(readOnly = true)
    public DashboardResponse dashboard() {
        var rows = customers.visibleEntities(actors.require(), true);
        Instant now = Instant.now();
        LocalDate today = LocalDate.now(BUSINESS_ZONE);
        var active = rows.stream().filter(row -> row.getArchivedAt() == null).toList();
        var dueToday = active.stream().filter(row -> row.getNextFollowUpAt() != null &&
                        row.getNextFollowUpAt().atZone(BUSINESS_ZONE).toLocalDate().equals(today))
                .map(CustomerDtos.Response::from).limit(8).toList();
        var overdue = active.stream().filter(row -> row.getNextFollowUpAt() != null && row.getNextFollowUpAt().isBefore(now))
                .map(CustomerDtos.Response::from).limit(8).toList();
        var stale = active.stream().filter(row -> "高".equals(row.getIntentionLevel()) &&
                        (row.getLastContactAt() == null || row.getLastContactAt().isBefore(now.minus(3, ChronoUnit.DAYS))))
                .map(CustomerDtos.Response::from).limit(8).toList();
        var recent = active.stream().filter(row -> row.getUpdatedAt().isAfter(now.minus(7, ChronoUnit.DAYS)))
                .limit(8).map(CustomerDtos.Response::from).toList();
        return new DashboardResponse(
                new Stats(active.size(), rows.size() - active.size(), dueToday.size(), overdue.size(),
                        active.stream().filter(row -> "高".equals(row.getIntentionLevel())).count()),
                dueToday, overdue, stale, recent
        );
    }

    public record Stats(long totalActive, long archivedCount, long todayDueCount, long overdueCount,
                        long highIntentionCount) {}

    public record DashboardResponse(Stats stats, List<CustomerDtos.Response> dueToday,
                                    List<CustomerDtos.Response> overdue,
                                    List<CustomerDtos.Response> staleHighIntention,
                                    List<CustomerDtos.Response> recentUpdated) {}
}
