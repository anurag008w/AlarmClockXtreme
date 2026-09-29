from __future__ import annotations

import asyncio
import copy
import json
import os
import secrets
import time
from pathlib import Path
from typing import Any

import httpx
import jwt
from fastapi import Depends, FastAPI, HTTPException, Request
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import FileResponse
from pydantic import BaseModel, Field

import github_sync
import usersync

BASE = Path(__file__).resolve().parent
PUBLIC = (BASE / "public").resolve()
JWT_SECRET = os.environ.get("JWT_SECRET", "").strip()
AI_API_KEY = os.environ.get("AI_API_KEY", "").strip()
AI_BASE_URL = os.environ.get("AI_BASE_URL", "https://api.openai.com/v1").rstrip("/")
AI_MODEL = os.environ.get("AI_MODEL", "gpt-4o-mini")

if not JWT_SECRET:
    raise RuntimeError("JWT_SECRET is required")
if not github_sync.GH_TOKEN:
    raise RuntimeError("GH_TOKEN is required")
if "/" not in github_sync.DATA_REPO:
    raise RuntimeError("GITHUB_REPO must be owner/repo")

app = FastAPI(title="AlarmClockXtreme Cloud", version="1.1.0")
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_methods=["GET", "POST", "PUT", "DELETE", "OPTIONS"],
    allow_headers=["*"],
)

_sync_task: asyncio.Task | None = None
_sync_lock = asyncio.Lock()
_alarm_lock = asyncio.Lock()


class Credentials(BaseModel):
    email: str
    password: str = Field(min_length=8, max_length=128)


class AlarmWrite(BaseModel):
    payload: dict[str, Any]
    expectedVersion: int = 0
    clientUpdatedAt: str = ""


class AiCommand(BaseModel):
    command: str = Field(min_length=1, max_length=2000)


def public_user(user: dict) -> dict:
    return {
        "id": user["id"],
        "email": user["email"],
        "createdAt": user.get("created_at"),
    }


def token_for(user: dict) -> str:
    now = int(time.time())
    return jwt.encode(
        {
            "sub": user["id"],
            "email": user["email"],
            "iat": now,
            "exp": now + 60 * 60 * 24 * 30,
        },
        JWT_SECRET,
        algorithm="HS256",
    )


def current_user(request: Request) -> dict:
    raw = request.headers.get("authorization", "")
    if not raw.startswith("Bearer "):
        raise HTTPException(401, "missing_token")
    token = raw[7:].strip()
    if not token:
        raise HTTPException(401, "invalid_token")
    try:
        payload = jwt.decode(token, JWT_SECRET, algorithms=["HS256"])
        user_id = payload.get("sub")
        email = payload.get("email")
        if not isinstance(user_id, str) or not user_id or not isinstance(email, str) or not email:
            raise ValueError("invalid_claims")
    except (jwt.PyJWTError, ValueError, TypeError):
        raise HTTPException(401, "invalid_token")
    return {
        "id": user_id,
        "email": email,
    }


def sanitize_alarm(payload: dict[str, Any]) -> dict[str, Any]:
    if not isinstance(payload, dict):
        raise HTTPException(400, "alarm_payload_must_be_object")

    out = copy.deepcopy(payload)

    try:
        if "hour" in out:
            out["hour"] = max(0, min(23, int(out["hour"])))
        if "minute" in out:
            out["minute"] = max(0, min(59, int(out["minute"])))
        if "volume" in out:
            out["volume"] = max(0, min(100, int(out["volume"])))
        if "snoozeDurationMinutes" in out:
            out["snoozeDurationMinutes"] = max(
                1, min(180, int(out["snoozeDurationMinutes"]))
            )
        if "maxSnoozeCount" in out:
            out["maxSnoozeCount"] = max(0, min(20, int(out["maxSnoozeCount"])))
    except (TypeError, ValueError):
        raise HTTPException(400, "invalid_alarm_number")

    if "ringtoneUri" in out:
        ringtone_value = str(out.get("ringtoneUri", "")).strip()
        if ringtone_value.lower() in {"default", "default_alarm", "system_default"}:
            out["ringtoneUri"] = ""
        elif ringtone_value.lower() == "silent":
            out["ringtoneUri"] = "silent"

    for key, max_len in (
        ("label", 120),
        ("group", 40),
        ("profileName", 40),
        ("guardianPhone", 40),
        ("dismissActionPayload", 2048),
        ("ringtoneUri", 2048),
        ("spotifyUri", 2048),
        ("internetRadioUrl", 2048),
        ("photoMatchUri", 2048),
        ("firingBackgroundImageUri", 2048),
        ("nfcTagId", 128),
        ("barcodeValue", 512),
        ("wifiDismissSsid", 64),
        ("fixedTimezoneId", 128),
        ("shiftPatternStartDate", 32),
        ("specificDate", 32),
        ("morningRoutine", 2048),
        ("challengeChain", 512),
        ("ringtonePool", 4096),
    ):
        if key in out:
            out[key] = str(out[key])[:max_len]

    valid_days = {
        "MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY",
        "FRIDAY", "SATURDAY", "SUNDAY",
    }
    repeat_days = out.get("repeatDays", [])
    if isinstance(repeat_days, str):
        repeat_days = [x.strip() for x in repeat_days.split(",")]
    if not isinstance(repeat_days, list):
        repeat_days = []
    out["repeatDays"] = sorted({
        str(x).upper() for x in repeat_days
        if str(x).upper() in valid_days
    })
    def parse_bool(value: Any, default: bool = False) -> bool:
        if isinstance(value, bool):
            return value
        if value is None:
            return default
        token = str(value).strip().lower()
        if token in {"true", "1", "yes", "on"}:
            return True
        if token in {"false", "0", "no", "off"}:
            return False
        return default

    boolean_defaults = {
        "isEnabled": True,
        "vibrationEnabled": True,
        "overrideSystemVolume": True,
        "showOnLockScreen": True,
        "flashWake": False,
        "ttsEnabled": False,
        "wakeConfirmEnabled": False,
        "smartAlarmEnabled": False,
        "skipOnHolidays": False,
        "hueEnabled": False,
        "progressiveSnooze": False,
        "backupSoundEnabled": False,
        "sunriseSimulation": False,
        "guardianEnabled": False,
        "locationDismissEnabled": False,
        "flashlightStrobe": False,
        "dismissAtRingtoneEnd": False,
        "holdToDismissEnabled": False,
        "firingBackgroundImageEnabled": False,
        "firingBackgroundBlurEnabled": True,
    }
    for key, default in boolean_defaults.items():
        out[key] = parse_bool(out.get(key), default)
    out["isEnabled"] = out["isEnabled"]

    # These are Android-device-local scheduling values. The cloud stores
    # alarm intent, not a trigger timestamp tied to one handset.
    valid_vibration_patterns = {"default", "gentle", "heartbeat", "escalating", "sos"}
    out["vibrationPattern"] = str(out.get("vibrationPattern", "default")).lower()
    if out["vibrationPattern"] not in valid_vibration_patterns:
        out["vibrationPattern"] = "default"

    valid_hardware_actions = {"NONE", "SNOOZE", "DISMISS"}
    out["hardwareButtonAction"] = str(out.get("hardwareButtonAction", "NONE")).upper()
    if out["hardwareButtonAction"] not in valid_hardware_actions:
        out["hardwareButtonAction"] = "NONE"

    valid_dismiss_actions = {"NONE", "WEBHOOK", "HUE_SCENE", "BROADCAST"}
    out["dismissActionType"] = str(out.get("dismissActionType", "NONE")).upper()
    if out["dismissActionType"] not in valid_dismiss_actions:
        out["dismissActionType"] = "NONE"

    out["timezonePolicy"] = "FIXED" if str(out.get("timezonePolicy", "LOCAL")).upper() == "FIXED" else "LOCAL"
    out["solarAnchor"] = "SUNSET" if str(out.get("solarAnchor", "SUNRISE")).upper() == "SUNSET" else "SUNRISE"

    valid_shift_patterns = {"", "DDNNO", "FOUR_ON_FOUR_OFF", "PANAMA", "DUPONT", "PITMAN"}
    shift = str(out.get("shiftPattern", "")).upper()
    out["shiftPattern"] = shift if shift in valid_shift_patterns else ""
    
    out["id"] = 0
    out["nextTriggerTime"] = 0
    return out


def item_response(item: dict) -> dict:
    return {
        "id": item["id"],
        "payload": item.get("payload") or {},
        "version": int(item.get("version", 1)),
        "updatedAt": item.get("updated_at", ""),
        "deletedAt": item.get("deleted_at"),
    }


async def alarms_record(user_id: str) -> dict:
    record = await usersync.get_scope(user_id, "alarms")
    if not isinstance(record.get("items"), dict):
        record["items"] = {}
    return record


async def audit(user_id: str, action: str, entity_id: str | None = None, detail: dict | None = None) -> None:
    record = await usersync.get_scope(user_id, "audit")
    events = record.get("events", [])
    if not isinstance(events, list):
        events = []
    events.append({
        "action": action,
        "entityType": "alarm" if entity_id else "user",
        "entityId": entity_id,
        "detail": detail or {},
        "createdAt": usersync.now_utc(),
    })
    record["events"] = events[-200:]
    await usersync.save_scope(user_id, "audit", record)


async def persist_alarm_record(user_id: str, record: dict) -> dict:
    stored = await usersync.save_scope(user_id, "alarms", record)
    async with _sync_lock:
        pushed = await asyncio.to_thread(github_sync.push_data)
    if not pushed:
        raise HTTPException(503, "github_sync_failed_retry")
    return stored


async def mutate_alarm(
    user_id: str,
    alarm_id: str,
    payload: dict | None,
    *,
    delete: bool = False,
    expected: int = 0,
) -> dict:
    async with _alarm_lock:
        record = await alarms_record(user_id)
        current = record["items"].get(alarm_id)

        if current:
            current_version = int(current.get("version", 1))
            if expected <= 0:
                raise HTTPException(409, "version_required")
            if current_version != expected:
                raise HTTPException(409, "version_conflict")
            if current.get("deleted_at") and not delete:
                # A tombstone is final for this alarm id. A stale client must
                # create a new id rather than resurrecting this alarm.
                raise HTTPException(409, "alarm_deleted_conflict")
        elif delete:
            raise HTTPException(404, "alarm_not_found")

        version = int(current.get("version", 0)) + 1 if current else 1
        updated_at = usersync.now_utc()

        item = {
            "id": alarm_id,
            "version": version,
            "updated_at": updated_at,
            "deleted_at": updated_at if delete else None,
            "payload": None if delete else sanitize_alarm(payload or {}),
        }

        record["items"][alarm_id] = item
        await persist_alarm_record(user_id, record)
        await audit(
            user_id,
            "delete" if delete else ("update" if current else "create"),
            alarm_id,
            {"version": version},
        )
        return item_response(item)


@app.on_event("startup")
async def startup() -> None:
    global _sync_task

    github_sync.DATA_DIR.mkdir(parents=True, exist_ok=True)

    # Never let a fresh Render filesystem overwrite an existing GitHub data repo.
    if not await asyncio.to_thread(github_sync.pull_data):
        raise RuntimeError(
            "GitHub data pull failed; refusing startup to protect existing data"
        )

    async def loop() -> None:
        while True:
            await asyncio.sleep(github_sync.SYNC_INTERVAL)
            async with _sync_lock:
                if github_sync.has_data_changed():
                    await asyncio.to_thread(github_sync.push_data)
                else:
                    await asyncio.to_thread(github_sync.pull_data)

    _sync_task = asyncio.create_task(loop())


@app.on_event("shutdown")
async def shutdown() -> None:
    global _sync_task
    if _sync_task:
        _sync_task.cancel()
        _sync_task = None


@app.get("/api/health")
async def health():
    sync = github_sync.status()
    return {
        "ok": bool(sync["pull_ok"]),
        "service": "alarmclockxtreme-cloud",
        "sync": sync,
    }


@app.post("/api/auth/register")
async def register(body: Credentials):
    email = body.email.strip().lower()
    if "@" not in email or len(email) > 200:
        raise HTTPException(400, "invalid_email")
    try:
        user = await usersync.register(email, body.password)
    except ValueError as exc:
        raise HTTPException(409, str(exc))

    pushed = await asyncio.to_thread(github_sync.push_data)
    if not pushed:
        raise HTTPException(503, "github_sync_failed_retry")

    return {"token": token_for(user), "user": public_user(user)}


@app.post("/api/auth/login")
async def login(body: Credentials):
    email = body.email.strip().lower()
    if "@" not in email or len(email) > 200:
        raise HTTPException(400, "invalid_email")
    user = await usersync.authenticate(email, body.password)
    if not user:
        raise HTTPException(401, "invalid_credentials")
    await audit(user["id"], "login")
    return {"token": token_for(user), "user": public_user(user)}


@app.get("/api/me")
async def me(user=Depends(current_user)):
    return {"user": user}


@app.post("/api/devices/register")
async def register_device(body: dict, user=Depends(current_user)):
    device_id = str(body.get("deviceId", "")).strip()
    if not device_id or len(device_id) > 128:
        raise HTTPException(400, "invalid_device_id")

    record = await usersync.get_scope(user["id"], "devices")
    devices = record.get("devices", {})
    if not isinstance(devices, dict):
        devices = {}

    devices[device_id] = {
        "platform": str(body.get("platform", "android"))[:32],
        "appVersion": str(body.get("appVersion", ""))[:64],
        "lastSeenAt": usersync.now_utc(),
    }
    record["devices"] = devices
    await usersync.save_scope(user["id"], "devices", record)
    if not await asyncio.to_thread(github_sync.push_data):
        raise HTTPException(503, "github_sync_failed_retry")
    return {"ok": True}


@app.post("/api/sync/refresh")
async def refresh_sync(user=Depends(current_user)):
    # GitHub is the durable source of truth, but a previous write can still be
    # pending locally after a transient network failure. The GitHub push path
    # is conflict-safe and merges alarm rows against the newest checkout, so
    # flush that pending state before pulling the durable dataset back down.
    async with _sync_lock:
        if github_sync.has_data_changed():
            pushed = await asyncio.to_thread(github_sync.push_data)
            if not pushed:
                raise HTTPException(503, "github_sync_failed_retry")

        pulled = await asyncio.to_thread(github_sync.pull_data)
        if not pulled:
            raise HTTPException(503, "github_pull_failed_retry")
    return {"ok": True, "sync": github_sync.status()}


@app.get("/api/alarms")
async def get_alarms(
    since: str = "1970-01-01T00:00:00Z",
    user=Depends(current_user),
):
    record = await alarms_record(user["id"])
    changed = [
        item_response(item)
        for item in record["items"].values()
        if str(item.get("updated_at", "")) > since
    ]
    changed.sort(key=lambda x: x["updatedAt"])
    cursor = max([since] + [x["updatedAt"] for x in changed])
    return {"alarms": changed, "cursor": cursor}


@app.put("/api/alarms/{alarm_id}")
async def put_alarm(
    alarm_id: str,
    body: AlarmWrite,
    user=Depends(current_user),
):
    return await mutate_alarm(
        user["id"],
        alarm_id,
        body.payload,
        expected=max(0, body.expectedVersion),
    )


@app.delete("/api/alarms/{alarm_id}")
async def delete_alarm(
    alarm_id: str,
    expectedVersion: int = 0,
    user=Depends(current_user),
):
    return await mutate_alarm(
        user["id"], alarm_id, None, delete=True, expected=max(0, expectedVersion)
    )


@app.get("/api/audit")
async def get_audit(user=Depends(current_user)):
    record = await usersync.get_scope(user["id"], "audit")
    events = record.get("events", [])
    return {"events": events[-100:] if isinstance(events, list) else []}


ALARM_TOOLS = [
    {
        "type": "function",
        "function": {
            "name": "list_alarms",
            "description": "List this user's active alarms.",
            "parameters": {
                "type": "object",
                "properties": {},
                "additionalProperties": False,
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "create_alarm",
            "description": "Create a new alarm using AlarmClockXtreme Alarm field names.",
            "parameters": {
                "type": "object",
                "properties": {
                    "alarm": {
                        "type": "object",
                        "additionalProperties": True,
                    }
                },
                "required": ["alarm"],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "update_alarm",
            "description": "Patch an existing alarm. List alarms first when the id is unknown.",
            "parameters": {
                "type": "object",
                "properties": {
                    "id": {"type": "string"},
                    "patch": {"type": "object", "additionalProperties": True},
                },
                "required": ["id", "patch"],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "delete_alarm",
            "description": "Delete an existing alarm. List first when the id is unknown.",
            "parameters": {
                "type": "object",
                "properties": {"id": {"type": "string"}},
                "required": ["id"],
            },
        },
    },
]


async def ai_call(messages: list[dict]) -> dict:
    if not AI_API_KEY:
        raise HTTPException(503, "ai_not_configured")

    async with httpx.AsyncClient(timeout=45) as client:
        response = await client.post(
            f"{AI_BASE_URL}/chat/completions",
            headers={
                "Authorization": f"Bearer {AI_API_KEY}",
                "Content-Type": "application/json",
            },
            json={
                "model": AI_MODEL,
                "messages": messages,
                "tools": ALARM_TOOLS,
                "temperature": 0.1,
            },
        )

    if response.status_code >= 400:
        raise HTTPException(502, "ai_provider_failed")
    return response.json()


async def execute_ai(user_id: str, tool_call: dict) -> dict:
    name = tool_call["function"]["name"]
    arguments = json.loads(tool_call["function"].get("arguments") or "{}")

    if name == "list_alarms":
        record = await alarms_record(user_id)
        return [
            {
                "id": alarm_id,
                "version": item.get("version", 1),
                "payload": item.get("payload"),
            }
            for alarm_id, item in record["items"].items()
            if not item.get("deleted_at")
        ]

    if name == "create_alarm":
        return await mutate_alarm(
            user_id,
            secrets.token_hex(16),
            arguments.get("alarm") or {},
        )

    record = await alarms_record(user_id)
    alarm_id = str(arguments.get("id", ""))
    item = record["items"].get(alarm_id)
    if not item or item.get("deleted_at"):
        raise HTTPException(404, "alarm_not_found")

    version = int(item.get("version", 1))

    if name == "update_alarm":
        merged = dict(item.get("payload") or {})
        merged.update(arguments.get("patch") or {})
        return await mutate_alarm(
            user_id,
            alarm_id,
            merged,
            expected=version,
        )

    if name == "delete_alarm":
        return await mutate_alarm(
            user_id,
            alarm_id,
            None,
            delete=True,
            expected=version,
        )

    raise HTTPException(400, "unknown_ai_tool")


@app.post("/api/ai/command")
async def ai_command(body: AiCommand, user=Depends(current_user)):
    system = (
        "You are AlarmClockXtreme's alarm assistant. You can only operate on the "
        "authenticated user's alarms. Use 24-hour time. Preserve all unspecified "
        "advanced alarm fields. List before editing if an alarm must be found by "
        "label/time. After tools finish, explain exactly what changed."
    )

    messages = [
        {"role": "system", "content": system},
        {"role": "user", "content": body.command},
    ]
    executed = []

    for _ in range(4):
        data = await ai_call(messages)
        message = data["choices"][0]["message"]
        tool_calls = message.get("tool_calls") or []
        messages.append(message)

        if not tool_calls:
            return {
                "mode": "ai",
                "message": message.get("content") or "done",
                "executed": executed,
            }

        for tool_call in tool_calls:
            result = await execute_ai(user["id"], tool_call)
            executed.append({
                "tool": tool_call["function"]["name"],
                "result": result,
            })
            messages.append({
                "role": "tool",
                "tool_call_id": tool_call["id"],
                "content": json.dumps(result),
            })

    raise HTTPException(500, "ai_tool_round_limit")


@app.get("/{path:path}")
async def spa(path: str):
    candidate = (PUBLIC / path).resolve()
    try:
        candidate.relative_to(PUBLIC)
    except ValueError:
        return FileResponse(PUBLIC / "index.html")
    if path and candidate.is_file():
        return FileResponse(candidate)
    return FileResponse(PUBLIC / "index.html")