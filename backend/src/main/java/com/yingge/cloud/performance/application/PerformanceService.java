package com.yingge.cloud.performance.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yingge.cloud.audit.application.AuditService;
import com.yingge.cloud.auth.application.CurrentActorProvider;
import com.yingge.cloud.performance.api.PerformanceDtos;
import com.yingge.cloud.permission.domain.CurrentActor;
import com.yingge.cloud.permission.domain.RoleCode;
import com.yingge.cloud.shared.api.ApiException;
import com.yingge.cloud.shared.domain.TextNormalizer;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
public class PerformanceService {
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    private static final Set<String> CLAIM_TYPES = Set.of("ORDINARY", "DEEP");
    private static final Set<String> CALENDAR_MODES = Set.of("ALL_DAYS", "WEEKDAYS", "CUSTOM");
    private static final Set<String> SHORTFALL_MODES = Set.of("STATUS_ONLY", "DEDUCT_GAP");
    private static final long MAX_EVIDENCE_SIZE = 5L * 1024 * 1024;
    private static final TypeReference<Set<Integer>> INTEGER_SET = new TypeReference<>() {};
    private static final TypeReference<Set<LocalDate>> DATE_SET = new TypeReference<>() {};
    private static final TypeReference<List<PerformanceDtos.EventTier>> TIER_LIST = new TypeReference<>() {};

    private final NamedParameterJdbcTemplate jdbc;
    private final CurrentActorProvider actors;
    private final AuditService audit;
    private final ObjectMapper json;

    public PerformanceService(NamedParameterJdbcTemplate jdbc, CurrentActorProvider actors,
                              AuditService audit, ObjectMapper json) {
        this.jdbc = jdbc;
        this.actors = actors;
        this.audit = audit;
        this.json = json;
    }

    @Transactional
    public PerformanceDtos.TodayResponse today() {
        CurrentActor actor = actors.require();
        LocalDate date = LocalDate.now(BUSINESS_ZONE);
        RuleRow rule = ruleFor(actor.organizationId(), date);
        boolean scoringDay = isScoringDay(rule, date);
        String status = scoringDay ? refreshDay(actor.organizationId(), date, rule) : "NON_SCORING";
        ParticipantRow participant = participant(actor.organizationId(), actor.membershipId(), date);
        boolean submissionOpen = participant != null && scoringDay
                && canSubmit(actor.organizationId(), date, rule, status);
        return new PerformanceDtos.TodayResponse(
                date, participant != null, status, submissionOpen,
                rule.opensAt(), rule.closesAt(), ruleResponse(rule),
                score(actor.organizationId(), actor.membershipId(), date, rule),
                countClaims(actor.organizationId(), actor.membershipId(), date, "PENDING"),
                countActivities(actor.organizationId(), actor.membershipId(), date, "PROPOSED,APPROVED,COMPLETION_SUBMITTED"),
                approvedDeepCount(actor.organizationId(), actor.membershipId(), date),
                pendingDeepCount(actor.organizationId(), actor.membershipId(), date),
                availableInteractions(actor.organizationId(), actor.membershipId(), date),
                claimsFor(actor, null, actor.membershipId(), date),
                activitiesFor(actor, null, actor.membershipId(), null),
                ledger(actor.organizationId(), actor.membershipId(), date, date)
        );
    }

    @Transactional(readOnly = true)
    public List<PerformanceDtos.LeaderboardRow> leaderboard(LocalDate from, LocalDate to, UUID teamId) {
        CurrentActor actor = actors.require();
        validateRange(from, to, 366);
        UUID scopedTeam = leaderboardTeam(actor, teamId);
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("organizationId", actor.organizationId())
                .addValue("fromDate", from)
                .addValue("toDate", to)
                .addValue("teamId", scopedTeam);
        String teamClause = scopedTeam == null ? "" : "and p.team_id_snapshot=:teamId";
        List<LeaderboardBase> rows = jdbc.query("""
                with participant_members as (
                    select distinct p.membership_id, p.team_id_snapshot
                    from performance_participants p
                    where p.organization_id=:organizationId
                      and p.starts_on<=:toDate and (p.ends_on is null or p.ends_on>=:fromDate)
                      %s
                ), ledger_daily as (
                    select l.membership_id, l.team_id_snapshot, l.performance_date, sum(l.points) earned
                    from performance_score_ledger l
                    where l.organization_id=:organizationId
                      and l.performance_date between :fromDate and :toDate
                    group by l.membership_id, l.team_id_snapshot, l.performance_date
                ), daily_scores as (
                    select s.membership_id, s.team_id_snapshot, s.performance_date, s.earned_points earned,
                           s.final_points, s.baseline
                    from performance_daily_settlements s
                    where s.organization_id=:organizationId
                      and s.active=true
                      and s.performance_date between :fromDate and :toDate
                    union all
                    select ld.membership_id, ld.team_id_snapshot, ld.performance_date, ld.earned,
                           ld.earned final_points, null::numeric baseline
                    from ledger_daily ld
                    where not exists (
                        select 1 from performance_daily_settlements s
                        where s.organization_id=:organizationId
                          and s.membership_id=ld.membership_id
                          and s.performance_date=ld.performance_date
                          and s.active=true
                    )
                ), totals as (
                    select pm.membership_id,
                           pm.team_id_snapshot,
                           coalesce(sum(ds.earned),0) earned,
                           coalesce(sum(ds.final_points),0) final_points,
                           count(distinct ds.performance_date) scored_days,
                           count(distinct case when ds.baseline is not null and ds.earned>=ds.baseline
                                               then ds.performance_date end) achieved_days
                    from participant_members pm
                    left join daily_scores ds on ds.membership_id=pm.membership_id
                     and ds.team_id_snapshot is not distinct from pm.team_id_snapshot
                    group by pm.membership_id, pm.team_id_snapshot
                )
                select totals.membership_id, account.display_name,
                       team.id team_id, coalesce(team.name,'未分组') team_name,
                       totals.earned, totals.final_points, totals.scored_days, totals.achieved_days
                from totals
                join memberships membership on membership.id=totals.membership_id
                join user_accounts account on account.id=membership.user_id
                left join teams team on team.id=totals.team_id_snapshot
                order by totals.final_points desc, totals.earned desc, account.display_name
                """.formatted(teamClause), params, (row, index) -> new LeaderboardBase(
                row.getObject("membership_id", UUID.class), row.getString("display_name"),
                row.getObject("team_id", UUID.class), row.getString("team_name"),
                money(row.getBigDecimal("earned")), money(row.getBigDecimal("final_points")),
                row.getInt("scored_days"), row.getInt("achieved_days")));
        List<PerformanceDtos.LeaderboardRow> result = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            LeaderboardBase row = rows.get(i);
            result.add(new PerformanceDtos.LeaderboardRow(i + 1, row.membershipId(), row.displayName(),
                    row.teamId(), row.teamName(), row.earned(), row.finalPoints(), row.scoredDays(), row.achievedDays()));
        }
        return result;
    }

    @Transactional(readOnly = true)
    public List<PerformanceDtos.ClaimResponse> claims(String status, UUID membershipId, LocalDate date) {
        CurrentActor actor = actors.require();
        UUID scopedMember = scopedMember(actor, membershipId);
        return claimsFor(actor, normalizeStatus(status), scopedMember, date);
    }

    @Transactional(readOnly = true)
    public List<PerformanceDtos.HistoryPoint> history(LocalDate from, LocalDate to) {
        CurrentActor actor = actors.require();
        validateRange(from, to, 93);
        return jdbc.query("""
                with dates as (
                    select generate_series(:fromDate::date, :toDate::date, interval '1 day')::date performance_date
                ), ledger_daily as (
                    select performance_date, sum(points) earned
                    from performance_score_ledger
                    where organization_id=:organizationId and membership_id=:membershipId
                      and performance_date between :fromDate and :toDate
                    group by performance_date
                )
                select dates.performance_date,
                       coalesce(settlement.earned_points, ledger_daily.earned, 0) earned_points,
                       coalesce(settlement.final_points, ledger_daily.earned, 0) final_points,
                       settlement.baseline,
                       settlement.id is not null settled
                from dates
                left join ledger_daily on ledger_daily.performance_date=dates.performance_date
                left join performance_daily_settlements settlement
                  on settlement.organization_id=:organizationId
                 and settlement.membership_id=:membershipId
                 and settlement.performance_date=dates.performance_date
                 and settlement.active=true
                where ledger_daily.performance_date is not null or settlement.id is not null
                order by dates.performance_date
                """, new MapSqlParameterSource().addValue("organizationId", actor.organizationId())
                .addValue("membershipId", actor.membershipId()).addValue("fromDate", from).addValue("toDate", to),
                (row, index) -> new PerformanceDtos.HistoryPoint(
                        row.getObject("performance_date", LocalDate.class), money(row.getBigDecimal("earned_points")),
                        money(row.getBigDecimal("final_points")), moneyNullable(row.getBigDecimal("baseline")),
                        row.getBoolean("settled")));
    }

    @Transactional
    public PerformanceDtos.ClaimResponse createClaim(PerformanceDtos.CreateClaimRequest body) {
        CurrentActor actor = contributor();
        String type = claimType(body.requestedType());
        InteractionRow interaction = jdbc.query("""
                select interaction.id, interaction.customer_id, interaction.contacted_at,
                       interaction.channel, interaction.summary, customer.full_name
                from interactions interaction
                join customers customer on customer.id=interaction.customer_id
                where interaction.id=:interactionId and interaction.organization_id=:organizationId
                  and interaction.created_by_membership_id=:membershipId
                """, new MapSqlParameterSource()
                .addValue("interactionId", body.interactionId())
                .addValue("organizationId", actor.organizationId())
                .addValue("membershipId", actor.membershipId()), (row, index) -> new InteractionRow(
                row.getObject("id", UUID.class), row.getObject("customer_id", UUID.class),
                row.getTimestamp("contacted_at").toInstant(), row.getString("channel"),
                row.getString("summary"), row.getString("full_name"))).stream().findFirst()
                .orElseThrow(() -> ApiException.notFound("PERFORMANCE_INTERACTION_NOT_FOUND", "沟通记录不存在或不是由当前账号创建"));
        LocalDate date = interaction.contactedAt().atZone(BUSINESS_ZONE).toLocalDate();
        RuleRow rule = ruleFor(actor.organizationId(), date);
        ParticipantRow scoringParticipant = requireParticipant(actor.organizationId(), actor.membershipId(), date);
        requireScoringDay(rule, date);
        String dayStatus = refreshDay(actor.organizationId(), date, rule);
        if (!canSubmit(actor.organizationId(), date, rule, dayStatus)) {
            throw ApiException.conflict("PERFORMANCE_SUBMISSION_CLOSED", "该日期的绩效申报窗口未开放");
        }
        UUID id = UUID.randomUUID();
        try {
            jdbc.update("""
                    insert into performance_interaction_claims (
                        id, organization_id, membership_id, team_id_snapshot, interaction_id,
                        customer_id, performance_date, requested_type
                    ) values (:id,:organizationId,:membershipId,:teamId,:interactionId,:customerId,:date,:type)
                    """, new MapSqlParameterSource()
                    .addValue("id", id).addValue("organizationId", actor.organizationId())
                    .addValue("membershipId", actor.membershipId())
                    .addValue("teamId", scoringParticipant.teamId())
                    .addValue("interactionId", interaction.id()).addValue("customerId", interaction.customerId())
                    .addValue("date", date).addValue("type", type));
        } catch (DataIntegrityViolationException exception) {
            throw ApiException.conflict("PERFORMANCE_INTERACTION_ALREADY_CLAIMED", "该沟通记录已经申报过绩效");
        }
        audit.record(actor, "PERFORMANCE_CLAIM_CREATED", "PERFORMANCE_CLAIM", id.toString(), "SUCCESS",
                Map.of("interactionId", interaction.id().toString(), "type", type));
        return claim(id, actor.organizationId());
    }

    @Transactional
    public PerformanceDtos.EvidenceResponse uploadEvidence(String ownerType, UUID ownerId, MultipartFile file) {
        CurrentActor actor = actors.require();
        String normalizedOwner = ownerType.toUpperCase(Locale.ROOT);
        requireEvidenceOwner(normalizedOwner, ownerId, actor, true);
        Long existing = jdbc.queryForObject("""
                select count(*) from performance_evidence
                where organization_id=:organizationId and owner_type=:ownerType and owner_id=:ownerId
                """, new MapSqlParameterSource().addValue("organizationId", actor.organizationId())
                .addValue("ownerType", normalizedOwner).addValue("ownerId", ownerId), Long.class);
        if (existing != null && existing >= 3) {
            throw ApiException.conflict("PERFORMANCE_EVIDENCE_LIMIT", "每次申报最多上传 3 张证据图片");
        }
        EvidenceBytes evidence = evidenceBytes(file);
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into performance_evidence (
                    id, organization_id, owner_type, owner_id, uploaded_by_membership_id,
                    file_name, content_type, file_size, content
                ) values (:id,:organizationId,:ownerType,:ownerId,:membershipId,
                          :fileName,:contentType,:fileSize,:content)
                """, new MapSqlParameterSource().addValue("id", id)
                .addValue("organizationId", actor.organizationId()).addValue("ownerType", normalizedOwner)
                .addValue("ownerId", ownerId).addValue("membershipId", actor.membershipId())
                .addValue("fileName", evidence.fileName()).addValue("contentType", evidence.contentType())
                .addValue("fileSize", evidence.content().length).addValue("content", evidence.content()));
        audit.record(actor, "PERFORMANCE_EVIDENCE_UPLOADED", normalizedOwner, ownerId.toString(), "SUCCESS",
                Map.of("fileSize", evidence.content().length));
        return evidenceMetadata(id, actor.organizationId());
    }

    @Transactional
    public PerformanceDtos.ClaimResponse submitClaim(UUID id) {
        CurrentActor actor = contributor();
        ClaimRow claim = claimRow(id, actor.organizationId());
        requireOwner(claim.membershipId(), actor);
        if (!"DRAFT".equals(claim.status())) {
            throw ApiException.conflict("PERFORMANCE_CLAIM_NOT_DRAFT", "只有草稿申报可以提交");
        }
        RuleRow rule = ruleFor(actor.organizationId(), claim.performanceDate());
        String dayStatus = refreshDay(actor.organizationId(), claim.performanceDate(), rule);
        if (!canSubmit(actor.organizationId(), claim.performanceDate(), rule, dayStatus)) {
            throw ApiException.conflict("PERFORMANCE_SUBMISSION_CLOSED", "该日期的绩效申报窗口未开放");
        }
        requireEvidence(id, "INTERACTION", actor.organizationId());
        jdbc.update("""
                update performance_interaction_claims set status='PENDING', submitted_at=now(),
                       updated_at=now(), version=version+1
                where id=:id and organization_id=:organizationId and version=:version
                """, new MapSqlParameterSource().addValue("id", id)
                .addValue("organizationId", actor.organizationId()).addValue("version", claim.version()));
        audit.record(actor, "PERFORMANCE_CLAIM_SUBMITTED", "PERFORMANCE_CLAIM", id.toString(), "SUCCESS", null);
        return claim(id, actor.organizationId());
    }

    @Transactional
    public PerformanceDtos.ClaimResponse reviewClaim(UUID id, PerformanceDtos.ReviewClaimRequest body) {
        CurrentActor actor = admin();
        ClaimRow claim = claimRow(id, actor.organizationId());
        if (!"PENDING".equals(claim.status())) {
            throw ApiException.conflict("PERFORMANCE_CLAIM_NOT_PENDING", "只有待审核申报可以处理");
        }
        requireVersion(claim.version(), body.expectedVersion());
        String decision = body.decision().trim().toUpperCase(Locale.ROOT);
        String reason = TextNormalizer.optional(body.reason());
        if ("REJECT".equals(decision)) {
            if (reason == null) throw ApiException.badRequest("PERFORMANCE_REVIEW_REASON_REQUIRED", "驳回时必须填写原因");
            updateClaimReview(claim, "REJECTED", null, reason, actor);
        } else if ("APPROVE".equals(decision)) {
            String approvedType = claimType(body.approvedType() == null ? claim.requestedType() : body.approvedType());
            RuleRow rule = ruleFor(actor.organizationId(), claim.performanceDate());
            Integer cap = "DEEP".equals(approvedType) ? rule.deepCap() : rule.ordinaryCap();
            if (cap != null && approvedCount(actor.organizationId(), claim.membershipId(), claim.performanceDate(), approvedType) >= cap) {
                throw ApiException.conflict("PERFORMANCE_DAILY_CAP_REACHED", "该成员当天此类交流已达到计分上限");
            }
            BigDecimal points = "DEEP".equals(approvedType) ? rule.deepPoints() : rule.ordinaryPoints();
            updateClaimReview(claim, "APPROVED", approvedType, reason, actor);
            insertLedger(actor.organizationId(), claim.membershipId(), claim.teamId(), claim.performanceDate(),
                    "INTERACTION", claim.id(), points,
                    ("DEEP".equals(approvedType) ? "深度交流" : "普通交流") + "审核通过");
        } else {
            throw ApiException.badRequest("INVALID_PERFORMANCE_DECISION", "审核决定只能是 APPROVE 或 REJECT");
        }
        audit.record(actor, "PERFORMANCE_CLAIM_REVIEWED", "PERFORMANCE_CLAIM", id.toString(), "SUCCESS",
                Map.of("decision", decision));
        return claim(id, actor.organizationId());
    }

    @Transactional(readOnly = true)
    public List<PerformanceDtos.ActivityResponse> activities(String status, UUID membershipId, LocalDate date) {
        CurrentActor actor = actors.require();
        return activitiesFor(actor, normalizeStatus(status), scopedMember(actor, membershipId), date);
    }

    @Transactional
    public PerformanceDtos.ActivityResponse createActivity(PerformanceDtos.CreateActivityRequest body) {
        CurrentActor actor = contributor();
        ParticipantRow scoringParticipant = requireParticipant(actor.organizationId(), actor.membershipId(), body.eventDate());
        requireScoringDay(ruleFor(actor.organizationId(), body.eventDate()), body.eventDate());
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into performance_activities (
                    id, organization_id, organizer_membership_id, team_id_snapshot, event_date,
                    title, goal, expected_participants, description
                ) values (:id,:organizationId,:membershipId,:teamId,:eventDate,:title,:goal,:expected,:description)
                """, new MapSqlParameterSource().addValue("id", id)
                .addValue("organizationId", actor.organizationId()).addValue("membershipId", actor.membershipId())
                .addValue("teamId", scoringParticipant.teamId())
                .addValue("eventDate", body.eventDate()).addValue("title", body.title().trim())
                .addValue("goal", body.goal().trim()).addValue("expected", body.expectedParticipants())
                .addValue("description", body.description().trim()));
        audit.record(actor, "PERFORMANCE_ACTIVITY_PROPOSED", "PERFORMANCE_ACTIVITY", id.toString(), "SUCCESS", null);
        return activity(id, actor.organizationId());
    }

    @Transactional
    public PerformanceDtos.ActivityResponse reviewActivity(UUID id, PerformanceDtos.ReviewActivityRequest body) {
        CurrentActor actor = admin();
        ActivityRow activity = activityRow(id, actor.organizationId());
        if (!"PROPOSED".equals(activity.status())) {
            throw ApiException.conflict("PERFORMANCE_ACTIVITY_NOT_PROPOSED", "只有待立项活动可以审核");
        }
        requireVersion(activity.version(), body.expectedVersion());
        String decision = body.decision().trim().toUpperCase(Locale.ROOT);
        String reason = TextNormalizer.optional(body.reason());
        if ("REJECT".equals(decision) && reason == null) {
            throw ApiException.badRequest("PERFORMANCE_REVIEW_REASON_REQUIRED", "驳回时必须填写原因");
        }
        String status = switch (decision) {
            case "APPROVE" -> "APPROVED";
            case "REJECT" -> "REJECTED";
            default -> throw ApiException.badRequest("INVALID_PERFORMANCE_DECISION", "审核决定只能是 APPROVE 或 REJECT");
        };
        jdbc.update("""
                update performance_activities set status=:status, review_reason=:reason,
                       reviewed_by_membership_id=:reviewer, approved_at=case when :status='APPROVED' then now() else null end,
                       updated_at=now(), version=version+1
                where id=:id and organization_id=:organizationId and version=:version
                """, new MapSqlParameterSource().addValue("status", status).addValue("reason", reason)
                .addValue("reviewer", actor.membershipId()).addValue("id", id)
                .addValue("organizationId", actor.organizationId()).addValue("version", activity.version()));
        audit.record(actor, "PERFORMANCE_ACTIVITY_REVIEWED", "PERFORMANCE_ACTIVITY", id.toString(), "SUCCESS",
                Map.of("decision", decision));
        return activity(id, actor.organizationId());
    }

    @Transactional
    public PerformanceDtos.ActivityResponse completeActivity(UUID id, PerformanceDtos.CompleteActivityRequest body) {
        CurrentActor actor = contributor();
        ActivityRow activity = activityRow(id, actor.organizationId());
        requireOwner(activity.organizerId(), actor);
        if (!"APPROVED".equals(activity.status())) {
            throw ApiException.conflict("PERFORMANCE_ACTIVITY_NOT_APPROVED", "活动尚未立项或已经提交成果");
        }
        requireVersion(activity.version(), body.expectedVersion());
        requireEvidence(id, "ACTIVITY", actor.organizationId());
        requireDayUnlocked(actor.organizationId(), activity.eventDate());
        jdbc.update("""
                update performance_activities set actual_participants=:actual, status='COMPLETION_SUBMITTED',
                       completed_submitted_at=now(), updated_at=now(), version=version+1
                where id=:id and organization_id=:organizationId and version=:version
                """, new MapSqlParameterSource().addValue("actual", body.actualParticipants()).addValue("id", id)
                .addValue("organizationId", actor.organizationId()).addValue("version", activity.version()));
        audit.record(actor, "PERFORMANCE_ACTIVITY_COMPLETED", "PERFORMANCE_ACTIVITY", id.toString(), "SUCCESS",
                Map.of("actualParticipants", body.actualParticipants()));
        return activity(id, actor.organizationId());
    }

    @Transactional
    public PerformanceDtos.ActivityResponse allocateActivity(UUID id, PerformanceDtos.AllocateActivityRequest body) {
        CurrentActor actor = admin();
        ActivityRow activity = activityRow(id, actor.organizationId());
        if (!"COMPLETION_SUBMITTED".equals(activity.status())) {
            throw ApiException.conflict("PERFORMANCE_ACTIVITY_NOT_READY", "活动成果尚未提交或已经完成分配");
        }
        requireVersion(activity.version(), body.expectedVersion());
        requireDayUnlocked(actor.organizationId(), activity.eventDate());
        RuleRow rule = ruleFor(actor.organizationId(), activity.eventDate());
        PerformanceDtos.EventTier tier = tierFor(rule.tiers(), body.verifiedParticipants());
        if (tier == null) {
            throw ApiException.badRequest("PERFORMANCE_EVENT_TIER_NOT_FOUND", "核定人数没有匹配的活动档次");
        }
        BigDecimal total = points(tier.points(), false);
        BigDecimal allocated = BigDecimal.ZERO;
        Set<UUID> seen = new LinkedHashSet<>();
        for (PerformanceDtos.ActivityAllocation allocation : body.allocations()) {
            if (!seen.add(allocation.membershipId())) {
                throw ApiException.badRequest("PERFORMANCE_DUPLICATE_ALLOCATION", "同一成员不能重复分配活动分");
            }
            requireParticipant(actor.organizationId(), allocation.membershipId(), activity.eventDate());
            allocated = allocated.add(points(allocation.points(), false));
        }
        if (allocated.compareTo(total) != 0) {
            throw ApiException.badRequest("PERFORMANCE_ALLOCATION_TOTAL_MISMATCH", "活动分配合计必须等于档次总分 " + total);
        }
        for (PerformanceDtos.ActivityAllocation allocation : body.allocations()) {
            BigDecimal value = points(allocation.points(), false);
            UUID allocationId = UUID.randomUUID();
            UUID teamId = requireParticipant(actor.organizationId(), allocation.membershipId(), activity.eventDate()).teamId();
            jdbc.update("""
                    insert into performance_activity_allocations (
                        id, organization_id, activity_id, membership_id, team_id_snapshot, points
                    ) values (:id,:organizationId,:activityId,:membershipId,:teamId,:points)
                    """, new MapSqlParameterSource().addValue("id", allocationId)
                    .addValue("organizationId", actor.organizationId()).addValue("activityId", id)
                    .addValue("membershipId", allocation.membershipId()).addValue("teamId", teamId)
                    .addValue("points", value));
            insertLedger(actor.organizationId(), allocation.membershipId(), teamId, activity.eventDate(),
                    "ACTIVITY", allocationId, value, "活动「" + activity.title() + "」分配");
        }
        jdbc.update("""
                update performance_activities set verified_participants=:verified, tier_label=:tierLabel,
                       total_points=:totalPoints, status='ALLOCATED', reviewed_by_membership_id=:reviewer,
                       allocated_at=now(), updated_at=now(), version=version+1
                where id=:id and organization_id=:organizationId and version=:version
                """, new MapSqlParameterSource().addValue("verified", body.verifiedParticipants())
                .addValue("tierLabel", tier.label()).addValue("totalPoints", total)
                .addValue("reviewer", actor.membershipId()).addValue("id", id)
                .addValue("organizationId", actor.organizationId()).addValue("version", activity.version()));
        audit.record(actor, "PERFORMANCE_ACTIVITY_ALLOCATED", "PERFORMANCE_ACTIVITY", id.toString(), "SUCCESS",
                Map.of("verifiedParticipants", body.verifiedParticipants(), "totalPoints", total));
        return activity(id, actor.organizationId());
    }

    @Transactional(readOnly = true)
    public EvidenceFile evidence(UUID id) {
        CurrentActor actor = actors.require();
        EvidenceRow row = evidenceRow(id, actor.organizationId());
        requireEvidenceOwner(row.ownerType(), row.ownerId(), actor, false);
        return new EvidenceFile(row.fileName(), row.contentType(), row.content());
    }

    @Transactional(readOnly = true)
    public List<PerformanceDtos.RuleResponse> rules() {
        CurrentActor actor = admin();
        return jdbc.query("""
                select * from performance_rule_versions where organization_id=:organizationId
                order by effective_from desc, version_no desc
                """, new MapSqlParameterSource().addValue("organizationId", actor.organizationId()),
                (row, index) -> ruleResponse(mapRule(row)));
    }

    @Transactional
    public PerformanceDtos.RuleResponse createRule(PerformanceDtos.CreateRuleRequest body) {
        CurrentActor actor = admin();
        if (!body.effectiveFrom().isAfter(LocalDate.now(BUSINESS_ZONE))) {
            throw ApiException.badRequest("PERFORMANCE_RULE_FUTURE_REQUIRED", "新规则必须从未来日期开始生效");
        }
        String calendarMode = enumValue(body.calendarMode(), CALENDAR_MODES, "INVALID_PERFORMANCE_CALENDAR");
        String shortfallMode = enumValue(body.shortfallMode(), SHORTFALL_MODES, "INVALID_PERFORMANCE_SHORTFALL");
        if (!body.submissionOpensAt().isBefore(body.submissionClosesAt())) {
            throw ApiException.badRequest("INVALID_PERFORMANCE_WINDOW", "申报开始时间必须早于截止时间");
        }
        Set<Integer> weekdays = body.weekdays() == null || body.weekdays().isEmpty()
                ? Set.of(1, 2, 3, 4, 5) : Set.copyOf(body.weekdays());
        if (weekdays.stream().anyMatch(day -> day < 1 || day > 7)) {
            throw ApiException.badRequest("INVALID_PERFORMANCE_WEEKDAY", "星期必须在 1 到 7 之间");
        }
        BigDecimal baseline = points(body.dailyBaseline(), false);
        BigDecimal ordinary = points(body.ordinaryPoints(), false);
        BigDecimal deep = points(body.deepPoints(), false);
        Integer ordinaryCap = positiveCap(body.ordinaryDailyCap());
        Integer deepCap = positiveCap(body.deepDailyCap());
        List<PerformanceDtos.EventTier> tiers = validateTiers(body.eventTiers());
        Integer version = jdbc.queryForObject("""
                select coalesce(max(version_no),0)+1 from performance_rule_versions where organization_id=:organizationId
                """, new MapSqlParameterSource().addValue("organizationId", actor.organizationId()), Integer.class);
        UUID id = UUID.randomUUID();
        try {
            jdbc.update("""
                    insert into performance_rule_versions (
                        id, organization_id, version_no, effective_from, daily_baseline, calendar_mode,
                        weekdays, included_dates, excluded_dates, submission_opens_at, submission_closes_at,
                        shortfall_mode, ordinary_points, deep_points, ordinary_daily_cap, deep_daily_cap,
                        event_tiers, created_by_membership_id
                    ) values (:id,:organizationId,:version,:effectiveFrom,:baseline,:calendarMode,
                              cast(:weekdays as jsonb),cast(:included as jsonb),cast(:excluded as jsonb),:opens,:closes,
                              :shortfall,:ordinary,:deep,:ordinaryCap,:deepCap,cast(:tiers as jsonb),:creator)
                    """, new MapSqlParameterSource().addValue("id", id)
                    .addValue("organizationId", actor.organizationId()).addValue("version", version)
                    .addValue("effectiveFrom", body.effectiveFrom()).addValue("baseline", baseline)
                    .addValue("calendarMode", calendarMode).addValue("weekdays", toJson(weekdays))
                    .addValue("included", toJson(body.includedDates() == null ? Set.of() : body.includedDates()))
                    .addValue("excluded", toJson(body.excludedDates() == null ? Set.of() : body.excludedDates()))
                    .addValue("opens", body.submissionOpensAt()).addValue("closes", body.submissionClosesAt())
                    .addValue("shortfall", shortfallMode).addValue("ordinary", ordinary).addValue("deep", deep)
                    .addValue("ordinaryCap", ordinaryCap).addValue("deepCap", deepCap)
                    .addValue("tiers", toJson(tiers)).addValue("creator", actor.membershipId()));
        } catch (DataIntegrityViolationException exception) {
            throw ApiException.conflict("PERFORMANCE_RULE_DATE_EXISTS", "该生效日期已经存在规则版本");
        }
        audit.record(actor, "PERFORMANCE_RULE_CREATED", "PERFORMANCE_RULE", id.toString(), "SUCCESS",
                Map.of("effectiveFrom", body.effectiveFrom().toString(), "version", version));
        return ruleResponse(ruleById(id, actor.organizationId()));
    }

    @Transactional(readOnly = true)
    public List<PerformanceDtos.ParticipantResponse> participants() {
        CurrentActor actor = admin();
        return participantRows(actor.organizationId());
    }

    @Transactional
    public PerformanceDtos.ParticipantResponse addParticipant(PerformanceDtos.ParticipantRequest body) {
        CurrentActor actor = admin();
        validateParticipantDates(body.startsOn(), body.endsOn());
        MemberRow member = eligibleMember(actor.organizationId(), body.membershipId());
        if (overlapsParticipant(actor.organizationId(), body.membershipId(), body.startsOn(), body.endsOn(), null)) {
            throw ApiException.conflict("PERFORMANCE_PARTICIPANT_OVERLAP", "该成员在所选日期已经参与绩效");
        }
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into performance_participants (
                    id, organization_id, membership_id, team_id_snapshot, starts_on, ends_on, created_by_membership_id
                ) values (:id,:organizationId,:membershipId,:teamId,:startsOn,:endsOn,:creator)
                """, new MapSqlParameterSource().addValue("id", id)
                .addValue("organizationId", actor.organizationId()).addValue("membershipId", body.membershipId())
                .addValue("teamId", member.teamId()).addValue("startsOn", body.startsOn())
                .addValue("endsOn", body.endsOn()).addValue("creator", actor.membershipId()));
        audit.record(actor, "PERFORMANCE_PARTICIPANT_ADDED", "PERFORMANCE_PARTICIPANT", id.toString(), "SUCCESS", null);
        return participantResponse(id, actor.organizationId());
    }

    @Transactional
    public PerformanceDtos.ParticipantResponse updateParticipant(UUID id, PerformanceDtos.ParticipantUpdateRequest body) {
        CurrentActor actor = admin();
        ParticipantRow row = participantById(id, actor.organizationId());
        validateParticipantDates(row.startsOn(), body.endsOn());
        requireVersion(row.version(), body.expectedVersion());
        if (overlapsParticipant(actor.organizationId(), row.membershipId(), row.startsOn(), body.endsOn(), id)) {
            throw ApiException.conflict("PERFORMANCE_PARTICIPANT_OVERLAP", "结束日期与该成员其他参与周期重叠");
        }
        int updated = jdbc.update("""
                update performance_participants set ends_on=:endsOn, updated_at=now(), version=version+1
                where id=:id and organization_id=:organizationId and version=:version
                """, new MapSqlParameterSource().addValue("endsOn", body.endsOn()).addValue("id", id)
                .addValue("organizationId", actor.organizationId()).addValue("version", row.version()));
        if (updated == 0) throw ApiException.conflict("STALE_VERSION", "参与人员设置已被其他管理员修改");
        audit.record(actor, "PERFORMANCE_PARTICIPANT_UPDATED", "PERFORMANCE_PARTICIPANT", id.toString(), "SUCCESS", null);
        return participantResponse(id, actor.organizationId());
    }

    @Transactional
    public PerformanceDtos.LedgerEntry createManualEntry(PerformanceDtos.ManualEntryRequest body) {
        CurrentActor actor = admin();
        String type = body.entryType().trim().toUpperCase(Locale.ROOT);
        if (!Set.of("LOGISTICS", "CORRECTION").contains(type)) {
            throw ApiException.badRequest("INVALID_PERFORMANCE_MANUAL_TYPE", "人工分类型只能是 LOGISTICS 或 CORRECTION");
        }
        BigDecimal value = points(body.points(), "CORRECTION".equals(type));
        if ("LOGISTICS".equals(type) && value.signum() < 0) {
            throw ApiException.badRequest("PERFORMANCE_LOGISTICS_NON_NEGATIVE", "基础后勤分不能为负数");
        }
        if ("CORRECTION".equals(type)) {
            if (body.relatedLedgerId() == null) {
                throw ApiException.badRequest("PERFORMANCE_CORRECTION_SOURCE_REQUIRED", "纠错必须关联原积分记录");
            }
            requireLedger(body.relatedLedgerId(), actor.organizationId(), body.membershipId());
        } else if (body.relatedLedgerId() != null) {
            throw ApiException.badRequest("PERFORMANCE_LOGISTICS_SOURCE_FORBIDDEN", "基础后勤分不能关联纠错来源");
        }
        ParticipantRow scoringParticipant = requireParticipant(actor.organizationId(), body.membershipId(), body.performanceDate());
        requireDayUnlocked(actor.organizationId(), body.performanceDate());
        UUID id = UUID.randomUUID();
        UUID teamId = scoringParticipant.teamId();
        jdbc.update("""
                insert into performance_manual_entries (
                    id, organization_id, membership_id, team_id_snapshot, performance_date,
                    entry_type, title, reason, points, related_ledger_id, created_by_membership_id
                ) values (:id,:organizationId,:membershipId,:teamId,:date,:type,:title,:reason,:points,:related,:creator)
                """, new MapSqlParameterSource().addValue("id", id).addValue("organizationId", actor.organizationId())
                .addValue("membershipId", body.membershipId()).addValue("teamId", teamId)
                .addValue("date", body.performanceDate()).addValue("type", type)
                .addValue("title", body.title().trim()).addValue("reason", body.reason().trim())
                .addValue("points", value).addValue("related", body.relatedLedgerId())
                .addValue("creator", actor.membershipId()));
        UUID ledgerId = insertLedger(actor.organizationId(), body.membershipId(), teamId, body.performanceDate(),
                type, id, value, body.title().trim() + "：" + body.reason().trim());
        audit.record(actor, "PERFORMANCE_MANUAL_ENTRY_CREATED", "PERFORMANCE_MANUAL_ENTRY", id.toString(), "SUCCESS",
                Map.of("type", type, "points", value));
        return ledgerById(ledgerId, actor.organizationId());
    }

    @Transactional
    public PerformanceDtos.DayResponse day(LocalDate date) {
        return findDay(date).orElseThrow(() ->
                ApiException.conflict("PERFORMANCE_NON_SCORING_DAY", "该日期不参与绩效计算"));
    }

    @Transactional
    public Optional<PerformanceDtos.DayResponse> findDay(LocalDate date) {
        CurrentActor actor = admin();
        RuleRow rule = ruleFor(actor.organizationId(), date);
        if (!isScoringDay(rule, date)) return Optional.empty();
        refreshDay(actor.organizationId(), date, rule);
        return Optional.of(dayResponse(actor.organizationId(), date));
    }

    @Transactional
    public PerformanceDtos.DayResponse settle(LocalDate date, long expectedVersion) {
        CurrentActor actor = admin();
        RuleRow rule = ruleFor(actor.organizationId(), date);
        requireScoringDay(rule, date);
        String status = refreshDay(actor.organizationId(), date, rule);
        DayRow day = dayRow(actor.organizationId(), date);
        requireVersion(day.version(), expectedVersion);
        if (!"REVIEWING".equals(status)) {
            throw ApiException.conflict("PERFORMANCE_DAY_NOT_REVIEWING", "只有审核阶段可以结算");
        }
        long pendingClaims = countClaims(actor.organizationId(), null, date, "PENDING");
        long pendingActivities = countActivities(actor.organizationId(), null, date,
                "PROPOSED,APPROVED,COMPLETION_SUBMITTED");
        if (pendingClaims > 0 || pendingActivities > 0) {
            throw ApiException.conflict("PERFORMANCE_PENDING_ITEMS", "仍有待处理的交流或活动，不能结算");
        }
        List<ParticipantRow> participants = activeParticipants(actor.organizationId(), date);
        for (ParticipantRow participant : participants) {
            PerformanceDtos.ScoreBreakdown score = score(actor.organizationId(), participant.membershipId(), date, rule);
            jdbc.update("""
                    insert into performance_daily_settlements (
                        id, organization_id, performance_date, membership_id, team_id_snapshot, rule_version_id,
                        baseline, business_points, logistics_points, correction_points, earned_points,
                        shortfall, deduction, final_points, settled_at, settlement_revision, active
                    ) values (:id,:organizationId,:date,:membershipId,:teamId,:ruleId,:baseline,:business,
                              :logistics,:correction,:earned,:shortfall,:deduction,:finalPoints,now(),
                              (select coalesce(max(previous.settlement_revision),0)+1
                               from performance_daily_settlements previous
                               where previous.organization_id=:organizationId
                                 and previous.performance_date=:date
                                 and previous.membership_id=:membershipId), true)
                    """, new MapSqlParameterSource().addValue("id", UUID.randomUUID())
                    .addValue("organizationId", actor.organizationId()).addValue("date", date)
                    .addValue("membershipId", participant.membershipId()).addValue("teamId", participant.teamId())
                    .addValue("ruleId", rule.id()).addValue("baseline", score.baseline())
                    .addValue("business", score.businessPoints()).addValue("logistics", score.logisticsPoints())
                    .addValue("correction", score.correctionPoints()).addValue("earned", score.earnedPoints())
                    .addValue("shortfall", score.shortfall()).addValue("deduction", score.deduction())
                    .addValue("finalPoints", score.finalPoints()));
        }
        int updated = jdbc.update("""
                update performance_days set status='SETTLED', settled_by_membership_id=:actor,
                       settled_at=now(), updated_at=now(), version=version+1
                where organization_id=:organizationId and performance_date=:date and version=:version
                """, new MapSqlParameterSource().addValue("actor", actor.membershipId())
                .addValue("organizationId", actor.organizationId()).addValue("date", date)
                .addValue("version", day.version()));
        if (updated == 0) throw ApiException.conflict("STALE_VERSION", "该日期已被其他管理员处理");
        audit.record(actor, "PERFORMANCE_DAY_SETTLED", "PERFORMANCE_DAY", date.toString(), "SUCCESS",
                Map.of("participantCount", participants.size()));
        return dayResponse(actor.organizationId(), date);
    }

    @Transactional
    public PerformanceDtos.DayResponse reopen(LocalDate date, PerformanceDtos.ReopenDayRequest body) {
        CurrentActor actor = admin();
        DayRow day = dayRow(actor.organizationId(), date);
        requireVersion(day.version(), body.expectedVersion());
        if (!"SETTLED".equals(day.status())) {
            throw ApiException.conflict("PERFORMANCE_DAY_NOT_SETTLED", "只有已结算日期可以重开");
        }
        jdbc.update("""
                update performance_daily_settlements
                   set active=false, reopened_at=now(), reopened_reason=:reason
                 where organization_id=:organizationId and performance_date=:date and active=true
                """, new MapSqlParameterSource().addValue("organizationId", actor.organizationId())
                .addValue("date", date).addValue("reason", body.reason().trim()));
        int updated = jdbc.update("""
                update performance_days set status='OPEN', reopened_reason=:reason, settled_by_membership_id=null,
                       settled_at=null, updated_at=now(), version=version+1
                where organization_id=:organizationId and performance_date=:date and version=:version
                """, new MapSqlParameterSource().addValue("reason", body.reason().trim())
                .addValue("organizationId", actor.organizationId()).addValue("date", date)
                .addValue("version", day.version()));
        if (updated == 0) throw ApiException.conflict("STALE_VERSION", "该日期已被其他管理员处理");
        audit.record(actor, "PERFORMANCE_DAY_REOPENED", "PERFORMANCE_DAY", date.toString(), "SUCCESS",
                Map.of("reason", body.reason().trim()));
        return dayResponse(actor.organizationId(), date);
    }

    @Transactional
    public byte[] export(LocalDate from, LocalDate to, UUID teamId) {
        CurrentActor actor = admin();
        validateRange(from, to, 366);
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("organizationId", actor.organizationId()).addValue("fromDate", from)
                .addValue("toDate", to).addValue("teamId", teamId);
        String teamClause = teamId == null ? "" : "and ledger.team_id_snapshot=:teamId";
        List<ExportRow> rows = jdbc.query("""
                select ledger.performance_date, account.display_name, coalesce(team.name,'未分组') team_name,
                       ledger.category, ledger.points, ledger.description, ledger.created_at
                from performance_score_ledger ledger
                join memberships membership on membership.id=ledger.membership_id
                join user_accounts account on account.id=membership.user_id
                left join teams team on team.id=ledger.team_id_snapshot
                where ledger.organization_id=:organizationId
                  and ledger.performance_date between :fromDate and :toDate %s
                order by ledger.performance_date, account.display_name, ledger.created_at
                """.formatted(teamClause), params, (row, index) -> new ExportRow(
                row.getObject("performance_date", LocalDate.class), row.getString("display_name"),
                row.getString("team_name"), row.getString("category"), money(row.getBigDecimal("points")),
                row.getString("description"), row.getTimestamp("created_at").toInstant()));
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("绩效明细");
            var header = sheet.createRow(0);
            String[] columns = {"日期", "成员", "团队", "类别", "分数", "说明", "入账时间"};
            for (int i = 0; i < columns.length; i++) header.createCell(i).setCellValue(columns[i]);
            for (int i = 0; i < rows.size(); i++) {
                ExportRow row = rows.get(i);
                var excel = sheet.createRow(i + 1);
                excel.createCell(0).setCellValue(row.date().toString());
                excel.createCell(1).setCellValue(row.displayName());
                excel.createCell(2).setCellValue(row.teamName());
                excel.createCell(3).setCellValue(row.category());
                excel.createCell(4).setCellValue(row.points().doubleValue());
                excel.createCell(5).setCellValue(row.description());
                excel.createCell(6).setCellValue(row.createdAt().atZone(BUSINESS_ZONE).toLocalDateTime().toString());
            }
            for (int i = 0; i < columns.length; i++) sheet.autoSizeColumn(i);
            workbook.write(output);
            audit.record(actor, "PERFORMANCE_EXPORTED", "PERFORMANCE", from + "_" + to, "SUCCESS",
                    Map.of("rowCount", rows.size()));
            return output.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("无法生成绩效导出文件", exception);
        }
    }

    @Scheduled(cron = "0 * * * * *", zone = "Asia/Shanghai")
    @Transactional
    public void closeExpiredSubmissionWindows() {
        LocalDate today = LocalDate.now(BUSINESS_ZONE);
        LocalTime now = LocalTime.now(BUSINESS_ZONE);
        jdbc.query("""
                select day.organization_id, day.performance_date, rule.submission_closes_at
                from performance_days day join performance_rule_versions rule on rule.id=day.rule_version_id
                where day.status='OPEN' and day.performance_date=:today
                """, new MapSqlParameterSource().addValue("today", today), row -> {
            if (!now.isBefore(row.getObject("submission_closes_at", LocalTime.class))) {
                jdbc.update("""
                        update performance_days set status='REVIEWING', updated_at=now(), version=version+1
                        where organization_id=:organizationId and performance_date=:date and status='OPEN'
                        """, new MapSqlParameterSource()
                        .addValue("organizationId", row.getObject("organization_id", UUID.class))
                        .addValue("date", row.getObject("performance_date", LocalDate.class)));
            }
        });
    }

    public int approvedDeepCount(UUID organizationId, UUID membershipId, LocalDate date) {
        Long count = jdbc.queryForObject("""
                select count(*) from performance_interaction_claims
                where organization_id=:organizationId and membership_id=:membershipId
                  and performance_date=:date and status='APPROVED' and approved_type='DEEP'
                """, new MapSqlParameterSource().addValue("organizationId", organizationId)
                .addValue("membershipId", membershipId).addValue("date", date), Long.class);
        return count == null ? 0 : count.intValue();
    }

    public int pendingDeepCount(UUID organizationId, UUID membershipId, LocalDate date) {
        Long count = jdbc.queryForObject("""
                select count(*) from performance_interaction_claims
                where organization_id=:organizationId and membership_id=:membershipId
                  and performance_date=:date and status='PENDING' and requested_type='DEEP'
                """, new MapSqlParameterSource().addValue("organizationId", organizationId)
                .addValue("membershipId", membershipId).addValue("date", date), Long.class);
        return count == null ? 0 : count.intValue();
    }

    public boolean performanceManaged(UUID organizationId, UUID membershipId, LocalDate date) {
        Long count = jdbc.queryForObject("""
                select count(*) from performance_rule_versions rule
                where rule.organization_id=:organizationId and rule.effective_from<=:date
                  and exists (
                    select 1 from performance_participants participant
                    join memberships membership on membership.id=participant.membership_id
                    join user_accounts account on account.id=membership.user_id
                    where participant.organization_id=:organizationId
                      and participant.membership_id=:membershipId
                      and participant.starts_on<=:date
                      and (participant.ends_on is null or participant.ends_on>=:date)
                      and (:date < current_date or (membership.status='ACTIVE' and account.enabled=true))
                  )
                """, new MapSqlParameterSource().addValue("organizationId", organizationId)
                .addValue("membershipId", membershipId).addValue("date", date), Long.class);
        return count != null && count > 0;
    }

    private PerformanceDtos.DayResponse dayResponse(UUID organizationId, LocalDate date) {
        DayRow day = dayRow(organizationId, date);
        RuleRow rule = ruleById(day.ruleId(), organizationId);
        List<PerformanceDtos.DayMember> members = activeParticipants(organizationId, date).stream().map(participant -> {
            ScoreAndSettlement value = scoreOrSettlement(organizationId, participant.membershipId(), date, rule);
            MemberRow member = member(organizationId, participant.membershipId());
            return new PerformanceDtos.DayMember(participant.membershipId(), member.displayName(),
                    participant.teamId(), member.teamName(), value.score(), value.settled());
        }).toList();
        return new PerformanceDtos.DayResponse(date, day.status(), ruleResponse(rule),
                countClaims(organizationId, null, date, "PENDING"),
                countActivities(organizationId, null, date, "PROPOSED,APPROVED,COMPLETION_SUBMITTED"),
                members, day.version());
    }

    private ScoreAndSettlement scoreOrSettlement(UUID organizationId, UUID membershipId,
                                                   LocalDate date, RuleRow rule) {
        return jdbc.query("""
                select * from performance_daily_settlements
                where organization_id=:organizationId and membership_id=:membershipId and performance_date=:date
                  and active=true
                """, new MapSqlParameterSource().addValue("organizationId", organizationId)
                .addValue("membershipId", membershipId).addValue("date", date), (row, index) -> new ScoreAndSettlement(
                new PerformanceDtos.ScoreBreakdown(money(row.getBigDecimal("business_points")),
                        money(row.getBigDecimal("logistics_points")), money(row.getBigDecimal("correction_points")),
                        money(row.getBigDecimal("earned_points")), money(row.getBigDecimal("baseline")),
                        money(row.getBigDecimal("shortfall")), money(row.getBigDecimal("deduction")),
                        money(row.getBigDecimal("final_points"))), true)).stream().findFirst()
                .orElseGet(() -> new ScoreAndSettlement(score(organizationId, membershipId, date, rule), false));
    }

    private PerformanceDtos.ScoreBreakdown score(UUID organizationId, UUID membershipId, LocalDate date, RuleRow rule) {
        Map<String, BigDecimal> sums = new java.util.HashMap<>();
        jdbc.query("""
                select category, coalesce(sum(points),0) points from performance_score_ledger
                where organization_id=:organizationId and membership_id=:membershipId and performance_date=:date
                group by category
                """, new MapSqlParameterSource().addValue("organizationId", organizationId)
                .addValue("membershipId", membershipId).addValue("date", date), row -> {
                    sums.put(row.getString("category"), money(row.getBigDecimal("points")));
                });
        BigDecimal business = sums.getOrDefault("INTERACTION", BigDecimal.ZERO)
                .add(sums.getOrDefault("ACTIVITY", BigDecimal.ZERO));
        BigDecimal logistics = sums.getOrDefault("LOGISTICS", BigDecimal.ZERO);
        BigDecimal correction = sums.getOrDefault("CORRECTION", BigDecimal.ZERO);
        BigDecimal earned = money(business.add(logistics).add(correction));
        BigDecimal baseline = isScoringDay(rule, date) ? rule.baseline() : BigDecimal.ZERO.setScale(1);
        BigDecimal shortfall = money(baseline.subtract(earned).max(BigDecimal.ZERO));
        BigDecimal deduction = "DEDUCT_GAP".equals(rule.shortfallMode()) ? shortfall : BigDecimal.ZERO.setScale(1);
        return new PerformanceDtos.ScoreBreakdown(business, logistics, correction, earned, baseline,
                shortfall, deduction, money(earned.subtract(deduction)));
    }

    private List<PerformanceDtos.InteractionOption> availableInteractions(UUID organizationId, UUID membershipId,
                                                                           LocalDate date) {
        return jdbc.query("""
                select interaction.id, interaction.customer_id, customer.full_name, interaction.contacted_at,
                       interaction.channel, interaction.summary
                from interactions interaction join customers customer on customer.id=interaction.customer_id
                where interaction.organization_id=:organizationId
                  and interaction.created_by_membership_id=:membershipId
                  and interaction.contacted_at >= (:date::date::timestamp at time zone 'Asia/Shanghai')
                  and interaction.contacted_at < ((:date::date + 1)::timestamp at time zone 'Asia/Shanghai')
                  and not exists (select 1 from performance_interaction_claims claim
                                  where claim.organization_id=interaction.organization_id
                                    and claim.interaction_id=interaction.id)
                order by interaction.contacted_at desc
                """, new MapSqlParameterSource().addValue("organizationId", organizationId)
                .addValue("membershipId", membershipId).addValue("date", date), (row, index) ->
                new PerformanceDtos.InteractionOption(row.getObject("id", UUID.class),
                        row.getObject("customer_id", UUID.class), row.getString("full_name"),
                        row.getTimestamp("contacted_at").toInstant(), row.getString("channel"), row.getString("summary")));
    }

    private List<PerformanceDtos.ClaimResponse> claimsFor(CurrentActor actor, String status,
                                                           UUID membershipId, LocalDate date) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("organizationId", actor.organizationId()).addValue("status", status)
                .addValue("membershipId", membershipId).addValue("date", date);
        StringBuilder filters = new StringBuilder();
        if (status != null) filters.append(" and claim.status=:status");
        if (membershipId != null) filters.append(" and claim.membership_id=:membershipId");
        if (date != null) filters.append(" and claim.performance_date=:date");
        if (!actor.isAdmin() && actor.isTeamManager()) {
            if (actor.managedTeamIds().isEmpty()) filters.append(" and claim.membership_id=:actorMembershipId");
            else {
                filters.append(" and (claim.membership_id=:actorMembershipId or claim.team_id_snapshot in (:managedTeamIds))");
                params.addValue("managedTeamIds", actor.managedTeamIds());
            }
            params.addValue("actorMembershipId", actor.membershipId());
        } else if (!actor.isAdmin()) {
            filters.append(" and claim.membership_id=:actorMembershipId");
            params.addValue("actorMembershipId", actor.membershipId());
        }
        List<UUID> ids = jdbc.query("""
                select claim.id from performance_interaction_claims claim
                where claim.organization_id=:organizationId %s
                order by claim.performance_date desc, claim.created_at desc limit 300
                """.formatted(filters), params, (row, index) -> row.getObject("id", UUID.class));
        return ids.stream().map(id -> claim(id, actor.organizationId())).toList();
    }

    private PerformanceDtos.ClaimResponse claim(UUID id, UUID organizationId) {
        ClaimRow row = claimRow(id, organizationId);
        MemberRow member = member(organizationId, row.membershipId());
        String customerName = jdbc.queryForObject("select full_name from customers where id=:id and organization_id=:organizationId",
                new MapSqlParameterSource().addValue("id", row.customerId()).addValue("organizationId", organizationId), String.class);
        return new PerformanceDtos.ClaimResponse(row.id(), row.membershipId(), member.displayName(), row.interactionId(),
                row.customerId(), customerName, row.performanceDate(), row.requestedType(), row.approvedType(),
                row.status(), row.reviewReason(), row.submittedAt(), row.reviewedAt(),
                evidenceFor(organizationId, "INTERACTION", id), row.version());
    }

    private ClaimRow claimRow(UUID id, UUID organizationId) {
        return jdbc.query("""
                select * from performance_interaction_claims where id=:id and organization_id=:organizationId
                """, new MapSqlParameterSource().addValue("id", id).addValue("organizationId", organizationId),
                (row, index) -> new ClaimRow(row.getObject("id", UUID.class),
                        row.getObject("membership_id", UUID.class), row.getObject("team_id_snapshot", UUID.class),
                        row.getObject("interaction_id", UUID.class), row.getObject("customer_id", UUID.class),
                        row.getObject("performance_date", LocalDate.class), row.getString("requested_type"),
                        row.getString("approved_type"), row.getString("status"), row.getString("review_reason"),
                        instant(row, "submitted_at"), instant(row, "reviewed_at"), row.getLong("version")))
                .stream().findFirst().orElseThrow(() -> ApiException.notFound("PERFORMANCE_CLAIM_NOT_FOUND", "绩效申报不存在"));
    }

    private void updateClaimReview(ClaimRow claim, String status, String approvedType, String reason, CurrentActor actor) {
        int updated = jdbc.update("""
                update performance_interaction_claims set status=:status, approved_type=:approvedType,
                       review_reason=:reason, reviewed_by_membership_id=:reviewer, reviewed_at=now(),
                       updated_at=now(), version=version+1
                where id=:id and organization_id=:organizationId and version=:version
                """, new MapSqlParameterSource().addValue("status", status).addValue("approvedType", approvedType)
                .addValue("reason", reason).addValue("reviewer", actor.membershipId()).addValue("id", claim.id())
                .addValue("organizationId", actor.organizationId()).addValue("version", claim.version()));
        if (updated == 0) throw ApiException.conflict("STALE_VERSION", "该申报已被其他管理员处理");
    }

    private List<PerformanceDtos.ActivityResponse> activitiesFor(CurrentActor actor, String status,
                                                                  UUID membershipId, LocalDate date) {
        MapSqlParameterSource params = new MapSqlParameterSource().addValue("organizationId", actor.organizationId())
                .addValue("status", status).addValue("membershipId", membershipId).addValue("date", date);
        StringBuilder filters = new StringBuilder();
        if (status != null) filters.append(" and activity.status=:status");
        if (membershipId != null) filters.append(" and activity.organizer_membership_id=:membershipId");
        if (date != null) filters.append(" and activity.event_date=:date");
        if (!actor.isAdmin() && actor.isTeamManager() && !actor.managedTeamIds().isEmpty()) {
            filters.append(" and (activity.organizer_membership_id=:actorMembershipId or activity.team_id_snapshot in (:managedTeamIds))");
            params.addValue("actorMembershipId", actor.membershipId()).addValue("managedTeamIds", actor.managedTeamIds());
        } else if (!actor.isAdmin()) {
            filters.append(" and activity.organizer_membership_id=:actorMembershipId");
            params.addValue("actorMembershipId", actor.membershipId());
        }
        List<UUID> ids = jdbc.query("""
                select activity.id from performance_activities activity
                where activity.organization_id=:organizationId %s
                order by activity.event_date desc, activity.created_at desc limit 300
                """.formatted(filters), params, (row, index) -> row.getObject("id", UUID.class));
        return ids.stream().map(id -> activity(id, actor.organizationId())).toList();
    }

    private PerformanceDtos.ActivityResponse activity(UUID id, UUID organizationId) {
        ActivityRow row = activityRow(id, organizationId);
        MemberRow organizer = member(organizationId, row.organizerId());
        List<PerformanceDtos.ActivityAllocation> allocations = jdbc.query("""
                select membership_id, points from performance_activity_allocations
                where organization_id=:organizationId and activity_id=:activityId order by created_at
                """, new MapSqlParameterSource().addValue("organizationId", organizationId).addValue("activityId", id),
                (result, index) -> new PerformanceDtos.ActivityAllocation(
                        result.getObject("membership_id", UUID.class), money(result.getBigDecimal("points"))));
        return new PerformanceDtos.ActivityResponse(row.id(), row.organizerId(), organizer.displayName(), row.eventDate(),
                row.title(), row.goal(), row.expectedParticipants(), row.description(), row.actualParticipants(),
                row.verifiedParticipants(), row.tierLabel(), row.totalPoints(), row.status(), row.reviewReason(),
                allocations, evidenceFor(organizationId, "ACTIVITY", id), row.createdAt(), row.version());
    }

    private ActivityRow activityRow(UUID id, UUID organizationId) {
        return jdbc.query("""
                select * from performance_activities where id=:id and organization_id=:organizationId
                """, new MapSqlParameterSource().addValue("id", id).addValue("organizationId", organizationId),
                (row, index) -> new ActivityRow(row.getObject("id", UUID.class),
                        row.getObject("organizer_membership_id", UUID.class),
                        row.getObject("team_id_snapshot", UUID.class), row.getObject("event_date", LocalDate.class),
                        row.getString("title"), row.getString("goal"), row.getInt("expected_participants"),
                        row.getString("description"), (Integer) row.getObject("actual_participants"),
                        (Integer) row.getObject("verified_participants"), row.getString("tier_label"),
                        moneyNullable(row.getBigDecimal("total_points")), row.getString("status"),
                        row.getString("review_reason"), row.getTimestamp("created_at").toInstant(), row.getLong("version")))
                .stream().findFirst().orElseThrow(() -> ApiException.notFound("PERFORMANCE_ACTIVITY_NOT_FOUND", "活动不存在"));
    }

    private void requireEvidenceOwner(String ownerType, UUID ownerId, CurrentActor actor, boolean writing) {
        UUID owner = switch (ownerType) {
            case "INTERACTION" -> claimRow(ownerId, actor.organizationId()).membershipId();
            case "ACTIVITY" -> activityRow(ownerId, actor.organizationId()).organizerId();
            case "MANUAL" -> {
                if (!actor.isAdmin()) throw ApiException.forbidden("只有管理员可以查看人工分证据");
                UUID membershipId = jdbc.query("""
                        select membership_id from performance_manual_entries
                        where id=:id and organization_id=:organizationId
                        """, new MapSqlParameterSource().addValue("id", ownerId)
                        .addValue("organizationId", actor.organizationId()),
                        (row, index) -> row.getObject("membership_id", UUID.class)).stream().findFirst()
                        .orElseThrow(() -> ApiException.notFound("PERFORMANCE_MANUAL_ENTRY_NOT_FOUND", "人工分记录不存在"));
                yield membershipId;
            }
            default -> throw ApiException.badRequest("INVALID_PERFORMANCE_EVIDENCE_OWNER", "证据类型无效");
        };
        if (!actor.isAdmin() && !owner.equals(actor.membershipId())) {
            throw ApiException.forbidden("不能访问其他成员的绩效证据");
        }
        if (writing && "INTERACTION".equals(ownerType)) {
            ClaimRow claim = claimRow(ownerId, actor.organizationId());
            if (!"DRAFT".equals(claim.status())) throw ApiException.conflict("PERFORMANCE_EVIDENCE_LOCKED", "已提交申报不能继续上传证据");
        }
        if (writing && "ACTIVITY".equals(ownerType)) {
            ActivityRow activity = activityRow(ownerId, actor.organizationId());
            if (!"APPROVED".equals(activity.status())) throw ApiException.conflict("PERFORMANCE_EVIDENCE_LOCKED", "只有已立项活动可以上传成果证据");
        }
        if (writing && "MANUAL".equals(ownerType)) {
            LocalDate date = jdbc.queryForObject("""
                    select performance_date from performance_manual_entries
                    where id=:id and organization_id=:organizationId
                    """, new MapSqlParameterSource().addValue("id", ownerId)
                    .addValue("organizationId", actor.organizationId()), LocalDate.class);
            requireDayUnlocked(actor.organizationId(), date);
        }
    }

    private EvidenceBytes evidenceBytes(MultipartFile file) {
        if (file == null || file.isEmpty()) throw ApiException.badRequest("EMPTY_FILE", "证据图片不能为空");
        if (file.getSize() > MAX_EVIDENCE_SIZE) throw ApiException.badRequest("FILE_TOO_LARGE", "单张证据不能超过 5MB");
        try {
            byte[] bytes = file.getBytes();
            String contentType = detectedImageType(bytes);
            if (contentType == null) throw ApiException.badRequest("INVALID_EVIDENCE_TYPE", "证据仅支持 JPEG、PNG 或 WebP 图片");
            String name = file.getOriginalFilename() == null ? "evidence" : file.getOriginalFilename();
            name = name.replaceAll("[\\r\\n\\\\/]", "_");
            if (name.length() > 255) name = name.substring(name.length() - 255);
            return new EvidenceBytes(name, contentType, bytes);
        } catch (IOException exception) {
            throw ApiException.badRequest("FILE_READ_FAILED", "无法读取证据图片");
        }
    }

    private String detectedImageType(byte[] bytes) {
        if (bytes.length >= 3 && (bytes[0] & 0xff) == 0xff && (bytes[1] & 0xff) == 0xd8 && (bytes[2] & 0xff) == 0xff) return "image/jpeg";
        if (bytes.length >= 8 && bytes[0] == (byte) 0x89 && bytes[1] == 'P' && bytes[2] == 'N' && bytes[3] == 'G'
                && bytes[4] == 0x0d && bytes[5] == 0x0a && bytes[6] == 0x1a && bytes[7] == 0x0a) return "image/png";
        if (bytes.length >= 12 && bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F'
                && bytes[8] == 'W' && bytes[9] == 'E' && bytes[10] == 'B' && bytes[11] == 'P') return "image/webp";
        return null;
    }

    private List<PerformanceDtos.EvidenceResponse> evidenceFor(UUID organizationId, String ownerType, UUID ownerId) {
        return jdbc.query("""
                select id, owner_type, owner_id, file_name, content_type, file_size, created_at
                from performance_evidence where organization_id=:organizationId and owner_type=:ownerType and owner_id=:ownerId
                order by created_at
                """, new MapSqlParameterSource().addValue("organizationId", organizationId)
                .addValue("ownerType", ownerType).addValue("ownerId", ownerId), (row, index) ->
                new PerformanceDtos.EvidenceResponse(row.getObject("id", UUID.class), row.getString("owner_type"),
                        row.getObject("owner_id", UUID.class), row.getString("file_name"), row.getString("content_type"),
                        row.getLong("file_size"), row.getTimestamp("created_at").toInstant()));
    }

    private PerformanceDtos.EvidenceResponse evidenceMetadata(UUID id, UUID organizationId) {
        return jdbc.query("""
                select id, owner_type, owner_id, file_name, content_type, file_size, created_at
                from performance_evidence where id=:id and organization_id=:organizationId
                """, new MapSqlParameterSource().addValue("id", id).addValue("organizationId", organizationId),
                (row, index) -> new PerformanceDtos.EvidenceResponse(row.getObject("id", UUID.class),
                        row.getString("owner_type"), row.getObject("owner_id", UUID.class), row.getString("file_name"),
                        row.getString("content_type"), row.getLong("file_size"), row.getTimestamp("created_at").toInstant()))
                .stream().findFirst().orElseThrow();
    }

    private EvidenceRow evidenceRow(UUID id, UUID organizationId) {
        return jdbc.query("""
                select * from performance_evidence where id=:id and organization_id=:organizationId
                """, new MapSqlParameterSource().addValue("id", id).addValue("organizationId", organizationId),
                (row, index) -> new EvidenceRow(row.getString("owner_type"), row.getObject("owner_id", UUID.class),
                        row.getString("file_name"), row.getString("content_type"), row.getBytes("content")))
                .stream().findFirst().orElseThrow(() -> ApiException.notFound("PERFORMANCE_EVIDENCE_NOT_FOUND", "证据不存在"));
    }

    private void requireEvidence(UUID ownerId, String ownerType, UUID organizationId) {
        Long count = jdbc.queryForObject("""
                select count(*) from performance_evidence where organization_id=:organizationId and owner_type=:ownerType and owner_id=:ownerId
                """, new MapSqlParameterSource().addValue("organizationId", organizationId)
                .addValue("ownerType", ownerType).addValue("ownerId", ownerId), Long.class);
        if (count == null || count == 0) throw ApiException.badRequest("PERFORMANCE_EVIDENCE_REQUIRED", "请至少上传一张证据图片");
    }

    private UUID insertLedger(UUID organizationId, UUID membershipId, UUID teamId, LocalDate date,
                              String category, UUID sourceId, BigDecimal value, String description) {
        UUID id = UUID.randomUUID();
        try {
            jdbc.update("""
                    insert into performance_score_ledger (
                        id, organization_id, membership_id, team_id_snapshot, performance_date,
                        category, source_id, points, description
                    ) values (:id,:organizationId,:membershipId,:teamId,:date,:category,:sourceId,:points,:description)
                    """, new MapSqlParameterSource().addValue("id", id).addValue("organizationId", organizationId)
                    .addValue("membershipId", membershipId).addValue("teamId", teamId).addValue("date", date)
                    .addValue("category", category).addValue("sourceId", sourceId).addValue("points", value)
                    .addValue("description", description));
        } catch (DataIntegrityViolationException exception) {
            throw ApiException.conflict("PERFORMANCE_LEDGER_DUPLICATE", "该业务记录已经计入绩效");
        }
        return id;
    }

    private List<PerformanceDtos.LedgerEntry> ledger(UUID organizationId, UUID membershipId,
                                                       LocalDate from, LocalDate to) {
        return jdbc.query("""
                select ledger.*, account.display_name from performance_score_ledger ledger
                join memberships membership on membership.id=ledger.membership_id
                join user_accounts account on account.id=membership.user_id
                where ledger.organization_id=:organizationId and ledger.membership_id=:membershipId
                  and ledger.performance_date between :fromDate and :toDate
                order by ledger.performance_date desc, ledger.created_at desc
                """, new MapSqlParameterSource().addValue("organizationId", organizationId)
                .addValue("membershipId", membershipId).addValue("fromDate", from).addValue("toDate", to),
                (row, index) -> mapLedger(row));
    }

    private PerformanceDtos.LedgerEntry ledgerById(UUID id, UUID organizationId) {
        return jdbc.query("""
                select ledger.*, account.display_name from performance_score_ledger ledger
                join memberships membership on membership.id=ledger.membership_id
                join user_accounts account on account.id=membership.user_id
                where ledger.id=:id and ledger.organization_id=:organizationId
                """, new MapSqlParameterSource().addValue("id", id).addValue("organizationId", organizationId),
                (row, index) -> mapLedger(row)).stream().findFirst().orElseThrow();
    }

    private PerformanceDtos.LedgerEntry mapLedger(java.sql.ResultSet row) throws java.sql.SQLException {
        return new PerformanceDtos.LedgerEntry(row.getObject("id", UUID.class),
                row.getObject("membership_id", UUID.class), row.getString("display_name"),
                row.getObject("performance_date", LocalDate.class), row.getString("category"),
                row.getObject("source_id", UUID.class), money(row.getBigDecimal("points")),
                row.getString("description"), row.getTimestamp("created_at").toInstant());
    }

    private void requireLedger(UUID id, UUID organizationId, UUID membershipId) {
        Long count = jdbc.queryForObject("""
                select count(*) from performance_score_ledger where id=:id and organization_id=:organizationId and membership_id=:membershipId
                """, new MapSqlParameterSource().addValue("id", id).addValue("organizationId", organizationId)
                .addValue("membershipId", membershipId), Long.class);
        if (count == null || count == 0) throw ApiException.notFound("PERFORMANCE_LEDGER_NOT_FOUND", "关联的原积分记录不存在");
    }

    private RuleRow ruleFor(UUID organizationId, LocalDate date) {
        return jdbc.query("""
                select * from performance_rule_versions where organization_id=:organizationId and effective_from<=:date
                order by effective_from desc, version_no desc limit 1
                """, new MapSqlParameterSource().addValue("organizationId", organizationId).addValue("date", date),
                (row, index) -> mapRule(row)).stream().findFirst()
                .orElseThrow(() -> ApiException.conflict("PERFORMANCE_NOT_CONFIGURED", "组织尚未配置绩效规则"));
    }

    private RuleRow ruleById(UUID id, UUID organizationId) {
        return jdbc.query("""
                select * from performance_rule_versions where id=:id and organization_id=:organizationId
                """, new MapSqlParameterSource().addValue("id", id).addValue("organizationId", organizationId),
                (row, index) -> mapRule(row)).stream().findFirst()
                .orElseThrow(() -> ApiException.notFound("PERFORMANCE_RULE_NOT_FOUND", "绩效规则不存在"));
    }

    private RuleRow mapRule(java.sql.ResultSet row) throws java.sql.SQLException {
        return new RuleRow(row.getObject("id", UUID.class), row.getInt("version_no"),
                row.getObject("effective_from", LocalDate.class), money(row.getBigDecimal("daily_baseline")),
                row.getString("calendar_mode"), read(row.getString("weekdays"), INTEGER_SET),
                read(row.getString("included_dates"), DATE_SET), read(row.getString("excluded_dates"), DATE_SET),
                row.getObject("submission_opens_at", LocalTime.class),
                row.getObject("submission_closes_at", LocalTime.class), row.getString("shortfall_mode"),
                money(row.getBigDecimal("ordinary_points")), money(row.getBigDecimal("deep_points")),
                (Integer) row.getObject("ordinary_daily_cap"), (Integer) row.getObject("deep_daily_cap"),
                read(row.getString("event_tiers"), TIER_LIST), row.getTimestamp("created_at").toInstant());
    }

    private PerformanceDtos.RuleResponse ruleResponse(RuleRow row) {
        return new PerformanceDtos.RuleResponse(row.id(), row.version(), row.effectiveFrom(), row.baseline(),
                row.calendarMode(), row.weekdays(), row.includedDates(), row.excludedDates(), row.opensAt(),
                row.closesAt(), row.shortfallMode(), row.ordinaryPoints(), row.deepPoints(), row.ordinaryCap(),
                row.deepCap(), row.tiers(), row.createdAt());
    }

    private boolean isScoringDay(RuleRow rule, LocalDate date) {
        if (rule.includedDates().contains(date)) return true;
        if (rule.excludedDates().contains(date)) return false;
        return switch (rule.calendarMode()) {
            case "ALL_DAYS" -> true;
            case "WEEKDAYS" -> date.getDayOfWeek().getValue() <= DayOfWeek.FRIDAY.getValue();
            case "CUSTOM" -> rule.weekdays().contains(date.getDayOfWeek().getValue());
            default -> false;
        };
    }

    private void requireScoringDay(RuleRow rule, LocalDate date) {
        if (!isScoringDay(rule, date)) throw ApiException.conflict("PERFORMANCE_NON_SCORING_DAY", "该日期不参与绩效计算");
    }

    private String refreshDay(UUID organizationId, LocalDate date, RuleRow rule) {
        jdbc.update("""
                insert into performance_days (organization_id, performance_date, rule_version_id, status)
                values (:organizationId,:date,:ruleId,'OPEN') on conflict do nothing
                """, new MapSqlParameterSource().addValue("organizationId", organizationId)
                .addValue("date", date).addValue("ruleId", rule.id()));
        DayRow day = dayRow(organizationId, date);
        LocalDate today = LocalDate.now(BUSINESS_ZONE);
        boolean expired = date.isBefore(today) || (date.equals(today)
                && !LocalTime.now(BUSINESS_ZONE).isBefore(rule.closesAt()));
        if ("OPEN".equals(day.status()) && day.reopenedReason() == null && expired) {
            jdbc.update("""
                    update performance_days set status='REVIEWING', updated_at=now(), version=version+1
                    where organization_id=:organizationId and performance_date=:date and status='OPEN'
                    """, new MapSqlParameterSource().addValue("organizationId", organizationId).addValue("date", date));
            return "REVIEWING";
        }
        return day.status();
    }

    private boolean canSubmit(UUID organizationId, LocalDate date, RuleRow rule, String status) {
        if (!"OPEN".equals(status)) return false;
        DayRow day = dayRow(organizationId, date);
        if (day.reopenedReason() != null) return true;
        if (!date.equals(LocalDate.now(BUSINESS_ZONE))) return false;
        LocalTime now = LocalTime.now(BUSINESS_ZONE);
        return !now.isBefore(rule.opensAt()) && now.isBefore(rule.closesAt());
    }

    private void requireDayUnlocked(UUID organizationId, LocalDate date) {
        Long settled = jdbc.queryForObject("""
                select count(*) from performance_days where organization_id=:organizationId and performance_date=:date and status='SETTLED'
                """, new MapSqlParameterSource().addValue("organizationId", organizationId).addValue("date", date), Long.class);
        if (settled != null && settled > 0) throw ApiException.conflict("PERFORMANCE_DAY_SETTLED", "该日期已经结算，请先重开");
    }

    private DayRow dayRow(UUID organizationId, LocalDate date) {
        return jdbc.query("""
                select * from performance_days where organization_id=:organizationId and performance_date=:date
                """, new MapSqlParameterSource().addValue("organizationId", organizationId).addValue("date", date),
                (row, index) -> new DayRow(row.getObject("rule_version_id", UUID.class), row.getString("status"),
                        row.getString("reopened_reason"), row.getLong("version"))).stream().findFirst()
                .orElseThrow(() -> ApiException.notFound("PERFORMANCE_DAY_NOT_FOUND", "绩效日期不存在"));
    }

    private ParticipantRow requireParticipant(UUID organizationId, UUID membershipId, LocalDate date) {
        ParticipantRow row = participant(organizationId, membershipId, date);
        if (row == null) throw ApiException.forbidden("当前账号在该日期未参与绩效计算");
        return row;
    }

    private ParticipantRow participant(UUID organizationId, UUID membershipId, LocalDate date) {
        return jdbc.query("""
                select p.* from performance_participants p
                join memberships m on m.id=p.membership_id
                join user_accounts u on u.id=m.user_id
                where p.organization_id=:organizationId and p.membership_id=:membershipId
                  and p.starts_on<=:date and (p.ends_on is null or p.ends_on>=:date)
                  and (:date < current_date or (m.status='ACTIVE' and u.enabled=true))
                order by p.starts_on desc limit 1
                """, new MapSqlParameterSource().addValue("organizationId", organizationId)
                .addValue("membershipId", membershipId).addValue("date", date),
                (row, index) -> mapParticipant(row)).stream().findFirst().orElse(null);
    }

    private ParticipantRow participantById(UUID id, UUID organizationId) {
        return jdbc.query("select * from performance_participants where id=:id and organization_id=:organizationId",
                new MapSqlParameterSource().addValue("id", id).addValue("organizationId", organizationId),
                (row, index) -> mapParticipant(row)).stream().findFirst()
                .orElseThrow(() -> ApiException.notFound("PERFORMANCE_PARTICIPANT_NOT_FOUND", "绩效参与记录不存在"));
    }

    private ParticipantRow mapParticipant(java.sql.ResultSet row) throws java.sql.SQLException {
        return new ParticipantRow(row.getObject("id", UUID.class), row.getObject("membership_id", UUID.class),
                row.getObject("team_id_snapshot", UUID.class), row.getObject("starts_on", LocalDate.class),
                row.getObject("ends_on", LocalDate.class), row.getLong("version"));
    }

    private List<ParticipantRow> activeParticipants(UUID organizationId, LocalDate date) {
        return jdbc.query("""
                select distinct on (p.membership_id) p.* from performance_participants p
                join memberships m on m.id=p.membership_id
                join user_accounts u on u.id=m.user_id
                where p.organization_id=:organizationId and p.starts_on<=:date and (p.ends_on is null or p.ends_on>=:date)
                  and (:date < current_date or (m.status='ACTIVE' and u.enabled=true))
                order by p.membership_id, p.starts_on desc
                """, new MapSqlParameterSource().addValue("organizationId", organizationId).addValue("date", date),
                (row, index) -> mapParticipant(row));
    }

    private List<PerformanceDtos.ParticipantResponse> participantRows(UUID organizationId) {
        List<UUID> ids = jdbc.query("""
                select id from performance_participants where organization_id=:organizationId
                order by starts_on desc, created_at desc
                """, new MapSqlParameterSource().addValue("organizationId", organizationId),
                (row, index) -> row.getObject("id", UUID.class));
        return ids.stream().map(id -> participantResponse(id, organizationId)).toList();
    }

    private PerformanceDtos.ParticipantResponse participantResponse(UUID id, UUID organizationId) {
        ParticipantRow row = participantById(id, organizationId);
        MemberRow member = member(organizationId, row.membershipId());
        LocalDate today = LocalDate.now(BUSINESS_ZONE);
        boolean active = !today.isBefore(row.startsOn()) && (row.endsOn() == null || !today.isAfter(row.endsOn()));
        return new PerformanceDtos.ParticipantResponse(row.id(), row.membershipId(), member.displayName(),
                row.teamId(), member.teamName(), row.startsOn(), row.endsOn(), active, row.version());
    }

    private boolean overlapsParticipant(UUID organizationId, UUID membershipId, LocalDate startsOn,
                                        LocalDate endsOn, UUID excludeId) {
        Long count = jdbc.queryForObject("""
                select count(*) from performance_participants
                where organization_id=:organizationId and membership_id=:membershipId
                  and (:excludeId::uuid is null or id<>:excludeId)
                  and starts_on<=coalesce(:endsOn,'9999-12-31'::date)
                  and coalesce(ends_on,'9999-12-31'::date)>=:startsOn
                """, new MapSqlParameterSource().addValue("organizationId", organizationId)
                .addValue("membershipId", membershipId).addValue("startsOn", startsOn)
                .addValue("endsOn", endsOn).addValue("excludeId", excludeId), Long.class);
        return count != null && count > 0;
    }

    private MemberRow eligibleMember(UUID organizationId, UUID membershipId) {
        MemberRow member = member(organizationId, membershipId);
        Long eligible = jdbc.queryForObject("""
                select count(*) from membership_roles mr join roles r on r.id=mr.role_id
                where mr.membership_id=:membershipId and r.code in ('ORG_ADMIN','TEAM_MANAGER','MEMBER')
                """, new MapSqlParameterSource().addValue("membershipId", membershipId), Long.class);
        if (eligible == null || eligible == 0) throw ApiException.badRequest("PERFORMANCE_MEMBER_NOT_ELIGIBLE", "该角色不能参与绩效申报");
        return member;
    }

    private MemberRow member(UUID organizationId, UUID membershipId) {
        return jdbc.query("""
                select membership.id, account.display_name, team.id team_id, coalesce(team.name,'未分组') team_name
                from memberships membership join user_accounts account on account.id=membership.user_id
                left join lateral (
                    select t.id, t.name from team_members tm join teams t on t.id=tm.team_id and t.active=true
                    where tm.membership_id=membership.id order by tm.created_at limit 1
                ) team on true
                where membership.id=:membershipId and membership.organization_id=:organizationId
                """, new MapSqlParameterSource().addValue("membershipId", membershipId)
                .addValue("organizationId", organizationId), (row, index) -> new MemberRow(
                row.getObject("id", UUID.class), row.getString("display_name"),
                row.getObject("team_id", UUID.class), row.getString("team_name")))
                .stream().findFirst().orElseThrow(() -> ApiException.notFound("MEMBER_NOT_FOUND", "成员不存在"));
    }

    private UUID primaryTeam(UUID organizationId, UUID membershipId) {
        return member(organizationId, membershipId).teamId();
    }

    private UUID leaderboardTeam(CurrentActor actor, UUID requested) {
        if (actor.isAdmin()) return requested;
        Set<UUID> allowed = new LinkedHashSet<>(actor.teamIds());
        if (actor.isTeamManager()) allowed.addAll(actor.managedTeamIds());
        if (requested != null && !allowed.contains(requested)) throw ApiException.forbidden("不能查看其他团队绩效");
        if (requested != null) return requested;
        if (!allowed.isEmpty()) return allowed.stream().sorted().findFirst().orElseThrow();
        throw ApiException.forbidden("当前账号没有可查看的团队绩效");
    }

    private UUID scopedMember(CurrentActor actor, UUID requested) {
        if (requested == null) return actor.isAdmin() || actor.isTeamManager() ? null : actor.membershipId();
        if (actor.isAdmin() || requested.equals(actor.membershipId())) return requested;
        if (actor.isTeamManager()) {
            Long count = jdbc.queryForObject("""
                    select count(*) from team_members where membership_id=:membershipId and team_id in (:teamIds)
                    """, new MapSqlParameterSource().addValue("membershipId", requested)
                    .addValue("teamIds", actor.managedTeamIds().isEmpty() ? Set.of(UUID.randomUUID()) : actor.managedTeamIds()), Long.class);
            if (count != null && count > 0) return requested;
        }
        throw ApiException.forbidden("不能查看该成员绩效");
    }

    private CurrentActor admin() {
        CurrentActor actor = actors.require();
        if (!actor.isAdmin()) throw ApiException.forbidden("只有组织管理员可以执行该绩效操作");
        return actor;
    }

    private CurrentActor contributor() {
        CurrentActor actor = actors.require();
        if (actor.roles().stream().noneMatch(role -> role == RoleCode.ORG_ADMIN
                || role == RoleCode.TEAM_MANAGER || role == RoleCode.MEMBER)) {
            throw ApiException.forbidden("当前角色不能申报绩效");
        }
        return actor;
    }

    private void requireOwner(UUID ownerId, CurrentActor actor) {
        if (!ownerId.equals(actor.membershipId()) && !actor.isAdmin()) throw ApiException.forbidden("不能操作其他成员的绩效记录");
    }

    private void validateRange(LocalDate from, LocalDate to, int maxDays) {
        if (from == null || to == null || to.isBefore(from)) throw ApiException.badRequest("INVALID_PERFORMANCE_RANGE", "日期范围无效");
        if (java.time.temporal.ChronoUnit.DAYS.between(from, to) + 1 > maxDays) {
            throw ApiException.badRequest("PERFORMANCE_RANGE_TOO_LARGE", "日期范围不能超过 " + maxDays + " 天");
        }
    }

    private void validateParticipantDates(LocalDate startsOn, LocalDate endsOn) {
        if (endsOn != null && endsOn.isBefore(startsOn)) throw ApiException.badRequest("INVALID_PERFORMANCE_PARTICIPANT_DATES", "结束日期不能早于开始日期");
    }

    private void requireVersion(long actual, Long expected) {
        if (expected == null || actual != expected) throw ApiException.conflict("STALE_VERSION", "数据已被其他人修改，请刷新后重试");
    }

    private String claimType(String value) {
        return enumValue(value, CLAIM_TYPES, "INVALID_PERFORMANCE_CLAIM_TYPE");
    }

    private String enumValue(String value, Set<String> allowed, String code) {
        String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (!allowed.contains(normalized)) throw ApiException.badRequest(code, "选项无效");
        return normalized;
    }

    private String normalizeStatus(String value) {
        return value == null || value.isBlank() ? null : value.trim().toUpperCase(Locale.ROOT);
    }

    private BigDecimal points(BigDecimal value, boolean allowNegative) {
        if (value == null) throw ApiException.badRequest("PERFORMANCE_POINTS_REQUIRED", "分数不能为空");
        BigDecimal normalized = value.setScale(1, RoundingMode.UNNECESSARY);
        if (!allowNegative && normalized.signum() < 0) throw ApiException.badRequest("PERFORMANCE_POINTS_NON_NEGATIVE", "分数不能为负数");
        if (normalized.multiply(BigDecimal.valueOf(2)).stripTrailingZeros().scale() > 0) {
            throw ApiException.badRequest("PERFORMANCE_HALF_POINT_REQUIRED", "分数必须是 0.5 的倍数");
        }
        if (normalized.abs().compareTo(BigDecimal.valueOf(100000)) > 0) throw ApiException.badRequest("PERFORMANCE_POINTS_TOO_LARGE", "分数超出允许范围");
        return normalized;
    }

    private Integer positiveCap(Integer value) {
        if (value == null) return null;
        if (value <= 0) throw ApiException.badRequest("PERFORMANCE_CAP_POSITIVE", "每日上限必须大于 0，留空表示不限");
        return value;
    }

    private List<PerformanceDtos.EventTier> validateTiers(List<PerformanceDtos.EventTier> input) {
        if (input == null || input.isEmpty()) return List.of();
        List<PerformanceDtos.EventTier> tiers = input.stream()
                .map(tier -> new PerformanceDtos.EventTier(tier.label().trim(), tier.minParticipants(),
                        tier.maxParticipants(), points(tier.points(), false)))
                .sorted(Comparator.comparingInt(PerformanceDtos.EventTier::minParticipants)).toList();
        for (int i = 0; i < tiers.size(); i++) {
            PerformanceDtos.EventTier tier = tiers.get(i);
            if (tier.label().isBlank()) throw ApiException.badRequest("PERFORMANCE_TIER_LABEL_REQUIRED", "活动档次名称不能为空");
            if (tier.maxParticipants() != null && tier.maxParticipants() < tier.minParticipants()) {
                throw ApiException.badRequest("INVALID_PERFORMANCE_TIER_RANGE", "活动档次最大人数不能小于最小人数");
            }
            if (i < tiers.size() - 1) {
                PerformanceDtos.EventTier next = tiers.get(i + 1);
                if (tier.maxParticipants() == null || tier.maxParticipants() + 1 != next.minParticipants()) {
                    throw ApiException.badRequest("INVALID_PERFORMANCE_TIER_GAP", "活动档次必须连续且不能重叠");
                }
            }
        }
        return tiers;
    }

    private PerformanceDtos.EventTier tierFor(List<PerformanceDtos.EventTier> tiers, int participants) {
        return tiers.stream().filter(tier -> participants >= tier.minParticipants()
                && (tier.maxParticipants() == null || participants <= tier.maxParticipants())).findFirst().orElse(null);
    }

    private long countClaims(UUID organizationId, UUID membershipId, LocalDate date, String statuses) {
        Set<String> values = Set.of(statuses.split(","));
        MapSqlParameterSource params = new MapSqlParameterSource().addValue("organizationId", organizationId)
                .addValue("membershipId", membershipId).addValue("date", date).addValue("statuses", values);
        Long count = jdbc.queryForObject("""
                select count(*) from performance_interaction_claims
                where organization_id=:organizationId and performance_date=:date and status in (:statuses)
                  and (:membershipId::uuid is null or membership_id=:membershipId)
                """, params, Long.class);
        return count == null ? 0 : count;
    }

    private long countActivities(UUID organizationId, UUID membershipId, LocalDate date, String statuses) {
        Set<String> values = Set.of(statuses.split(","));
        Long count = jdbc.queryForObject("""
                select count(*) from performance_activities
                where organization_id=:organizationId and event_date=:date and status in (:statuses)
                  and (:membershipId::uuid is null or organizer_membership_id=:membershipId)
                """, new MapSqlParameterSource().addValue("organizationId", organizationId)
                .addValue("membershipId", membershipId).addValue("date", date).addValue("statuses", values), Long.class);
        return count == null ? 0 : count;
    }

    private long approvedCount(UUID organizationId, UUID membershipId, LocalDate date, String type) {
        Long count = jdbc.queryForObject("""
                select count(*) from performance_interaction_claims where organization_id=:organizationId
                  and membership_id=:membershipId and performance_date=:date and status='APPROVED' and approved_type=:type
                """, new MapSqlParameterSource().addValue("organizationId", organizationId)
                .addValue("membershipId", membershipId).addValue("date", date).addValue("type", type), Long.class);
        return count == null ? 0 : count;
    }

    private String toJson(Object value) {
        try { return json.writeValueAsString(value); }
        catch (JsonProcessingException exception) { throw new IllegalStateException(exception); }
    }

    private <T> T read(String value, TypeReference<T> type) {
        try { return json.readValue(value, type); }
        catch (JsonProcessingException exception) { throw new IllegalStateException("绩效规则配置损坏", exception); }
    }

    private BigDecimal money(BigDecimal value) { return (value == null ? BigDecimal.ZERO : value).setScale(1, RoundingMode.HALF_UP); }
    private BigDecimal moneyNullable(BigDecimal value) { return value == null ? null : money(value); }
    private Instant instant(java.sql.ResultSet row, String column) throws java.sql.SQLException {
        java.sql.Timestamp value = row.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private record RuleRow(UUID id, int version, LocalDate effectiveFrom, BigDecimal baseline,
                           String calendarMode, Set<Integer> weekdays, Set<LocalDate> includedDates,
                           Set<LocalDate> excludedDates, LocalTime opensAt, LocalTime closesAt,
                           String shortfallMode, BigDecimal ordinaryPoints, BigDecimal deepPoints,
                           Integer ordinaryCap, Integer deepCap, List<PerformanceDtos.EventTier> tiers,
                           Instant createdAt) {}
    private record ParticipantRow(UUID id, UUID membershipId, UUID teamId, LocalDate startsOn,
                                  LocalDate endsOn, long version) {}
    private record MemberRow(UUID id, String displayName, UUID teamId, String teamName) {}
    private record InteractionRow(UUID id, UUID customerId, Instant contactedAt, String channel,
                                  String summary, String customerName) {}
    private record ClaimRow(UUID id, UUID membershipId, UUID teamId, UUID interactionId, UUID customerId,
                            LocalDate performanceDate, String requestedType, String approvedType, String status,
                            String reviewReason, Instant submittedAt, Instant reviewedAt, long version) {}
    private record ActivityRow(UUID id, UUID organizerId, UUID teamId, LocalDate eventDate, String title,
                               String goal, int expectedParticipants, String description, Integer actualParticipants,
                               Integer verifiedParticipants, String tierLabel, BigDecimal totalPoints, String status,
                               String reviewReason, Instant createdAt, long version) {}
    private record EvidenceRow(String ownerType, UUID ownerId, String fileName, String contentType, byte[] content) {}
    private record EvidenceBytes(String fileName, String contentType, byte[] content) {}
    public record EvidenceFile(String fileName, String contentType, byte[] content) {}
    private record DayRow(UUID ruleId, String status, String reopenedReason, long version) {}
    private record ScoreAndSettlement(PerformanceDtos.ScoreBreakdown score, boolean settled) {}
    private record LeaderboardBase(UUID membershipId, String displayName, UUID teamId, String teamName,
                                   BigDecimal earned, BigDecimal finalPoints, int scoredDays, int achievedDays) {}
    private record ExportRow(LocalDate date, String displayName, String teamName, String category,
                             BigDecimal points, String description, Instant createdAt) {}
}
