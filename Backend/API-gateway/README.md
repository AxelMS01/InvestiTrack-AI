# API Gateway

Public entry point for the REST contract in [`../.agents/api-endpoints.md`](../.agents/api-endpoints.md).
Java 21, Spring Boot 3.5 and Spring Cloud Gateway WebFlux. Boot 3.x follows the documented baseline;
Spring Cloud 2025.0 is the compatible release train. This service has no database or domain workflows.

## Run

```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
./mvnw test
./mvnw spring-boot:run
```

The wrapper uses Maven 3.9.11. The first build requires Maven Central access.

Public listener: `8080`. Management listener: `127.0.0.1:9090`.
Configure TLS at the ingress/reverse proxy and keep downstream listeners on a private network.

| Environment variable | Default / meaning |
|---|---|
| `PORT` | `8080` |
| `OIDC_ISSUER_URI` | `http://localhost:8180/realms/investitrack` |
| `OIDC_JWK_SET_URI` | Local realm's `/protocol/openid-connect/certs`; set explicitly with a custom issuer |
| `OIDC_AUDIENCE` | `research-platform-api` |
| `CORS_ALLOWED_ORIGINS` | `http://localhost:3000`; comma-separated exact origins |
| `MAX_REQUEST_BYTES` | `10485760` (10 MiB), including chunked request bodies |
| `RATE_LIMIT_REQUESTS` | `120` requests per authenticated subject per window |
| `RATE_LIMIT_WINDOW_SECONDS` | `60` |
| `RATE_LIMIT_MAX_SUBJECTS` | `10000` active subjects per instance |
| `MANAGEMENT_PORT` | `9090`; must differ from the public port |
| `MANAGEMENT_ADDRESS` | `127.0.0.1`; change only within a trusted monitoring network |

| Downstream variable | Default URL |
|---|---|
| `IDENTITY_SERVICE_URL` | `http://localhost:8081` |
| `PROPOSAL_SERVICE_URL` | `http://localhost:8082` |
| `PROJECT_SERVICE_URL` | `http://localhost:8083` |
| `DOCUMENT_SERVICE_URL` | `http://localhost:8084` |
| `REPORTING_SERVICE_URL` | `http://localhost:8085` |
| `AI_SERVICE_URL` | `http://localhost:8086` |
| `AUDIT_SERVICE_URL` | `http://localhost:8087` |
| `NOTIFICATION_SERVICE_URL` | `http://localhost:8088` |

Service URLs are HTTP(S) origins without a path prefix. Public paths and queries are preserved.
The existing Identity and Proposal configurations both use `8081` and different OIDC settings.
Start Proposal on `8082` (e.g. `--server.port=8082`) or override its gateway URL.
Align issuer/JWK settings and accepted audience across the gateway and services. The gateway forwards
the original signed bearer token; configure domain services to accept `research-platform-api`, or
introduce delegated audience-limited token exchange when the identity provider supports it.
Unavailable or unimplemented downstream services return `503` through the gateway.

## Routing and security

[`src/main/resources/routes.tsv`](src/main/resources/routes.tsv) explicitly lists all 144 documented
public method/path pairs. [`openapi.json`](openapi.json) is a distributable OpenAPI description.
Unknown paths/methods are not forwarded. `/internal/**`, `/actuator/**`, and password/token issuance
paths are unavailable on the public listener.

Ownership overrides are deliberate:

- `/me/projects` goes to Project; `/me/proposals` and `/me/evaluation-assignments` go to Proposal.
- `/me/notification-preferences` goes to Notification.
- `/projects/{projectId}/deadlines` goes to Reporting.
- Reference research areas, document types and publication types go to their domain owners.
- The cross-domain reference status display catalog goes to Reporting; that owner must implement it.

JWTs require a valid signature, issuer, audience, subject and expiration; expiration/not-before use
Spring Security's default clock tolerance. Institutional roles come from the signed `roles` claim;
OAuth scopes remain available as `SCOPE_` authorities. Coarse restrictions cover administration,
evaluator operations, proposal/project creation, dashboards and audit access. `AUDITOR` is accepted
for the contract's authorized-auditor endpoints. Resource ownership, current account state,
project membership, AI entitlement, transition rules and live permissions remain domain-service checks.

The bearer token, trace context, concurrency/idempotency headers, response statuses and streaming
responses pass through. Client identity assertions and forwarded-address headers are removed.
`X-Correlation-ID` is accepted only as a single 1–100 character alphanumeric/`._:-` value; otherwise
a UUID is generated. The ID accompanies downstream requests and all gateway responses.
Gateway failures use RFC 9457 Problem Details and hide dependency details.

Rate limiting uses a bounded in-memory fixed window per signed token subject. It is intended for
the documented single-instance MVP: counters reset at restart, and replicas have independent limits.
Use a shared limiter before scaling when a cluster-wide budget is required. Expired entries are evicted;
a full active-subject map rejects new subjects with `429` and `Retry-After` until capacity returns.
Unauthenticated traffic is rejected before this limiter; ingress should manage connection-level traffic.
Request bodies are buffered only up to the configured limit to reject oversized chunks before forwarding.
Use the documented signed-storage upload flow for larger documents. Responses, including AI SSE,
are streamed. Connection timeout is 1 second; response timeout is 30 seconds (120 seconds for AI).
No application retry filter is configured, so gateway workflows never replay mutations.

Only `/actuator/health/liveness` is anonymous on the management listener. Other exposed actuator
endpoints (`health`, `info`, `prometheus`) require a signed token with the configured issuer/audience
and `monitoring` scope. Liveness does not depend on downstream services.

## Verification

`./mvnw verify` runs HTTP integration tests with a local RSA/JWK provider and eight mock downstream
services, exercising every public route, token validation, roles, CORS, header/body forwarding,
size limits, management isolation, SSE and upstream failures. A clock-controlled unit test verifies
rate-limit exhaustion, subject isolation, capacity bounds and recovery. Live domain integrations
require the separately configured services and institutional identity provider.

## Container

```bash
docker build -t investitrack-api-gateway .
docker run --rm -p 8080:8080 --env-file .env investitrack-api-gateway
```

Set downstream service URLs and OIDC URLs to addresses reachable from the container. Management
is bound to container loopback by default; expose it only through your trusted monitoring setup.
