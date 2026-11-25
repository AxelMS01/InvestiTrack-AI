# Identity & Access Service

Java 21 / Spring Boot 4.1.2 service implementing the Identity endpoints in `../.agents/api-endpoints.md`, the PostgreSQL model in `database-schema.md`, and the newer ownership/security/event rules in `service-contracts.md`. The existing Gradle and Spring Data JDBC baseline is retained. PostgreSQL owns profiles, account state, role assignments, global capabilities, status history, idempotency records, and the transactional outbox. No passwords or tokens are stored in the database.

## Run

Provide a dedicated PostgreSQL database and credentials, an institutional OIDC issuer, and RabbitMQ. Flyway runs only this service's migrations. The service listens on port 8081 by default.

```bash
export DATABASE_URL=jdbc:postgresql://localhost:5432/identity_db
export DATABASE_USERNAME=identity
export DATABASE_PASSWORD='<database secret>'
export OIDC_ISSUER_URI=https://idp.example/realms/investitrack
export RABBITMQ_HOST=localhost
export RABBITMQ_USERNAME=identity
export RABBITMQ_PASSWORD='<broker secret>'
./gradlew bootRun
```

On an **empty database**, configure `IDENTITY_BOOTSTRAP_SUBJECT`, `IDENTITY_BOOTSTRAP_EMAIL`, and optionally `IDENTITY_BOOTSTRAP_NAME` to create the first active administrator. Remove these settings afterward. Bootstrap never changes an existing database, reactivates an account, or restores a revoked role. Institutional `sub` values are mapped through `app_user.identity_subject`; they need not be UUIDs. If a signed `user_id` is present it must match that mapping. Configure the IdP to include the mapped `user_id` for downstream services that require it.

Access tokens must have the configured issuer, valid signature/lifetime, and `OIDC_AUDIENCE` (default `research-platform-api`) for public endpoints. Application roles are loaded from the database on each request; token role claims cannot elevate privileges. The fixed role catalog is `ADMIN`, `COORDINATOR`, `RESEARCHER`, `EVALUATOR`, `STUDENT`. There are no password, token issuance, or arbitrary role creation endpoints.

`GET /v3/api-docs` publishes the OpenAPI contract and requires scope `monitoring`. Liveness is public at `/actuator/health/liveness`; readiness, health details, info, and metrics require `monitoring`. Configure mutual TLS at the gateway/service mesh and restrict internal/operational routes to trusted networks; they must never be publicly routed. The application validates bearer tokens and route-specific audiences/scopes.

## APIs and concurrency

All documented `/api/v1/me`, user administration, role assignment, role catalog, and `/internal/v1` identity APIs are implemented. See [the contract](src/main/resources/openapi.json) and [HTTP examples](http/identity.http).

Mutable user responses carry an ETag, initially `"1"`. PATCH, role mutations, deactivation, and reactivation require an exact quoted numeric `If-Match`. Missing headers return 428, stale values 412. Repeated role additions with the current ETag do not change the version or emit another event. The final active administrator cannot be deactivated or stripped of ADMIN. Profile updates cannot change account status or identity subject. Personal updates permit display name, given/family names, and locale; changing email/institutional ID requires an administrator. Null or omitted PATCH fields are unchanged; an empty string clears optional fields. Blank names are rejected.

Provisioning accepts `Idempotency-Key` (1–200 characters). The application stores the request hash and original response for at least 24 hours, scoped to actor and operation. A changed request under the same key returns 409. Unique institutional subject, normalized email, and institutional ID constraints remain permanent. A single database lock serializes identity mutations and enforces administrator/concurrency invariants across instances. This is appropriate for the MVP's low-volume identity administration.

User search supports `q`, repeatable `status`, `role`, `limit` (1–100), and a query-bound cursor. Stable ordering uses UUID. Coordinators receive only UUID, institutional ID, and display name in lookup results. Search cursors are not authorization credentials and every request is authorized again.

## Workload and delegated authorization

Internal tokens must use `OIDC_INTERNAL_AUDIENCE` (default `identity-service`). Assign the minimum scopes:

| Operation | Scope |
|---|---|
| Current account status | `identity:status` |
| Single decision | `identity:check` |
| Bounded intersection | `identity:filter` |
| Workload queries for arbitrary users | additional `identity:any-subject` |

Status queries require a workload token. Workload `sub` must equal `client_id`, and `client_id` must be in `IDENTITY_WORKLOAD_CLIENTS` (comma separated; defaults are the domain service names). Configure this claim convention in the IdP. Delegated tokens without `identity:any-subject` must map to an active account matching the requested `subjectId`. Arbitrary user-ID headers are ignored.

`check` and `filter` use live account state, capabilities, and access facts from resource owners. Unknown action/type combinations deny access. Denials return 200/`allowed:false`; dependency or malformed context failures return 503. Filters accept at most 100 distinct resource IDs, return only input IDs, and fail as a complete batch. They share a 2.5-second deadline, with 300-ms connect and 1-second individual request timeouts; no retries. Authorization never trusts a consumer-supplied project association. Archived/closed resources remain readable when permitted; writes to closed/cancelled projects are denied. Proposal writes require ownership/collaboration and DRAFT/CHANGES_REQUESTED. Project writes require explicit action grants or a permitted institutional manager. Confidential projects/documents require membership; restricted documents currently deny access until the institution defines clearance policy. Document reads require AVAILABLE status. Project closure requires COMPLETED status. This conservative initial policy is explicit in `AccessPolicy` and should be reviewed against institutional requirements before deployment.

Set `PROJECT_SERVICE_URL`, `PROPOSAL_SERVICE_URL`, `DOCUMENT_SERVICE_URL` to fixed trusted owner URLs. Corresponding `*_SERVICE_TOKEN` values are owner-audience workload credentials with access-context read permissions. Supply rotated credentials through deployment secrets; restart the instance when rotating these environment-backed values. Missing configuration or an unavailable owner returns 503. The application does not synthesize project memberships or keep a duplicate membership database.

Owner contracts used:

- Project: `GET /internal/v1/projects/{id}/access-context?subjectId=...` returns `projectId,status,classification,accessVersion,subject:{userId,membershipStatus,permissions}`. `permissions` contains action codes such as `ACTIVITY_WRITE`; active membership is `ACTIVE`. Classification: PUBLIC/INTERNAL/CONFIDENTIAL/RESTRICTED.
- Proposal: `GET /internal/v1/proposals/{id}/access-context?subjectId=...` returns `proposalId,status,accessVersion,subject:{userId,isOwner,isCollaborator,isAssignedEvaluator}`.
- Document: `GET /internal/v1/documents/{id}/access-context` returns `documentId,status,classification,accessVersion,context:{projectId? ,proposalId?}`. Exactly one parent must exist. Identity resolves that parent's current access facts.
- Additional child lookup needed to securely authorize activities/milestones: `GET /internal/v1/activities/{id}/access-context?subjectId=...` or `/milestones/{id}/access-context?subjectId=...` returns `activityId`/`milestoneId`, `projectId`, `accessVersion`. The owner validates the actual association. This extends the proposed service contracts, which omit child-resource lookup; absent support fails closed with 503.

Current-user project/proposal lists forward the original bearer token to the owning service's documented `GET /api/v1/projects` or `/proposals` endpoint, which must enforce user visibility. Assigned evaluations use `GET /api/v1/evaluation-assignments` with that token; this aggregate listing endpoint is an integration addition because the original proposal contract only specifies per-proposal assignment routes. Pagination accepts `limit` and `cursor`. No user IDs or caller-provided filters can override the signed user context. The current proposal implementation does not yet expose all of these integration endpoints; deploy corresponding owner changes before enabling those flows.

## Reliable events

User provisioning, role changes, state changes, and profile updates insert canonical JSON envelopes into `outbox_event` in the same PostgreSQL transaction. Events include application actor UUID (or bootstrap SYSTEM actor), correlation ID, positive aggregate/identity version, and no profile content. Published event types: `UserCreated`, `UserRolesChanged`, `UserDeactivated`, `UserReactivated`, and additive `UserProfileChanged` (IDs/state only).

The scheduled publisher locks pending rows with `FOR UPDATE SKIP LOCKED`, publishes persistent messages to durable topic exchange `invesctictrack.domain` with routing key `identity.<EventType>.v1`, and marks them published only after broker confirmation with no mandatory return. Failed/unroutable deliveries remain pending with bounded exponential retry. Consumers must create durable bindings and deduplicate `eventId`; a crash after broker acceptance and before database commit may redeliver. `OUTBOX_ENABLED=false` disables publishing for isolated development; events still accumulate durably. Monitor pending rows/attempt counts and retain/clean delivered events and expired idempotency records under institutional retention policy.

Global role/permission policy is migration-backed. A deployment changing permissions must increment `identity_policy.version` and insert `AuthorizationPolicyChanged` in the same migration/transaction using the documented envelope. Its policy version is `identity-<version>`. There is no arbitrary policy-editing API in the MVP. Membership events belong to Project, per the newer service contracts, rather than Identity.

## Verify

```bash
./gradlew test bootJar
```

Integration tests use a disposable PostgreSQL 16 Testcontainer by default (Docker required). To use a dedicated local **test-only** database instead:

```bash
IDENTITY_TEST_DATABASE_URL=jdbc:postgresql://localhost:55432/identity_test \
IDENTITY_TEST_DATABASE_USERNAME=identity \
IDENTITY_TEST_DATABASE_PASSWORD='' ./gradlew test bootJar
```

Tests truncate identity business tables in that test database. They run real Flyway migrations and JDBC transactions, with signed-token authentication simulated by Spring Security's test support and mocked resource-owner responses. They cover account/role authorization, subject impersonation, stale token roles, ETags, idempotency, case-insensitive uniqueness, last-administrator invariants, cursor privacy, status history, atomic event creation, and dependency failures. Unit tests cover resource policies, outbound HTTP credentials and failure handling, and real RSA-signed JWT validation against a local OIDC discovery/JWKS server. Tests also exercise concurrent last-administrator changes and roll back user/history/assignment writes when event insertion fails. Live institutional issuer interoperability, RabbitMQ delivery, container image execution, and the downstream services require environment integration checks.
