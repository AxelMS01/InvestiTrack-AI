# Audit Service

Java 25 / Spring Boot 4.1.2, following the implemented domain services. Owns `audit_db`: append-only audit history and durable, private CSV export jobs. Default port: 8087. Public routes are already mapped by the gateway; the export content route is included in this change. The architecture's Java 25 baseline is retained, with the repository's Boot 4/JDBC conventions.

## Run and database permissions

Copy `.env.example` to `.env`, fill credentials and export the variables in your shell. Spring does not automatically read `.env` files. Run `JAVA_HOME=/path/to/java21 ./mvnw spring-boot:run`. Flyway migrates only this service's database.

Provision a dedicated database and a migration owner. For the application, provision a separate LOGIN role named `audit_runtime` that does not own the database, schema, tables or functions and does not inherit an owner/superuser role. After the owner runs Flyway, apply:

```sh
psql -v ON_ERROR_STOP=1 -v runtime_role=audit_runtime -f deploy/runtime-grants.sql
```

Use normal `PG*` connection variables to connect as the migration owner. The script grants runtime SELECT/INSERT on history and inbox, sequence access, and CRUD on mutable export/grant/idempotency tables. It grants no history UPDATE, DELETE, TRUNCATE or schema CREATE. Append-only triggers additionally reject row mutation and truncation. Run future migrations using separate migration credentials (`SPRING_FLYWAY_USER` / `SPRING_FLYWAY_PASSWORD`), or migrate separately and set `SPRING_FLYWAY_ENABLED=false` for the application. Preserve the runtime grants when adding tables.

For local Docker, copy `.env.example` to `.env.docker`, set `DATABASE_URL=jdbc:postgresql://audit-postgres:5432/audit_db`, and use network names for Identity, the IdP and RabbitMQ. Run `docker compose --env-file .env.docker up --build` on the existing `investitrack` network. This local compose creates one PostgreSQL owner account from `DATABASE_USERNAME`, allowing Flyway to bootstrap; use the separate owner/runtime setup above for deployments that require database-enforced least privilege. Identity, IdP and broker infrastructure are shared and not created by this compose file.

## Authentication and authorization

User JWTs require the configured issuer, expiration, subject and public audience. Identity `/api/v1/me` supplies the current active account and application roles; an optional signed `user_id` must match the resolved UUID. `ADMIN` and `AUDITOR` are institution-wide audit privileges. Coordinators and project membership alone confer no audit access. The Identity migration in this change registers `AUDITOR` without granting project, document, user-management or AI capabilities. Assign it through the existing administrator role workflow and provide the matching IdP role for the gateway. Account deactivation or role revocation denies subsequent reads and downloads even if the access token has not expired.

Export jobs are visible only to their requesting auditor, including when another caller is an administrator. Authorization is repeated when jobs are generated and when downloads are authorized or served. Set `IDENTITY_SERVICE_TOKEN` to an **audit-service workload** token for the Identity audience with `identity:status` scope. Identity's default workload allowlist now includes audit-service; deployments overriding `IDENTITY_WORKLOAD_CLIENTS` must include it too. Supplied short-lived workload credentials must be refreshed by deployment infrastructure. Required Identity failures return 503 and do not release protected data.

`GET /v3/api-docs` serves the checked-in OpenAPI contract with `monitoring` scope. Liveness is public; readiness and metrics require monitoring credentials. Regenerate the contract using `python3 scripts/generate-openapi.py`. Runnable HTTP examples are in `http/audit.http`.

## Ingestion and privacy

The main ingestion path consumes domain events from RabbitMQ. Enable `MESSAGING_ENABLED=true` with broker credentials. The durable `audit.domain-events` queue has bindings for the identity, proposal, project, document, ai and reporting domains on `invesctictrack.domain`. Unknown event versions are received and rejected to `audit.failed-events`; valid additive v1 event types are accepted when their producer/resource domain is supported. Configure broker topic permissions so each producer can publish only its own domain prefix, and prevent producers from sending directly to Audit queues/default exchange. The received exchange, route domain/type/version and claimed producer must match. Broker credentials and network transport must authenticate the producer; the payload's `producer` field alone is never authority.

A valid event is normalized and sanitized before persistence. Audit row, hash-chain link and processed-event inbox are inserted in one PostgreSQL transaction. The listener acknowledges only after commit. Invalid schemas, producer mismatches, unsupported versions and conflicting duplicate IDs are rejected immediately. Transient failures get five bounded retries (1, 5, 25, 125 and 600 seconds), then rejection to the durable error queue. Duplicate equal deliveries are acknowledged without a second history row. Different facts sharing an aggregate version and out-of-order versions are all retained. Audit is historical and does not discard older facts as a projection would.

Restrict the failed queue to operators and apply an institutional retention policy: it contains original rejected broker messages. Consumer logs never print Message objects or arbitrary producer bodies. Alert on failures/error-queue depth and replay the original event ID after fixing the cause. The service does not silently replace rejected history.

The exceptional REST fallback is `POST /internal/v1/audit-events`. It is excluded from gateway routes and requires a signed workload JWT with audience `audit-service`, scope `audit:append`, allowlisted `client_id` and `sub=client_id`, over authenticated TLS/mTLS infrastructure. No delegated user token is accepted as a workload. Source service is derived from credentials, and each producer is restricted to its resource types. Allowlisted identities are identity-service, proposal-service, project-execution-service, document-service, ai-service, reporting-service and idp-audit-adapter. The IdP adapter may attest USER/AUTHENTICATION events; it must map external subjects to application UUIDs before sending user actors. An IdP-specific adapter itself is external to this service.

Fallback body: `eventId,occurredAt,actor:{type,id},action,resource:{type,id?},outcome,correlationId,metadata?`. First acceptance is 201; equal replay is 200; contradictory content or producer under the same event ID is 409. Event IDs remain deduplicated indefinitely. All JSON POST bodies and broker envelopes are bounded to 256 KiB. Service actors must match the attesting producer; user actors are application UUIDs. Future timestamps beyond 60 seconds are rejected; historical events remain valid.

The metadata allowlist retains UUID identifiers, bounded codes, explicit dates/timestamps, hashes, counters, progress and role codes. Unknown fields, arbitrary nested objects, full text, questions, responses, credentials and signed URLs are omitted. Known fields must have the expected type/shape; malformed known metadata rejects the event. No full incoming payload is persisted: only its SHA-256 fingerprint, sanitized history, and inbox identity. A changed raw payload still conflicts under an already accepted event ID, even when the changed field would be redacted. Producer action and correlation identifiers must be structural codes, never content or secrets.

## Search and exports

`GET /api/v1/audit-events` filters: `actorId`, `action`, `resourceType`, `resourceId`, `outcome`, `from`, `to`, `correlationId`, plus `projectId` and `sourceService`. Dates are inclusive UTC calendar dates or timestamp bounds. Results sort by occurrence time and UUID descending. Page size is 1–100; cursors bind the caller, filters, page size and an ingestion-sequence boundary. Late arrivals cannot alter the remainder of a page sequence. `GET /audit-events/{id}` returns actor/resource objects and sanitized metadata; the raw-payload fingerprint is not exposed.

`POST /api/v1/audit-export-jobs` accepts `{"filters":{...}}` and returns 202 with a Location for polling. Filters match search without limit/cursor. Optional `Idempotency-Key` replays the original job response and rejects different filters; records are retained. Creation captures the maximum committed ingestion sequence. Workers export only records inside that boundary, including events that originally arrived out of occurrence-time order. Exports are bounded to 10000 events and 10 MiB; narrower filters are required for larger selections. Failed jobs record a sanitized failure code and no partial artifact.

Completed CSVs are stored as private PostgreSQL bytea artifacts for this bounded MVP. They contain only sanitized audit columns, escape CSV quotes/newlines and neutralize spreadsheet formulas. Artifacts expire after 24 hours and are erased by cleanup. This storage choice keeps the exported audit data private without introducing another object-store dependency; audit history itself is retained indefinitely. No retention deletion API exists.

`POST /audit-export-jobs/{id}/download-authorization` returns a relative `/content?token=...` URL valid for 60 seconds. The 256-bit random grant is stored only as a hash and is bound to the requesting auditor and job. GET content still requires that user's current authenticated auditor account. A URL alone cannot download anything. Downloads send no-store and attachment headers; configure infrastructure access logs to redact query tokens.

## Tamper detection and restoration

The application serializes small append transactions using a PostgreSQL advisory lock and assigns a monotonic sequence. Each row's SHA-256 covers all stored audit fields except its own event hash, including the previous link and raw-input fingerprint. Timestamps are normalized to PostgreSQL microsecond precision and metadata has deterministic canonical encoding. Sequence gaps from rolled-back transactions are valid.

Run the independent verifier against a consistent database/backup snapshot using read-only credentials:

```sh
PGHOST=localhost PGPORT=5432 PGDATABASE=audit_db PGUSER=audit_verifier \
python3 scripts/verify-chain.py
```

It streams a single consistent SELECT, verifies ordering, predecessors and every content hash, and prints a head checkpoint. Preserve checkpoints in an independent trusted system. `--expected-head <hash>` compares an immutable backup/restored snapshot with its trusted head and also detects removal of the tail. An owner/superuser can bypass database triggers and rewrite an entire unanchored chain; external checkpoints and separate restricted migration credentials provide the corresponding control. Back up history, inbox, schema and checkpoints together, and run verification after restoration. The service does not claim cryptographic proof against a compromised producer or database administrator.

## Verification

`JAVA_HOME=/path/to/java21 ./mvnw verify` uses PostgreSQL 16 Testcontainers by default. Without Docker, create an isolated database whose name contains `test` and run:

```sh
AUDIT_TEST_DATABASE_URL=jdbc:postgresql://localhost:55441/audit_service_test \
AUDIT_TEST_DATABASE_USERNAME=audit_test AUDIT_TEST_DATABASE_PASSWORD= \
JAVA_HOME=/path/to/java21 ./mvnw verify
```

Tests exercise actual Flyway/PostgreSQL transactions, database immutability, hash consistency, concurrent duplicates, chronological snapshots, role failures, private/expired exports, JWT signature validation, actual HTTP Identity calls, broker acknowledgment order and enabled retry/listener wiring. Audit history is never truncated by test fixtures. RabbitMQ transport is tested through channel doubles; a live broker/IdP smoke test is still required for deployment integration.
