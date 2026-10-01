#!/usr/bin/env python3
"""Atomically update AlarmClockXtreme version across all declared project files."""

from __future__ import annotations

import re
import sys
from pathlib import Path

SCRIPTS_DIR = Path(__file__).resolve().parent
ROOT_DIR = SCRIPTS_DIR.parent
sys.path.insert(0, str(SCRIPTS_DIR))

from verify_release_metadata import verify_release_metadata, VERSION_PATTERN


def read_text(file_path: Path) -> tuple[str, str]:
    raw = file_path.read_bytes()
    newline = "\r\n" if b"\r\n" in raw else "\n"
    text = raw.decode("utf-8")
    return text, newline


def write_text(file_path: Path, content: str, newline: str) -> None:
    normalized = content.replace("\r\n", "\n")
    if newline == "\r\n":
        final_content = normalized.replace("\n", "\r\n")
    else:
        final_content = normalized
    file_path.write_bytes(final_content.encode("utf-8"))


def bump_version(
    new_version_name: str,
    new_version_code: int | None = None,
    release_notes: str | None = None,
) -> int:
    new_version_name = new_version_name.strip().lstrip("v")
    if not re.match(rf"^{VERSION_PATTERN}$", new_version_name):
        raise ValueError(f"Invalid semantic version format: {new_version_name}")

    app_gradle_path = ROOT_DIR / "app/build.gradle.kts"
    app_gradle_content, app_gradle_nl = read_text(app_gradle_path)

    current_code_match = re.search(
        r'^\s*versionCode\s*=\s*([0-9]+)\s*$', app_gradle_content, re.MULTILINE
    )
    if not current_code_match:
        raise ValueError("Could not find current versionCode in app/build.gradle.kts")
    current_version_code = int(current_code_match.group(1))

    if new_version_code is None:
        new_version_code = current_version_code + 1

    print(f"Bumping version to {new_version_name} (versionCode: {new_version_code})...")

    # 1. app/build.gradle.kts
    content = re.sub(
        rf"^// AlarmClockXtreme v{VERSION_PATTERN}$",
        f"// AlarmClockXtreme v{new_version_name}",
        app_gradle_content,
        flags=re.MULTILINE,
    )
    content = re.sub(
        rf'^\s*versionName\s*=\s*"{VERSION_PATTERN}"\s*$',
        f'        versionName = "{new_version_name}"',
        content,
        flags=re.MULTILINE,
    )
    content = re.sub(
        r"^\s*versionCode\s*=\s*[0-9]+\s*$",
        f"        versionCode = {new_version_code}",
        content,
        flags=re.MULTILINE,
    )
    write_text(app_gradle_path, content, app_gradle_nl)

    # 2. build.gradle.kts
    root_gradle_path = ROOT_DIR / "build.gradle.kts"
    root_content, root_nl = read_text(root_gradle_path)
    content = re.sub(
        rf"^// AlarmClockXtreme v{VERSION_PATTERN}$",
        f"// AlarmClockXtreme v{new_version_name}",
        root_content,
        flags=re.MULTILINE,
    )
    write_text(root_gradle_path, content, root_nl)

    # 3. wear/build.gradle.kts
    wear_gradle_path = ROOT_DIR / "wear/build.gradle.kts"
    wear_content, wear_nl = read_text(wear_gradle_path)
    content = re.sub(
        rf'^\s*versionName\s*=\s*"{VERSION_PATTERN}"\s*$',
        f'        versionName = "{new_version_name}"',
        wear_content,
        flags=re.MULTILINE,
    )
    content = re.sub(
        r"^\s*versionCode\s*=\s*[0-9]+\s*$",
        f"        versionCode = {new_version_code}",
        content,
        flags=re.MULTILINE,
    )
    write_text(wear_gradle_path, content, wear_nl)

    # 4. README.md
    readme_path = ROOT_DIR / "README.md"
    readme_content, readme_nl = read_text(readme_path)
    content = re.sub(
        rf"shields\.io/badge/version-{VERSION_PATTERN}-([A-Za-z0-9]+)",
        f"shields.io/badge/version-{new_version_name}-\\1",
        readme_content,
    )
    content = re.sub(
        rf"AlarmClockXtreme-v{VERSION_PATTERN}-play-release\.apk",
        f"AlarmClockXtreme-v{new_version_name}-play-release.apk",
        content,
    )
    write_text(readme_path, content, readme_nl)

    # 5. CHANGELOG.md
    changelog_path = ROOT_DIR / "CHANGELOG.md"
    changelog_content, changelog_nl = read_text(changelog_path)
    if f"## [{new_version_name}]" not in changelog_content:
        if release_notes and release_notes.strip():
            bullets = "\n".join(
                f"- {line.strip()}"
                for line in release_notes.strip().splitlines()
                if line.strip()
            )
            new_entry = (
                f"## [{new_version_name}]\n\n"
                f"{bullets}\n"
                f"- Release: v{new_version_name} (versionCode {new_version_code}).\n\n"
            )
        else:
            new_entry = (
                f"## [{new_version_name}]\n\n"
                f"- Automated release update for v{new_version_name}.\n"
                f"- Release: v{new_version_name} (versionCode {new_version_code}).\n\n"
            )
        first_entry_match = re.search(r"^## \[[0-9.]+\].*$", changelog_content, re.MULTILINE)
        if first_entry_match:
            pos = first_entry_match.start()
            changelog_content = changelog_content[:pos] + new_entry + changelog_content[pos:]
        else:
            changelog_content += "\n\n" + new_entry
        write_text(changelog_path, changelog_content, changelog_nl)

    # 6. scripts/verify_api37_release.py
    api37_path = ROOT_DIR / "scripts/verify_api37_release.py"
    api37_content, api37_nl = read_text(api37_path)
    content = re.sub(
        rf'^EXPECTED_VERSION_NAME\s*=\s*"{VERSION_PATTERN}"$',
        f'EXPECTED_VERSION_NAME = "{new_version_name}"',
        api37_content,
        flags=re.MULTILINE,
    )
    content = re.sub(
        r'^EXPECTED_VERSION_CODE\s*=\s*"[0-9]+"$',
        f'EXPECTED_VERSION_CODE = "{new_version_code}"',
        content,
        flags=re.MULTILINE,
    )
    write_text(api37_path, content, api37_nl)

    # 7. metadata/com.sysadmindoc.alarmclock.yml & metadata/en-US/fdroid.yml
    for metadata_rel in ("metadata/com.sysadmindoc.alarmclock.yml", "metadata/en-US/fdroid.yml"):
        meta_path = ROOT_DIR / metadata_rel
        meta_content, meta_nl = read_text(meta_path)
        content = re.sub(
            rf"^CurrentVersion:\s*{VERSION_PATTERN}\s*$",
            f"CurrentVersion: {new_version_name}",
            meta_content,
            flags=re.MULTILINE,
        )
        content = re.sub(
            r"^CurrentVersionCode:\s*[0-9]+\s*$",
            f"CurrentVersionCode: {new_version_code}",
            content,
            flags=re.MULTILINE,
        )
        content = re.sub(
            rf"(\s+-\s+versionName:\s*){VERSION_PATTERN}",
            rf"\g<1>{new_version_name}",
            content,
        )
        content = re.sub(
            r"(\s+versionCode:\s*)[0-9]+",
            rf"\g<1>{new_version_code}",
            content,
        )
        content = re.sub(
            rf"(\s+commit:\s*v){VERSION_PATTERN}",
            rf"\g<1>{new_version_name}",
            content,
        )
        write_text(meta_path, content, meta_nl)

    # Verify everything matches perfectly
    snapshot = verify_release_metadata(ROOT_DIR)
    print(f"Successfully bumped and verified: v{snapshot.version_name} ({snapshot.version_code})")
    return new_version_code


def main() -> None:
    if len(sys.argv) < 2:
        print("Usage: python3 bump_version.py <version_name> [version_code] [release_notes]")
        print("Example: python3 bump_version.py 1.15.46 148 'Fixed notifications'")
        sys.exit(1)

    version_name = sys.argv[1]
    version_code = int(sys.argv[2]) if len(sys.argv) > 2 and sys.argv[2].strip().isdigit() else None
    release_notes = sys.argv[3] if len(sys.argv) > 3 else None

    try:
        bump_version(version_name, version_code, release_notes)
    except Exception as e:
        print(f"Error bumping version: {e}", file=sys.stderr)
        sys.exit(1)


if __name__ == "__main__":
    main()
