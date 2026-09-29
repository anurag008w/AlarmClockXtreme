from __future__ import annotations

import hashlib
import logging
import os
import shutil
import subprocess
import tempfile
import time
from pathlib import Path

log = logging.getLogger("alarmclockxtreme.github_sync")

PROJECT_ROOT = Path(__file__).resolve().parent
DATA_DIR = Path(os.environ.get("ALARM_DATA_DIR", str(PROJECT_ROOT / "data")))
DATA_REPO = os.environ.get("GITHUB_REPO", "anurag008w/smartrotator-data").strip()
DATA_SUBDIR = os.environ.get("GITHUB_DATA_SUBDIR", "alarmclockxtreme").strip("/") or "alarmclockxtreme"
GH_TOKEN = os.environ.get("GH_TOKEN", "").strip()
SYNC_ENABLED = os.environ.get("GITHUB_SYNC_ENABLED", "true").strip().lower() != "false"

try:
    SYNC_INTERVAL = max(60, int(os.environ.get("GITHUB_SYNC_INTERVAL", "180")))
except ValueError:
    SYNC_INTERVAL = 180

_last_push_fingerprint = ""
_last_pull_ok = False


def _redact(value: str) -> str:
    return value.replace(GH_TOKEN, "***") if GH_TOKEN else value


def _auth_url() -> str:
    return "https://" + GH_TOKEN + "@github.com/" + DATA_REPO + ".git"


def _run(args: list[str], *, cwd: Path | None = None, timeout: int = 90):
    try:
        return subprocess.run(
            args, cwd=str(cwd) if cwd else None, capture_output=True,
            text=True, timeout=timeout
        )
    except (FileNotFoundError, subprocess.TimeoutExpired) as exc:
        return type("Result", (), {
            "returncode": 127,
            "stdout": "",
            "stderr": str(exc),
        })()


def compute_fingerprint() -> str:
    digest = hashlib.sha256()
    if not DATA_DIR.exists():
        return ""
    for item in sorted(DATA_DIR.rglob("*")):
        if not item.is_file() or item.name.endswith(".tmp"):
            continue
        try:
            digest.update(str(item.relative_to(DATA_DIR)).encode())
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
    global _last_pull_ok
    if not _ensure_ready():
        _last_pull_ok = False
        return False

    try:
        with tempfile.TemporaryDirectory() as tmp:
            repo = Path(tmp) / "repo"
            clone = _run(["git", "clone", "--depth", "1", _auth_url(), str(repo)], timeout=120)
            if clone.returncode != 0:
                _last_pull_ok = False
                log.warning("github pull failed: %s", _redact(clone.stderr[-500:]))
                return False

            remote = repo / DATA_SUBDIR
            if not remote.exists():
                DATA_DIR.mkdir(parents=True, exist_ok=True)
                _last_pull_ok = True
                mark_pushed()
                return True

            for item in DATA_DIR.iterdir():
                if item.name.startswith("."):
                    continue
                if item.is_dir():
                    shutil.rmtree(item)
                else:
                    item.unlink()

            for item in remote.iterdir():
                target = DATA_DIR / item.name
                if item.is_dir():
                    shutil.copytree(item, target)
                else:
                    shutil.copy2(item, target)

        _last_pull_ok = True
        mark_pushed()
        return True
    except Exception:
        log.exception("github pull crashed")
        _last_pull_ok = False
        return False


def push_data(force: bool = False) -> bool:
    if not _ensure_ready():
        return False
    if not _last_pull_ok:
        log.warning("github push blocked because startup pull has not succeeded")
        return False
    if not DATA_DIR.exists():
        return False

    try:
        with tempfile.TemporaryDirectory() as tmp:
            repo = Path(tmp) / "repo"
            clone = _run(["git", "clone", "--depth", "1", _auth_url(), str(repo)], timeout=120)
            if clone.returncode != 0:
                log.warning("github push clone failed: %s", _redact(clone.stderr[-500:]))
                return False

            target = repo / DATA_SUBDIR
            if target.exists():
                shutil.rmtree(target)
            target.mkdir(parents=True, exist_ok=True)

            for item in DATA_DIR.iterdir():
                if item.name.startswith("."):
                    continue
                dst = target / item.name
                if item.is_dir():
                    shutil.copytree(item, dst)
                else:
                    shutil.copy2(item, dst)

            _run(["git", "config", "user.name", "AlarmClockXtreme Sync"], cwd=repo)
            _run(["git", "config", "user.email", "alarmclockxtreme-sync@users.noreply.github.com"], cwd=repo)
            _run(["git", "add", "-A"], cwd=repo)

            commit = _run(
                ["git", "commit", "-m", "alarmclockxtreme sync " + time.strftime("%Y-%m-%d %H:%M:%S UTC")],
                cwd=repo
            )
            if commit.returncode != 0:
                mark_pushed()
                return True

            push_args = ["git", "push", "origin", "HEAD:main"]
            if force:
                push_args.append("--force-with-lease")
            pushed = _run(push_args, cwd=repo, timeout=120)
            if pushed.returncode != 0:
                log.warning("github push failed: %s", _redact(pushed.stderr[-700:]))
                return False

        mark_pushed()
        return True
    except Exception:
        log.exception("github push crashed")
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