#!/usr/bin/env python3
"""Drives the installed app on an emulator: walks onboarding, visits each tab, saves screenshots."""
import re, subprocess, sys, time, xml.etree.ElementTree as ET

OUT = sys.argv[1]
PKG = "com.sysadmindoc.alarmclock"

def adb(*a, **kw):
    return subprocess.run(["adb", *a], capture_output=True, **kw)

def shot(name):
    data = adb("exec-out", "screencap", "-p").stdout
    open(f"{OUT}/{name}.png", "wb").write(data)

def nodes():
    adb("shell", "uiautomator", "dump", "/sdcard/u.xml")
    xml = adb("shell", "cat", "/sdcard/u.xml").stdout.decode("utf-8", "ignore")
    try:
        root = ET.fromstring(xml[xml.index("<?xml"):] if "<?xml" in xml else xml)
    except Exception:
        return []
    out = []
    for n in root.iter("node"):
        m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", n.get("bounds", ""))
        if m:
            x1, y1, x2, y2 = map(int, m.groups())
            out.append(((n.get("text") or "") + "|" + (n.get("content-desc") or ""), (x1 + x2) // 2, (y1 + y2) // 2, y1))
    return out

def tap_first(words, min_y=0):
    for label, x, y, y1 in nodes():
        low = label.lower()
        if y1 >= min_y and any(w in low for w in words):
            adb("shell", "input", "tap", str(x), str(y))
            return label
    return None

time.sleep(6)
shot("00_launch")
for i in range(1, 9):
    hit = tap_first(["while using the app", "allow", "skip", "get started", "continue", "next", "done", "got it", "not now", "later", "no thanks", "dismiss", "close"])
    time.sleep(2)
    shot(f"{i:02d}_step")
    if not hit:
        break
size = adb("shell", "wm", "size").stdout.decode()
m = re.search(r"(\d+)x(\d+)", size)
h = int(m.group(2)) if m else 1920
for tab in ["Today", "Alarms", "Timer", "World", "News", "Settings"]:
    tap_first([tab.lower()], min_y=int(h * 0.8))
    time.sleep(3)
    shot(f"tab_{tab}")

def back():
    adb("shell", "input", "keyevent", "4")
    time.sleep(2)

def visit(tab, words, name, min_y=0):
    tap_first([tab.lower()], min_y=int(h * 0.8))
    time.sleep(2)
    hit = tap_first(words, min_y=min_y)
    time.sleep(3)
    if hit:
        shot(name)
        back()

visit("Alarms", ["wake up", "weekday"], "page_alarm_edit", min_y=int(h * 0.12))
visit("Alarms", ["new alarm"], "page_new_alarm", min_y=int(h * 0.12))
visit("Timer", ["stopwatch"], "page_stopwatch")
visit("Settings", ["defaults"], "page_settings_defaults", min_y=int(h * 0.1))
visit("Settings", ["personalization"], "page_settings_personal", min_y=int(h * 0.1))
visit("Settings", ["integrations"], "page_settings_integrations", min_y=int(h * 0.1))
tap_first(["settings"], min_y=int(h * 0.8))
time.sleep(2)
adb("shell", "input", "swipe", "540", str(int(h * 0.8)), "540", str(int(h * 0.25)), "400")
time.sleep(2)
shot("page_settings_scrolled")
