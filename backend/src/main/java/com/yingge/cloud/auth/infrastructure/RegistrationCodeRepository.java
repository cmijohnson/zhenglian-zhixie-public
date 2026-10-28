package com.yingge.cloud.auth.infrastructure;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RegistrationCodeRepository extends JpaRepository<RegistrationCodeEntity, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select code from RegistrationCodeEntity code where code.codeHash = :codeHash")
    Optional<RegistrationCodeEntity> findForRegistration(@Param("codeHash") String codeHash);

    Optional<RegistrationCodeEntity> findByCodeHash(String codeHash);

    List<RegistrationCodeEntity> findByOrganizationIdOrderByCreatedAtDesc(UUID organizationId);

    Optional<RegistrationCodeEntity> findByIdAndOrganizationId(UUID id, UUID organizationId);
}
