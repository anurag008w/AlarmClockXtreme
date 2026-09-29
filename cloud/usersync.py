from __future__ import annotations

import asyncio
import hashlib
import hmac
import json
import re
import secrets
from datetime import datetime, timezone
from pathlib import Path

import github_sync

DATA_DIR = github_sync.DATA_DIR
USERS_FILE = DATA_DIR / "users.json"
SYNC_DIR = DATA_DIR / "sync"
_LOCK = asyncio.Lock()
_SAFE_RE = re.compile(r"[^A-Za-z0-9_.-]")


def now_utc() -> str:
    return datetime.now(timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")


def safe_key(value: str) -> str:
    return _SAFE_RE.sub("_", value)[:100]


def _read(path: Path, default):
    if not path.exists():
        return default
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return default


def _write(path: Path, value) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_suffix(path.suffix + ".tmp")
    tmp.write_text(json.dumps(value, ensure_ascii=False, indent=2), encoding="utf-8")
    tmp.replace(path)


def _users() -> list[dict]:
    raw = _read(USERS_FILE, {"users": []})
    return raw.get("users", []) if isinstance(raw, dict) else []


def _save_users(users: list[dict]) -> None:
    _write(USERS_FILE, {"schema": 1, "users": users})


def _user_file(user_id: str, scope: str) -> Path:
    return SYNC_DIR / safe_key(user_id) / (safe_key(scope) + ".json")


def _hash_password(password: str, salt: bytes | None = None) -> str:
    salt = salt or secrets.token_bytes(16)
    digest = hashlib.scrypt(
        password.encode(), salt=salt, n=2**14, r=8, p=1, dklen=32
    )
    return "scrypt$" + salt.hex() + "$" + digest.hex()


def verify_password(password: str, encoded: str) -> bool:
    try:
        prefix, salt_hex, digest_hex = encoded.split("$", 2)
        if prefix != "scrypt":
            return False
        salt = bytes.fromhex(salt_hex)
        actual = hashlib.scrypt(
            password.encode(), salt=salt, n=2**14, r=8, p=1, dklen=32
        ).hex()
        return hmac.compare_digest(actual, digest_hex)
    except (ValueError, TypeError):
        return False


async def register(email: str, password: str) -> dict:
    email = email.strip().lower()
    async with _LOCK:
        users = _users()
        if any(u.get("email") == email for u in users):
            raise ValueError("email_exists")

        user = {
            "id": secrets.token_hex(16),
            "email": email,
            "password_hash": _hash_password(password),
            "created_at": now_utc(),
            "updated_at": now_utc(),
        }
        users.append(user)
        _save_users(users)
        _write(_user_file(user["id"], "alarms"), {
            "schema": 1, "updated_at": now_utc(), "items": {}
        })
        return user


async def authenticate(email: str, password: str) -> dict | None:
    email = email.strip().lower()
    async with _LOCK:
        for user in _users():
            if user.get("email") == email and verify_password(
                password, user.get("password_hash", "")
            ):
                return user
    return None


async def get_scope(user_id: str, scope: str = "alarms") -> dict:
    async with _LOCK:
        return _read(_user_file(user_id, scope), {
            "schema": 1, "updated_at": "", "items": {}
        })


async def save_scope(user_id: str, scope: str, value: dict) -> dict:
    async with _LOCK:
        record = dict(value)
        record["schema"] = int(record.get("schema", 1))
        record["updated_at"] = now_utc()
        _write(_user_file(user_id, scope), record)
        return record


async def status(user_id: str, scope: str = "alarms") -> dict:
    async with _LOCK:
        record = _read(_user_file(user_id, scope), {})
        return {
            "exists": bool(record),
            "updated_at": str(record.get("updated_at", "")),
        }