"""Firebase Cloud Messaging sender (HTTP v1) for AlarmClockXtreme Cloud.

Sends data-only "sync" wake-ups so the Android app can pull alarm/settings
changes in the background. The message carries no alarm content: the phone
always fetches the real state from the cloud API, so a lost, duplicated or
spoofed push can never change an alarm by itself.

Configuration (Render env):
  FCM_SERVICE_ACCOUNT_JSON  full service-account JSON (secret)
Without it every call is a safe no-op.
"""
from __future__ import annotations

import json
import logging
import os
import time
from typing import Any, Optional

import httpx
import jwt

log = logging.getLogger("acx.fcm")

SCOPE = "https://www.googleapis.com/auth/firebase.messaging"
DEFAULT_TOKEN_URI = "https://oauth2.googleapis.com/token"
MAX_TOKEN_LEN = 4096
_UNREGISTERED = {"UNREGISTERED", "INVALID_ARGUMENT", "NOT_FOUND"}

_cached: dict[str, Any] = {"token": "", "exp": 0.0}


def _credentials() -> Optional[dict]:
    raw = os.environ.get("FCM_SERVICE_ACCOUNT_JSON", "").strip()
    if not raw:
        return None
    try:
        data = json.loads(raw)
    except ValueError:
        log.error("FCM_SERVICE_ACCOUNT_JSON is not valid JSON")
        return None
    if not all(data.get(k) for k in ("client_email", "private_key", "project_id")):
        log.error("FCM_SERVICE_ACCOUNT_JSON is missing required fields")
        return None
    return data


def is_configured() -> bool:
    return _credentials() is not None


def valid_push_token(value: Any) -> bool:
    return isinstance(value, str) and 20 <= len(value) <= MAX_TOKEN_LEN and not any(c.isspace() for c in value)


async def _access_token(client: httpx.AsyncClient, creds: dict) -> str:
    now = time.time()
    if _cached["token"] and _cached["exp"] - 60 > now:
        return _cached["token"]
    uri = creds.get("token_uri") or DEFAULT_TOKEN_URI
    assertion = jwt.encode(
        {"iss": creds["client_email"], "scope": SCOPE, "aud": uri,
         "iat": int(now), "exp": int(now) + 3300},
        creds["private_key"], algorithm="RS256",
        headers={"kid": creds.get("private_key_id", "")} if creds.get("private_key_id") else None,
    )
    resp = await client.post(uri, data={
        "grant_type": "urn:ietf:params:oauth:grant-type:jwt-bearer",
        "assertion": assertion,
    })
    resp.raise_for_status()
    body = resp.json()
    _cached["token"] = body["access_token"]
    _cached["exp"] = now + int(body.get("expires_in", 3300))
    return _cached["token"]


def build_message(token: str, reason: str = "sync", validate_only: bool = False) -> dict:
    message = {
        "token": token,
        "data": {"type": "sync", "reason": str(reason)[:32]},
        "android": {"priority": "HIGH", "ttl": "3600s", "collapse_key": "acx_sync"},
    }
    body: dict[str, Any] = {"message": message}
    if validate_only:
        body["validate_only"] = True
    return body


async def send_sync(tokens: dict[str, str], reason: str = "sync",
                    validate_only: bool = False) -> dict:
    """Send a wake-up to {device_id: token}. Returns {"sent": n, "dead": [device_id]}."""
    result: dict[str, Any] = {"sent": 0, "dead": [], "failed": 0}
    creds = _credentials()
    if not creds or not tokens:
        return result
    url = f"https://fcm.googleapis.com/v1/projects/{creds['project_id']}/messages:send"
    try:
        async with httpx.AsyncClient(timeout=10) as client:
            access = await _access_token(client, creds)
            headers = {"Authorization": f"Bearer {access}"}
            for device_id, token in tokens.items():
                resp = await client.post(url, headers=headers,
                                         json=build_message(token, reason, validate_only))
                if resp.status_code == 200:
                    result["sent"] += 1
                    continue
                status = ""
                try:
                    err = resp.json().get("error", {})
                    status = err.get("status", "")
                    for d in err.get("details", []):
                        if d.get("errorCode"):
                            status = d["errorCode"]
                except ValueError:
                    pass
                if resp.status_code == 404 or status in ("UNREGISTERED",):
                    result["dead"].append(device_id)
                else:
                    result["failed"] += 1
                    log.warning("FCM send failed device=%s http=%s status=%s",
                                device_id, resp.status_code, status)
    except Exception as exc:  # never let push break an API write
        result["failed"] += 1
        log.warning("FCM send error: %s", type(exc).__name__)
        _cached["token"] = ""
    return result
