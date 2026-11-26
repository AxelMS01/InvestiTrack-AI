"""Provision the local Identity accounts through the public Identity API."""

import json
import os
import time
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode
from urllib.request import Request, urlopen

IDENTITY = "http://identity-service:8081/api/v1"
TOKEN = "http://keycloak:8080/realms/investitrack/protocol/openid-connect/token"


def request(url, data=None, token=None, key=None):
    headers = {"Accept": "application/json"}
    if token:
        headers["Authorization"] = "Bearer " + token
    if key:
        headers["Idempotency-Key"] = key
    if data is not None:
        headers["Content-Type"] = "application/json" if token else "application/x-www-form-urlencoded"
        data = (json.dumps(data) if token else urlencode(data)).encode()
    with urlopen(Request(url, data=data, headers=headers), timeout=8) as response:
        return json.load(response)


def admin_token():
    for attempt in range(30):
        stage = "Keycloak"
        try:
            token = request(TOKEN, {
                "grant_type": "password", "client_id": "investitrack-dev-cli",
                "username": "admin", "password": os.environ["LOCAL_ADMIN_PASSWORD"],
            })["access_token"]
            stage = "Identity /me"
            request(IDENTITY + "/me", token=token)
            return token
        except (URLError, TimeoutError) as error:
            status = getattr(error, "code", "sin respuesta")
            if attempt == 0 or status in (400, 403):
                detail = (json.load(error).get("code", "sin código")
                          if isinstance(error, HTTPError) and "json" in error.headers.get("Content-Type", "")
                          else "sin código")
                print(f"Aprovisionamiento: {stage}, HTTP {status}, {detail}", flush=True)
            if status in (400, 403):
                raise SystemExit("Revisa el realm integrado y el bootstrap de Identity.") from None
            if attempt == 29:
                raise SystemExit(f"No se pudo inicializar {stage} (HTTP {status}).") from None
            time.sleep(2)


def main():
    token = admin_token()
    realm = json.loads(Path(__file__).with_name("investitrack-realm.json").read_text())
    for user in realm["users"]:
        if user["username"] == "admin":
            continue
        query = urlencode({"q": user["email"]})
        current = request(IDENTITY + "/users?" + query, token=token)["items"]
        if any(account["email"] == user["email"] for account in current):
            continue
        request(IDENTITY + "/users", {
            "identitySubject": user["id"], "email": user["email"],
            "displayName": user["firstName"] + " Local", "status": "ACTIVE",
            "roles": user["realmRoles"],
        }, token, "local-seed-" + user["id"])
    print("Cuentas locales aprovisionadas en Identity.")


if __name__ == "__main__":
    try:
        main()
    except HTTPError as error:
        raise SystemExit(f"Falló el aprovisionamiento (HTTP {error.code}).") from None
