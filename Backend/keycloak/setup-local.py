#!/usr/bin/env python3
"""Configure the local development user after `docker compose up -d`. Never prints credentials."""
import json
import time
from pathlib import Path
from urllib.error import HTTPError, URLError
from http.client import RemoteDisconnected
from urllib.parse import urlencode
from urllib.request import Request, urlopen

ROOT = Path(__file__).resolve().parent.parent
BASE = "http://localhost:8180"
ISSUER = BASE + "/realms/investitrack"


def env_values(path):
    return dict(line.split("=", 1) for line in path.read_text().splitlines()
                if "=" in line and not line.lstrip().startswith("#"))


def update_env(path, changes):
    lines = path.read_text().splitlines() if path.exists() else []
    found = set()
    for index, line in enumerate(lines):
        key = line.split("=", 1)[0]
        if key in changes:
            lines[index] = key + "=" + changes[key]
            found.add(key)
    lines.extend(key + "=" + value for key, value in changes.items() if key not in found)
    path.write_text("\n".join(lines) + "\n")
    path.chmod(0o600)


def request(path, method="GET", data=None, token=None, form=False):
    headers = {}
    if token:
        headers["Authorization"] = "Bearer " + token
    if data is not None:
        headers["Content-Type"] = "application/x-www-form-urlencoded" if form else "application/json"
        data = (urlencode(data) if form else json.dumps(data)).encode()
    with urlopen(Request(BASE + path, data=data, headers=headers, method=method), timeout=5) as response:
        raw = response.read()
        return json.loads(raw) if raw else None


def main():
    credentials = env_values(ROOT / "keycloak/.env")
    identity = env_values(ROOT / "identity-service/.env")
    for attempt in range(15):
        try:
            discovery = request("/realms/investitrack/.well-known/openid-configuration")
            break
        except (HTTPError, URLError, TimeoutError, RemoteDisconnected):
            if attempt == 14:
                raise SystemExit("Keycloak is not ready. Check docker compose logs and run setup-local.py again.")
            time.sleep(2)
    assert discovery["issuer"] == ISSUER
    admin = request("/realms/master/protocol/openid-connect/token", "POST", {
        "client_id": "admin-cli", "grant_type": "password",
        "username": credentials["KC_BOOTSTRAP_ADMIN_USERNAME"],
        "password": credentials["KC_BOOTSTRAP_ADMIN_PASSWORD"]}, form=True)["access_token"]
    username = credentials["INVESTITRACK_DEV_USERNAME"]
    email = identity.get("IDENTITY_BOOTSTRAP_EMAIL", "admin@investitrack.local")
    if "@" not in email or email.endswith("@example.com"):
        email = "admin@investitrack.local"
    users = request("/admin/realms/investitrack/users?" + urlencode({"username": username, "exact": "true"}), token=admin)
    if not users:
        request("/admin/realms/investitrack/users", "POST", {
            "username": username, "enabled": True, "email": email, "emailVerified": True,
            "firstName": "InvestiTrack", "lastName": "Administrator", "requiredActions": [],
            "credentials": [{"type": "password", "value": credentials["INVESTITRACK_DEV_PASSWORD"], "temporary": False}]}, token=admin)
        users = request("/admin/realms/investitrack/users?" + urlencode({"username": username, "exact": "true"}), token=admin)
    user = users[0]
    role = request("/admin/realms/investitrack/roles/ADMIN", token=admin)
    request("/admin/realms/investitrack/users/" + user["id"] + "/role-mappings/realm", "POST", [role], admin)
    result = request("/realms/investitrack/protocol/openid-connect/token", "POST", {
        "client_id": "investitrack-dev-cli", "grant_type": "password", "username": username,
        "password": credentials["INVESTITRACK_DEV_PASSWORD"]}, form=True)
    # Smoke-test claims here; the Java security test verifies signature validation against JWKs.
    import base64
    claims = json.loads(base64.urlsafe_b64decode(result["access_token"].split(".")[1] + "=="))
    assert claims["iss"] == ISSUER and "ADMIN" in claims["roles"]
    audiences = claims["aud"] if isinstance(claims["aud"], list) else [claims["aud"]]
    assert "research-platform-api" in audiences
    assert request("/realms/investitrack/protocol/openid-connect/certs")["keys"]
    for name in ["identity-service", "API-gateway"]:
        path = ROOT / name / ".env"
        changes = {"OIDC_ISSUER_URI": ISSUER,
                   "OIDC_JWK_SET_URI": ISSUER + "/protocol/openid-connect/certs"}
        if name == "identity-service":
            changes.update(IDENTITY_BOOTSTRAP_SUBJECT=user["id"], IDENTITY_BOOTSTRAP_EMAIL=user["email"])
        update_env(path, changes)
        docker_path = ROOT / name / ".env.docker"
        docker_path.write_text(path.read_text())
        changes = {"OIDC_JWK_SET_URI": "http://keycloak:8080/realms/investitrack/protocol/openid-connect/certs"}
        if name == "API-gateway":
            changes["IDENTITY_SERVICE_URL"] = "http://identity-service:8081"
        update_env(docker_path, changes)
    print("Realm ready: investitrack")
    print("Issuer: " + ISSUER)
    print("Development user: " + username)
    print("Verified token issuer, API audience, ADMIN role, and JWK endpoint.")
    print("Updated host .env and container .env.docker files. Credentials remain in keycloak/.env.")


if __name__ == "__main__":
    try:
        main()
    except HTTPError as error:
        raise SystemExit(f"Keycloak request failed: HTTP {error.code}. Inspect Keycloak logs; credentials were not printed.")
