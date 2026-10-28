package com.yingge.cloud.interaction.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface InteractionRepository extends JpaRepository<InteractionEntity, UUID> {
    List<InteractionEntity> findByCustomerIdOrderByContactedAtDesc(UUID customerId);
}

