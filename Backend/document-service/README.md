# Document Service

Java 21 / Spring Boot 4.1.2, matching the implemented sibling services. Owns document metadata in PostgreSQL and document bodies in a private S3-compatible bucket. The architecture's Java 21 baseline is retained; Boot 4 and JDBC follow this repository rather than introducing a different Boot/JPA stack.

## Run

Copy `.env.example` to `.env`, fill credentials and export its variables in your shell. Spring does not automatically load `.env` files. Use Java 21, then `./mvnw spring-boot:run`. The default port is 8084. Flyway creates only this service's tables in its dedicated database.

For Docker, copy `.env.example` to `.env.docker`, set `DATABASE_URL=jdbc:postgresql://document-postgres:5432/document_db`, use network hostnames for Identity, Project, RabbitMQ and S3, and set the browser-reachable `S3_PUBLIC_ENDPOINT`. Run `docker compose --env-file .env.docker up --build` on the shared `investitrack` network. S3, the IdP, Identity, Project and RabbitMQ are shared infrastructure and are not created by this compose file.

Provision `S3_BUCKET` as private before starting uploads. Use a dedicated service credential for GetObject/PutObject in this bucket. Configure lifecycle expiration for `uploads/` and `extractions/` (temporary inputs), with at least one day of retention. Preserve `documents/` and `text/`; soft deletion retains content indefinitely for restoration. Bucket public access must remain disabled. Configure browser CORS for the trusted frontend origin, PUT/GET and Content-Type. Signed URLs expire after 15 minutes for uploads and 60 seconds for downloads; the public endpoint must resolve to the same server as the internal endpoint and preserve the signed Host header.

Enable `MESSAGING_ENABLED=true` with RabbitMQ credentials to publish committed outbox events to the durable topic exchange `invesctictrack.domain`, routing key `document.<EventType>.v1`. Publishing requires broker confirmation and no returned message; failed events remain pending with bounded exponential backoff. With messaging disabled, events remain durable in PostgreSQL until it is enabled. The scanner/extractor/indexer must bind durable queues before uploads are processed.

## API and identity

`GET /v3/api-docs` serves the checked-in OpenAPI contract (requires `monitoring` scope). Regenerate with `python3 scripts/generate-openapi.py`. Public gateway routes already exist; examples are in `http/document.http`. Liveness is public; readiness and metrics require monitoring credentials.

User JWTs require the configured issuer, expiration, subject and public audience. `/api/v1/me` in Identity resolves the application UUID and current active account; an optional signed `user_id` must match that UUID. Authorization is checked against current owner data through Identity on every operation, including download and AI source validation. No arbitrary user headers, cross-database reads, or cached membership grants are used. A dependency failure returns 503. Restricted documents follow Identity's current deny policy. Lists return only available documents in the caller's authorized scope, with bounded scanning and query/user-bound cursors. Upload state and processing metadata allow the uploader to track quarantine before availability.

Set `IDENTITY_SERVICE_TOKEN` to a **document-service workload** token for Identity's audience, with `identity:status`, `identity:check`, `identity:filter`, and `identity:any-subject` scopes. Identity must allowlist document-service. Set `PROJECT_SERVICE_TOKEN` to a document-service workload token for Project's audience with `project:association`; Project must allowlist document-service.

Internal endpoints are not routed by the gateway. They require `document-service` audience, an allowlisted signed `client_id`, the matching client below, and a route-specific scope:

| Client | Scope | Operations |
|---|---|---|
| identity-service | document:context | GET access-context; reads local facts only, avoiding recursion into Identity |
| malware-scanner | document:scan, document:process | POST scan-results; GET processing-jobs/{jobId}/content |
| text-extractor | document:extract, document:process | POST extractions; GET processing-jobs/{jobId}/content |
| ai-service | document:index | GET indexing-content; POST indexing-status |
| ai-service | document:source | POST source-validation with signed delegated user |
| project-execution-service (also project-service) | document:reference | POST reference-validation; PUT/DELETE holds/{referenceId} |

All paths above are below `/internal/v1/documents/{documentId}`. Workload tokens have `sub=client_id`. Source validation, reference validation and hold creation require a signed delegated token with `client_id` identifying the caller, `user_id` carrying the application UUID and a user subject distinct from the workload identity. Source `subjectId` must match `user_id`; current account and resource access are rechecked. Project defaults to the `project-execution-service` token-exchange client; `project-service` is also supported. Hold release uses the same Project client identity as hold creation. Configure the IdP's token exchange to issue these claims and audience. Credentials in configuration are externally supplied short-lived tokens and must be refreshed by the deployment.

## Upload and processing pipeline

1. Initialize with filename, exact MIME type, positive size (default maximum 50 MiB), lowercase SHA-256, document type, classification and a context containing exactly one project or proposal. Activity/milestone ownership is validated through Project. New versions retain their document's context/type/classification. `Idempotency-Key` replays initialization or association creation and rejects contradictory bodies; records are retained. Mutable document operations require a quoted aggregate `If-Match` ETag (428 missing, 412 stale). Aggregate `version`, binary `documentVersion` and `accessVersion` are distinct.
2. PUT bytes directly to the returned signed URL. Completion rechecks authorization, reads a bounded object, verifies actual size/hash and file signature, then writes a separate immutable content key and creates metadata, scan job and outbox event in one database transaction. Reusing the upload URL cannot change committed bytes. PDF, DOCX, XLSX, PPTX, PNG, JPEG and GIF are accepted; Office ZIP inspection is bounded and rejects macros, traversal and expansion bombs. The full malware decision belongs to the scanner; signature detection alone never releases a file.
3. A separately deployed malware worker consumes `DocumentUploadCompleted` or `DocumentVersionCreated`. Its `jobId` identifies the job. The processing-content endpoint supplies a short-lived URL to the exact committed bytes plus version/hash. The worker reports CLEAN, INFECTED or ERROR. Scan callbacks require `X-Result-Signature`, the lowercase HMAC-SHA256 hex digest of the **exact UTF-8 HTTP body** using `SCAN_CALLBACK_SECRET`, in addition to the scanner's JWT. Only CLEAN makes content downloadable. INFECTED is quarantined; ERROR stays inaccessible. The service does not ship a malware engine or an automatic success stub.
4. `DocumentScanPassed` includes `extractionJobId`. The extractor obtains its input using that job and writes a JSON segment array to the managed `contentRef` (`extractions/{jobId}.json`) in the private bucket. Restrict worker storage credentials to that prefix. Segments are `{position:0,page?:1,section?:"...",text:"..."}`, ordered with zero-based contiguous positions; each text is at most 16 KiB UTF-8 and the complete object is at most 20 MiB. A successful callback supplies its byte-level SHA-256 and segment count. The service verifies these and freezes the text at a separate private key. Formats without extraction remain downloadable after a clean scan but outside RAG.
5. `DocumentTextExtracted` identifies the extraction and access version. The AI worker pages through indexing-content (maximum 100 segments and under 1 MiB per page), builds its index, and reports indexing-status. Only current, clean, available versions can be indexed. Callbacks bind job/version/hash, deduplicate equal results and return 409 for contradictory results. A late callback cannot resurrect deleted content. Failed jobs remain recorded for operator investigation; automatic processing retries require a new worker pipeline/run design and are not silently accepted as successful.
6. Every AI answer revalidates the signed user's active account, current version, exact accessVersion, clean scan, successful extraction/index and current Identity permissions. Deleted, replaced, reclassified or revoked sources are denied. Current project membership is checked synchronously, so Document does not need a stale authorization projection or membership event cache.

Associations are limited to the primary project/proposal so an additional link cannot broaden access. Project's durable holds protect final-report/evidence references against deletion, classification change, association removal and binary replacement until released by the same service. Holds survive restarts. Metadata renaming remains permitted. Preview and thumbnail generation explicitly report unsupported.

Temporary/object writes cannot participate in a database transaction. A failed transaction may leave an unreferenced immutable object; reconcile such objects against committed version/extraction rows before removing them. Download URLs already issued remain valid for their short TTL. Database, private bucket and required credentials/configuration must be backed up together.

## Verification

`JAVA_HOME=/path/to/java21 ./mvnw test` uses PostgreSQL 16 through Testcontainers by default. Without Docker, create an isolated database whose name contains `test`, then run:

```sh
DOCUMENT_TEST_DATABASE_URL=jdbc:postgresql://localhost:55440/document_service_test \
DOCUMENT_TEST_DATABASE_USERNAME=document_test \
DOCUMENT_TEST_DATABASE_PASSWORD= \
JAVA_HOME=/path/to/java21 ./mvnw verify
```

Tests exercise real Flyway/PostgreSQL transactions and HTTP security, fake the remote owner and object-storage boundaries for workflow cases, and separately test real HTTP owner clients and MinIO signing/bounded reads against a local HTTP server. A live MinIO/RabbitMQ/IdP deployment and malware/extraction workers are needed for an end-to-end smoke test.
