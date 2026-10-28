# Architecture decisions

## ADR-001: independent modular monolith

The cloud application lives only under `cloud-java/`. It never imports or packages code from the paused desktop applications. Business modules expose application services and DTOs; repositories remain module-private.

## ADR-002: server-side authorization

The authenticated organization and membership come from the server-side session. Customer visibility is converted to database predicates before data is loaded. Controllers, exports, dashboards and assistant operations call the same scoped services.

## ADR-003: optional provider-neutral AI

The assistant depends on `AiProvider`, not a vendor SDK. A disabled provider is the default when no server key exists. Model output is always an uncommitted draft.

## ADR-004: persistence and recovery

PostgreSQL data and backups use explicit host bind mounts. Containers are disposable; data directories are not. Flyway owns schema changes, and daily custom-format dumps are verified with `pg_restore --list`.

## ADR-005: Spring Boot lifecycle

The project uses Spring Boot 3.5.16 because 3.x is a hard requirement. This is the final OSS 3.5 release, so CI must keep dependency scanning enabled and a future Spring Boot 4 upgrade remains an explicit maintenance item.

## ADR-006: auditable team work distribution

Required work is represented by server-owned tasks and assignments. Mandatory tasks fan out to every active contributor, directly assigned tasks require an explicit membership list, and optional tasks use a capacity-checked self-claim flow. Assignment submission, acceptance and return are state transitions retained for audit; browser state never determines completion or eligibility.

## ADR-007: reviewed content counts

Campaign content is stored separately from general work tasks because it has platform/day uniqueness, organization-wide circle visibility, mutable engagement metrics and an administrative quality review. Only `VALID` posts count toward platform targets. Daily reports derive the grade-26 lead value from organization-scoped customer data and store only the member-entered fields.

## ADR-008: invitation-code registration

Public registration requires a high-entropy server-generated code. The locked code row determines the organization; after validating the code, the registrant must select one active team in that organization. The server validates the team/organization relationship and always assigns the global `MEMBER` role, so the browser cannot choose another organization or elevate its role. Codes are stored only as SHA-256 hashes, can expire, have a bounded usage count and can be disabled. Code consumption, account creation, team membership, mandatory-task assignment and registration audit are one database transaction.

## ADR-009: one leader per team

`team_members.is_manager` is the source of truth for team leadership, with a partial unique index enforcing at most one leader per team. A leader's customer scope includes customers whose explicit team is managed and customers whose owner belongs to the managed team, so private records cannot disappear merely because the recorder omitted a team field. Leader replacement updates roles and invalidates affected server sessions in one transaction; customer, dashboard, export and assistant paths continue to reuse the same scoped customer service.

## ADR-010: server-scoped team intelligence

The team-data endpoint accepts optional team and member filters, but resolves every request against the authenticated membership. Team leaders can query only teams they currently manage; organization administrators can query the whole organization. Customer lists, daily reports and content progress all use database-level organization/team/member predicates. A member filter matches customers they own or recorded, and customer projections expose both names so management views preserve accountability without widening visibility.

## ADR-011: announcements and weighted circle discovery

Announcements are organization-owned, visible to every authenticated member and mutable only by organization administrators. Archive is a state transition instead of deletion, while pinning and urgency control display order. Circle discovery supports server-side full-text-like matching over topic, direction, URL and sender plus an exact sender filter. The recommended score gives reviewed valid content the strongest quality tier, then adds views, likes and favorites with explicit weights; alternative chronological and metric sorts remain available.
