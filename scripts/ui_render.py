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

open(f"{OUT}/wm.txt","wb").write(adb("shell","wm","size").stdout+adb("shell","wm","density").stdout)
for perm in ("ACCESS_FINE_LOCATION", "ACCESS_COARSE_LOCATION", "POST_NOTIFICATIONS"):
    adb("shell", "pm", "grant", PKG, f"android.permission.{perm}")
adb("emu", "geo", "fix", "72.8777", "19.0760")
adb("shell", "monkey", "-p", PKG, "-c", "android.intent.category.LAUNCHER", "1")
time.sleep(6)
for _ in range(8):
    if not tap_first(["while using the app", "allow", "skip", "get started", "continue", "next", "done", "got it", "not now", "later", "no thanks", "dismiss", "close"]):
        break
    time.sleep(2)

m0 = re.search(r"(\d+)x(\d+)", adb("shell", "wm", "size").stdout.decode().split("Override size:")[-1])
h0 = int(m0.group(2)) if m0 else 2280
tap_first(["alarms"], min_y=int(h0 * 0.88))
time.sleep(2)
for i in range(4):
    if not tap_first(["new alarm"], min_y=int(h0 * 0.12)):
        break
    time.sleep(3)
    tap_first(["create"], min_y=int(h0 * 0.7))
    time.sleep(3)
for _ in range(4):
    adb("shell", "cmd", "statusbar", "expand-notifications")
    time.sleep(2)
    hit = tap_first(["dismiss"])
    time.sleep(2)
    adb("shell", "cmd", "statusbar", "collapse")
    if not hit:
        break
adb("shell", "input", "keyevent", "3")
time.sleep(1)
adb("shell", "am", "force-stop", PKG)
time.sleep(2)
adb("shell", "cmd", "statusbar", "collapse")
adb("shell", "monkey", "-p", PKG, "-c", "android.intent.category.LAUNCHER", "1")
time.sleep(4)

m = re.search(r"(\d+)x(\d+)", adb("shell", "wm", "size").stdout.decode().split("Override size:")[-1])
h = int(m.group(2)) if m else 2280

time.sleep(8)
for _ in range(3):
    if not tap_first(["later"]):
        break
    time.sleep(2)
for tab in ["Today", "Alarms", "Timer", "World", "News", "Settings"]:
    tap_first(["later"])
    tap_first([tab.lower()], min_y=int(h * 0.88))
    time.sleep(3)
    shot(f"tab_{tab}")

def launch():
    adb("shell", "am", "force-stop", PKG)
    time.sleep(1)
    adb("shell", "monkey", "-p", PKG, "-c", "android.intent.category.LAUNCHER", "1")
    time.sleep(5)
    tap_first(["later"])
    time.sleep(1)

NAV = int(h * 0.7)

def tap_re(pattern, min_y=0):
    for label, x, y, y1 in nodes():
        if y1 >= min_y and re.search(pattern, label):
            adb("shell", "input", "tap", str(x), str(y))
            return label
    return None

def finish(name, hit):
    time.sleep(3)
    shot(name if hit else name + "_MISSING")

def swipe_up():
    adb("shell", "input", "swipe", "540", str(int(h * 0.7)), "540", str(int(h * 0.25)), "400")
    time.sleep(2)

launch(); tap_first(["alarms"], min_y=NAV); time.sleep(2)
finish("page_alarm_edit", tap_re(r"\d{1,2}:\d{2}\s?[AP]M", min_y=int(h * 0.12)))
launch(); tap_first(["alarms"], min_y=NAV); time.sleep(2)
finish("page_new_alarm", tap_first(["new alarm", "add"], min_y=int(h * 0.12)))
launch(); tap_first(["timer"], min_y=NAV); time.sleep(2)
finish("page_stopwatch", tap_first(["stopwatch"]))
for words, name in ((["updates"], "page_settings_updates"), (["defaults"], "page_settings_defaults")):
    launch(); tap_first(["settings"], min_y=NAV); time.sleep(2)
    hit = tap_first(words, min_y=int(h * 0.1))
    for _ in range(3):
        if hit:
            break
        swipe_up()
        hit = tap_first(words, min_y=int(h * 0.1))
    finish(name, hit)
launch(); tap_first(["alarms"], min_y=NAV); time.sleep(2)
swipe_up()
shot("tab_Alarms_scrolled")
launch(); tap_first(["timer"], min_y=NAV); time.sleep(2)
hit = tap_first(["timer alert"])
time.sleep(1)
shot("tab_Timer_vibrate" if hit else "tab_Timer_vibrate_MISSING")
