package com.yingge.cloud.customer.infrastructure;

import com.yingge.cloud.customer.domain.CustomerVisibility;
import com.yingge.cloud.customerpool.infrastructure.CustomerOwnershipStateEntity;
import com.yingge.cloud.customerpool.infrastructure.SecondLineRecruitmentEntity;
import com.yingge.cloud.permission.domain.CurrentActor;
import com.yingge.cloud.permission.domain.RoleCode;
import com.yingge.cloud.permission.infrastructure.TeamMembershipScopeEntity;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

import java.util.ArrayList;
import java.util.Collection;
import java.util.UUID;

public final class CustomerSpecifications {
    private CustomerSpecifications() {
    }

    public static Specification<CustomerEntity> visibleTo(CurrentActor actor) {
        return (root, query, cb) -> {
            Predicate organization = cb.equal(root.get("organizationId"), actor.organizationId());
            if (actor.isAdmin()) {
                return organization;
            }

            var grant = query.subquery(UUID.class);
            var access = grant.from(CustomerAccessGrantEntity.class);
            var grantPredicates = new ArrayList<Predicate>();
            grantPredicates.add(cb.equal(access.get("customerId"), root.get("id")));
            var grantees = new ArrayList<Predicate>();
            grantees.add(cb.equal(access.get("granteeMembershipId"), actor.membershipId()));
            if (!actor.teamIds().isEmpty()) {
                grantees.add(access.get("granteeTeamId").in(actor.teamIds()));
            }
            grant.select(access.get("customerId")).where(cb.and(
                    grantPredicates.getFirst(), cb.or(grantees.toArray(Predicate[]::new))
            ));

            var poolRestriction = query.subquery(UUID.class);
            var ownershipState = poolRestriction.from(CustomerOwnershipStateEntity.class);
            poolRestriction.select(ownershipState.get("customerId")).where(
                    cb.equal(ownershipState.get("customerId"), root.get("id")),
                    cb.notEqual(ownershipState.get("status"), "ASSIGNED")
            );
            var recruitmentRestriction = query.subquery(UUID.class);
            var recruitment = recruitmentRestriction.from(SecondLineRecruitmentEntity.class);
            recruitmentRestriction.select(recruitment.get("customerId")).where(
                    cb.equal(recruitment.get("customerId"), root.get("id")),
                    recruitment.get("status").in("VOLUNTEER_OPEN", "ADMIN_PENDING")
            );
            Predicate broadVisibilityAllowed = cb.and(
                    cb.not(cb.exists(poolRestriction)), cb.not(cb.exists(recruitmentRestriction)));

            var allowed = new ArrayList<Predicate>();
            allowed.add(cb.equal(root.get("ownerMembershipId"), actor.membershipId()));
            allowed.add(cb.exists(grant));

            if (!actor.hasRole(RoleCode.VIEWER)) {
                allowed.add(cb.and(broadVisibilityAllowed,
                        cb.equal(root.get("visibility"), CustomerVisibility.ORGANIZATION)));
                if (!actor.teamIds().isEmpty()) {
                    allowed.add(cb.and(
                            broadVisibilityAllowed,
                            cb.equal(root.get("visibility"), CustomerVisibility.TEAM),
                            root.get("teamId").in(actor.teamIds())
                    ));
                }
            }
            if (actor.isTeamManager() && !actor.managedTeamIds().isEmpty()) {
                allowed.add(cb.and(broadVisibilityAllowed, root.get("teamId").in(actor.managedTeamIds())));
                var teamOwner = query.subquery(UUID.class);
                var teamMembership = teamOwner.from(TeamMembershipScopeEntity.class);
                teamOwner.select(teamMembership.get("membershipId")).where(
                        cb.equal(teamMembership.get("membershipId"), root.get("ownerMembershipId")),
                        teamMembership.get("teamId").in(actor.managedTeamIds())
                );
                allowed.add(cb.and(broadVisibilityAllowed, cb.exists(teamOwner)));
            }
            return cb.and(organization, cb.or(allowed.toArray(Predicate[]::new)));
        };
    }

    public static Specification<CustomerEntity> matches(
            String search, String status, String intention, String verification, Collection<UUID> tagIds,
            boolean includeArchived
    ) {
        return matches(search, status, intention, verification, null, null, tagIds,
                includeArchived, null, null, null);
    }

    public static Specification<CustomerEntity> matches(
            String search, String status, String intention, String verification, String grade, String school,
            Collection<UUID> tagIds, boolean includeArchived, UUID teamId, UUID membershipId,
            UUID ownerMembershipId
    ) {
        return (root, query, cb) -> {
            query.distinct(true);
            var predicates = new ArrayList<Predicate>();
            if (!includeArchived) predicates.add(cb.isNull(root.get("archivedAt")));
            if (search != null && !search.isBlank()) {
                String pattern = "%" + search.trim().toLowerCase() + "%";
                predicates.add(cb.or(
                        cb.like(cb.lower(root.get("fullName")), pattern),
                        cb.like(cb.lower(root.get("school")), pattern),
                        cb.like(cb.lower(root.get("phone")), pattern),
                        cb.like(cb.lower(root.get("wechatId")), pattern),
                        cb.like(cb.lower(root.get("qqId")), pattern)
                ));
            }
            if (status != null && !status.isBlank()) predicates.add(cb.equal(root.get("currentStatus"), status));
            if (intention != null && !intention.isBlank()) predicates.add(cb.equal(root.get("intentionLevel"), intention));
            if (verification != null && !verification.isBlank()) predicates.add(cb.equal(root.get("verificationStatus"), verification));
            if (school != null && !school.isBlank()) {
                predicates.add(cb.like(cb.lower(root.get("school")), "%" + school.trim().toLowerCase() + "%"));
            }
            if (grade != null && !grade.isBlank()) {
                String normalizedGrade = grade.trim().toLowerCase();
                var gradeColumn = cb.lower(root.get("grade"));
                if (normalizedGrade.matches("20\\d{2}")) {
                    String shortGrade = normalizedGrade.substring(2);
                    predicates.add(cb.or(
                            cb.equal(gradeColumn, normalizedGrade),
                            cb.equal(gradeColumn, normalizedGrade + "级"),
                            cb.equal(gradeColumn, normalizedGrade + "届"),
                            cb.equal(gradeColumn, shortGrade),
                            cb.equal(gradeColumn, shortGrade + "级"),
                            cb.equal(gradeColumn, shortGrade + "届"),
                            cb.like(gradeColumn, "%" + normalizedGrade + "%")
                    ));
                } else {
                    predicates.add(cb.equal(gradeColumn, normalizedGrade));
                }
            }
            if (teamId != null) {
                var teamOwner = query.subquery(UUID.class);
                var teamMembership = teamOwner.from(TeamMembershipScopeEntity.class);
                teamOwner.select(teamMembership.get("membershipId")).where(
                        cb.equal(teamMembership.get("membershipId"), root.get("ownerMembershipId")),
                        cb.equal(teamMembership.get("teamId"), teamId)
                );
                predicates.add(cb.or(cb.equal(root.get("teamId"), teamId), cb.exists(teamOwner)));
            }
            if (membershipId != null) {
                predicates.add(cb.or(
                        cb.equal(root.get("ownerMembershipId"), membershipId),
                        cb.equal(root.get("createdByMembershipId"), membershipId)
                ));
            }
            if (ownerMembershipId != null) {
                predicates.add(cb.equal(root.get("ownerMembershipId"), ownerMembershipId));
            }
            if (tagIds != null && !tagIds.isEmpty()) {
                for (UUID tagId : tagIds) {
                    var tagged = query.subquery(UUID.class);
                    var candidate = tagged.from(CustomerEntity.class);
                    tagged.select(candidate.get("id")).where(
                            cb.equal(candidate.get("id"), root.get("id")),
                            cb.equal(candidate.join("tags").get("id"), tagId)
                    );
                    predicates.add(cb.exists(tagged));
                }
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }
}
