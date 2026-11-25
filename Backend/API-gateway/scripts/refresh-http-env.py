#!/usr/bin/env python3
"""Provision local HTTP-client accounts and refresh private access tokens. Prints no secrets."""
import json
import secrets
import sys
import subprocess
import time
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode
from urllib.request import Request, urlopen

ROOT = Path(__file__).resolve().parents[2]
PRIVATE = ROOT / "API-gateway/http/http-client.private.env.json"
KEYCLOAK = "http://localhost:8180"
GATEWAY = "http://localhost:8080"
REALM = "/admin/realms/investitrack"


def call(base, path, method="GET", data=None, token=None, form=False):
    headers = {}
    if token:
        headers["Authorization"] = "Bearer " + token
    if data is not None:
        headers["Content-Type"] = "application/x-www-form-urlencoded" if form else "application/json"
        data = (urlencode(data) if form else json.dumps(data)).encode()
    with urlopen(Request(base + path, data=data, headers=headers, method=method), timeout=20) as response:
        body = response.read()
        return json.loads(body) if body else None


def save(environment):
    # Restrict permissions before writing credentials, including when refreshing an existing file.
    PRIVATE.touch(mode=0o600, exist_ok=True)
    PRIVATE.chmod(0o600)
    PRIVATE.write_text(json.dumps(environment, indent=2) + "\n")


def dependencies(local, master):
    """Set up real, narrowly scoped workload tokens for the local test workflow."""
    if "--setup" not in sys.argv and not local.get("httpWorkloadClients"):
        return
    if "--setup" in sys.argv:
        subprocess.run(["docker", "exec", "-i", "proposal-service-proposal-postgres-1",
                        "psql", "-v", "ON_ERROR_STOP=1", "-U", "proposal", "-d", "proposal_db"],
                       input=(ROOT / "proposal-service/http/seed-test-catalogs.sql").read_bytes(),
                       stdout=subprocess.DEVNULL, check=True)
    clients = local.setdefault("httpWorkloadClients", {})
    if "--setup" not in sys.argv and clients and local.get("httpWorkloadExpiresAt", 0) > time.time() + 600:
        return
    tokens = {}
    for workload in ("proposal-service", "project-execution-service"):
        name = workload
        found = call(KEYCLOAK, REALM + "/clients?" + urlencode({"clientId": name}), token=master)
        if not found:
            secret = secrets.token_urlsafe(32)
            mappers = []
            claims = {"sub": workload, "client_id": workload,
                      "scope": "identity:status" + (" proposal:project-integration" if workload == "project-execution-service" else "")}
            for claim, value in claims.items():
                mappers.append({"name": "http-test-" + claim, "protocol": "openid-connect",
                                "protocolMapper": "oidc-hardcoded-claim-mapper", "config": {
                                    "claim.name": claim, "claim.value": value, "jsonType.label": "String",
                                    "access.token.claim": "true", "id.token.claim": "false"}})
            mappers.append({"name": "http-test-audience", "protocol": "openid-connect",
                            "protocolMapper": "oidc-audience-mapper", "config": {
                                "included.custom.audience": "identity-service", "access.token.claim": "true"}})
            if workload == "project-execution-service":
                mappers.append({"name": "http-test-proposal-audience", "protocol": "openid-connect",
                                "protocolMapper": "oidc-audience-mapper", "config": {
                                    "included.custom.audience": "proposal-service", "access.token.claim": "true"}})
            call(KEYCLOAK, REALM + "/clients", "POST", {"clientId": name, "enabled": True,
                 "protocol": "openid-connect", "publicClient": False, "secret": secret,
                 "serviceAccountsEnabled": True, "standardFlowEnabled": False,
                 "directAccessGrantsEnabled": False, "attributes": {"access.token.lifespan": "3600"},
                 "protocolMappers": mappers}, master)
            clients[workload] = secret
            found = call(KEYCLOAK, REALM + "/clients?" + urlencode({"clientId": name}), token=master)
        if workload not in clients:
            raise SystemExit("Existing workload client is not owned by this environment: " + name)
        scopes = ["identity:status"] + (["proposal:project-integration"] if workload == "project-execution-service" else [])
        available = {scope["name"]: scope for scope in call(KEYCLOAK, REALM + "/client-scopes", token=master)}
        for scope in scopes:
            if scope not in available:
                call(KEYCLOAK, REALM + "/client-scopes", "POST", {
                    "name": scope, "protocol": "openid-connect",
                    "attributes": {"include.in.token.scope": "true", "display.on.consent.screen": "false"}}, master)
                available = {item["name"]: item for item in call(KEYCLOAK, REALM + "/client-scopes", token=master)}
            call(KEYCLOAK, REALM + "/clients/" + found[0]["id"] + "/default-client-scopes/" + available[scope]["id"], "PUT", token=master)
        result = call(KEYCLOAK, "/realms/investitrack/protocol/openid-connect/token", "POST", {
            "grant_type": "client_credentials", "client_id": name, "client_secret": clients[workload]}, form=True)
        tokens[workload] = result["access_token"]
    for directory, changes in {
        "proposal-service": {"IDENTITY_SERVICE_TOKEN": tokens["proposal-service"]},
        "project-service": {"IDENTITY_SERVICE_TOKEN": tokens["project-execution-service"],
                            "PROPOSAL_SERVICE_TOKEN": tokens["project-execution-service"]},
    }.items():
        path = ROOT / directory / ".env.docker"
        lines = path.read_text().splitlines()
        found = set()
        for i, line in enumerate(lines):
            key = line.split("=", 1)[0]
            if key in changes:
                lines[i] = key + "=" + changes[key]
                found.add(key)
        lines.extend(key + "=" + value for key, value in changes.items() if key not in found)
        path.chmod(0o600)
        path.write_text("\n".join(lines) + "\n")
        subprocess.run(["docker", "compose", "--env-file", ".env.docker", "up", "-d", "--no-build"],
                       cwd=path.parent, check=True, stdout=subprocess.DEVNULL)
    local["httpWorkloadExpiresAt"] = int(time.time()) + 3500
    for port in (8082, 8083):
        for attempt in range(60):
            try:
                if call("http://localhost:" + str(port), "/actuator/health/liveness")["status"] == "UP":
                    break
            except (OSError, ValueError):
                pass
            time.sleep(1)
        else:
            raise SystemExit("Service did not become ready on port " + str(port))
    print("Local test catalogs and workload credentials are ready.")


def main():
    credentials = dict(line.split("=", 1) for line in (ROOT / "keycloak/.env").read_text().splitlines()
                       if "=" in line and not line.lstrip().startswith("#"))
    environment = json.loads(PRIVATE.read_text()) if PRIVATE.exists() else {"local": {}}
    local = environment.setdefault("local", {})
    local.setdefault("httpTestPassword", secrets.token_urlsafe(32))
    local.setdefault("httpTestAccountSuffix", secrets.token_hex(3))
    subjects = local.setdefault("httpTestSubjects", {})
    save(environment)
    master = call(KEYCLOAK, "/realms/master/protocol/openid-connect/token", "POST", {
        "client_id": "admin-cli", "grant_type": "password",
        "username": credentials["KC_BOOTSTRAP_ADMIN_USERNAME"],
        "password": credentials["KC_BOOTSTRAP_ADMIN_PASSWORD"]}, form=True)["access_token"]
    try:
        dependencies(local, master)
    finally:
        save(environment)

    def login(username, password):
        return call(KEYCLOAK, "/realms/investitrack/protocol/openid-connect/token", "POST", {
            "client_id": "investitrack-dev-cli", "grant_type": "password",
            "username": username, "password": password}, form=True)

    admin = login(credentials["INVESTITRACK_DEV_USERNAME"], credentials["INVESTITRACK_DEV_PASSWORD"])
    local["adminToken"] = admin["access_token"]
    lifetimes = [admin["expires_in"]]
    for account, role, id_key in [
        ("researcher", "RESEARCHER", "researcherId"),
        ("coordinator", "COORDINATOR", "coordinatorId"),
        ("evaluator", "EVALUATOR", "evaluatorUserId"),
        ("collaborator", "RESEARCHER", "collaboratorId"),
        ("member", "RESEARCHER", "memberUserId"),
        ("disposable", "RESEARCHER", "newIdentitySubject"),
    ]:
        username = "http-test-" + account + "-" + local["httpTestAccountSuffix"]
        users = call(KEYCLOAK, REALM + "/users?" + urlencode({"username": username, "exact": "true"}), token=master)
        if not users:
            call(KEYCLOAK, REALM + "/users", "POST", {
                "username": username, "enabled": True, "emailVerified": True,
                "email": username + "@investitrack.local", "firstName": "HTTP Test", "lastName": account.title(),
                "attributes": {"investitrack-http-client": ["true"]}, "requiredActions": [],
                "credentials": [{"type": "password", "value": local["httpTestPassword"], "temporary": False}],
            }, master)
            users = call(KEYCLOAK, REALM + "/users?" + urlencode({"username": username, "exact": "true"}), token=master)
            subjects[account] = users[0]["id"]
            save(environment)
        user = call(KEYCLOAK, REALM + "/users/" + users[0]["id"], token=master)
        if subjects.get(account) != user["id"]:
            raise SystemExit("Existing account " + username + " is not managed by this script; left unchanged.")
        role_data = call(KEYCLOAK, REALM + "/roles/" + role, token=master)
        call(KEYCLOAK, REALM + "/users/" + user["id"] + "/role-mappings/realm", "POST", [role_data], master)
        if account == "disposable":
            # Leave this subject unprovisioned for the optional POST /users request.
            local[id_key] = user["id"]
            save(environment)
            continue
        result = login(username, local["httpTestPassword"])
        token = result["access_token"]
        lifetimes.append(result["expires_in"])
        try:
            profile = call(GATEWAY, "/api/v1/me", token=token)
        except HTTPError as error:
            problem = json.loads(error.read())
            if error.code != 403 or problem.get("code") != "USER_MAPPING_REQUIRED":
                raise
            profile = call(GATEWAY, "/api/v1/users", "POST", {
                "identitySubject": user["id"], "email": user["email"],
                "displayName": "HTTP Test " + account.title(), "roles": [role],
            }, local["adminToken"])
        if role not in profile["roles"] or profile["status"] != "ACTIVE":
            raise SystemExit("Identity profile for " + username + " needs active role " + role + "; left unchanged.")
        local[id_key] = profile["id"]
        if account in ("researcher", "coordinator", "evaluator"):
            local[account + "Token"] = token
        save(environment)
        print("Ready:", username, "(" + role + ")")

    criteria = call(GATEWAY, "/api/v1/evaluation-criteria?limit=100", token=local["evaluatorToken"])
    active = [item for item in criteria["items"] if item.get("active", True)]
    local["criterionCode"] = active[0]["code"] if active else ""
    local["evaluationCriteria"] = [{"criterionCode": item["code"], "score": item["minimumScore"],
                                    "comment": "HTTP Client test"} for item in active]
    local["evaluationCriteria"] = json.dumps(local["evaluationCriteria"])
    areas = call(GATEWAY, "/api/v1/reference/research-areas", token=local["researcherToken"])["items"]
    local["researchAreaCode"] = areas[0]["code"] if areas else ""
    local["roleCode"] = "RESEARCHER"
    local.setdefault("documentId", "")
    save(environment)
    print("Saved private environment; access tokens expire in about", min(lifetimes), "seconds.")
    print("Select local in IntelliJ. Run this script again to refresh tokens.")
    if not active:
        print("criterionCode is empty: seed the Proposal evaluation catalog before the evaluation workflow.")
    if not local["documentId"]:
        print("documentId is empty: an available Project document is required for evidence/report requests.")


if __name__ == "__main__":
    try:
        main()
    except HTTPError as error:
        raise SystemExit("Setup request failed: HTTP " + str(error.code) + " at " + error.url.split("?")[0] + "; no credentials printed.")
    except URLError:
        raise SystemExit("Cannot connect to local Keycloak or gateway. Start the containers and retry.")
