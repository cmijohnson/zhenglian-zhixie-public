package com.yingge.cloud.task.infrastructure;

import com.yingge.cloud.task.domain.TaskAssignmentStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WorkTaskAssignmentRepository extends JpaRepository<WorkTaskAssignmentEntity, UUID> {
    Optional<WorkTaskAssignmentEntity> findByTaskIdAndMembershipId(UUID taskId, UUID membershipId);
    Optional<WorkTaskAssignmentEntity> findByIdAndOrganizationIdAndTaskId(UUID id, UUID organizationId, UUID taskId);
    List<WorkTaskAssignmentEntity> findByTaskIdOrderByUpdatedAtDesc(UUID taskId);
    List<WorkTaskAssignmentEntity> findByTaskIdInOrderByUpdatedAtDesc(Collection<UUID> taskIds);
    List<WorkTaskAssignmentEntity> findByTaskIdAndMembershipIdInOrderByUpdatedAtDesc(
            UUID taskId, Collection<UUID> membershipIds);
    List<WorkTaskAssignmentEntity> findByMembershipIdAndTaskIdIn(UUID membershipId, Collection<UUID> taskIds);
    long countByTaskId(UUID taskId);
    boolean existsByTaskIdAndStatusNot(UUID taskId, TaskAssignmentStatus status);
    long countByTaskIdAndStatus(UUID taskId, TaskAssignmentStatus status);
    long countByTaskIdAndMembershipIdIn(UUID taskId, Collection<UUID> membershipIds);
    long countByTaskIdAndMembershipIdInAndStatus(UUID taskId, Collection<UUID> membershipIds,
                                                  TaskAssignmentStatus status);
}
