import re, subprocess, sys
import xml.etree.ElementTree as ET
want = sys.argv[1].lower()
subprocess.run(["adb", "shell", "uiautomator", "dump", "/sdcard/u.xml"], capture_output=True)
xml = subprocess.run(["adb", "shell", "cat", "/sdcard/u.xml"], capture_output=True).stdout.decode("utf-8", "ignore")
root = ET.fromstring(xml[xml.index("<?xml"):] if "<?xml" in xml else xml)
for n in root.iter("node"):
    if (n.get("text") or "").strip().lower() == want:
        m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", n.get("bounds", ""))
        x1, y1, x2, y2 = map(int, m.groups())
        subprocess.run(["adb", "shell", "input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2)])
        print("tapped", want, (x1 + x2) // 2, (y1 + y2) // 2)
        sys.exit(0)
print("not found", want)
