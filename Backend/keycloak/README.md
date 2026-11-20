# Local Keycloak

Keycloak 26.8.2 runs at http://localhost:8180 with realm `investitrack` and a persistent Docker volume.
This configuration uses Keycloak's development mode and is for local development.

From the Backend directory:

```bash
docker compose --env-file keycloak/.env -f keycloak/compose.yaml up -d
python3 keycloak/setup-local.py
```

The `investitrack` Docker network must exist (`docker network create investitrack` for a new installation).
Initial startup compiles Keycloak configuration and may take several minutes. If the setup script reports
that Keycloak is not ready, inspect the logs and rerun it after startup finishes:

```bash
docker compose --env-file keycloak/.env -f keycloak/compose.yaml logs --tail 30 keycloak
```

## Credentials

The admin console is at http://localhost:8180/admin/. Its username is `admin`.
The generated admin password is `KC_BOOTSTRAP_ADMIN_PASSWORD` in the ignored, private `keycloak/.env`.
The realm's local application user is `investitrack-admin`, with password `INVESTITRACK_DEV_PASSWORD`
in that same file. The setup script assigns its realm ADMIN role and sets the Identity bootstrap subject
to the user's actual Keycloak subject. Identity creates its application administrator on an empty database.
Existing application accounts and role assignments are not overwritten.

Admin-console users and application users belong to separate realms. Use `investitrack-admin` when
signing into the application; use `admin` to manage Keycloak itself.

## Clients and token claims

- `investitrack-web`: public authorization-code client with PKCE S256, frontend origin/redirects on
  `http://localhost:3000`. Change the exact frontend URLs if your frontend uses another port.
- `investitrack-dev-cli`: public password-grant client used only for local smoke tests.
- Both clients add audience `research-platform-api` and the multivalued `roles` claim expected by the gateway.
- Realm roles: ADMIN, COORDINATOR, RESEARCHER, EVALUATOR, STUDENT, AUDITOR.
- Identity owns live application roles in its database; changes in Keycloak do not synchronize those
  application assignments. Provision real users through Identity. Proposal additionally requires a mapped
  application UUID in the signed `user_id` claim; configure that mapping when integrating Proposal.

The setup script tests discovery, JWK retrieval, token issuance, issuer, audience and ADMIN claim.
It prints no access tokens or passwords.

## Service networking

The token issuer is always:

```dotenv
OIDC_ISSUER_URI=http://localhost:8180/realms/investitrack
```

The setup script updates both services' `.env` files for host execution and creates ignored `.env.docker`
files for container execution. Inside Docker, the signing key URL must use the shared network:

```dotenv
OIDC_JWK_SET_URI=http://keycloak:8080/realms/investitrack/protocol/openid-connect/certs
```

The public issuer remains unchanged. Identity supports an explicit JWK URI so startup does not attempt
OIDC discovery against its own container's localhost. If JWK URI is omitted, it retains issuer discovery.
Gateway `.env.docker` also points Identity traffic to `http://identity-service:8081`.

Rebuild Identity to include this configuration support before running it:

```bash
docker build -t investitrack-identity:local identity-service

docker run -d --name identity-service --network investitrack \
  --env-file identity-service/.env.docker \
  -p 127.0.0.1:8081:8081 investitrack-identity:local
```

Existing running containers keep their original environment; restart alone does not apply env-file changes.
Recreate the gateway on the `investitrack` network with `--env-file API-gateway/.env.docker` when ready.
Additional domain-service URLs in that file still need their respective container names as services are deployed.
Event publishing is disabled in the existing Identity env file; RabbitMQ is separate from this Keycloak setup.

## Lifecycle

```bash
docker compose --env-file keycloak/.env -f keycloak/compose.yaml stop
docker compose --env-file keycloak/.env -f keycloak/compose.yaml start
```

Data survives container replacement in volume `keycloak_keycloak-data`. Startup import skips an existing
realm; it does not overwrite manual edits. The previous misconfigured container is retained, stopped,
as `keycloak-before-setup`, with its original data intact.
