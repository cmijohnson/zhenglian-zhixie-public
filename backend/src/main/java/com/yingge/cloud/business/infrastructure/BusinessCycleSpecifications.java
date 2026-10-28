package com.yingge.cloud.business.infrastructure;

import com.yingge.cloud.business.domain.BusinessCycleStatus;
import com.yingge.cloud.business.domain.BusinessParticipantRole;
import com.yingge.cloud.customer.infrastructure.CustomerAccessGrantEntity;
import com.yingge.cloud.customer.domain.CustomerQuality;
import com.yingge.cloud.organization.infrastructure.MembershipEntity;
import com.yingge.cloud.organization.infrastructure.UserAccountEntity;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Set;
import java.util.UUID;

public final class BusinessCycleSpecifications {
    private BusinessCycleSpecifications() {
    }

    public static Specification<BusinessCycleEntity> adminMatches(
            UUID organizationId, Boolean crossSale, UUID primaryMembershipId, UUID assistantMembershipId,
            BusinessCycleStatus status, Instant markedFrom, Instant markedTo, String qualityCode) {
        return (root, query, cb) -> {
            query.distinct(true);
            var predicates = new ArrayList<Predicate>();
            predicates.add(cb.equal(root.get("organizationId"), organizationId));
            if (crossSale != null) {
                Predicate hasCollaborator = activeCollaboratorExists(root, query, cb);
                predicates.add(crossSale ? hasCollaborator : cb.not(hasCollaborator));
            }
            if (status != null) predicates.add(cb.equal(root.get("status"), status));
            if (markedFrom != null) predicates.add(cb.greaterThanOrEqualTo(root.get("statusMarkedAt"), markedFrom));
            if (markedTo != null) predicates.add(cb.lessThan(root.get("statusMarkedAt"), markedTo));
            if (primaryMembershipId != null) {
                predicates.add(currentParticipantExists(root, query, cb, primaryMembershipId,
                        BusinessParticipantRole.PRIMARY));
            }
            if (assistantMembershipId != null) {
                predicates.add(currentParticipantExists(root, query, cb, assistantMembershipId,
                        BusinessParticipantRole.ASSISTANT));
            }
            String qualityLabel = qualityLabel(qualityCode);
            if (qualityLabel != null) {
                var tagged = query.subquery(UUID.class);
                var candidate = tagged.from(com.yingge.cloud.customer.infrastructure.CustomerEntity.class);
                tagged.select(candidate.get("id")).where(
                        cb.equal(candidate.get("id"), root.get("customerId")),
                        cb.equal(candidate.join("tags").get("category"), CustomerQuality.CATEGORY),
                        cb.equal(candidate.join("tags").get("name"), qualityLabel)
                );
                predicates.add(cb.exists(tagged));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }

    public static Specification<BusinessCycleEntity> customerTeamIn(Set<UUID> teamIds) {
        return (root, query, cb) -> teamIds == null || teamIds.isEmpty()
                ? cb.disjunction()
                : root.join("customer").get("teamId").in(teamIds);
    }

    private static Predicate currentParticipantExists(
            jakarta.persistence.criteria.Root<BusinessCycleEntity> root,
            jakarta.persistence.criteria.CriteriaQuery<?> query,
            jakarta.persistence.criteria.CriteriaBuilder cb,
            UUID membershipId, BusinessParticipantRole role) {
        var participant = query.subquery(UUID.class);
        var snapshot = participant.from(BusinessCycleParticipantSnapshotEntity.class);
        participant.select(snapshot.get("id")).where(
                cb.equal(snapshot.get("businessCycleId"), root.get("id")),
                cb.equal(snapshot.get("snapshotVersion"), root.get("currentSnapshotVersion")),
                cb.equal(snapshot.get("membershipId"), membershipId),
                cb.equal(snapshot.get("roleType"), role)
        );
        return cb.exists(participant);
    }

    private static Predicate activeCollaboratorExists(
            jakarta.persistence.criteria.Root<BusinessCycleEntity> root,
            jakarta.persistence.criteria.CriteriaQuery<?> query,
            jakarta.persistence.criteria.CriteriaBuilder cb) {
        var collaborator = query.subquery(UUID.class);
        var grant = collaborator.from(CustomerAccessGrantEntity.class);
        var membership = collaborator.from(MembershipEntity.class);
        var account = collaborator.from(UserAccountEntity.class);
        collaborator.select(grant.get("customerId")).where(
                cb.equal(grant.get("organizationId"), root.get("organizationId")),
                cb.equal(grant.get("customerId"), root.get("customerId")),
                cb.isNotNull(grant.get("granteeMembershipId")),
                cb.equal(membership.get("id"), grant.get("granteeMembershipId")),
                cb.equal(membership.get("organizationId"), root.get("organizationId")),
                cb.equal(membership.get("status"), "ACTIVE"),
                cb.equal(account.get("id"), membership.get("userId")),
                cb.isTrue(account.get("enabled")),
                cb.notEqual(membership.get("id"), root.join("customer").get("ownerMembershipId"))
        );
        return cb.exists(collaborator);
    }

    private static String qualityLabel(String qualityCode) {
        if (qualityCode == null || qualityCode.isBlank()) return null;
        return CustomerQuality.LEVELS.stream()
                .filter(level -> level.code().equalsIgnoreCase(qualityCode.trim())
                        || level.label().equalsIgnoreCase(qualityCode.trim()))
                .map(CustomerQuality.Level::label)
                .findFirst().orElse("__INVALID_QUALITY__");
    }
}
