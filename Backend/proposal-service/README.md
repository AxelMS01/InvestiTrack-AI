# Proposal & Evaluation API

Implements the Proposal & Evaluation routes in `.agents/api-endpoints.md`, the research-area lookup, and the two internal proposal/project integration routes.

## Running

Requires Java 21 and PostgreSQL. Flyway applies the migrations at startup. Configure the datasource using `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, and `SPRING_DATASOURCE_PASSWORD`.

Configure institutional JWT validation with:

- `OIDC_ISSUER_URI`: trusted token issuer.
- `OIDC_JWK_SET_URI`: issuer signing-key endpoint.
- `OIDC_AUDIENCE`: expected audience; defaults to `proposal-service`.

User tokens must contain a signed `user_id` claim with the mapped application user UUID and a `roles` array (`RESEARCHER`, `EVALUATOR`, `COORDINATOR`, `ADMINISTRATOR`, `AUDITOR`, as appropriate). The institutional `sub` is not assumed to equal the application user ID. Internal project integration requires the `proposal:project-integration` scope, issued only to the trusted project service. Keep `/internal/**` and `/actuator/**` behind the trusted network boundary; management access requires `monitoring` scope, except liveness.

```bash
cd proposal-service
./mvnw spring-boot:run
```

## API behavior

- Collections return `{items, page: {limit, nextCursor, hasMore}}`. Limits default to 25 and cannot exceed 100. Proposal filters run before pagination in the database. Proposal sorting supports `id`, `title`, `createdAt`, `updatedAt`, and `submittedAt`, with optional `-`; the default is `-updatedAt`. Reuse the same filters and ordering with the returned cursor.
- Fetch the resource's `ETag` and supply it as `If-Match` for updates, deletes, and transitions. Missing preconditions return 428; stale versions return 412. Collaborator mutations and assignment creation use the **proposal** ETag. Assignment and evaluation mutations use their own ETags. Refetch the proposal after changing its children.
- Creation routes accept `Idempotency-Key`, scoped to the application user and operation. Replays return the original response; a different body with the same key returns 409. Replay records are durable and retained until an institution-approved cleanup policy is added.
- PATCH applies supplied non-null fields; omitted or null optional fields preserve their current values. Supplied arrays replace the corresponding collection. The collaborator responsibility field may be cleared with null.
- Owners and collaborators can edit drafts and proposals with requested changes. Only owners manage collaborators. Draft deletion removes draft data and retains an audit tombstone.
- Submission requires an active research area, abstract, research problem, methodology, expected results, duration, and a general objective. Starting review requires a pending evaluator assignment for the latest submission. A coordinator decision requires all active assignments for that submission to be completed. Resubmission starts a new review round; earlier completed assignments cannot satisfy its decision policy. Researchers cannot evaluate their own proposals.
- Evaluation submission requires a decision and a score for every active criterion. Scores must respect catalog ranges; totals use criterion weights. Submission locks the evaluation and completes its assignment. Researchers see evaluations only after coordinator release.
- Mutations write an event envelope to the transactional outbox and a separate retained history record in the same transaction. Delivery of outbox events to a broker is outside this API implementation.
- Approval records an immutable proposal snapshot. Internal `GET /internal/v1/proposals/{proposalId}/project-creation-data` returns it, including `approvalId`. Internal `PUT /internal/v1/proposals/{proposalId}/project-reference` accepts `{approvalId, projectId}` and returns 204 for identical repeats, or 409 for incompatible references.

The local policies above implement the endpoint contract. The additional creation-plan and identity/access-context contracts in `.agents/service-contracts.md` are separate integration work.

## Tests

Use a dedicated PostgreSQL database: the suite runs Flyway and exercises HTTP routes through MockMvc. Endpoint tests roll back their data.

```bash
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/proposal_test \
SPRING_DATASOURCE_USERNAME=proposal_test \
SPRING_DATASOURCE_PASSWORD=your-test-password \
./mvnw test
```

The tests cover authorization, filtered cursor pagination, optimistic concurrency, retries, draft deletion/cancellation, evaluation submission/release, approval snapshots, and idempotent project linking, in addition to persistence model checks.

## IntelliJ HTTP Client

Open [http/proposal-service.http](http/proposal-service.http), replace the three access-token placeholders and the evaluator UUID, and execute requests 1–18 in order using the gutter run buttons. The response handlers capture IDs and ETags automatically. Requests at the bottom deliberately exercise error responses; run them separately.

Your configured OIDC provider must be running and issue tokens with the expected issuer, audience, application `user_id`, and `roles` claims. The default localhost URLs do not create an identity provider. If catalogs are empty, execute [http/seed-test-catalogs.sql](http/seed-test-catalogs.sql) against your development database before running the workflow. No real credentials are included in these examples.
