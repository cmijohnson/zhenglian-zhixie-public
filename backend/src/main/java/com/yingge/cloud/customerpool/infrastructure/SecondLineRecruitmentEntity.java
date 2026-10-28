package com.yingge.cloud.customerpool.infrastructure;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.UUID;

@Entity
@Table(name = "second_line_recruitments")
public class SecondLineRecruitmentEntity {
    @Id
    private UUID id;
    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;
    @Column(name = "customer_id", nullable = false)
    private UUID customerId;
    @Column(name = "status", nullable = false)
    private String status;

    protected SecondLineRecruitmentEntity() {
    }
}
