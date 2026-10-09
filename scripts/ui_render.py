#!/usr/bin/env python3
"""Seeds 5 alarms via the SET_ALARM intent, then screenshots the main screens (full res png)."""
import re, subprocess, sys, time, xml.etree.ElementTree as ET

OUT = sys.argv[1]
PKG = "com.sysadmindoc.alarmclock.debug"

def adb(*a):
    return subprocess.run(["adb", *a], capture_output=True)

def shot(name):
    open(f"{OUT}/{name}.png", "wb").write(adb("exec-out", "screencap", "-p").stdout)

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

def back():
    adb("shell", "input", "keyevent", "4")
    time.sleep(2)

adb("shell", "monkey", "-p", PKG, "-c", "android.intent.category.LAUNCHER", "1")
time.sleep(6)
for _ in range(8):
    if not tap_first(["while using the app", "allow", "skip", "get started", "continue", "next", "done", "got it", "not now", "later", "no thanks", "dismiss", "close"]):
        break
    time.sleep(2)

seeds = [(7, 0, "Wake Up", "2,3,4,5,6"), (8, 30, "Weekend Alarm", "1,7"), (15, 0, "Medicine", ""),
         (22, 0, "Sleep", ""), (6, 15, "Gym", "2,4,6")]
for hh, mm, label, days in seeds:
    cmd = ["shell", "am", "start", "-a", "android.intent.action.SET_ALARM", "-p", PKG,
           "--ei", "android.intent.extra.alarm.HOUR", str(hh), "--ei", "android.intent.extra.alarm.MINUTES", str(mm),
           "--es", "android.intent.extra.alarm.MESSAGE", label, "--ez", "android.intent.extra.alarm.SKIP_UI", "true"]
    if days:
        cmd += ["--eia", "android.intent.extra.alarm.DAYS", days]
    adb(*cmd)
    time.sleep(2)
adb("shell", "monkey", "-p", PKG, "-c", "android.intent.category.LAUNCHER", "1")
time.sleep(4)

m = re.search(r"(\d+)x(\d+)", adb("shell", "wm", "size").stdout.decode().split("Override size:")[-1])
h = int(m.group(2)) if m else 2280

for tab in ["Today", "Alarms", "Timer", "World", "News", "Settings"]:
    tap_first([tab.lower()], min_y=int(h * 0.88))
    time.sleep(3)
    shot(f"tab_{tab}")

def visit(tab, words, name, min_y=0):
    tap_first([tab.lower()], min_y=int(h * 0.88))
    time.sleep(2)
    hit = tap_first(words, min_y=min_y)
    time.sleep(3)
    shot(name if hit else name + "_MISSING")
    if hit:
        back()

visit("Alarms", ["wake up"], "page_alarm_edit", min_y=int(h * 0.12))
visit("Alarms", ["new alarm", "add"], "page_new_alarm", min_y=int(h * 0.12))
visit("Timer", ["stopwatch"], "page_stopwatch")
visit("Settings", ["updates"], "page_settings_updates", min_y=int(h * 0.1))
visit("Settings", ["defaults"], "page_settings_defaults", min_y=int(h * 0.1))
tap_first(["alarms"], min_y=int(h * 0.88))
time.sleep(2)
adb("shell", "input", "swipe", "540", str(int(h * 0.75)), "540", str(int(h * 0.3)), "400")
time.sleep(2)
shot("tab_Alarms_scrolled")
