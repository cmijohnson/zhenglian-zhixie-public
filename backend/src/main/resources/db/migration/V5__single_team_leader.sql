WITH ranked_leaders AS (
    SELECT team_id, membership_id,
           row_number() OVER (PARTITION BY team_id ORDER BY created_at, membership_id) AS leader_rank
    FROM team_members
    WHERE is_manager = true
)
UPDATE team_members member
SET is_manager = false
FROM ranked_leaders ranked
WHERE member.team_id = ranked.team_id
  AND member.membership_id = ranked.membership_id
  AND ranked.leader_rank > 1;

DELETE FROM membership_roles role_link
WHERE role_link.role_id = '00000000-0000-0000-0000-000000000002'
  AND NOT EXISTS (
      SELECT 1 FROM team_members member
      WHERE member.membership_id = role_link.membership_id
        AND member.is_manager = true
  );

INSERT INTO membership_roles (membership_id, role_id)
SELECT membership.id, '00000000-0000-0000-0000-000000000003'
FROM memberships membership
WHERE NOT EXISTS (
    SELECT 1 FROM membership_roles role_link
    WHERE role_link.membership_id = membership.id
);

CREATE UNIQUE INDEX team_members_single_leader_idx
    ON team_members (team_id)
    WHERE is_manager = true;
