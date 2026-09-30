from __future__ import annotations

import hashlib
import json
import logging
import os
import shutil
import subprocess
import tempfile
import time
from pathlib import Path
from typing import Any

log = logging.getLogger("alarmclockxtreme.github_sync")

PROJECT_ROOT = Path(__file__).resolve().parent
DATA_DIR = Path(os.environ.get("ALARM_DATA_DIR", str(PROJECT_ROOT / "data")))
DATA_REPO = os.environ.get("GITHUB_REPO", "anurag008w/smartrotator-data").strip()
DATA_SUBDIR = os.environ.get("GITHUB_DATA_SUBDIR", "alarmclockxtreme").strip("/") or "alarmclockxtreme"
GH_TOKEN = os.environ.get("GH_TOKEN", "").strip()
SYNC_ENABLED = os.environ.get("GITHUB_SYNC_ENABLED", "true").strip().lower() != "false"

try:
    SYNC_INTERVAL = max(10, int(os.environ.get("GITHUB_SYNC_INTERVAL", "10")))
except ValueError:
    SYNC_INTERVAL = 10

PUSH_RETRIES = 3

_last_push_fingerprint = ""
_last_pull_ok = False
# Commit that DATA_DIR was pulled from. A push is refused when GitHub advanced
# since this baseline, preventing stale Render instances from overwriting data.
_base_remote_sha = ""


def _redact(value: str) -> str:
    return value.replace(GH_TOKEN, "***") if GH_TOKEN else value


def _auth_url() -> str:
    return "https://" + GH_TOKEN + "@" + "github.com/" + DATA_REPO + ".git"


def _run(args: list[str], *, cwd: Path | None = None, timeout: int = 90):
    try:
        return subprocess.run(
            args,
            cwd=str(cwd) if cwd else None,
            capture_output=True,
            text=True,
            timeout=timeout,
        )
    except (FileNotFoundError, subprocess.TimeoutExpired) as exc:
        return type("Result", (), {
            "returncode": 127,
            "stdout": "",
            "stderr": str(exc),
        })()


def _read_json(path: Path, default: Any) -> Any:
    if not path.exists():
        return default
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return default


def _write_json(path: Path, value: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_suffix(path.suffix + ".tmp")
    tmp.write_text(
        json.dumps(value, ensure_ascii=False, indent=2),
        encoding="utf-8",
    )
    tmp.replace(path)


def _timestamp(value: Any) -> str:
    return str(value or "")


def _merge_alarm_scope(local: Any, remote: Any) -> dict:
    """
    Merge the alarm dataset at item level instead of replacing the whole file.

    Each alarm row carries its own updated_at and tombstones are first-class
    rows. Newer rows win, so a stale Render copy cannot resurrect a newer
    Android/web edit or delete. This is the critical protection needed when
    more than one Render process/device races through the GitHub-backed store.
    """
    local_record = local if isinstance(local, dict) else {}
    remote_record = remote if isinstance(remote, dict) else {}

    local_items = local_record.get("items", {})
    remote_items = remote_record.get("items", {})
    if not isinstance(local_items, dict):
        local_items = {}
    if not isinstance(remote_items, dict):
        remote_items = {}

    merged_items = dict(remote_items)

    for alarm_id, local_item in local_items.items():
        if not isinstance(local_item, dict):
            continue

        remote_item = merged_items.get(alarm_id)
        if not isinstance(remote_item, dict):
            merged_items[alarm_id] = local_item
            continue

        # Version is the logical mutation clock. Prefer it over wall-clock
        # time so a stale Render filesystem cannot overwrite a newer durable
        # alarm simply because its local retry received a later timestamp.
        local_version = int(local_item.get("version", 0) or 0)
        remote_version = int(remote_item.get("version", 0) or 0)

        local_ts = _timestamp(local_item.get("updated_at"))
        remote_ts = _timestamp(remote_item.get("updated_at"))

        if local_version > remote_version:
            merged_items[alarm_id] = local_item
        elif local_version < remote_version:
            merged_items[alarm_id] = remote_item
        elif local_ts > remote_ts:
            merged_items[alarm_id] = local_item
        elif local_ts == remote_ts:
            # Deterministic safety rule for the unlikely exact tie:
            # tombstones beat live rows, never the other way around.
            if local_item.get("deleted_at") and not remote_item.get("deleted_at"):
                merged_items[alarm_id] = local_item

    local_updated = _timestamp(local_record.get("updated_at"))
    remote_updated = _timestamp(remote_record.get("updated_at"))

    return {
        "schema": max(
            int(local_record.get("schema", 1) or 1),
            int(remote_record.get("schema", 1) or 1),
        ),
        "updated_at": max(local_updated, remote_updated),
        "items": merged_items,
    }


def _is_alarm_scope(relative: Path) -> bool:
    parts = relative.parts
    return len(parts) >= 3 and parts[-1] == "alarms.json" and parts[-3] == "sync"


def _overlay_local_data(local_root: Path, remote_root: Path) -> None:
    """
    Start from the fresh GitHub checkout and overlay local changes.

    Alarm scope files are merged per-row. Other local files are copied as
    before, preserving the existing app's persistent data layout while making
    alarm replication race-safe.
    """
    if not local_root.exists():
        return

    for item in local_root.rglob("*"):
        if not item.is_file() or item.name.startswith(".") or item.name.endswith(".tmp"):
            continue

        relative = item.relative_to(local_root)
        destination = remote_root / relative

        if _is_alarm_scope(relative):
            local_alarm = _read_json(item, {"schema": 1, "items": {}})
            remote_alarm = _read_json(destination, {"schema": 1, "items": {}})
            _write_json(destination, _merge_alarm_scope(local_alarm, remote_alarm))
            continue

        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(item, destination)


def _replace_local_data_from(remote_root: Path) -> None:
    DATA_DIR.mkdir(parents=True, exist_ok=True)

    for item in list(DATA_DIR.iterdir()):
        if item.name.startswith("."):
            continue
        if item.is_dir():
            shutil.rmtree(item)
        else:
            item.unlink()

    for item in remote_root.iterdir():
        destination = DATA_DIR / item.name
        if item.is_dir():
            shutil.copytree(item, destination)
        else:
            shutil.copy2(item, destination)


def compute_fingerprint(root: Path | None = None) -> str:
    root = root or DATA_DIR
    digest = hashlib.sha256()
    if not root.exists():
        return ""

    for item in sorted(root.rglob("*")):
        if not item.is_file() or item.name.endswith(".tmp"):
            continue
        try:
            digest.update(str(item.relative_to(root)).encode())
            digest.update(item.read_bytes())
        except OSError:
            continue

    return digest.hexdigest()


def has_data_changed() -> bool:
    return compute_fingerprint() != _last_push_fingerprint


def mark_pushed() -> None:
    global _last_push_fingerprint
    _last_push_fingerprint = compute_fingerprint()


def _ensure_ready() -> bool:
    if not SYNC_ENABLED or not GH_TOKEN or "/" not in DATA_REPO:
        return False
    DATA_DIR.mkdir(parents=True, exist_ok=True)
    return True


def pull_data() -> bool:
    global _last_pull_ok, _base_remote_sha
    if not _ensure_ready():
        _last_pull_ok = False
        return False

    try:
        with tempfile.TemporaryDirectory() as tmp:
            repo = Path(tmp) / "repo"
            clone = _run(
                ["git", "clone", "--depth", "1", _auth_url(), str(repo)],
                timeout=120,
            )
            if clone.returncode != 0:
                _last_pull_ok = False
                log.warning(
                    "github pull failed: %s",
                    _redact(clone.stderr[-500:]),
                )
                return False

            head = _run(["git", "rev-parse", "HEAD"], cwd=repo)
            if head.returncode != 0:
                _last_pull_ok = False
                log.warning("github pull could not resolve HEAD")
                return False
            remote_sha = head.stdout.strip()

            remote = repo / DATA_SUBDIR
            if not remote.exists():
                DATA_DIR.mkdir(parents=True, exist_ok=True)
                _last_pull_ok = True
                mark_pushed()
                _base_remote_sha = remote_sha
                return True

            # Never replace a live alarm dataset with a possibly stale
            # GitHub checkout. Another service/device can push an older whole
            # file while this process is alive. Merge the current local state
            # onto the fresh checkout first; alarm tombstones and newer edits
            # therefore survive an out-of-band rollback.
            remote_fingerprint = compute_fingerprint(remote)
            _overlay_local_data(DATA_DIR, remote)
            merged_fingerprint = compute_fingerprint(remote)
            _replace_local_data_from(remote)

        _last_pull_ok = True
        global _last_push_fingerprint
        if merged_fingerprint == remote_fingerprint:
            mark_pushed()
        else:
            # The pull recovered local state that is newer than GitHub. Keep
            # the durable fingerprint as the pending baseline so the watchdog
            # immediately pushes the repaired merged dataset.
            _last_push_fingerprint = remote_fingerprint
        return True
    except Exception:
        log.exception("github pull crashed")
        _last_pull_ok = False
        return False


def push_data(force: bool = False) -> bool:
    global _base_remote_sha
    if not _ensure_ready():
        return False
    if not _last_pull_ok:
        log.warning("github push blocked because startup pull has not succeeded")
        return False
    if not DATA_DIR.exists():
        return False

    for attempt in range(1, PUSH_RETRIES + 1):
        try:
            with tempfile.TemporaryDirectory() as tmp:
                repo = Path(tmp) / "repo"

                # Always clone the newest GitHub state for every attempt.
                # This makes retries converge instead of retrying against an
                # obsolete base after another device/process has pushed.
                clone = _run(
                    ["git", "clone", "--depth", "1", _auth_url(), str(repo)],
                    timeout=120,
                )
                if clone.returncode != 0:
                    log.warning(
                        "github push clone failed (attempt %d/%d): %s",
                        attempt,
                        PUSH_RETRIES,
                        _redact(clone.stderr[-500:]),
                    )
                    continue

                head = _run(["git", "rev-parse", "HEAD"], cwd=repo)
            if head.returncode != 0:
                return False
            remote_sha = head.stdout.strip()
            if _base_remote_sha and remote_sha != _base_remote_sha:
                log.warning(
                    "github push rejected: remote advanced from %s to %s",
                    _base_remote_sha,
                    remote_sha,
                )
                return False

            target = repo / DATA_SUBDIR
                target.mkdir(parents=True, exist_ok=True)

                # Merge current local state into the fresh durable state. The
                # alarm scope is item-level LWW with tombstone protection.
                _overlay_local_data(DATA_DIR, target)

                _run(
                    ["git", "config", "user.name", "AlarmClockXtreme Sync"],
                    cwd=repo,
                )
                _run(
                    [
                        "git",
                        "config",
                        "user.email",
                        "alarmclockxtreme-sync@users.noreply.github.com",
                    ],
                    cwd=repo,
                )
                _run(["git", "add", "-A"], cwd=repo)

                commit = _run(
                    [
                        "git",
                        "commit",
                        "-m",
                        "alarmclockxtreme sync "
                        + time.strftime("%Y-%m-%d %H:%M:%S UTC"),
                    ],
                    cwd=repo,
                )

                if commit.returncode != 0:
                    # Nothing changed relative to the current GitHub state.
                    _replace_local_data_from(target)
                    mark_pushed()
                    return True

                push_args = ["git", "push", "origin", "HEAD:main"]
                if force:
                    push_args.append("--force-with-lease")

                pushed = _run(push_args, cwd=repo, timeout=120)
                if pushed.returncode != 0:
                    log.warning(
                        "github push failed (attempt %d/%d): %s",
                        attempt,
                        PUSH_RETRIES,
                        _redact(pushed.stderr[-700:]),
                    )
                    continue

                # Make the running service consume exactly the merged dataset
                # that was committed, so web/Android requests immediately read
                # the same state that is now durable in GitHub.
                _replace_local_data_from(target)
                mark_pushed()
                return True

        except Exception:
            log.exception("github push crashed (attempt %d/%d)", attempt, PUSH_RETRIES)

    return False


def status() -> dict:
    return {
        "enabled": _ensure_ready(),
        "repo": DATA_REPO,
        "subdir": DATA_SUBDIR,
        "interval_seconds": SYNC_INTERVAL,
        "data_changed": has_data_changed(),
        "pull_ok": _last_pull_ok,
    }
