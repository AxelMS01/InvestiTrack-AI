# Proposal & Evaluation Service

Java 25 / Spring Boot 4.1.2 service implementing the Proposal/Evaluation endpoints in
[the API contract](../.agents/api-endpoints.md) and the approval, ownership, security and event contracts in
[service-contracts.md](../.agents/service-contracts.md). The existing JPA models and migrations V1–V4 are retained;
V5 adds integration state and V6 quarantines unsafe legacy unpublished events. PostgreSQL owns all domain,
HTTP replay, history, approval snapshot, outbox and inbox records. No other service's tables are read.

## Run with Docker

From `proposal-service`, reuse the existing `investitrack` network, Keycloak and Identity containers:

```bash
cp .env.docker.example .env.docker
# Edit .env.docker: choose a database password and supply integration credentials.
docker compose --env-file .env.docker up -d --build
docker compose --env-file .env.docker logs --tail 50 proposal-service
curl --fail http://localhost:8082/actuator/health/liveness
```

Compose creates a dedicated PostgreSQL 16 database with persistent storage. Proposal listens on `8082`,
without colliding with Identity on `8081` or Project on `8083`. Public ports bind to loopback. Keep internal
and operational paths on trusted networks and apply service-to-service TLS at ingress/mesh as required.
The gateway routes Proposal traffic using `PROPOSAL_SERVICE_URL=http://proposal-service:8082` on the shared
Docker network. Recreate containers after changing their environment files.

For host execution, copy `.env.example` to `.env`, configure its values and explicitly load it:

```bash
export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64
set -a
source .env
set +a
./mvnw spring-boot:run
```

Flyway runs automatically. `DATABASE_URL`, `DATABASE_USERNAME` and `DATABASE_PASSWORD` replace the previous
hardcoded connection settings; Spring's `SPRING_DATASOURCE_*` overrides remain usable. No credentials are
committed in the examples. The Maven wrapper uses Maven 3.9.11.

## Authentication and authorization

Public tokens require a valid signature, lifetime, subject, configured issuer and `research-platform-api`
audience. Inside Docker use the public issuer and a reachable signing-key URL:

```dotenv
OIDC_ISSUER_URI=http://localhost:8180/realms/investitrack
OIDC_JWK_SET_URI=http://keycloak:8080/realms/investitrack/protocol/openid-connect/certs
OIDC_AUDIENCE=research-platform-api
OIDC_INTERNAL_AUDIENCE=proposal-service
IDENTITY_SERVICE_URL=http://identity-service:8081
```

Every public request resolves the current profile through Identity's `/api/v1/me`, forwarding the signed
bearer token. The UUID, active account state and roles come from Identity. Institutional `sub` values need
not be UUIDs, and a `user_id` claim is optional; when present, it must agree with Identity's mapping.
Token role claims and user-ID headers cannot elevate domain permissions. `ADMIN` is the administrator role;
the old `ADMINISTRATOR` spelling is no longer used. Identity outage fails closed with `503`.

The gateway still applies coarse token-role checks. Users therefore need the applicable role in both their
Keycloak token and Identity role assignments. The local Keycloak administrator application user initially
has only ADMIN; grant RESEARCHER/COORDINATOR/EVALUATOR to the respective test accounts before those workflows.

Set `IDENTITY_SERVICE_TOKEN` to a workload token with audience `identity-service`, scope `identity:status`,
and `client_id=sub=proposal-service`. It validates selected researchers, evaluator roles and the approved
coordinator. Rotate environment-backed workload tokens and recreate the service before expiration.
Supporting documents are checked through Document's user-facing metadata API with the signed user token:
metadata must identify the requested document, its proposal context, and AVAILABLE processing state.
A document must belong to the actual proposal; a caller-supplied association is insufficient.

Internal endpoints require audience `proposal-service`, matching workload `client_id` and `sub`, an allowlisted
client, and a route-specific scope:

| Operation | Client | Scope |
|---|---|---|
| Approved creation snapshot / project reference | `project-execution-service` | `proposal:project-integration` |
| Proposal access context | `identity-service` or `document-service` | `proposal:context` |
| Projection state and snapshots | `reporting-service` | `proposal:projection` |

Configure these IdP workload claims explicitly; Keycloak's default service-account subject does not follow
this convention automatically. Internal owner-context queries use only local data and never call Identity,
avoiding recursive authorization. Liveness is anonymous. Other exposed actuator endpoints and `/v3/api-docs`
require the public audience plus `monitoring` scope. OpenAPI is also available as a local
[artifact](src/main/resources/openapi.json); regenerate it with `python3 scripts/generate-openapi.py`.

## Workflow and concurrency

All 25 documented Proposal/Evaluation method/path pairs are implemented, plus research-area reference data,
`/me/proposals`, `/me/evaluation-assignments`, and the `/evaluation-assignments` alias used directly by Identity.
Collections use `{items,page:{limit,nextCursor,hasMore}}`, with default 25 and maximum 100. Proposal filters
run before database pagination; sort keys are `id,title,createdAt,updatedAt,submittedAt`, optionally prefixed
with `-`. Cursors bind to the caller, filters and ordering. Other collections have stable UUID ordering and
resource-bound cursors. Authorization is checked again on every page and HTTP replay.

Fetch a resource's ETag and send one quoted numeric `If-Match`: missing returns `428`, malformed `400`, stale
`412`. Collaborator mutations and assignment creation use the proposal ETag; assignment/evaluation changes
use their own ETags. Parent locks serialize concurrent child changes. Refetch proposal ETags after modifying
children. Creation endpoints accept `Idempotency-Key`; identical retries return the original result, changed
requests return `409`, and replay records remain at least 24 hours. Cleanup only expires HTTP replay/export
records; histories, approvals and processed event IDs are retained.

Owners/collaborators edit DRAFT/CHANGES_REQUESTED proposals; only owners manage collaborators. The owner is
the protected principal researcher. Selected accounts must be active. Draft deletion retains an audit event
and a versioned projection tombstone. Terminal proposal states cannot be reopened through ordinary APIs.

Submission requires an active research area, abstract, research problem, methodology, expected results,
estimated duration and general objective. A review round starts from the latest submission and needs a
pending evaluator assignment. Researchers cannot evaluate their own proposals. Evaluators require the live
EVALUATOR role and a current assignment; revoked/older assignments do not grant access to revised proposals.
Submission of an evaluation requires a decision and scores for all active criteria. Catalog ranges and
weights are validated and snapshotted in scores. Submission locks the evaluation and completes its assignment.
Only a coordinator can release submitted evaluations; researchers see results after release. The coordinator
makes the academic decision after all active assignments in the current round are complete; no automatic
score threshold is imposed by this MVP policy.

PATCH applies supplied non-null fields. Arrays replace their collections; collaborator responsibility may be
cleared with null. Field/list constraints are validated and duplicate IDs/codes rejected. Errors use RFC 9457
Problem Details with stable `code` and `correlationId`. Correlation IDs are normalized before authentication
and carried into history, outgoing HTTP requests and event envelopes.

## Approval → Project

Approval requires an explicitly reviewed creation plan. The coordinator request is:

```http
POST /api/v1/proposals/{proposalId}/transitions
Authorization: Bearer <coordinator-token>
If-Match: "<current-version>"
Content-Type: application/json

{
  "targetStatus": "APPROVED",
  "reason": "Evaluation policy satisfied",
  "creationPlan": {
    "coordinatorId": "<active-coordinator-application-uuid>",
    "startDate": "2026-12-01",
    "plannedEndDate": "2027-12-01"
  }
}
```

`plannedEndDate` is mandatory and cannot precede `startDate`; no dates are inferred from estimated duration.
A plan is accepted only for APPROVED. The decision, immutable approved snapshot, status history and
`ProposalApproved` outbox event commit together. The snapshot contains `proposalId,approvalId,approvedAt,
proposalVersion,title,abstract,researchAreaCode,principalResearcherId,collaborators,creationPlan`, matching the
Project service's consumer. Its version is the actual committed approval version. Subsequent project linking
may advance the proposal version without changing that snapshot.

Project retrieves the snapshot through `GET /internal/v1/proposals/{id}/project-creation-data?approvalId=...`.
A mismatched approval returns `409`. Omitting `approvalId` resolves the current immutable approval for the
Project manual-command compatibility lookup. `PUT .../project-reference` accepts `{approvalId,projectId}`;
equal repeats return `204`, contradictions return `409`. Project's `ProjectCreated` event records the same
reference asynchronously, validates the approved plan, and is safe against duplicate/manual linking races.
Public proposal responses include the resulting `approvalId`, creation plan and `projectId`.

Legacy approved snapshots lacking creation plans return `409 LEGACY_APPROVAL_SNAPSHOT`. They require an
explicit institutional administrative correction procedure; this release does not invent or silently backfill
plans for historical decisions. Old zero-valued proposal versions advance to 1 during migration; refetch ETags.

## Durable messaging and reporting

Set `MESSAGING_ENABLED=true` with working RabbitMQ credentials in both Proposal and Project for automatic
approval-to-project processing. Both use durable topic exchange `invesctictrack.domain`. Configure Project's
`PROPOSAL_SERVICE_TOKEN` for the workload scope above and its Identity status credential. Without messaging,
new events remain durable in the outbox and manual Project creation can consume the same approved snapshot.

Proposal publishes minimal canonical envelopes with matching DB/envelope/message IDs, positive aggregate
versions, correlation/causation metadata, and required event data. Proposal text, evaluation scores/comments,
tokens and full request bodies are excluded from new events/history. Publisher confirmations and mandatory
routing are required before marking delivery complete; outages/nacks/returns retain the same event for retry.
V6 quarantines unpublished legacy envelopes carrying full content or missing aggregate metadata. Review
`outbox_event.quarantined_at` and `last_error_code` before replay; never clear quarantine on an unsafe payload.
Existing historic audit records are retained; external Audit retention rules remain a separate concern.

Queue `proposal.domain-events` consumes `project.ProjectCreated.v1` and `document.DocumentDeleted.v1`. Broker
ACLs must restrict producer routing keys. Invalid schema/version/producer goes to `proposal.invalid-events`.
Valid messages are first committed to `proposal_incoming_event`, then acknowledged. Processing stores the
domain effect and `processed_event` in one transaction. Duplicates do not repeat effects; conflicting payloads
are rejected. Transient processing retries after 1s, 5s, 30s, 2m and 10m. Exhausted/permanent failures remain
FAILED for operator review; after correcting the cause, reset status to PENDING, attempts to 0, and
`next_attempt_at` to null while retaining the original event ID. No failed operation is recorded as processed.
Document deletion notices retain references for history and invalidate access versions; live Document checks
remain authoritative for new attachments. `JOBS_ENABLED=false` pauses inbox processing and expiry cleanup.

Reporting can read `/internal/v1/projection-state/{PROPOSAL|EVALUATOR_ASSIGNMENT|EVALUATION}/{id}` and consistent
`/internal/v1/projection-snapshots?projection=EVALUATIONS|PROPOSALS`. Snapshot cursors expire after 15 minutes
(`409 SNAPSHOT_EXPIRED`); pages are capped at 100. Projection state excludes abstracts, assessments and
credentials; deleted drafts return versioned tombstones.

## Verification and HTTP examples

```bash
./mvnw verify
```

Tests start a disposable PostgreSQL Testcontainer by default. To use an isolated existing test database:

```bash
PROPOSAL_TEST_DATABASE_URL=jdbc:postgresql://localhost:55435/proposal_test \
PROPOSAL_TEST_DATABASE_USERNAME=proposal_test \
PROPOSAL_TEST_DATABASE_PASSWORD=proposal_test ./mvnw verify
```

The database name must contain `test`. Integration tests truncate their own domain tables; do not point them
at application data. They cover the complete academic workflow, approval-plan/snapshot contracts, event
privacy and confirmations, duplicate/conflicting deliveries, rollback on outbox failure, optimistic concurrency,
revocation, role/subject/workload checks, cursor isolation, and consistent reporting exports. JWT tests use
real RSA signatures and a JWK server. Broker exchange permissions and institutional provider interoperability
must be checked in the deployment environment.

[IntelliJ HTTP requests](http/proposal-service.http) execute the workflow in order and capture IDs/ETags.
Set real researcher/coordinator/evaluator tokens and application UUIDs privately. If reference catalogs are
empty, review and run [development catalog SQL](http/seed-test-catalogs.sql) against your development database.
