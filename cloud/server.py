from __future__ import annotations

import asyncio
import copy
import json
import os
import secrets
import re
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError
import time
from datetime import datetime, timedelta, timezone
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
import utility_control
import news
import today
import dashboard
import challenge_rules

BASE = Path(__file__).resolve().parent
PUBLIC = (BASE / "public").resolve()
JWT_SECRET = os.environ.get("JWT_SECRET", "").strip()
AI_API_KEY = os.environ.get("AI_API_KEY", "").strip()
AI_BASE_URL = os.environ.get("AI_BASE_URL", "https://api.openai.com/v1").rstrip("/")
AI_MODEL = os.environ.get("AI_MODEL", "gpt-4o-mini")
APP_VERSION = "1.15.47"
try:
    DUPLICATE_CREATE_WINDOW_SECONDS = max(
        0, int(os.environ.get("DUPLICATE_CREATE_WINDOW_SECONDS", "120"))
    )
except ValueError:
    DUPLICATE_CREATE_WINDOW_SECONDS = 120

if not JWT_SECRET:
    raise RuntimeError("JWT_SECRET is required")
if not github_sync.GH_TOKEN:
    raise RuntimeError("GH_TOKEN is required")
if "/" not in github_sync.DATA_REPO:
    raise RuntimeError("GITHUB_REPO must be owner/repo")

app = FastAPI(title="AlarmClockXtreme Cloud", version=APP_VERSION)
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_methods=["GET", "POST", "PUT", "DELETE", "OPTIONS"],
    allow_headers=["*"],
)

@app.middleware("http")
async def private_api_cache_policy(request: Request, call_next):
    response = await call_next(request)
    if request.url.path.startswith("/api/"):
        response.headers["Cache-Control"] = "no-store, private"
        response.headers["Pragma"] = "no-cache"
        response.headers["Vary"] = "Authorization"
    return response

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


async def audit(
    user_id: str,
    action: str,
    entity_id: str | None = None,
    detail: dict | None = None,
    *,
    push: bool = False,
) -> None:
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

    if push:
        async with _sync_lock:
            pushed = await asyncio.to_thread(github_sync.push_data)
        if not pushed:
            raise HTTPException(503, "github_sync_failed_retry")


async def persist_alarm_record(
    user_id: str,
    record: dict,
    *,
    audit_action: str | None = None,
    audit_entity_id: str | None = None,
    audit_detail: dict | None = None,
) -> dict:
    # Every alarm mutation starts from the newest durable GitHub snapshot.
    # This is what makes multiple Render instances behave like one server:
    # a stale instance cannot validate against an old version and then replace
    # the newer GitHub dataset.
    async with _sync_lock:
        pulled = await asyncio.to_thread(github_sync.pull_data)
        if not pulled:
            raise HTTPException(503, "github_pull_failed_retry")

        durable = await alarms_record(user_id)

        if audit_entity_id:
            proposed = record.get("items", {}).get(audit_entity_id)
            latest = durable.get("items", {}).get(audit_entity_id)
            if isinstance(proposed, dict):
                proposed_version = int(proposed.get("version", 0) or 0)
                expected_base = max(0, proposed_version - 1)
                latest_version = (
                    int(latest.get("version", 0) or 0)
                    if isinstance(latest, dict)
                    else 0
                )
                if latest_version != expected_base:
                    # Another device/Render instance advanced this alarm after
                    # this request's read. Never silently overwrite or roll
                    # that change back to an older version.
                    raise HTTPException(409, "version_conflict")

        # Merge the complete request record into the freshly pulled dataset.
        # Alarm rows merge by version/tombstone, so unrelated concurrent alarm
        # edits survive even when the running instance had an older copy.
        merged = github_sync._merge_alarm_scope(record, durable)

        stored = await usersync.save_scope(user_id, "alarms", merged)
        if audit_action:
            await audit(
                user_id,
                audit_action,
                audit_entity_id,
                audit_detail,
                push=False,
            )

        pushed = await asyncio.to_thread(github_sync.push_data)
        if not pushed:
            raise HTTPException(503, "github_sync_failed_retry")
        return stored

def _guard_duplicate_create(record: dict, new_payload: dict) -> None:
    """Refuse retry-storm creates.

    A create under a brand-new id whose sanitized payload is identical to a
    live alarm row written moments ago is the same logical alarm submitted
    again (double click, Enter resubmit, or a client retry after a slow
    response). Rejecting it here is what stops one dashboard save from
    turning into two identical alarm cards. Intentional duplicate alarms
    still work: their createdAt differs, or they are created after the
    window, or an identical older row is tombstoned first.
    """
    if DUPLICATE_CREATE_WINDOW_SECONDS <= 0:
        return
    try:
        new_key = json.dumps(new_payload, sort_keys=True)
    except (TypeError, ValueError):
        return
    now = datetime.now(timezone.utc)
    for existing in record.get("items", {}).values():
        if not isinstance(existing, dict) or existing.get("deleted_at"):
            continue
        existing_payload = existing.get("payload")
        if not isinstance(existing_payload, dict):
            continue
        try:
            if json.dumps(existing_payload, sort_keys=True) != new_key:
                continue
            updated = datetime.fromisoformat(
                str(existing.get("updated_at", "")).replace("Z", "+00:00")
            )
        except (TypeError, ValueError):
            continue
        if (now - updated) <= timedelta(seconds=DUPLICATE_CREATE_WINDOW_SECONDS):
            raise HTTPException(409, "duplicate_create_suspected")


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
        sanitized = None if delete else sanitize_alarm(payload or {})

        if not delete and current is None and sanitized is not None:
            _guard_duplicate_create(record, sanitized)

        item = {
            "id": alarm_id,
            "version": version,
            "updated_at": updated_at,
            "deleted_at": updated_at if delete else None,
            "payload": sanitized,
        }

        record["items"][alarm_id] = item
        await persist_alarm_record(
            user_id,
            record,
            audit_action="delete" if delete else ("update" if current else "create"),
            audit_entity_id=alarm_id,
            audit_detail={"version": version},
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
                    pushed = await asyncio.to_thread(github_sync.push_data)
                    if not pushed:
                        # The durable repo may have advanced on another
                        # instance. Pull/merge that state now; the next loop
                        # (or a client read) will retry the pending local write.
                        await asyncio.to_thread(github_sync.pull_data)
                else:
                    await asyncio.to_thread(github_sync.ensure_current, 0.0)

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
        "version": APP_VERSION,
        "commit": (
            os.environ.get("RENDER_GIT_COMMIT", "")
            or os.environ.get("GIT_COMMIT", "")
        )[:8],
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
    await audit(user["id"], "login", push=True)
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
    # Serialize the filesystem mutation with the GitHub durability cycle.
    async with _sync_lock:
        await usersync.save_scope(user["id"], "devices", record)
        if not await asyncio.to_thread(github_sync.push_data):
            raise HTTPException(503, "github_sync_failed_retry")
    return {"ok": True}


@app.post("/api/sync/refresh")
async def refresh_sync(user=Depends(current_user)):
    # GitHub is the durable source of truth. First reconcile the running
    # instance against the newest durable commit, then flush any local pending
    # write. This keeps login/refresh from ever reading a stale Render copy.
    async with _sync_lock:
        current = await asyncio.to_thread(github_sync.ensure_current, 0.0)
        if not current:
            raise HTTPException(503, "github_refresh_failed_retry")

        if github_sync.has_data_changed():
            pushed = await asyncio.to_thread(github_sync.push_data)
            if not pushed:
                raise HTTPException(503, "github_sync_failed_retry")

        current = await asyncio.to_thread(github_sync.ensure_current, 0.0)
        if not current:
            raise HTTPException(503, "github_refresh_failed_retry")
    return {"ok": True, "sync": github_sync.status()}


@app.get("/api/dashboard/{device_id}")
async def get_dashboard(device_id: str, user=Depends(current_user)):
    async with _sync_lock:
        if not await asyncio.to_thread(github_sync.ensure_current, 2.0):
            raise HTTPException(503, "github_refresh_failed_retry")
        record = await usersync.get_scope(user["id"], "dashboard")
        row = record.get("items", {}).get("snapshot-"+device_id)
    return {"serverNowMillis": int(time.time()*1000), "snapshot": row}

@app.put("/api/dashboard/{device_id}")
async def put_dashboard(device_id: str, body: dict, user=Depends(current_user)):
    payload = dashboard.validate(body)
    await asyncio.to_thread(dashboard.require_private_data_repo)
    async with _sync_lock:
        if not await asyncio.to_thread(github_sync.pull_data):
            raise HTTPException(503, "github_pull_failed_retry")
        devices = await usersync.get_scope(user["id"], "devices")
        if device_id not in devices.get("devices", {}):
            raise HTTPException(404, "device_not_registered")
        record = await usersync.get_scope(user["id"], "dashboard")
        items = record.setdefault("items", {})
        key = "snapshot-"+device_id
        previous = items.get(key,{})
        now = int(time.time()*1000)
        row = {"id":key,"deviceId":device_id,"payload":payload,"observedServerMillis":now,"version":previous.get("version",0)+1,"updated_at":usersync.now_utc()}
        items[key]=row
        await usersync.save_scope(user["id"],"dashboard",record)
        if not await asyncio.to_thread(github_sync.push_data):
            raise HTTPException(503,"github_sync_failed_retry")
    return row

@app.get("/api/today/cities")
async def find_weather_cities(name: str, user=Depends(current_user)):
    return await today.cities(name)

@app.get("/api/today/weather")
async def read_weather(latitude: float, longitude: float, unit: str = "celsius", user=Depends(current_user)):
    return await today.forecast(latitude, longitude, unit)

@app.get("/api/alarm-templates")
async def alarm_templates(user=Depends(current_user)):
    return {"templates": json.loads((BASE / "alarm-templates.json").read_text())}

@app.get("/api/news/feeds")
async def news_feeds(user=Depends(current_user)):
    return {"feeds": [{"id":key,"label":value[0],"url":value[1]} for key,value in news.FEEDS.items()]}

@app.get("/api/news")
async def read_news(feed: str = "bbc", user=Depends(current_user)):
    return await news.read_feed(feed)

SETTINGS_FIELDS = json.loads((BASE / "settings-fields.json").read_text())


@app.get("/api/utilities/devices")
async def utility_devices(user=Depends(current_user)):
    async with _sync_lock:
        if not await asyncio.to_thread(github_sync.ensure_current, 2.0):
            raise HTTPException(503, "github_refresh_failed_retry")
        record = await usersync.get_scope(user["id"], "devices")
    return {"devices": [{"id": key, **value} for key, value in record.get("devices", {}).items()]}


@app.get("/api/utilities/{device_id}")
async def get_utilities(device_id: str, user=Depends(current_user)):
    async with _sync_lock:
        if not await asyncio.to_thread(github_sync.ensure_current, 2.0):
            raise HTTPException(503, "github_refresh_failed_retry")
        record = await usersync.get_scope(user["id"], "utilities")
    return {"serverNowMillis": int(time.time()*1000), "items": [row for row in record.get("items", {}).values() if row.get("deviceId") == device_id]}


@app.put("/api/utilities/{device_id}/snapshot")
async def utility_snapshot(device_id: str, body: dict, user=Depends(current_user)):
    timers = body.get("timers", [])
    if not isinstance(timers, list) or len(timers) > 100:
        raise HTTPException(400, "invalid_timer_snapshot")
    clean = []
    for timer in timers:
        if not isinstance(timer, dict) or type(timer.get("id")) is not int or timer["id"] <= 0:
            raise HTTPException(400, "invalid_timer_snapshot")
        if timer.get("state") not in {"RUNNING", "PAUSED", "FINISHED"}:
            raise HTTPException(400, "invalid_timer_state")
        remaining = timer.get("remainingMillis")
        total = timer.get("totalSeconds")
        if type(remaining) is not int or type(total) is not int or not 0 <= remaining <= 86400000 or not 1 <= total <= 86400:
            raise HTTPException(400, "invalid_timer_snapshot")
        clean.append({"id":timer["id"], "state":timer["state"], "remainingMillis":remaining,
                      "totalSeconds":total, "label":str(timer.get("label", ""))[:120]})
    stopwatch = body.get("stopwatch", {})
    if not isinstance(stopwatch, dict) or stopwatch.get("state", "IDLE") not in {"IDLE", "PAUSED", "RUNNING"}:
        raise HTTPException(400, "invalid_stopwatch_snapshot")
    elapsed = stopwatch.get("elapsedMillis", 0)
    laps = stopwatch.get("laps", [])
    if type(elapsed) is not int or not 0 <= elapsed <= 315360000000 or not isinstance(laps, list) or len(laps) > 1000:
        raise HTTPException(400, "invalid_stopwatch_snapshot")
    clean_laps = []
    for lap in laps:
        if not isinstance(lap, dict) or any(type(lap.get(k)) is not int or lap[k] < 0 for k in ("number", "splitMillis", "totalMillis")):
            raise HTTPException(400, "invalid_stopwatch_lap")
        clean_laps.append({k: lap[k] for k in ("number", "splitMillis", "totalMillis")})
    clean_stopwatch = {"state": stopwatch.get("state", "IDLE"), "elapsedMillis": elapsed, "laps": clean_laps}
    async with _sync_lock:
        if not await asyncio.to_thread(github_sync.pull_data):
            raise HTTPException(503, "github_pull_failed_retry")
        devices = await usersync.get_scope(user["id"], "devices")
        if device_id not in devices.get("devices", {}):
            raise HTTPException(404, "device_not_registered")
        record = await usersync.get_scope(user["id"], "utilities")
        items = record.setdefault("items", {})
        key = "snapshot-" + device_id
        prior = items.get(key, {})
        now = int(time.time()*1000)
        row = {"id":key, "deviceId":device_id, "payload":{"timers":clean, "stopwatch":clean_stopwatch, "observedServerMillis":now},
               "version":prior.get("version",0)+1, "updated_at":usersync.now_utc()}
        items[key] = row
        await usersync.save_scope(user["id"], "utilities", record)
        if not await asyncio.to_thread(github_sync.push_data):
            raise HTTPException(503, "github_sync_failed_retry")
    return row


@app.post("/api/utilities/commands")
async def utility_command(body: dict, user=Depends(current_user)):
    command = utility_control.validate(body)
    async with _sync_lock:
        if not await asyncio.to_thread(github_sync.pull_data):
            raise HTTPException(503, "github_pull_failed_retry")
        devices = await usersync.get_scope(user["id"], "devices")
        if command["deviceId"] not in devices.get("devices", {}):
            raise HTTPException(404, "device_not_registered")
        record = await usersync.get_scope(user["id"], "utilities")
        items = record.setdefault("items", {})
        key = "command-" + command["commandId"]
        existing = items.get(key)
        if existing:
            if existing.get("command") != command:
                raise HTTPException(409, "command_id_reused")
            return existing
        # Only versioned IDs encode an immutable issuance time. Legacy UUIDs
        # keep their tombstones: evicting those would allow replay as new.
        cutoff=int(time.time()*1000)-86400000
        for old_key,old_row in list(items.items()):
            old_id=old_row.get('command',{}).get('commandId','')
            if re.fullmatch(r'v2-\d{13}-[a-f0-9]{32}',old_id) and int(old_id.split('-')[1])<cutoff:
                del items[old_key]
        if len(items) >= 10000:
            raise HTTPException(409, "utility_history_full")
        if sum(row.get("status") == "pending" and row.get("expiresMillis",0)>int(time.time()*1000) for row in items.values()) >= 100:
            raise HTTPException(409, "too_many_pending_commands")
        row = {"id":key, "deviceId":command["deviceId"], "command":command,
               "status":"pending", "createdMillis":int(time.time()*1000),
               "expiresMillis":min(int(time.time()*1000)+120000, int(command["commandId"].split("-")[1])+120000) if command["commandId"].startswith("v2-") else int(time.time()*1000)+120000,
               "version":1, "updated_at":usersync.now_utc()}
        items[key] = row
        await usersync.save_scope(user["id"], "utilities", record)
        if not await asyncio.to_thread(github_sync.push_data):
            raise HTTPException(503, "github_sync_failed_retry")
    return row


@app.post("/api/utilities/{device_id}/ack")
async def utility_ack(device_id: str, body: dict, user=Depends(current_user)):
    if body.get("status") not in {"applied", "rejected", "expired"}:
        raise HTTPException(400, "invalid_command_status")
    async with _sync_lock:
        if not await asyncio.to_thread(github_sync.pull_data):
            raise HTTPException(503, "github_pull_failed_retry")
        record = await usersync.get_scope(user["id"], "utilities")
        key = "command-" + str(body.get("commandId", ""))
        row = record.get("items", {}).get(key)
        if not row or row.get("deviceId") != device_id:
            raise HTTPException(404, "command_not_found")
        if row.get("status") == "pending":
            row.update(status=body["status"], version=row["version"]+1, updated_at=usersync.now_utc(), result=str(body.get("result", ""))[:200])
            await usersync.save_scope(user["id"], "utilities", record)
            if not await asyncio.to_thread(github_sync.push_data):
                raise HTTPException(503, "github_sync_failed_retry")
    return row


@app.get("/api/settings")
async def get_settings(user=Depends(current_user)):
    async with _sync_lock:
        if not await asyncio.to_thread(github_sync.ensure_current, 2.0):
            raise HTTPException(503, "github_refresh_failed_retry")
        record = await usersync.get_scope(user["id"], "settings")
    row = record.get("items", {}).get("preferences", {})
    return {"payload": row.get("payload", {}), "version": row.get("version", 0), "updatedAt": row.get("updated_at", "")}


@app.put("/api/settings")
async def put_settings(body: AlarmWrite, user=Depends(current_user)):
    payload = copy.deepcopy(body.payload)
    for key, value in payload.items():
        kind = SETTINGS_FIELDS.get(key)
        if kind is None:
            raise HTTPException(400, "unsupported_settings_field:" + key)
        valid = (kind == "Boolean" and isinstance(value, bool)) or (kind == "String" and isinstance(value, str) and len(value) <= 4096) or (kind in {"Int", "Long"} and isinstance(value, int) and not isinstance(value, bool)) or (kind == "Double" and isinstance(value, (float, int)) and not isinstance(value, bool))
        if not valid:
            raise HTTPException(400, "invalid_settings_type:" + key)
    if "worldClockZones" in payload:
        zones = payload["worldClockZones"].split("|") if payload["worldClockZones"] else []
        if len(zones) > 40 or len(zones) != len(set(zones)):
            raise HTTPException(400, "invalid_world_clock_zones")
        try:
            for zone in zones:
                ZoneInfo(zone)
        except (ZoneInfoNotFoundError, ValueError):
            raise HTTPException(400, "invalid_world_clock_zone")
    ranges = {'defaultSnoozeDuration': [1, 180], 'defaultGradualVolume': [0, 300], 'autoSilenceMinutes': [0, 240], 'bedtimeHour': [0, 23], 'bedtimeMinute': [0, 59], 'sleepGoalHours': [1, 16], 'sleepGoalMinutes': [0, 59], 'bedtimeReminderMinutes': [0, 180], 'sleepSoundTimerMinutes': [0, 240], 'sleepSoundFadeSeconds': [5, 600], 'napDefaultMinutes': [1, 180], 'cancellationLockMinutes': [0, 120], 'holdToDismissMillis': [500, 5000], 'challengeBypassDelaySeconds': [10, 120], 'challengeAudioDuckPercent': [10, 80], 'pauseUntilMillis': [0, 9223372036854775807], 'vacationStartMillis': [0, 9223372036854775807], 'vacationEndMillis': [0, 9223372036854775807], 'bedtimeStayUpLateUntilMillis': [0, 9223372036854775807]}
    for key, (low, high) in ranges.items():
        if key in payload and not low <= payload[key] <= high:
            raise HTTPException(400, "invalid_settings_range:" + key)
    for key, values in {"temperatureUnit":{"fahrenheit","celsius"}, "firingControlMode":{"hybrid","swipe","buttons"}}.items():
        if key in payload and payload[key] not in values:
            raise HTTPException(400, "invalid_settings_choice:" + key)
    if payload.get("vacationModeEnabled") and not 0 < payload.get("vacationStartMillis",0) < payload.get("vacationEndMillis",0):
        raise HTTPException(400, "invalid_vacation_window")
    async with _sync_lock:
        if not await asyncio.to_thread(github_sync.pull_data):
            raise HTTPException(503, "github_pull_failed_retry")
        record = await usersync.get_scope(user["id"], "settings")
        current = record.get("items", {}).get("preferences", {})
        version = int(current.get("version", 0))
        if body.expectedVersion != version:
            raise HTTPException(409, "version_conflict")
        row = {"id":"preferences", "payload":payload, "version":version+1, "updated_at":usersync.now_utc(), "deleted_at":None}
        record["items"] = {"preferences":row}
        await usersync.save_scope(user["id"], "settings", record)
        if not await asyncio.to_thread(github_sync.push_data):
            raise HTTPException(503, "github_sync_failed_retry")
    return {"payload":payload, "version":row["version"], "updatedAt":row["updated_at"]}


@app.get("/api/alarms")
async def get_alarms(
    since: str = "1970-01-01T00:00:00Z",
    user=Depends(current_user),
):
    # Every cloud read reconciles to the durable GitHub HEAD first. A lightweight
    # cached HEAD probe makes this safe for the 2-second Android/web watchdogs:
    # a clone only happens when the durable commit actually advances.
    async with _sync_lock:
        current = await asyncio.to_thread(github_sync.ensure_current, 2.0)
        if not current:
            raise HTTPException(503, "github_refresh_failed_retry")
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
    challenge_rules.validate(body.payload)
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