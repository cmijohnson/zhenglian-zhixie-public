package com.yingge.cloud.customerpool.infrastructure;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.UUID;

@Entity
@Table(name = "customer_ownership_states")
public class CustomerOwnershipStateEntity {
    @Id
    @Column(name = "customer_id")
    private UUID customerId;
    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;
    @Column(name = "status", nullable = false)
    private String status;

    protected CustomerOwnershipStateEntity() {
    }
}
