package com.yingge.cloud.customerpool.application;

import com.yingge.cloud.audit.application.AuditService;
import com.yingge.cloud.auth.application.CurrentActorProvider;
import com.yingge.cloud.customerpool.api.CustomerPoolDtos;
import com.yingge.cloud.market.application.MarketDeadlineEnforcementWorker;
import com.yingge.cloud.notification.application.NotificationService;
import com.yingge.cloud.permission.domain.CurrentActor;
import com.yingge.cloud.permission.domain.RoleCode;
import com.yingge.cloud.shared.api.ApiException;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Testcontainers(disabledWithoutDocker = true)
class CustomerPoolPostgresIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg18").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("yingge_pool_test")
            .withUsername("yingge_test")
            .withPassword("test-password");

    private JdbcTemplate jdbc;
    private NamedParameterJdbcTemplate namedJdbc;
    private TransactionTemplate transactions;
    private UUID organizationId;
    private UUID ownerId;
    private UUID claimantOneId;
    private UUID claimantTwoId;

    @BeforeEach
    void setUp() {
        var dataSource = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").cleanDisabled(false).load().clean();
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        namedJdbc = new NamedParameterJdbcTemplate(dataSource);
        transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        organizationId = UUID.randomUUID();
        jdbc.update("insert into organizations(id,name,code) values (?,?,?)",
                organizationId, "Pool Test", "pool-" + organizationId);
        ownerId = createMember("owner");
        claimantOneId = createMember("claimant-one");
        claimantTwoId = createMember("claimant-two");
    }

    @Test
    void releaseClaimConfirmAndPreviousOwnerReadonlyAreAtomic() {
        UUID customerId = createCustomer(ownerId, "13900000001");
        CustomerPoolService ownerService = service(ownerId);
        UUID entryId = transactions.execute(status -> ownerService.releaseToPool(
                organizationId, customerId, CustomerPoolService.PoolType.AB,
                CustomerPoolService.ReleaseReason.VOLUNTARY_RELEASE, ownerId));

        CustomerPoolService claimantService = service(claimantOneId);
        CustomerPoolDtos.ClaimResult claim = transactions.execute(status -> claimantService.claim(entryId,
                new CustomerPoolDtos.ClaimRequest("claim-once")));
        assertThat(claim.status()).isEqualTo("TEMPORARY");
        assertThat(jdbc.queryForObject("select owner_membership_id from customers where id=?", UUID.class, customerId))
                .isEqualTo(ownerId);
        assertThat(ownerService.mayWriteInCurrentOwnershipState(
                organizationId, customerId, ownerId, false)).isFalse();
        assertThat(claimantService.mayWriteInCurrentOwnershipState(
                organizationId, customerId, claimantOneId, false)).isTrue();

        UUID interactionId = UUID.randomUUID();
        jdbc.update("""
                insert into interactions
                  (id,organization_id,customer_id,contacted_at,channel,summary,created_by_membership_id,
                   interaction_type,deadline_effect)
                values (?,?,?,now(),'QQ','客户已回复',?,'CUSTOMER_REPLY','APPLIED')
                """, interactionId, organizationId, customerId, claimantOneId);
        boolean confirmed = transactions.execute(status -> claimantService.confirmTemporaryClaim(
                customerId, interactionId, "CUSTOMER_REPLY"));

        assertThat(confirmed).isTrue();
        assertThat(jdbc.queryForObject("select owner_membership_id from customers where id=?", UUID.class, customerId))
                .isEqualTo(claimantOneId);
        assertThat(jdbc.queryForObject("""
                select can_edit from customer_access_grants
                where customer_id=? and grantee_membership_id=?
                """, Boolean.class, customerId, ownerId)).isFalse();
        assertThat(jdbc.queryForObject("select status from customer_claim_records where id=?", String.class,
                claim.claimId())).isEqualTo("CONFIRMED");
    }

    @Test
    void concurrentClaimAllowsExactlyOneWinner() throws Exception {
        UUID customerId = createCustomer(ownerId, "13900000002");
        UUID entryId = transactions.execute(status -> service(ownerId).releaseToPool(
                organizationId, customerId, CustomerPoolService.PoolType.AB,
                CustomerPoolService.ReleaseReason.VOLUNTARY_RELEASE, ownerId));
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> claimAfter(start, service(claimantOneId), entryId, "concurrent-1"));
            var second = executor.submit(() -> claimAfter(start, service(claimantTwoId), entryId, "concurrent-2"));
            start.countDown();
            int successes = (first.get() ? 1 : 0) + (second.get() ? 1 : 0);
            assertThat(successes).isEqualTo(1);
        }
        assertThat(jdbc.queryForObject("""
                select count(*) from customer_claim_records
                where pool_entry_id=? and status='TEMPORARY'
                """, Integer.class, entryId)).isEqualTo(1);
    }

    @Test
    void expiredTemporaryClaimReturnsToPoolAndStartsCooldown() {
        UUID customerId = createCustomer(ownerId, "13900000003");
        UUID entryId = transactions.execute(status -> service(ownerId).releaseToPool(
                organizationId, customerId, CustomerPoolService.PoolType.AB,
                CustomerPoolService.ReleaseReason.VOLUNTARY_RELEASE, ownerId));
        CustomerPoolService claimant = service(claimantOneId);
        CustomerPoolDtos.ClaimResult claim = transactions.execute(status -> claimant.claim(entryId,
                new CustomerPoolDtos.ClaimRequest("expire-me")));
        jdbc.update("update customer_claim_records set expires_at=now()-interval '1 second' where id=?", claim.claimId());
        jdbc.update("update customer_pool_entries set temporary_claim_expires_at=now()-interval '1 second' where id=?", entryId);
        jdbc.update("update customer_ownership_states set temporary_claim_expires_at=now()-interval '1 second' where customer_id=?", customerId);

        transactions.executeWithoutResult(status -> claimant.expireTemporaryClaimsAndEscalate());

        assertThat(jdbc.queryForObject("select status from customer_pool_entries where id=?", String.class, entryId))
                .isEqualTo("OPEN");
        assertThat(jdbc.queryForObject("select status from customer_claim_records where id=?", String.class,
                claim.claimId())).isEqualTo("EXPIRED");
        assertThat(jdbc.queryForObject("""
                select cooldown_until>now() from customer_claim_cooldowns
                where customer_id=? and membership_id=?
                """, Boolean.class, customerId, claimantOneId)).isTrue();
    }

    @Test
    void reclassifyingCPooledCustomerToARestoresAssignedOwnership() {
        UUID customerId = createCustomer(ownerId, "13900000004");
        CustomerPoolService service = service(ownerId);
        UUID entryId = transactions.execute(status -> service.releaseToPool(
                organizationId, customerId, CustomerPoolService.PoolType.C,
                CustomerPoolService.ReleaseReason.C_CLASSIFIED, ownerId));

        transactions.executeWithoutResult(status -> service.reconcileClassification(
                organizationId, customerId, "A", ownerId));

        assertThat(jdbc.queryForObject("select status from customer_pool_entries where id=?",
                String.class, entryId)).isEqualTo("CANCELLED");
        assertThat(jdbc.queryForObject("select status from customer_ownership_states where customer_id=?",
                String.class, customerId)).isEqualTo("ASSIGNED");
        assertThat(jdbc.queryForObject("""
                select count(*) from second_line_recruitments
                where customer_id=? and status='VOLUNTEER_OPEN'
                """, Integer.class, customerId)).isEqualTo(1);
    }

    @Test
    void enforcedDeadlineAtomicallyReleasesCustomerToAbPool() {
        UUID customerId = createCustomer(ownerId, "13900000005");
        UUID ruleId = UUID.randomUUID();
        jdbc.update("""
                update organization_follow_up_policies
                set ab_auto_release_enabled=true,
                    ab_auto_release_effective_from=now()-interval '1 day'
                where organization_id=?
                """, organizationId);
        jdbc.update("""
                insert into market_deadline_rule_versions
                  (id,organization_id,version,effective_from,mode,created_by_membership_id)
                values (?,?,2,now()-interval '1 day','ENFORCED',?)
                """, ruleId, organizationId, ownerId);
        jdbc.update("""
                insert into customer_deadline_snapshots
                  (customer_id,organization_id,cycle_id,classification_level,rule_version_id,
                   computed_deadline,mode)
                values (?,?,?,?,?,now()-interval '1 minute','ENFORCED')
                """, customerId, organizationId, UUID.randomUUID(), "A", ruleId);
        MarketDeadlineEnforcementWorker worker = new MarketDeadlineEnforcementWorker(
                namedJdbc, service(ownerId), mock(com.yingge.cloud.market.application.MarketDeadlineService.class));

        Integer released = transactions.execute(status -> worker.enforceDueDeadlines());

        assertThat(released).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                select count(*) from customer_pool_entries
                where customer_id=? and status='OPEN' and pool_type='AB'
                  and release_reason='DEADLINE_EXPIRED'
                """, Integer.class, customerId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from customer_deadline_snapshots where customer_id=?",
                Integer.class, customerId)).isZero();
        assertThat(jdbc.queryForObject("""
                select count(*) from customer_deadline_events
                where customer_id=? and event_type='DEADLINE_ENFORCED_RELEASE'
                """, Integer.class, customerId)).isEqualTo(1);
    }

    @Test
    void disabledAbAutoReleaseKeepsExpiredSnapshotAndDoesNotCreatePoolEntry() {
        UUID customerId = createCustomer(ownerId, "13900000006");
        UUID ruleId = UUID.randomUUID();
        jdbc.update("""
                insert into market_deadline_rule_versions
                  (id,organization_id,version,effective_from,mode,created_by_membership_id)
                values (?,?,2,now()-interval '1 day','ENFORCED',?)
                """, ruleId, organizationId, ownerId);
        jdbc.update("""
                insert into customer_deadline_snapshots
                  (customer_id,organization_id,cycle_id,classification_level,rule_version_id,
                   computed_deadline,mode)
                values (?,?,?,?,?,now()-interval '1 minute','ENFORCED')
                """, customerId, organizationId, UUID.randomUUID(), "B", ruleId);
        MarketDeadlineEnforcementWorker worker = new MarketDeadlineEnforcementWorker(
                namedJdbc, service(ownerId), mock(com.yingge.cloud.market.application.MarketDeadlineService.class));

        Integer released = transactions.execute(status -> worker.enforceDueDeadlines());

        assertThat(released).isZero();
        assertThat(jdbc.queryForObject("select count(*) from customer_pool_entries where customer_id=?",
                Integer.class, customerId)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from customer_deadline_snapshots where customer_id=?",
                Integer.class, customerId)).isEqualTo(1);
    }

    private boolean claimAfter(CountDownLatch start, CustomerPoolService service, UUID entryId, String key)
            throws InterruptedException {
        start.await();
        try {
            transactions.execute(status -> service.claim(entryId, new CustomerPoolDtos.ClaimRequest(key)));
            return true;
        } catch (ApiException exception) {
            assertThat(exception.code()).isIn("POOL_ENTRY_UNAVAILABLE", "POOL_ENTRY_ALREADY_CLAIMED");
            return false;
        }
    }

    private CustomerPoolService service(UUID membershipId) {
        CurrentActorProvider actors = mock(CurrentActorProvider.class);
        when(actors.require()).thenReturn(new CurrentActor(UUID.randomUUID(), membershipId, organizationId,
                "test", "Test", Set.of(RoleCode.MEMBER), Set.of(), Set.of()));
        return new CustomerPoolService(namedJdbc, actors, mock(AuditService.class), mock(NotificationService.class));
    }

    private UUID createMember(String suffix) {
        UUID userId = UUID.randomUUID();
        UUID membershipId = UUID.randomUUID();
        jdbc.update("""
                insert into user_accounts(id,username,password_hash,display_name,password_change_required)
                values (?,?,?,?,false)
                """, userId, suffix + "-" + UUID.randomUUID(), "unused", suffix);
        jdbc.update("insert into memberships(id,organization_id,user_id) values (?,?,?)",
                membershipId, organizationId, userId);
        return membershipId;
    }

    private UUID createCustomer(UUID owner, String phone) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into customers
                  (id,organization_id,owner_membership_id,visibility,full_name,phone,normalized_phone,
                   school,major,grade,identity_type,demand_summary,intention_level,urgency_level,
                   verification_status,current_status,created_by_membership_id)
                values (?,?,?,'PRIVATE','并发测试客户',?,?, '示例大学','计算机','2026级','在校生',
                  '脱敏需求摘要','中','中','未核实','跟进中',?)
                """, id, organizationId, owner, phone, phone, owner);
        return id;
    }
}
