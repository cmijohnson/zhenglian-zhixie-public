package com.yingge.cloud.customer.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CustomerRepository extends JpaRepository<CustomerEntity, UUID>, JpaSpecificationExecutor<CustomerEntity> {
    Optional<CustomerEntity> findByIdAndOrganizationId(UUID id, UUID organizationId);
    @Query("""
            select c from CustomerEntity c where c.organizationId=:organizationId
            and c.archivedAt is null and c.id<>:excludedId and
            ((:phone is not null and c.normalizedPhone=:phone) or
             (:wechat is not null and c.normalizedWechatId=:wechat) or
             (:qq is not null and c.normalizedQqId=:qq))
            """)
    List<CustomerEntity> findExactDuplicates(UUID organizationId, UUID excludedId, String phone, String wechat, String qq);

    @Query("""
            select c from CustomerEntity c where c.organizationId=:organizationId
            and c.archivedAt is null and
            ((:phone is not null and c.normalizedPhone=:phone) or
             (:wechat is not null and c.normalizedWechatId=:wechat) or
             (:qq is not null and c.normalizedQqId=:qq))
            """)
    List<CustomerEntity> findExactDuplicates(UUID organizationId, String phone, String wechat, String qq);

    @Query(value = """
            select c.id from customers c
            where c.organization_id=:organizationId
              and c.archived_at is null
              and lower(regexp_replace(btrim(c.full_name), '[[:space:]]+', '', 'g'))=:fullName
              and lower(regexp_replace(btrim(c.major), '[[:space:]]+', '', 'g'))=:major
            """, nativeQuery = true)
    List<UUID> findNameMajorDuplicateIds(
            @Param("organizationId") UUID organizationId,
            @Param("fullName") String fullName,
            @Param("major") String major);

    @Query(value = """
            select c.id from customers c
            where c.organization_id=:organizationId and c.id<>:excludedId
              and c.archived_at is null
              and lower(regexp_replace(btrim(c.full_name), '[[:space:]]+', '', 'g'))=:fullName
              and lower(regexp_replace(btrim(c.major), '[[:space:]]+', '', 'g'))=:major
            """, nativeQuery = true)
    List<UUID> findNameMajorDuplicateIds(
            @Param("organizationId") UUID organizationId,
            @Param("excludedId") UUID excludedId,
            @Param("fullName") String fullName,
            @Param("major") String major);
}
