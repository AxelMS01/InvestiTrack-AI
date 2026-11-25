# Dashboard & Reporting Service

Java 25 / Spring Boot 4.1.2 service implementing the Dashboard & Reporting contracts in
[the architecture](../.agents/backend-architecture.md), [API endpoints](../.agents/api-endpoints.md),
and [service contracts](../.agents/service-contracts.md). It follows the existing Project service baseline.
The default port is `8085`; the gateway uses `REPORTING_SERVICE_URL`.

The service provides researcher, coordinator, project and evaluator dashboards; scoped deadline pages;
a report catalog, durable export jobs, protected downloads; status labels; and internal projection rebuilds.
The OpenAPI contract is [openapi.json](src/main/resources/openapi.json). Regenerate it with
`python3 scripts/generate-openapi.py`. Request examples are in [reporting.http](http/reporting.http).

## Run

```bash
export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64
cp .env.example .env
# Configure the database, issuer and workload credentials.
set -a
source .env
set +a
./mvnw spring-boot:run
```

Flyway creates only Reporting's own PostgreSQL tables. It never accesses another service's database.
The three read models are views over a common versioned JSONB projection store, indexed by project,
evaluator and deadline. Report definitions, jobs, inbox/processed IDs, rebuild staging, download grants
and idempotency records are persistent. CSV artifacts are private database `bytea` values, capped at
10 MiB per job, rather than public files or filesystem paths.

For Docker, reuse the `investitrack` network and existing Identity, Project, Proposal, Keycloak and RabbitMQ:

```bash
cp .env.docker.example .env.docker
# Set the dedicated Reporting database password and workload credentials.
docker compose --env-file .env.docker up -d --build
```

Compose creates a separate PostgreSQL 16 instance with a persistent volume and publishes application port
8085 on loopback. Set the gateway's `REPORTING_SERVICE_URL=http://report-service:8085` in Docker.
Keep `/internal/**` and operational routes restricted to trusted networks. No deployment is performed by tests.

## Authentication and current access

Public JWTs require a valid signature, issuer, expiration, subject and `research-platform-api` audience.
Reporting forwards the signed token to Identity's `/api/v1/me` to resolve the active application user and
current institutional roles. `sub` is not assumed to be an application UUID; an optional signed `user_id`
must match Identity. Arbitrary user headers and token role claims never establish local authorization.

Before reading protected projection payloads, Reporting submits candidate resource IDs to Identity's
`/internal/v1/authorization/filter` in batches of at most 100 and includes the allowed IDs in its SQL query.
Coordinator and administrator roles still undergo current project authorization. Researcher dashboards
additionally select the caller's memberships/principal role. Evaluator dashboards select only the caller's
assignments in currently readable proposals. A failed or malformed authorization response fails closed.

Configure these workload tokens, rotating them before expiration:

| Variable | Audience and required permission |
|---|---|
| `IDENTITY_SERVICE_TOKEN` | `identity-service`; `identity:status identity:filter identity:any-subject`; `client_id=sub=reporting-service`, already allowlisted by Identity |
| `PROJECT_SERVICE_TOKEN` | `project-service`; `project:projection`; `client_id=sub=reporting-service` |
| `PROPOSAL_SERVICE_TOKEN` | Proposal's configured internal audience; `proposal:projection`; `client_id=sub=reporting-service` |

Reporting derives the subject sent with workload authorization calls from the authenticated live profile
or persisted job owner. It never accepts an arbitrary subject from public request bodies.

Inbound rebuild requests require the `report-service` audience, `reporting:rebuild` scope, and matching
`client_id=sub` in `REPORT_WORKLOAD_CLIENTS` (default `reporting-admin`). A public administrator token alone
cannot call the internal control plane. Liveness is anonymous; other actuator endpoints and `/v3/api-docs`
require the public audience and `monitoring` scope. Errors use Problem Details with `code` and `correlationId`.

## Projection ingestion and recovery

Set `MESSAGING_ENABLED=true` and configure RabbitMQ to consume durable `reporting.domain-events` from
`invesctictrack.domain` using `project.*.v1` and `proposal.*.v1` bindings. Configure broker producer ACLs so
only the owning service can publish its routing keys. The consumer checks producer, canonical type,
envelope IDs, aggregate, actor, version and a 256 KiB size bound. Invalid or conflicting deliveries go to
`reporting.invalid-events` through `reporting.dead-letters`. Storage failures are requeued.

Valid events are committed to the durable inbox before acknowledgement. Duplicate event IDs are ignored;
reused IDs with different content are rejected. Workers fetch current minimal owner projection state,
including versioned tombstones, because event payloads are signals rather than complete read models.
Older/equal versions cannot overwrite newer projections. Proposal signals reconcile embedded assignment
sets, including revocations/removals. Inbox completion and projection writes commit atomically.
Transient failures retry after 1s, 5s, 30s, 2m and 10m. Exhausted or permanent failures remain `FAILED` with
sanitized codes; an operator may reset a corrected inbox record to `PENDING`, clearing `next_attempt_at`
and setting `attempt_count=0`. Original event IDs and payloads must be retained.

Rebuilds are required for initial bootstrap and reconciliation; RabbitMQ is not an event archive:

```http
POST /internal/v1/projections/rebuilds
Authorization: Bearer <reporting-admin workload token>
Content-Type: application/json

{"projection":"PROJECTS","scope":{},"reason":"Initial bootstrap"}
```

Repeat for `DEADLINES` and `EVALUATIONS`; poll the returned `Location`. Rebuilds materialize owner snapshot
pages in staging, verify a stable snapshot ID, then reconcile newer concurrent states/tombstones under the
same transaction lock used by event application. Replacement is atomic; failures leave the existing model
available. Project/deadline rebuilds accept up to 100 unique `scope.projectIds`. Evaluation rebuilds are full
because assignments belong to proposals. They refresh proposal metadata through the owner API so
research-area filtering also works immediately after bootstrap. Each job is bounded to one million source rows. Expired source
snapshots fail with `SNAPSHOT_EXPIRED`; submit a fresh job. Interrupted rebuilds are marked failed after
30 minutes and require a fresh job. `JOBS_ENABLED=false` disables inbox, export, rebuild and cleanup workers.

## Queries and exports

Deadlines use UTC dates: overdue is strictly before today; upcoming is today through seven days ahead.
Completed/cancelled work never becomes overdue. States are calculated at read time, so date changes do not
require another event. Dashboards expose `asOf`, the most recent local projection write, or `null` before
bootstrap; it is not a guarantee that every source has caught up. Dashboard/project filters use planned
end date for `from`/`to`; evaluator dates use assignment due date and status means proposal status.

Deadline pages use date/type/UUID ordering, with query-bound cursors and `limit=1..100`. Authorization is
rechecked before pagination. A changed/removed cursor resource requires restarting the query. Queries and
exports are bounded to 10,000 candidate rows; narrow filters if that limit is exceeded. Job listings show
only the caller's jobs (including administrators); an administrator can inspect a known job ID.

The seeded definitions are `PROJECT_PORTFOLIO_STATUS` and `PROJECT_DEADLINES`, both CSV. Consult the catalog
for parameter schemas. Portfolio supports a status array, research area, coordinator and explicit project IDs;
deadlines support project IDs, state, type and date bounds. Unknown/null parameters, duplicate IDs and
unsupported formats are rejected. These are current projections; historical `asOfDate`, PDF and XLSX are
not implemented or advertised.

`POST /api/v1/report-jobs` returns `202` and a `Location`. An optional `Idempotency-Key` retains the original
response for 24 hours; incompatible reuse returns `409`. Jobs capture the caller's current authorized project
IDs without storing user bearer tokens. Workers revalidate account, role and every project before generation
and again before saving the artifact. Scope can narrow but never expand to newly accessible projects.
CSV uses UTF-8, quoted cells and spreadsheet-formula neutralization. Artifacts expire after 24 hours;
cleanup removes bytes and marks jobs `EXPIRED`. Cancellation/removal erases the artifact and download grants.
Generation failures retain a sanitized failure code; retry by creating a new job.

`POST /report-jobs/{id}/download-authorization` returns a relative content URL valid for 60 seconds.
Follow it through the gateway with the bearer token. Both authorization and content retrieval recheck
current account, role and the generated artifact's project scope. Any revoked project blocks that artifact;
create a new report to obtain reduced scope. Download grants are random, stored only as hashes, bound to
the caller and job, and never replace authentication. Content responses use `no-store`, `nosniff`, and an
attachment filename. Disable query-string logging at proxies to avoid logging download grants.

## Verification

```bash
export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64
./mvnw verify
```

By default, tests use a disposable PostgreSQL Testcontainer and require Docker. Alternatively use a dedicated
existing test database whose name contains `test`:

```bash
REPORT_TEST_DATABASE_URL=jdbc:postgresql://localhost:55439/report_test \
REPORT_TEST_DATABASE_USERNAME=report_test \
REPORT_TEST_DATABASE_PASSWORD=report_test ./mvnw verify
```

Integration tests truncate Reporting test tables only. They exercise migrations, scoped dashboards and
pagination, evaluator updates/removals, duplicate inbox delivery, version/tombstone ordering, report privacy,
revocation during generation/download, expiry/cancellation, request idempotency, atomic full/scoped rebuilds
and concurrent states, failed rebuild retention, and public/workload boundaries. Additional HTTP/JWT tests
verify live identity mapping, bounded authorization batches, malformed dependency responses and real RSA
signatures/issuer/audience/expiry. Broker delivery and Docker deployment require a configured environment
and are not exercised by this suite.
