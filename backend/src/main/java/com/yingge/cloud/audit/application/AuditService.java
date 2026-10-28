package com.yingge.cloud.audit.application;

import com.yingge.cloud.audit.infrastructure.AuditLogEntity;
import com.yingge.cloud.audit.infrastructure.AuditLogRepository;
import com.yingge.cloud.permission.domain.CurrentActor;
import org.springframework.stereotype.Service;
import org.slf4j.MDC;

import java.util.Map;

@Service
public class AuditService {
    private final AuditLogRepository repository;

    public AuditService(AuditLogRepository repository) {
        this.repository = repository;
    }

    public void record(CurrentActor actor, String action, String entityType, String entityId,
                       String outcome, Map<String, Object> details) {
        repository.save(new AuditLogEntity(
                actor.organizationId(), actor.membershipId(), action, entityType, entityId, outcome, details,
                MDC.get("traceId")
        ));
    }
}
