"""Run HTTP, persistence and RabbitMQ checks against the local Compose project."""

import argparse
import json
import subprocess
from datetime import date, timedelta
from pathlib import Path
from time import monotonic, sleep
from urllib.error import HTTPError
from urllib.parse import urlencode
from urllib.request import Request, urlopen
from uuid import uuid4

ROOT = Path(__file__).resolve().parent.parent
BASE = "http://localhost:8080"
API = BASE + "/api/v1"
TOKEN = BASE + "/realms/investitrack/protocol/openid-connect/token"

PUBLISH = """
import base64, json, sys
from urllib.request import Request, urlopen
message = json.load(sys.stdin)
authorization = base64.b64encode(message.pop("credentials").encode()).decode()
url = "http://rabbitmq:15672/api/exchanges/%2F/invesctictrack.domain/publish"
request = Request(url, json.dumps(message).encode(), {
    "Content-Type": "application/json", "Authorization": "Basic " + authorization
})
with urlopen(request, timeout=10) as response:
    assert json.load(response)["routed"], "The duplicate was not routed"
"""


def credentials():
    return dict(
        line.split("=", 1) for line in (ROOT / ".env").read_text(encoding="utf-8").splitlines()
        if line and not line.startswith("#") and "=" in line
    )


def http(url, method="GET", body=None, token=None, version=None, expected=(200,), form=False):
    headers = {"Accept": "application/json"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    if version is not None:
        headers["If-Match"] = f'"{version}"'
    if method == "POST" and not form:
        headers["Idempotency-Key"] = str(uuid4())
    if body is not None:
        headers["Content-Type"] = "application/x-www-form-urlencoded" if form else "application/json"
        body = (urlencode(body) if form else json.dumps(body)).encode()
    try:
        response = urlopen(Request(url, body, headers, method=method), timeout=20)
    except HTTPError as error:
        response = error
    with response:
        status = response.code
        payload = response.read()
        content_type = response.headers.get("Content-Type", "")
    parsed = json.loads(payload) if payload and "json" in content_type else None
    assert status in expected, (
        f"{method} {url}: HTTP {status}, esperaba {expected}; "
        f"código: {(parsed or {}).get('code', 'sin código') if isinstance(parsed, dict) else 'sin código'}"
    )
    print(f"{method} {url.removeprefix(BASE)} {status}", flush=True)
    return parsed


def login(name, password_key, secrets):
    return http(TOKEN, "POST", {
        "client_id": "investitrack-dev-cli", "grant_type": "password",
        "username": name, "password": secrets[password_key],
    }, form=True)["access_token"]


def wait_for(operation, message, seconds=100):
    deadline = monotonic() + seconds
    while monotonic() < deadline:
        result = operation()
        if result:
            return result
        sleep(2)
    raise AssertionError(message)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--project", help="Nombre alternativo del proyecto Docker Compose")
    args = parser.parse_args()
    compose = ["docker", "compose"]
    if args.project:
        compose.extend(["-p", args.project])

    def docker(*arguments, input_text=None):
        return subprocess.run(compose + list(arguments), input=input_text, text=True,
                              capture_output=True, check=True, cwd=ROOT).stdout.strip()

    def sql(db, statement):
        return docker("exec", "-T", "postgres", "psql", "-U", "postgres",
                      "-d", db, "-At", "-v", "ON_ERROR_STOP=1", "-c", statement)

    print(docker("ps", "-a"))
    owners = ["identity", "proposal", "project", "document", "reporting", "audit", "keycloak"]
    for db in [owner + "_db" for owner in owners if owner != "keycloak"]:
        count = int(sql(db, "select count(*) from flyway_schema_history where success"))
        assert count > 0
        print(f"{db}: {count} migraciones Flyway aplicadas")
    names = ", ".join(f"('{owner}')" for owner in owners)
    assert sql("postgres", f"with owners(name) as (values {names}) "
               "select bool_and(has_database_privilege(a.name, b.name || '_db', 'CONNECT') = (a.name = b.name)) "
               "from owners a cross join owners b") == "t"
    print("Aislamiento de bases comprobado con privilegios de PostgreSQL.")
    queues = docker("exec", "-T", "rabbitmq", "rabbitmqctl", "-q", "list_queues", "name", "durable", "arguments")
    project_queue = next(line for line in queues.splitlines() if line.startswith("project.domain-events\t"))
    assert project_queue.split("\t")[1] == "true" and "project.dead-letters" in project_queue
    assert any(line.startswith("project.invalid-events\ttrue\t") for line in queues.splitlines())
    bindings = docker("exec", "-T", "rabbitmq", "rabbitmqctl", "-q", "list_bindings",
                      "source_name", "destination_name", "routing_key")
    assert "invesctictrack.domain\tproject.domain-events\tproposal.ProposalApproved.v1" in bindings
    assert "project.dead-letters\tproject.invalid-events\tinvalid" in bindings
    print("Topología RabbitMQ comprobada: exchange, enlace ProposalApproved, cola durable y cola de descarte.")

    secrets = credentials()
    researcher = login("investigador", "LOCAL_RESEARCHER_PASSWORD", secrets)
    coordinator = login("coordinacion", "LOCAL_COORDINATOR_PASSWORD", secrets)
    evaluator = login("evaluador", "LOCAL_EVALUATOR_PASSWORD", secrets)
    observer = login("observador", "LOCAL_OBSERVER_PASSWORD", secrets)
    for token, role in [(researcher, "RESEARCHER"), (coordinator, "COORDINATOR"),
                        (evaluator, "EVALUATOR"), (observer, "RESEARCHER")]:
        profile = http(API + "/me", token=token)
        assert role in profile["roles"]
        if role == "COORDINATOR":
            coordinator_id = profile["id"]
        if role == "EVALUATOR":
            evaluator_id = profile["id"]
    http(API + "/me", expected=(401,))
    http(API + "/ai/conversations", token=researcher, expected=(503,))
    http(API + "/notifications", token=researcher, expected=(503,))

    title = "Integración de propuestas " + str(uuid4())
    area = http(API + "/reference/research-areas", token=researcher)["items"][0]["code"]
    payload = {
        "title": title, "abstract": "Validación local de la trazabilidad de propuestas.",
        "researchProblem": "Cómo medir la trazabilidad del flujo institucional.",
        "objectives": [{"type": "GENERAL", "description": "Medir el flujo institucional."}],
        "methodology": "Pruebas repetibles con una muestra ficticia.",
        "expectedResults": "Indicadores de trazabilidad reproducibles.",
        "researchAreaCode": area, "estimatedDurationMonths": 2,
    }
    http(API + "/proposals", "POST", payload, evaluator, expected=(403,))
    proposal = http(API + "/proposals", "POST", payload, researcher, expected=(201,))
    proposal_id = proposal["id"]
    path = API + "/proposals/" + proposal_id
    proposal = http(path + "/transitions", "POST", {"targetStatus": "SUBMITTED"}, researcher, proposal["version"])
    assert proposal["status"] == "SUBMITTED"
    http(path + "/evaluator-assignments", "POST", {"evaluatorId": evaluator_id},
         researcher, proposal["version"], expected=(403,))
    http(path + "/evaluator-assignments", "POST", {"evaluatorId": evaluator_id},
         coordinator, proposal["version"], expected=(201,))
    proposal = http(path, token=coordinator)
    proposal = http(path + "/transitions", "POST", {"targetStatus": "UNDER_REVIEW"}, coordinator, proposal["version"])
    assert proposal["status"] == "UNDER_REVIEW"
    criteria = http(API + "/evaluation-criteria", token=evaluator)["items"]
    evaluation = http(path + "/evaluations", "POST", {
        "decision": "APPROVE", "observations": "Cumple el objetivo y la metodología.",
        "criteria": [{"criterionCode": criterion["code"], "score": criterion["maximumScore"]} for criterion in criteria],
    }, evaluator, expected=(201,))
    evaluation = http(path + f"/evaluations/{evaluation['id']}/submission", "POST",
                      token=evaluator, version=evaluation["version"])
    assert evaluation["status"] == "SUBMITTED"
    proposal = http(path, token=coordinator)
    proposal = http(path + "/transitions", "POST", {
        "targetStatus": "APPROVED", "creationPlan": {
            "coordinatorId": coordinator_id, "plannedEndDate": (date.today() + timedelta(days=60)).isoformat(),
        },
    }, coordinator, proposal["version"])
    assert proposal["status"] == "APPROVED"
    event = wait_for(lambda: sql("proposal_db",
        f"select payload::text from outbox_event where aggregate_id='{proposal_id}' "
        "and event_type='ProposalApproved' and published_at is not null"), "Falta la confirmación del outbox")
    event_id = json.loads(event)["eventId"]
    wait_for(lambda: sql("project_db",
        f"select result from processed_event where event_id='{event_id}'") == "SUCCEEDED",
        "Falta el registro de consumo del evento")
    query = urlencode({"q": title})
    found = http(API + "/projects?" + query, token=researcher)["items"]
    assert len(found) == 1 and found[0]["sourceProposalId"] == proposal_id
    project_id = found[0]["id"]
    print(f"ProposalApproved={event_id}, propuesta={proposal_id}, proyecto={project_id}")

    http(API + "/projects/" + project_id, token=observer, expected=(403, 404))
    assert http(API + "/projects?" + query, token=observer)["items"] == []
    http(BASE + f"/internal/v1/proposals/{proposal_id}/project-creation-data", token=researcher, expected=(404,))
    internal_status = docker("exec", "-T", "proposal-service", "bash", "-ec",
                             "exec 3<>/dev/tcp/localhost/8082; "
                             "printf 'GET /internal/v1/proposals/00000000-0000-0000-0000-000000000000/project-creation-data HTTP/1.0\r\nHost: localhost\r\n\r\n' >&3; "
                             "read -r status <&3; printf '%s' \"$status\"")
    assert internal_status.split()[1] == "401", internal_status
    print("Proposal interno sin credenciales: HTTP 401.")

    copies = {
        "credentials": secrets["RABBITMQ_DEFAULT_USER"] + ":" + secrets["RABBITMQ_DEFAULT_PASS"],
        "properties": {"content_type": "application/json", "delivery_mode": 2, "message_id": event_id},
        "routing_key": "proposal.ProposalApproved.v1", "payload": event, "payload_encoding": "string",
    }
    queue = "project.domain-events"
    def acknowledgements():
        result = docker("exec", "-T", "rabbitmq", "rabbitmqctl", "-q", "list_queues",
                        "name", "messages_ready", "messages_unacknowledged")
        return any(line.split() == [queue, "0", "0"] for line in result.splitlines())

    docker("run", "--rm", "--no-deps", "-T", "identity-init", "python", "-c", PUBLISH,
           input_text=json.dumps(copies))
    wait_for(acknowledgements, "La cola de proyectos no se vació después de la réplica")
    assert sql("project_db", f"select count(*) from project where source_proposal_id='{proposal_id}'") == "1"
    assert sql("project_db", f"select count(*) from processed_event where event_id='{event_id}'") == "1"
    print("Evento duplicado enrutado y consumido sin duplicar el proyecto ni el inbox.")

    docker("restart", "proposal-service", "project-service")
    docker("up", "-d", "--no-build", "--wait")
    assert sql("project_db", f"select id from project where source_proposal_id='{proposal_id}'") == project_id
    researcher = login("investigador", "LOCAL_RESEARCHER_PASSWORD", secrets)
    assert http(API + "/projects/" + project_id, token=researcher)["id"] == project_id
    print("Persistencia tras reiniciar servicios comprobada.")


if __name__ == "__main__":
    try:
        main()
    except subprocess.CalledProcessError as error:
        # The subprocess input may contain a test credential. Do not print args, input or raw output.
        raise SystemExit(f"Un comando de Docker falló con código {error.returncode}.") from None
