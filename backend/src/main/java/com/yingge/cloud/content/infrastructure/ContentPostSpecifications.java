package com.yingge.cloud.content.infrastructure;

import com.yingge.cloud.content.domain.ContentPlatform;
import com.yingge.cloud.content.domain.ContentReviewStatus;
import com.yingge.cloud.organization.infrastructure.MembershipEntity;
import com.yingge.cloud.organization.infrastructure.UserAccountEntity;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

import java.util.ArrayList;
import java.util.UUID;

public final class ContentPostSpecifications {
    private ContentPostSpecifications() {}

    public static Specification<ContentPostEntity> circle(UUID organizationId, String search, UUID membershipId,
                                                          ContentPlatform platform,
                                                          ContentReviewStatus reviewStatus) {
        return (root, query, cb) -> {
            var predicates = new ArrayList<Predicate>();
            predicates.add(cb.equal(root.get("organizationId"), organizationId));
            if (membershipId != null) predicates.add(cb.equal(root.get("membershipId"), membershipId));
            if (platform != null) predicates.add(cb.equal(root.get("platform"), platform));
            if (reviewStatus != null) predicates.add(cb.equal(root.get("reviewStatus"), reviewStatus));
            if (search != null && !search.isBlank()) {
                String pattern = "%" + search.trim().toLowerCase() + "%";
                var directionMatch = query.subquery(UUID.class);
                var direction = directionMatch.from(ContentDirectionEntity.class);
                directionMatch.select(direction.get("id")).where(
                        cb.equal(direction.get("id"), root.get("directionId")),
                        cb.like(cb.lower(direction.get("title")), pattern));

                var memberMatch = query.subquery(UUID.class);
                var member = memberMatch.from(MembershipEntity.class);
                var account = memberMatch.from(UserAccountEntity.class);
                memberMatch.select(member.get("id")).where(
                        cb.equal(member.get("id"), root.get("membershipId")),
                        cb.equal(member.get("userId"), account.get("id")),
                        cb.like(cb.lower(account.get("displayName")), pattern));

                predicates.add(cb.or(
                        cb.like(cb.lower(root.get("topic")), pattern),
                        cb.like(cb.lower(root.get("contentUrl")), pattern),
                        cb.exists(directionMatch), cb.exists(memberMatch)));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }
}
