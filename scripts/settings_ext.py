import re, subprocess, sys, time
import xml.etree.ElementTree as ET
OUT = sys.argv[1]
def adb(*a):
    return subprocess.run(["adb", *a], capture_output=True)
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
def tap(words, min_y=0):
    for label, x, y, y1 in nodes():
        if y1 >= min_y and any(w in label.lower() for w in words):
            adb("shell", "input", "tap", str(x), str(y))
            return True
    return False
def shot(name):
    open(f"{OUT}/{name}.png", "wb").write(adb("exec-out", "screencap", "-p").stdout)
size = adb("shell", "wm", "size").stdout.decode()
h = int(re.search(r"(\d+)x(\d+)", size).group(2))
adb("shell", "monkey", "-p", "com.sysadmindoc.alarmclock.debug", "-c", "android.intent.category.LAUNCHER", "1")
time.sleep(5)
for _ in range(3):
    tap(["skip", "later", "not now", "dismiss", "got it"])
    time.sleep(1)
for attempt in range(3):
    tap(["settings"], min_y=int(h * 0.8)); time.sleep(2)
    if tap(["integrations"], min_y=int(h * 0.1)):
        break
time.sleep(3)
shot("ext_integrations_top")
for i in range(1, 4):
    adb("shell", "input", "swipe", "540", str(int(h * 0.8)), "540", str(int(h * 0.25)), "400")
    time.sleep(2)
    shot(f"ext_integrations_{i}")
