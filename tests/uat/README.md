# Formal UAT automation

The formal-scale baseline targets the isolated `yingge-cloud-uat` Compose project at port `18080`.
Feature-specific runs may use another explicitly isolated project and port (for example
`yingge-cloud-intelligence-uat` on `18083`). Scripts must never be pointed at a production
host. Credentials are supplied through a temporary environment file outside the repository
and are removed after execution.

Core entry points:

- `sql/seed-scale.sql`: deterministic seed `20260720` at formal scale.
- `generate_fixtures.py`: CSV, XLSX, malformed, oversized and read-only SQLite fixtures.
- `run_rest_matrix.py`: role, tenant, CSRF, IDOR and business REST journeys.
- `playwright_business.py`: all 35 directly navigable current pages on Chrome and Edge, real forms, refresh, downloads,
  duplicate clicks, narrow viewport, console and layout checks.
- OAuth consent and one-time Agent approval routes require a live authorization/approval request and remain covered by the MCP protocol integration suite instead of being opened with fabricated IDs.
- `docker-compose.intelligence-uat.yml`: isolated UAT-only AI contract provider for deterministic customer-intelligence workflow tests; never load this override in production.
- `customer_intelligence_smoke.py`: configures only the isolated contract provider, executes a real queued analysis, verifies immutable evidence-backed output, and checks the two leadership ticker types.
- `customer_enrichment_snapshot_uat.py`: parses all 583 rows in the supplied workbook against an isolated restored snapshot, commits only safe matches, downloads the row report, and verifies same-file idempotency.
- `k6/formal-load.js`: configurable independent authenticated sessions and mixed load; only run at a concurrency explicitly approved for the environment.
- `sql/verify-scale.sql`: exact counts and relational invariants.

No script contains an SSH password, database password, application password or AI key.
