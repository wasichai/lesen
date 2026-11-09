#!/usr/bin/env python3
"""Aplica model/model.json, model/roles.json y (con --demo) model/demo-users.json a un Wasichai en marcha.

Solo biblioteca estándar. Idempotente: crea lo que falta y deja los permisos de cada rol en los declarados; nunca borra
objetos ni cambia contraseñas. Es el mismo enfoque que model/apply.py de elearning-backend, reducido a lo que Lesen usa:
dos objetos ancla de permisos, dos roles y las personas de la demostración.

Uso:
  WASICHAI_EMAIL=admin@wasichai.local WASICHAI_PASSWORD=admin python3 model/apply.py --url http://127.0.0.1:8095 [--demo]
  python3 model/apply.py --validate-only
"""
import argparse
import json
import os
import sys
import urllib.error
import urllib.request
from pathlib import Path

HERE = Path(__file__).resolve().parent
ACTIONS = {"READ", "CREATE", "UPDATE", "DELETE"}


class ApiError(Exception):
    def __init__(self, status: int, method: str, path: str, body: str):
        super().__init__(f"{method} {path} → {status}: {body[:300]}")
        self.status = status


class Client:
    def __init__(self, base: str):
        self.base = base.rstrip("/")
        self.token: str | None = None

    def call(self, method: str, path: str, body: object | None = None, ok: tuple[int, ...] = ()) -> object | None:
        data = None if body is None else json.dumps(body).encode()
        request = urllib.request.Request(self.base + path, data=data, method=method)
        request.add_header("Accept", "application/json")
        if data is not None:
            request.add_header("Content-Type", "application/json")
        if self.token:
            request.add_header("Authorization", f"Bearer {self.token}")
        try:
            with urllib.request.urlopen(request, timeout=60) as response:
                raw = response.read().decode()
        except urllib.error.HTTPError as error:
            raw = error.read().decode()
            if error.code in ok:
                return None
            raise ApiError(error.code, method, path, raw) from None
        return json.loads(raw) if raw.strip() else None

    def login(self, email: str, password: str) -> None:
        self.token = self.call("POST", "/api/auth/login", {"email": email, "password": password})["token"]


def load(name: str) -> dict:
    return json.loads((HERE / name).read_text())


def validate(model: dict, roles: dict, users: dict) -> list[str]:
    problems = []
    names = {o["name"] for o in model["objects"]}
    for o in model["objects"]:
        if not o.get("apiOnly"):
            problems.append(f"objeto {o['name']}: las anclas de permisos son apiOnly")
    role_names = set()
    for r in roles["roles"]:
        role_names.add(r["name"])
        if r["name"] == "ADMIN":
            problems.append("ADMIN es de Wasichai: no se declara")
        for p in r["permissions"]:
            if p["object"] not in names:
                problems.append(f"rol {r['name']}: objeto desconocido {p['object']}")
            for a in p["actions"]:
                if a not in ACTIONS:
                    problems.append(f"rol {r['name']}: acción desconocida {a}")
    for u in users["users"]:
        for r in u["roles"]:
            if r not in role_names:
                problems.append(f"usuario {u['email']}: rol desconocido {r}")
    return problems


def apply_objects(client: Client, model: dict) -> None:
    existing = {o["name"] for o in client.call("GET", "/api/objects") or []}
    for o in model["objects"]:
        if o["name"] in existing:
            continue
        body = {k: o[k] for k in ("name", "label", "pluralLabel", "description", "apiOnly") if k in o}
        body["fields"] = o.get("fields", [])
        client.call("POST", "/api/objects", body)
        print(f"  + objeto {o['name']}")


def apply_roles(client: Client, roles: dict) -> None:
    have = {r["name"]: r for r in client.call("GET", "/api/roles") or []}
    for role in roles["roles"]:
        name = role["name"]
        if name not in have:
            client.call("POST", "/api/roles", {"name": name, "label": role["label"], "ownRecordsOnly": False})
            print(f"  + rol {name}")
            current = set()
        else:
            current = {(p["objectName"], p["action"]) for p in have[name].get("permissions", []) if p.get("allowed", True)}
        wanted = {(p["object"], a) for p in role["permissions"] for a in p["actions"]}
        if wanted != current:
            entries = [{"objectName": o, "action": a} for o, a in sorted(wanted)]
            client.call("PUT", f"/api/roles/{name}/permissions", {"permissions": entries})
            print(f"  + permisos de {name} ({len(entries)})")


def apply_users(client: Client, users: dict, password: str) -> None:
    answer = client.call("GET", "/api/users?size=500")
    existing = {u["email"].lower(): u for u in (answer.get("content") if isinstance(answer, dict) else answer) or []}
    for user in users["users"]:
        wanted = sorted(user["roles"])
        have = existing.get(user["email"].lower())
        if have is None:
            client.call("POST", "/api/users", {"email": user["email"], "displayName": user["displayName"], "password": password, "roles": wanted})
            print(f"  + usuario {user['email']}")
        else:
            current = sorted(r["name"] if isinstance(r, dict) else r for r in have.get("roles") or [])
            if current != wanted:
                client.call("PUT", f"/api/users/{have['id']}/roles", {"roles": wanted})
                print(f"  + roles de {user['email']}")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--url", default=os.environ.get("WASICHAI_URL", "http://127.0.0.1:8095"))
    parser.add_argument("--demo", action="store_true", help="crea también las personas de model/demo-users.json")
    parser.add_argument("--validate-only", action="store_true")
    args = parser.parse_args()
    model, roles, users = load("model.json"), load("roles.json"), load("demo-users.json")
    problems = validate(model, roles, users)
    if problems:
        print("\n".join(problems))
        return 1
    if args.validate_only:
        print("modelo válido")
        return 0
    client = Client(args.url)
    client.login(os.environ.get("WASICHAI_EMAIL", ""), os.environ.get("WASICHAI_PASSWORD", ""))
    apply_objects(client, model)
    apply_roles(client, roles)
    if args.demo:
        password = os.environ.get("LESEN_DEMO_PASSWORD")
        if not password:
            print("--demo necesita LESEN_DEMO_PASSWORD")
            return 1
        apply_users(client, users, password)
    print("listo")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except ApiError as error:
        print(error)
        sys.exit(1)
