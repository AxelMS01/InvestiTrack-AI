# Project Execution Service

Java 21 / Spring Boot 4.1.2 service for the Project Execution contract in
[`../.agents/api-endpoints.md`](../.agents/api-endpoints.md), with the ownership and integration rules in
[`service-contracts.md`](../.agents/service-contracts.md). It owns its PostgreSQL database and project
memberships. The gateway remains the public entry point.

The implementation includes all 41 Project Execution method/path pairs: projects and state transitions,
members and explicit permissions, activities, milestones, evidence links, final-report submission and
reopening, publications and authors, calculated progress, and history. It additionally serves `/me/projects`,
`/reference/publication-types`, the internal owner-context/association/projection APIs, and protected OpenAPI.

## Build and run

```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
./mvnw verify
```

Tests start a disposable PostgreSQL Testcontainer by default. Docker must be reachable. To use a dedicated
existing test database whose name contains `test`:

```bash
PROJECT_TEST_DATABASE_URL=jdbc:postgresql://localhost:55434/project_test \
PROJECT_TEST_DATABASE_USERNAME=project_test \
PROJECT_TEST_DATABASE_PASSWORD=project_test ./mvnw verify
```

Integration tests truncate project-domain tables in that test database. They never use Identity's or
Proposal's database. They cover live-role boundaries, visibility before paging, ETags, weighted progress,
cross-project IDs, membership revocation, evidence validation, publication constraints, manual/event
creation races, atomic outbox/inbox writes, recoverable holds, projection snapshots, and overdue events.
JWT tests use real RSA signatures and JWK retrieval; HTTP client tests check forwarding, workload credentials,
delegation, malformed responses, and failure handling.

For host execution, copy `.env.example` to `.env`, configure its values, and load it explicitly:

```bash
set -a
source .env
set +a
./mvnw spring-boot:run
```

The default application port is `8083`. Flyway creates the normalized Project schema and its operational
outbox/inbox tables. No other service's database is read or migrated. Use a dedicated database/user.

## Docker

The existing `investitrack` network, Keycloak and Identity containers can be reused. From this directory:

```bash
cp .env.docker.example .env.docker
# Edit .env.docker: set the database password and integration credentials.
docker compose --env-file .env.docker up -d --build

docker compose --env-file .env.docker logs --tail 50 project-service
curl --fail http://localhost:8083/actuator/health/liveness
```

Compose creates a separate PostgreSQL 16 container with persistent storage. Application port `8083` is
published on loopback. Internal/operational routes must stay within trusted networks. The gateway should
route `PROJECT_SERVICE_URL=http://project-service:8083` on the shared Docker network; recreate its container
to apply changed environment variables. Gateway `/projects/{id}/deadlines` belongs to Reporting and stays there.

To allow Identity to resolve project access, set its `PROJECT_SERVICE_URL=http://project-service:8083` and
`PROJECT_SERVICE_TOKEN` to an Identity workload token with audience `project-service`, scope `project:context`,
and `client_id=sub=identity-service`. Recreate Identity after changing these environment variables.

## Identity and service credentials

Public tokens require the configured issuer, signature, lifetime, subject and `research-platform-api`
audience. Every public request also calls Identity's `/api/v1/me` with the signed user token to resolve the
application UUID, active account state, and current roles. Project does not assume `sub` is an application
UUID and does not trust user-ID headers or stale JWT roles. If `user_id` exists, it must match Identity's UUID.
Identity failures reject the operation. The gateway also checks signed role claims, so a coordinator must
have COORDINATOR in both the Keycloak token and the live Identity role assignments. The original bearer token is never written to the database or events.

Inside Docker:

```dotenv
OIDC_ISSUER_URI=http://localhost:8180/realms/investitrack
OIDC_JWK_SET_URI=http://keycloak:8080/realms/investitrack/protocol/openid-connect/certs
```

The issuer remains the exact public token `iss`; the JWK URL is independently reachable within Docker.

Outbound integration credentials are explicit:

| Setting | Required permission / use |
|---|---|
| `IDENTITY_SERVICE_TOKEN` | Identity workload token: audience `identity-service`, scope `identity:status`, `client_id=sub=project-execution-service`; validates members selected from approved snapshots and new memberships |
| `PROPOSAL_SERVICE_TOKEN` | Proposal workload token: audience accepted by Proposal, approved-snapshot reads and idempotent project-reference writes |
| `DOCUMENT_SERVICE_TOKEN` | Document workload token: release Project's own abandoned document holds |
| `TOKEN_EXCHANGE_URI` | OIDC provider's token endpoint supporting RFC 8693 exchange |
| `TOKEN_EXCHANGE_CLIENT_ID` / `TOKEN_EXCHANGE_CLIENT_SECRET` | Confidential Project workload client permitted to exchange the signed user token |
| `DOCUMENT_AUDIENCE` | Audience of the exchanged Document token, default `document-service` |

Document reference validation and hold creation require a delegated, audience-limited token obtained by
exchange. Missing configuration fails with `503`; no arbitrary subject header substitutes for delegation.
Workload tokens supplied in environment variables must be rotated and the container recreated before they
expire. The basic local Keycloak realm does not provision these workload clients/exchange permissions;
configure them according to the existing Identity workload conventions.

Internal tokens require audience `project-service`, a matching workload `client_id` and `sub`, an allowlisted
client and one of `project:context`, `project:association`, `project:projection`. Projection routes additionally
require `client_id=reporting-service`. Context routes return local facts without calling Identity, avoiding
recursive authorization. Other actuator endpoints and `/v3/api-docs` require the public audience and
`monitoring` scope; liveness is anonymous.

## Approval and creation

`POST /api/v1/projects` requires the caller's live `COORDINATOR` role. Creation fetches the immutable approved
snapshot from Proposal. Optional `approvalId` pins a known approval; if omitted the provider must resolve its
current approved snapshot, matching the older existing Proposal endpoint. Event delivery always supplies
`approvalId` as an internal query parameter.

The required snapshot is:

```json
{
  "proposalId": "11111111-1111-4111-8111-111111111111",
  "approvalId": "22222222-2222-4222-8222-222222222222",
  "proposalVersion": 7,
  "approvedAt": "2026-11-19T20:00:00Z",
  "title": "Approved sensor research",
  "abstract": "Optional approved summary",
  "researchAreaCode": "ENGINEERING",
  "principalResearcherId": "33333333-3333-4333-8333-333333333333",
  "collaborators": [],
  "creationPlan": {
    "coordinatorId": "44444444-4444-4444-8444-444444444444",
    "startDate": "2026-12-01",
    "plannedEndDate": "2027-12-01"
  }
}
```

Proposal now records the required `principalResearcherId`, normalized `collaborators`, and explicit
`creationPlan` when the coordinator approves a proposal. Supply the plan in the Proposal transition request;
see [Proposal's approval workflow](../proposal-service/README.md#approval--project). Legacy approvals without
plans still require an explicitly reviewed administrative correction. Document Management and delegated
credentials remain required for evidence/report validation and the document hold needed at closure.

Manual start/end/coordinator inputs, when supplied, must match the approved plan. New projects start as
`APPROVED` with zero progress. A proposal has at most one project, protected by a unique index and transaction
lock shared by event and manual workflows. Equal repeats return the existing project (`200`); incompatible
approvals/plans return `409`. Unknown optional provider metadata is ignored for snapshot equivalence.
A durable reference job records the resulting project back in Proposal after commit. Transient failures
retry; a permanent `409` is retained as `CONFLICT` for operator review.

## Authorization and concurrency

Reads are scoped to active members, the assigned institutional coordinator, or administrators. Confidential
and restricted projects additionally require membership for institutional managers. The principal researcher
and approved collaborators become members; the assigned coordinator is added as a collaborator if necessary.
Principal/researcher project roles derive project/work/report/publication write capabilities. Student and
collaborator roles receive reads and can receive explicit grants. Only the principal, assigned coordinator
or administrator can manage members. The principal membership cannot be replaced or removed. Open activity
assignments must be reassigned before removing a member.

All mutable resources return an ETag such as `"1"`. Mutations require exactly one quoted numeric `If-Match`:
missing = `428`, malformed = `400`, stale = `412`. Creation of members, activities, milestones and publications
uses the project ETag; evidence linking/unlinking uses the activity/milestone ETag. Item updates/transitions,
membership updates/removal, publication changes and final-report submission/reopening use their own ETag.
The first final-report PUT uses the project ETag; subsequent PUTs use the final-report ETag.

Creation endpoints accept `Idempotency-Key`, scoped to caller and route, retaining request hashes and the
original result for at least 24 hours. Conflicting key reuse returns `409`. Replays recheck current account,
role, project visibility and write permissions. Project row locks serialize aggregate mutations and enforce
concurrency even when different child resources change at once. No distributed DB transaction is used.

Projects list with `q,status,researchArea,memberId,coordinatorId,delayed,deadlineFrom,deadlineTo,limit,cursor,sort`.
Sort keys: `id,title,updatedAt,createdAt,plannedEndDate,progressPercent`; default `-updatedAt`.
Activities/milestones support their documented filters plus sorting by `id,name,deadline,updatedAt,createdAt,weight`
(and milestone `displayOrder`). Prefix `-` for descending order. Pagination is query-bound keyset pagination;
visibility is always applied before LIMIT. UUID breaks ties. Other collections use stable UUID ordering.
Null/omitted PATCH fields are unchanged; `clearMilestone:true` explicitly detaches an activity. State changes
use transition endpoints. Completed/cancelled work is immutable; only unstarted/cancelled unreferenced work
can be deleted. Deletes retain projection tombstones.

## Progress, reports and document holds

Progress is completed non-cancelled weight divided by all non-cancelled weight, rounded half up to two decimal
places. Zero work or entirely cancelled work produces zero. The configurable method is `ACTIVITY_WEIGHT` or
`MILESTONE_WEIGHT`; the service stores both the current percentage and calculation snapshots. Work changes,
progress and outbox events commit together. Milestone completion/cancellation requires associated activities
to be completed/cancelled or detached. Activity blocking requires a reason. A completed project must be
reopened before editing work items. Completing a project requires all remaining work completed and 100%
calculated progress; closing additionally requires a submitted, currently authorized final report.

Evidence and final-report bodies add `documentVersion` to the older endpoint examples, so validation and
references are pinned to a specific scanned version. Reference validation supplies a SHA-256 checksum.
Evidence bytes remain in Document Management. Final-report submission locks its draft association;
only the assigned coordinator can reopen it. Closing revalidates the exact version/checksum and acquires a
durable Document hold before committing. Hold intents are committed independently before the network call;
rollback marks them for release. A worker locks the project and reconciles intents after five minutes,
confirming only holds referenced by closed projects and releasing abandoned holds with Project credentials.
A timeout is never treated as a successful reservation or release. No worker releases a closed project's hold.

Publications store ordered external-author names and normalized unique DOIs. Optional attachments are checked
through Document metadata and delegated reference validation; metadata must expose a numeric `documentVersion`
or `currentVersion`. Publications cannot be changed after project closure/cancellation.

## Events, jobs and reporting

Set `MESSAGING_ENABLED=true` and configure RabbitMQ to enable durable topic exchange `invesctictrack.domain`,
consumer queue `project.domain-events`, and persistent outbox publishing using confirmations and mandatory
routing. Broker ACLs must restrict producer routing keys. Invalid schema/version/producer goes to durable
`project.invalid-events`. Valid ProposalApproved/DocumentDeleted deliveries are first committed to a durable
inbox, then acknowledged; workers apply domain changes plus `processed_event` atomically. Identical event IDs
are deduplicated; conflicting content is rejected. Original correlation and causation IDs are propagated.

Transient processing retries after 1s, 5s, 30s, 2m and 10m; exhausted/permanent failures remain `FAILED` in
`incoming_event` for review. After correcting a failure, an operator can reset its status to `PENDING`, clear
`next_attempt_at` and reset attempts, retaining its original ID. Unroutable/unconfirmed outbox events remain
pending. Delivery is at least once; consumers must deduplicate event IDs. Broker outage cannot erase local
business updates or events. `MESSAGING_ENABLED=false` leaves events durably pending for isolated development.

`JOBS_ENABLED=false` disables inbox processing, proposal-reference retries, hold reconciliation, deadline
notices and expired-record cleanup. Deadline notices are deduplicated by resource/date and do not override
coordinator-controlled lifecycle states. Document deletion notices are retained and produce ProjectChanged
notifications; live Document validation remains authoritative for subsequent associations and closure.

Reporting can read minimal `/internal/v1/projection-state/{PROJECT|ACTIVITY|MILESTONE}/{id}` results and versioned
tombstones. `/internal/v1/projection-snapshots?projection=PROJECTS|DEADLINES` materializes an internally consistent
snapshot with opaque cursors, pages up to 100 rows, and 15-minute expiration. Expired cursors return
`409 SNAPSHOT_EXPIRED`. Projection state excludes proposal abstracts, document contents and credentials.

The OpenAPI artifact is [`src/main/resources/openapi.json`](src/main/resources/openapi.json). Regenerate it
after DTO/controller changes with `python3 scripts/generate-openapi.py`. Gateway-generated errors and service
errors use RFC 9457 Problem Details with `code` and `correlationId`.
