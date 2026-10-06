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
import uuid
import stat
import sys
from urllib.parse import quote
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError
from datetime import datetime
from pathlib import Path
from typing import Any
from urllib import error, request

DEFAULT_BASE_URL = "https://alarmclockxtreme-cloud.onrender.com/"
SETTINGS_TYPES = json.loads((Path(__file__).with_name("settings-fields.json")).read_text())
FIELD_TYPES = json.loads((Path(__file__).with_name("alarm-fields.json")).read_text())
DAYS = {"MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY", "SATURDAY", "SUNDAY"}

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
        payload[key] = value if FIELD_TYPES.get(key) == "String" else parse_value(value)


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


def validate_payload(payload: dict[str, Any]) -> None:
    types = {payload.get("challengeType", "NONE")} | {part.strip() for part in str(payload.get("challengeChain", "")).split(",") if part.strip()}
    required = {"NFC_SCAN":"nfcTagId", "BARCODE_SCAN":"barcodeValue", "PHOTO_MATCH":"photoMatchUri", "WIFI_CONNECT":"wifiDismissSsid"}
    missing = [kind for kind, key in required.items() if kind in types and not str(payload.get(key, "")).strip()]
    if missing: raise SystemExit("Missing phone challenge references: " + ", ".join(missing))
    for key, value in payload.items():
        kind = FIELD_TYPES.get(key)
        if kind is None:
            raise SystemExit(f"Unknown or device-managed alarm field: {key}. Run fields.")
        valid = (
            (kind == "String" and isinstance(value, str)) or
            (kind == "Boolean" and isinstance(value, bool)) or
            (kind in {"Int", "Long"} and isinstance(value, int) and not isinstance(value, bool)) or
            (kind == "Double" and isinstance(value, (int, float)) and not isinstance(value, bool)) or
            (kind == "Set<DayOfWeek>" and isinstance(value, list) and all(day in DAYS for day in value))
        )
        if not valid:
            raise SystemExit(f"Invalid {key}: expected {kind}")
    for key, low, high in (("hour",0,23),("minute",0,59),("volume",0,100),
                           ("snoozeDurationMinutes",1,180),("maxSnoozeCount",0,20)):
        if key in payload and not low <= payload[key] <= high:
            raise SystemExit(f"{key} must be between {low} and {high}")


def verify_commit(result: dict[str, Any], deleted: bool = False) -> None:
    # Never blindly retry a write: an error can occur after the server committed.
    for item in list_alarms():
        if item.get("id") == result.get("id"):
            if item.get("version") != result.get("version"):
                raise SystemExit("Cloud changed after this write. Inspect get/list; do not retry blindly.")
            if bool(item.get("deletedAt")) != deleted or item.get("payload") != result.get("payload", item.get("payload")):
                raise SystemExit("Cloud readback differs. Inspect get/list before retrying.")
            print("Verified in cloud. Phone delivery is pending its next successful sync.")
            return
    raise SystemExit("Cloud readback missing. Inspect list before retrying; write may have committed.")


def cmd_create(args: argparse.Namespace) -> None:
    payload = build_payload(args)
    if "hour" not in payload or "minute" not in payload:
        raise SystemExit("Create needs --time HH:MM or a JSON payload containing hour/minute.")
    validate_payload(payload)
    result = api(
        "PUT",
        "api/alarms/" + os.urandom(16).hex(),
        body={"payload": payload, "expectedVersion": 0},
    )
    print_alarm(result)
    verify_commit(result)


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
    validate_payload({k:v for k,v in payload.items() if k not in {"id","createdAt","nextTriggerTime"}})
    result = api(
        "PUT",
        "api/alarms/" + quote(args.id, safe=""),
        body={"payload": payload, "expectedVersion": current["version"]},
    )
    print_alarm(result)
    verify_commit(result)


def cmd_delete(args: argparse.Namespace) -> None:
    current = find_alarm(args.id)
    result = api(
        "DELETE",
        f'api/alarms/{quote(args.id, safe="")}?expectedVersion={int(current["version"])}',
    )
    print(f"Deleted {result.get('id')} at cloud version {result.get('version')}.")
    verify_commit(result, deleted=True)


def cmd_pull() -> None:
    data = api("POST", "api/sync/refresh")
    print("Cloud dataset refreshed from GitHub.")
    sync = data.get("sync") or {}
    print(
        f'pull_ok={sync.get("pull_ok")} data_repo={sync.get("data_repo")} '
        f'last_pull={sync.get("last_pull")}'
    )


def new_command_id():
    import time
    return f"v2-{int(time.time()*1000)}-{uuid.uuid4().hex}"

def cmd_timer_command(args) -> None:
    if args.action == "start":
        if not args.seconds or not 1 <= args.seconds <= 86400:
            raise SystemExit("--seconds must be 1..86400")
        payload = {"seconds":args.seconds,"label":args.label}
    else:
        if not args.timer_id or args.timer_id <= 0:raise SystemExit("--timer-id is required")
        payload = {"timerId":args.timer_id}
    body = {"deviceId":args.device,"commandId":args.command_id or new_command_id(),
            "kind":"timer","action":args.action,"payload":payload}
    row = api("POST", "api/utilities/commands", body=body)
    print(json.dumps(row,indent=2))
    print("Pending is only queued. Read utilities for applied/rejected/expired; don't blindly retry with a new command ID.")


def cmd_skip_next(args):
    snapshot=api("GET","api/dashboard/"+quote(args.device,safe="")).get("snapshot") or {}
    details=snapshot.get("payload",snapshot).get("alarmDetails",[])
    alarm=next((a for a in details if a.get("cloudAlarmId")==args.id),None)
    if not alarm or not alarm.get("canSkipNext"): raise SystemExit("No phone-owned skippable occurrence. Refresh phone sync first.")
    print(f"Review phone {args.device}, alarm {args.id}, occurrence {alarm['nextTriggerTime']} epoch milliseconds.")
    if not args.confirm: raise SystemExit("Pass --confirm after reviewing this exact phone occurrence. No write made.")
    row=api("POST","api/utilities/commands",body={"deviceId":args.device,"commandId":args.command_id or new_command_id(),"kind":"alarm","action":"skip-next","payload":{"cloudAlarmId":args.id,"expectedNextTriggerTime":alarm["nextTriggerTime"]}})
    print(json.dumps(row,indent=2));print("Only queued. Read utilities for applied/rejected/expired. A changed phone occurrence is rejected.")

def cmd_template(args) -> None:
    templates=json.loads(Path(__file__).with_name("alarm-templates.json").read_text())
    if not args.key:
        print(json.dumps(templates,indent=2));return
    template=next((row for row in templates if row["key"]==args.key),None)
    if not template:raise SystemExit("Unknown template key")
    payload={**template["payload"],"label":template["label"],"isEnabled":False}
    if template["relativeMinutes"]:
        from datetime import timedelta
        when=datetime.now()+timedelta(minutes=template["relativeMinutes"])
        payload.update(hour=when.hour,minute=when.minute,specificDate=when.date().isoformat())
    print(json.dumps(payload,indent=2))
    print("Disabled draft only. Review your phone timezone and time before creating this alarm.",file=sys.stderr)

def cmd_batch(args) -> None:
    if not args.confirm: raise SystemExit("Review the exact IDs and action, then pass --confirm. Batches are sequential, not atomic.")
    ids=list(dict.fromkeys(args.ids))
    if not 1 <= len(ids) <= 100: raise SystemExit("Choose 1..100 unique alarm IDs")
    rows={row["id"]:row for row in list_alarms() if not row.get("deletedAt")}
    if any(id not in rows for id in ids): raise SystemExit("One or more selected alarms are missing. No writes made.")
    completed=0
    try:
        for id in ids:
            row=rows[id]
            if args.action=="delete":
                result=api("DELETE","api/alarms/"+quote(id,safe="")+"?expectedVersion="+str(row["version"]))
                verify_commit(result,deleted=True)
            else:
                payload={**row["payload"],"isEnabled":args.action=="enable"}
                result=api("PUT","api/alarms/"+quote(id,safe=""),body={"payload":payload,"expectedVersion":row["version"]})
                verify_commit(result)
            completed+=1
    except SystemExit as error:
        raise SystemExit(f"Stopped after {completed} verified writes. Earlier writes are not rolled back; the last request may have committed. Inspect before retrying. {error}")
    print(f"{completed} cloud changes verified. Phone delivery awaits sync.")

def cmd_world_clocks() -> None:
    settings = api("GET", "api/settings")
    if not settings.get("version") or "worldClockZones" not in settings.get("payload", {}):
        raise SystemExit("World clock zones have not synced from an updated phone yet.")
    raw = settings["payload"]["worldClockZones"]
    for name in raw.split("|") if raw else []:
        try:
            now = datetime.now(ZoneInfo(name))
        except (ZoneInfoNotFoundError, ValueError):
            raise SystemExit("Unsupported synced zone: " + name)
        print(name + "  " + now.strftime("%Y-%m-%d %H:%M:%S %Z"))


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
    sub.add_parser("fields", help="List every supported alarm field and type")
    sub.add_parser("export", help="Export cloud alarms, including tombstones, as JSON")
    sub.add_parser("utility-devices", help="List registered phone IDs")
    utilities = sub.add_parser("utilities", help="Read phone timer snapshots and command status")
    utilities.add_argument("device")
    timer = sub.add_parser("timer-command", help="Queue a phone-owned timer command; pending is not applied")
    timer.add_argument("device")
    timer.add_argument("action", choices=["start","pause","resume","stop"])
    timer.add_argument("--seconds", type=int)
    timer.add_argument("--label", default="")
    timer.add_argument("--timer-id", type=int)
    timer.add_argument("--command-id", help="Reuse only to retry the identical request")
    stopwatch = sub.add_parser("stopwatch-command", help="Queue phone stopwatch command; read utilities for acknowledgement")
    stopwatch.add_argument("device")
    stopwatch.add_argument("action",choices=["start","pause","resume","reset","lap"])
    stopwatch.add_argument("--command-id")
    skip=sub.add_parser("skip-next",help="Review/queue one phone-owned recurring occurrence; requires --confirm")
    skip.add_argument("device");skip.add_argument("id");skip.add_argument("--confirm",action="store_true");skip.add_argument("--command-id")
    template = sub.add_parser("template",help="List native presets or print a disabled alarm draft; no writes")
    template.add_argument("key",nargs="?")
    batch = sub.add_parser("batch", help="Sequential selected alarm actions; stops on conflict, no rollback")
    batch.add_argument("action",choices=["enable","disable","delete"])
    batch.add_argument("ids",nargs="+")
    batch.add_argument("--confirm",action="store_true")
    dashboard_parser = sub.add_parser("dashboard", help="Read phone Today and alarm stats snapshot; including existing bounded sleep readouts")
    dashboard_parser.add_argument("device")
    cities = sub.add_parser("weather-cities", help="Find public weather cities; no phone location access")
    cities.add_argument("name")
    weather = sub.add_parser("weather", help="Weather for explicitly supplied coordinates, not phone GPS")
    weather.add_argument("latitude",type=float)
    weather.add_argument("longitude",type=float)
    weather.add_argument("--unit",choices=["celsius","fahrenheit"],default="celsius")
    sub.add_parser("news-feeds", help="List supported Android preset news feeds")
    news = sub.add_parser("news", help="Read a preset public news feed")
    news.add_argument("--feed", default="bbc")
    bedtime = sub.add_parser("bedtime", help="Read synced bedtime configuration without private sleep data")
    sub.add_parser("world-clocks", help="Show synced world clock zones and their current time")
    sub.add_parser("settings", help="Inspect phone settings stored in cloud")
    sub.add_parser("settings-fields", help="List remotely configurable global settings")
    config = sub.add_parser("settings-update", help="Patch phone settings with version checks")
    config.add_argument("--json", help="JSON patch file or - for stdin")
    config.add_argument("--set", dest="set_values", action="append", default=[], metavar="KEY=VALUE")
    sub.add_parser("sounds", help="Show reusable sound references already present in your alarms")
    get = sub.add_parser("get", help="Inspect one full alarm payload and cloud version")
    get.add_argument("id")
    sound = sub.add_parser("sound", help="Set sound without changing other alarm settings")
    sound.add_argument("id")
    mode = sound.add_mutually_exclusive_group(required=True)
    mode.add_argument("--default", action="store_true")
    mode.add_argument("--silent", action="store_true")
    mode.add_argument("--uri", help="Existing Android content URI; does not upload a file")
    mode.add_argument("--radio", help="HTTPS audio stream; requires network on phone")
    mode.add_argument("--spotify", help="Spotify URI; requires Spotify on phone")
    mode.add_argument("--pool", help="Comma-separated device-local ringtone URIs")

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


def cmd_settings_update(args: argparse.Namespace) -> None:
    current = api("GET", "api/settings")
    if not current.get("version"):
        raise SystemExit("No phone settings synced yet. Sync an updated Android app first.")
    patch = read_json_object(args.json) if args.json else {}
    for assignment in args.set_values:
        if "=" not in assignment: raise SystemExit("Use key=value")
        key,value = assignment.split("=",1)
        patch[key] = value if SETTINGS_TYPES.get(key)=="String" else parse_value(value)
    if not patch: raise SystemExit("Settings update needs --json or --set")
    for key,value in patch.items():
        kind=SETTINGS_TYPES.get(key)
        valid=(kind=="Boolean" and isinstance(value,bool)) or (kind=="String" and isinstance(value,str)) or (kind in {"Int","Long"} and isinstance(value,int) and not isinstance(value,bool)) or (kind=="Double" and isinstance(value,(int,float)) and not isinstance(value,bool))
        if not valid: raise SystemExit(f"Unsupported setting or wrong type: {key}")
    payload={**current.get("payload",{}),**patch}
    saved=api("PUT","api/settings",body={"payload":payload,"expectedVersion":current["version"]})
    readback=api("GET","api/settings")
    if readback.get("version")!=saved.get("version") or readback.get("payload")!=saved.get("payload"):
        raise SystemExit("Settings changed after save. Inspect settings; do not retry blindly.")
    print(json.dumps(readback,indent=2))
    print("Verified in cloud. Phone applies these on the next successful sync.")


def cmd_sounds() -> None:
    sounds = []
    for item in list_alarms():
        if item.get("deletedAt"): continue
        p = item.get("payload") or {}
        sounds.append({"id":item["id"], "label":p.get("label", ""),
                       **{key:p.get(key, "") for key in ("ringtoneUri","ringtonePool","internetRadioUrl","spotifyUri")}})
    print(json.dumps(sounds, indent=2))


def cmd_sound(args: argparse.Namespace) -> None:
    patch = {"ringtoneUri":"", "ringtonePool":"", "internetRadioUrl":"", "spotifyUri":""}
    if args.silent: patch["ringtoneUri"] = "silent"
    if args.uri is not None: patch["ringtoneUri"] = args.uri
    if args.pool is not None: patch["ringtonePool"] = args.pool
    if args.radio is not None:
        if not args.radio.startswith("https://"): raise SystemExit("Radio URL must use HTTPS")
        patch["internetRadioUrl"] = args.radio
    if args.spotify is not None: patch["spotifyUri"] = args.spotify
    args.json = None
    args.set_values = [f"{key}={value}" for key,value in patch.items()]
    cmd_update(args)


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
        "fields": lambda: print(json.dumps(FIELD_TYPES, indent=2)),
        "export": lambda: print(json.dumps({"alarms":list_alarms()}, indent=2)),
        "get": lambda: print(json.dumps(find_alarm(args.id), indent=2)),
        "sounds": cmd_sounds,
        "utility-devices": lambda: print(json.dumps(api("GET","api/utilities/devices"),indent=2)),
        "utilities": lambda: print(json.dumps(api("GET","api/utilities/"+quote(args.device,safe="")),indent=2)),
        "timer-command": lambda: cmd_timer_command(args),
        "stopwatch-command": lambda: print(json.dumps(api("POST","api/utilities/commands",body={"deviceId":args.device,"commandId":args.command_id or new_command_id(),"kind":"stopwatch","action":args.action,"payload":{}}),indent=2)),
        "skip-next": lambda: cmd_skip_next(args),
        "template": lambda: cmd_template(args),
        "batch": lambda: cmd_batch(args),
        "dashboard": lambda: print(json.dumps(api("GET","api/dashboard/"+quote(args.device,safe="")),indent=2)),
        "weather-cities": lambda: print(json.dumps(api("GET","api/today/cities?name="+quote(args.name,safe="")),indent=2)),
        "weather": lambda: print(json.dumps(api("GET",f"api/today/weather?latitude={args.latitude}&longitude={args.longitude}&unit={args.unit}"),indent=2)),
        "news-feeds": lambda: print(json.dumps(api("GET","api/news/feeds"),indent=2)),
        "news": lambda: print(json.dumps(api("GET","api/news?feed="+quote(args.feed,safe="")),indent=2)),
        "bedtime": lambda: print(json.dumps({k:v for k,v in api("GET","api/settings").get("payload",{}).items() if k.startswith("bedtime") or k.startswith("sleepGoal") or k.startswith("sleepSound")},indent=2)),
        "world-clocks": cmd_world_clocks,
        "settings": lambda: print(json.dumps(api("GET","api/settings"),indent=2)),
        "settings-fields": lambda: print(json.dumps(SETTINGS_TYPES,indent=2)),
        "settings-update": lambda: cmd_settings_update(args),
        "sound": lambda: cmd_sound(args),
    }
    commands[args.command]()


if __name__ == "__main__":
    main()
