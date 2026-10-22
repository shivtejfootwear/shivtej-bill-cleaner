"""Prints a short pass/fail summary of JUnit XML files in a folder."""
import glob
import os
import sys
import xml.etree.ElementTree as ET

folder = sys.argv[1]
total = failed = skipped = 0
lines = []
for path in sorted(glob.glob(os.path.join(folder, "*.xml"))):
    root = ET.parse(path).getroot()
    suites = [root] if root.tag == "testsuite" else root.findall("testsuite")
    for s in suites:
        for case in s.findall("testcase"):
            total += 1
            name = f"{case.get('classname', '').split('.')[-1]}.{case.get('name')}"
            fail = case.find("failure") if case.find("failure") is not None else case.find("error")
            if fail is not None:
                failed += 1
                msg = (fail.get("message") or fail.text or "").strip().splitlines()
                lines.append(f"FAIL {name}: {msg[0][:400] if msg else ''}")
            elif case.find("skipped") is not None:
                skipped += 1
                lines.append(f"SKIP {name}")
            else:
                lines.append(f"PASS {name}")
print(f"total={total} passed={total - failed - skipped} failed={failed} skipped={skipped}")
print("\n".join(lines))
