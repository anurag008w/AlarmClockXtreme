#!/usr/bin/env python3
"""Print the user-facing notes for one version from WhatsNewNotes.kt as markdown bullets.

Usage: release_notes.py <version> [path-to-WhatsNewNotes.kt]
Prints nothing (exit 0) when the version has no entry, so the caller can fall back.
"""
import re
import sys

version = sys.argv[1]
path = sys.argv[2] if len(sys.argv) > 2 else "app/src/main/java/com/sysadmindoc/alarmclock/util/WhatsNewNotes.kt"
lines = open(path, encoding="utf-8").read().splitlines()
start = '"%s" to listOf(' % version
out = []
inside = False
for line in lines:
    s = line.strip()
    if not inside:
        if s.startswith(start):
            inside = True
        continue
    if s.startswith(")"):
        break
    m = re.match(r'^"(.*)",?$', s)
    if m:
        out.append("- " + m.group(1).replace('\\"', '"').replace("\\\\", "\\"))
print("\n".join(out))
