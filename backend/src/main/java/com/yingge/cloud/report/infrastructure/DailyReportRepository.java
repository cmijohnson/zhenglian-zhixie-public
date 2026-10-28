package com.yingge.cloud.report.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

public interface DailyReportRepository extends JpaRepository<DailyReportEntity, UUID> {
    Optional<DailyReportEntity> findByOrganizationIdAndMembershipIdAndReportDate(
            UUID organizationId, UUID membershipId, LocalDate reportDate);
}
