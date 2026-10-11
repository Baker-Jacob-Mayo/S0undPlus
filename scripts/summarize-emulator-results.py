#!/usr/bin/env python3
"""Prints the outcome of the emulator tests into the CI log (artifacts are awkward to read; the log is not).

Usage: summarize-emulator-results.py
Reads app/build/outputs/androidTest-results/**/TEST-*.xml, emulator-results/logcat.txt and any screenshots the tests saved.
"""
import base64
import glob
import os
import re
import sys
import xml.etree.ElementTree as ET

print("\n=== screenshots (JPEG, base64; decode to view) ===")
shots = sorted(glob.glob("app/build/outputs/**/*.jpg", recursive=True))
if not shots:
    print("(none)")
for shot in shots[:12]:
    data = base64.b64encode(open(shot, "rb").read()).decode()
    print(f"SCREENSHOT-BEGIN {os.path.basename(shot)}")
    print(data)
    print("SCREENSHOT-END")


passed = failed = skipped = 0
print("=== emulator test results ===")
files = sorted(glob.glob("app/build/outputs/androidTest-results/**/TEST-*.xml", recursive=True))
if not files:
    print("No result files: the tests did not run to completion. Gradle log tail:")
    log = "emulator-results/gradle.log"
    if os.path.exists(log):
        print("".join(open(log, errors="replace").readlines()[-60:]))
for path in files:
    root = ET.parse(path).getroot()
    for case in root.iter("testcase"):
        name = f'{case.get("classname")}.{case.get("name")}'
        seconds = case.get("time", "?")
        problem = case.find("failure")
        if problem is None:
            problem = case.find("error")
        if case.find("skipped") is not None:
            skipped += 1
            print(f"SKIP  {name}")
        elif problem is not None:
            failed += 1
            print(f"FAIL  {name} ({seconds}s)")
            for line in (problem.text or problem.get("message") or "").splitlines()[:60]:
                print(f"      {line}")
        else:
            passed += 1
            print(f"PASS  {name} ({seconds}s)")
print(f"--- {passed} passed, {failed} failed, {skipped} skipped ---")

print("\n=== logcat: crashes, test diagnostics, the app's own messages ===")
logcat = "emulator-results/logcat.txt"
if os.path.exists(logcat):
    interesting = re.compile(r"FATAL EXCEPTION|Process: com\.s0undplus|Screenshots|ANR in| TestLog")
    lines = [l.rstrip() for l in open(logcat, errors="replace") if interesting.search(l)]
    for l in lines[-120:]:
        print(l[:300])
    if not lines:
        print("(nothing notable)")
else:
    print("(no logcat file)")

sys.exit(0)
