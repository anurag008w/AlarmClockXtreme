#!/usr/bin/env python3
"""Terminal control for AlarmClockXtreme Cloud.

Uses only Python's standard library. The cloud API remains the source of truth
for terminal-created alarms; Android picks them up on its next cloud sync.
"""

from __future__ import annotations

import argparse
import getpass
import json
import os
import stat
import sys
from pathlib import Path
from typing import Any
from urllib import error, request

DEFAULT_BASE_URL = "https://alarmclockxtreme-cloud.onrender.com/"
DEFAULT_TOKEN_PATH = Path.home() / ".config" / "alarmclockxtreme" / "token.json"


def base_url() -> str:
    return os.environ.get("ACX_BASE_URL", DEFAULT_BASE_URL).rstrip("/") + "/"


def token_path() -> Path:
    return Path(os.environ.get("ACX_TOKEN_FILE", str(DEFAULT_TOKEN_PATH))).expanduser()


def save_token(token: str, email: str) -> None:
    path = token_path()
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps({"token": token, "email": email}, indent=2) + "\n")
    try:
        path.chmod(stat.S_IRUSR | stat.S_IWUSR)
    except OSError:
        pass


def load_token() -> str:
    env = os.environ.get("ACX_TOKEN", "").strip()
    if env:
        return env
    path = token_path()
    if not path.is_file():
        raise SystemExit("Not logged in. Run: python3 scripts/alarmctl.py login")
    try:
        data = json.loads(path.read_text())
        token = str(data.get("token", "")).strip()
    except (OSError, json.JSONDecodeError) as exc:
        raise SystemExit(f"Cannot read token file {path}: {exc}") from exc
    if not token:
        raise SystemExit("Token file is empty. Run login again.")
    return token


def api(
    method: str,
    path: str,
    *,
    body: dict[str, Any] | None = None,
    auth: bool = True,
) -> dict[str, Any]:
    url = base_url().rstrip("/") + "/" + path.lstrip("/")
    payload = None if body is None else json.dumps(body).encode()
    headers = {"Accept": "application/json"}
    if payload is not None:
        headers["Content-Type"] = "application/json"
    if auth:
        headers["Authorization"] = "Bearer " + load_token()

    req = request.Request(url, data=payload, headers=headers, method=method.upper())
    try:
        with request.urlopen(req, timeout=30) as response:
            raw = response.read().decode()
    except error.HTTPError as exc:
        raw = exc.read().decode(errors="replace")
        try:
            detail = json.loads(raw).get("detail", raw)
        except json.JSONDecodeError:
            detail = raw or f"HTTP {exc.code}"
        raise SystemExit(f"HTTP {exc.code}: {detail}") from exc
    except error.URLError as exc:
        raise SystemExit(f"Network error: {exc.reason}") from exc

    if not raw:
        return {}
    try:
        return json.loads(raw)
    except json.JSONDecodeError as exc:
        raise SystemExit(f"Cloud returned non-JSON data: {raw[:300]}") from exc


def parse_value(raw: str) -> Any:
    token = raw.strip()
    if token.lower() in {"true", "false"}:
        return token.lower() == "true"
    if token.lower() in {"null", "none"}:
        return None
    try:
        if token.startswith(("{", "[", '"')) or token in {"null", "true", "false"}:
            return json.loads(token)
    except json.JSONDecodeError:
        pass
    try:
        if "." in token:
            return float(token)
        return int(token)
    except ValueError:
        return token


def apply_sets(payload: dict[str, Any], assignments: list[str]) -> None:
    for assignment in assignments:
        if "=" not in assignment:
            raise SystemExit(f"Invalid --set {assignment!r}; use key=value")
        key, value = assignment.split("=", 1)
        key = key.strip()
        if not key:
            raise SystemExit(f"Invalid --set {assignment!r}; key is empty")
        payload[key] = parse_value(value)


def read_json_object(path: str) -> dict[str, Any]:
    if path == "-":
        data = json.load(sys.stdin)
    else:
        try:
            data = json.loads(Path(path).read_text())
        except (OSError, json.JSONDecodeError) as exc:
            raise SystemExit(f"Cannot read JSON file {path}: {exc}") from exc
    if not isinstance(data, dict):
        raise SystemExit(f"{path} must contain a JSON object")
    return data


def list_alarms() -> list[dict[str, Any]]:
    data = api("GET", "api/alarms?since=1970-01-01T00%3A00%3A00Z")
    return data.get("alarms", [])


def print_alarm(item: dict[str, Any]) -> None:
    payload = item.get("payload") or {}
    days = ",".join(payload.get("repeatDays") or []) or "ONCE"
    state = "ON" if payload.get("isEnabled", True) else "OFF"
    print(
        f'{item.get("id")}  {int(payload.get("hour", 0)):02d}:{int(payload.get("minute", 0)):02d}'
        f'  {state:3}  {payload.get("label", "")!r}'
        f'  days={days}  version={item.get("version")}'
    )


def cmd_login(register: bool) -> None:
    email = input("Email: ").strip()
    password = getpass.getpass("Password: ")
    if register and len(password) < 8:
        raise SystemExit("Password must be at least 8 characters.")
    endpoint = "api/auth/register" if register else "api/auth/login"
    data = api("POST", endpoint, body={"email": email, "password": password}, auth=False)
    save_token(str(data["token"]), str(data["user"]["email"]))
    print(f"Logged in as {data['user']['email']}.")
    print("Terminal alarms will sync to Android on the next cloud sync.")


def cmd_list() -> None:
    alarms = list_alarms()
    if not alarms:
        print("No alarms.")
        return
    for item in sorted(
        alarms,
        key=lambda x: (
            int((x.get("payload") or {}).get("hour", 0)),
            int((x.get("payload") or {}).get("minute", 0)),
        ),
    ):
        if not item.get("deletedAt"):
            print_alarm(item)


def build_payload(args: argparse.Namespace) -> dict[str, Any]:
    payload = read_json_object(args.json) if args.json else {}
    if args.time:
        try:
            hour_text, minute_text = args.time.split(":", 1)
            payload["hour"] = int(hour_text)
            payload["minute"] = int(minute_text)
        except ValueError as exc:
            raise SystemExit("--time must be HH:MM in 24-hour format") from exc
    if args.label is not None:
        payload["label"] = args.label
    if args.days:
        payload["repeatDays"] = [day.upper() for day in args.days]
    if args.challenge is not None:
        payload["challengeType"] = args.challenge.upper()
    if args.enabled is not None:
        payload["isEnabled"] = args.enabled
    apply_sets(payload, args.set_values)
    return payload


def cmd_create(args: argparse.Namespace) -> None:
    payload = build_payload(args)
    if "hour" not in payload or "minute" not in payload:
        raise SystemExit("Create needs --time HH:MM or a JSON payload containing hour/minute.")
    result = api(
        "PUT",
        "api/alarms/" + os.urandom(16).hex(),
        body={"payload": payload, "expectedVersion": 0},
    )
    print_alarm(result)


def find_alarm(alarm_id: str) -> dict[str, Any]:
    for item in list_alarms():
        if item.get("id") == alarm_id and not item.get("deletedAt"):
            return item
    raise SystemExit(f"Alarm not found: {alarm_id}")


def cmd_update(args: argparse.Namespace) -> None:
    current = find_alarm(args.id)
    payload = dict(current.get("payload") or {})
    if args.json:
        payload.update(read_json_object(args.json))
    apply_sets(payload, args.set_values)
    if not args.json and not args.set_values:
        raise SystemExit("Update needs --json PATCH.json and/or --set key=value.")
    result = api(
        "PUT",
        "api/alarms/" + args.id,
        body={"payload": payload, "expectedVersion": current["version"]},
    )
    print_alarm(result)


def cmd_delete(args: argparse.Namespace) -> None:
    current = find_alarm(args.id)
    result = api(
        "DELETE",
        f'api/alarms/{args.id}?expectedVersion={int(current["version"])}',
    )
    print(f"Deleted {result.get('id')} at cloud version {result.get('version')}.")


def cmd_pull() -> None:
    data = api("POST", "api/sync/refresh")
    print("Cloud dataset refreshed from GitHub.")
    sync = data.get("sync") or {}
    print(
        f'pull_ok={sync.get("pull_ok")} data_repo={sync.get("data_repo")} '
        f'last_pull={sync.get("last_pull")}'
    )


def cmd_status() -> None:
    data = api("GET", "api/me")
    print(f'account={data.get("user", {}).get("email")}')
    health = api("GET", "api/health", auth=False)
    print(json.dumps(health, indent=2))


def parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(description="AlarmClockXtreme terminal cloud control")
    sub = p.add_subparsers(dest="command", required=True)

    sub.add_parser("login", help="Log in and save a JWT locally")
    sub.add_parser("register", help="Create an account and save a JWT locally")
    sub.add_parser("list", help="List active cloud alarms")
    sub.add_parser("pull", help="Reconcile running cloud storage with the GitHub dataset")
    sub.add_parser("status", help="Show account and cloud sync status")

    create = sub.add_parser("create", help="Create a new alarm")
    create.add_argument("--json", help="Full alarm JSON file, or - for stdin")
    create.add_argument("--time", help="HH:MM in 24-hour format")
    create.add_argument("--label")
    create.add_argument("--days", nargs="*", metavar="DAY")
    create.add_argument("--challenge", help="ChallengeType name, e.g. MATH_EASY")
    state = create.add_mutually_exclusive_group()
    state.add_argument("--enabled", dest="enabled", action="store_true")
    state.add_argument("--disabled", dest="enabled", action="store_false")
    create.set_defaults(enabled=None)
    create.add_argument("--set", dest="set_values", action="append", default=[], metavar="KEY=VALUE")

    update = sub.add_parser("update", help="Update an alarm without overwriting unspecified fields")
    update.add_argument("id")
    update.add_argument("--json", help="JSON patch object")
    update.add_argument("--set", dest="set_values", action="append", default=[], metavar="KEY=VALUE")

    delete = sub.add_parser("delete", help="Delete an alarm with optimistic concurrency protection")
    delete.add_argument("id")

    return p


def main() -> None:
    args = parser().parse_args()
    commands = {
        "login": lambda: cmd_login(False),
        "register": lambda: cmd_login(True),
        "list": cmd_list,
        "create": lambda: cmd_create(args),
        "update": lambda: cmd_update(args),
        "delete": lambda: cmd_delete(args),
        "pull": cmd_pull,
        "status": cmd_status,
    }
    commands[args.command]()


if __name__ == "__main__":
    main()
