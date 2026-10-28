-- One membership represents one collaborator on a customer. Existing duplicate
-- grants are collapsed without changing the customer or its timeline.
WITH ranked AS (
    SELECT id,
           row_number() OVER (
               PARTITION BY organization_id, customer_id, grantee_membership_id
               ORDER BY can_edit DESC, created_at, id
           ) AS position
    FROM customer_access_grants
    WHERE grantee_membership_id IS NOT NULL
)
DELETE FROM customer_access_grants grant_row
USING ranked
WHERE grant_row.id = ranked.id AND ranked.position > 1;

CREATE UNIQUE INDEX uq_customer_member_access_grant
    ON customer_access_grants (organization_id, customer_id, grantee_membership_id)
    WHERE grantee_membership_id IS NOT NULL;
