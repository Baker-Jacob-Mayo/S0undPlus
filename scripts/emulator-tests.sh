#!/usr/bin/env bash
# Runs the instrumented tests on the already-booted emulator.
#
# reactivecircus/android-emulator-runner executes its `script:` input one line at a time, so anything with control flow
# or a pipeline lives here instead. The emulator disappears when that step ends, so logcat is saved before exiting.
set -u
mkdir -p emulator-results
adb logcat -c || true
{
  echo "device: $(adb shell getprop ro.product.model | tr -d '\r') / API $(adb shell getprop ro.build.version.sdk | tr -d '\r') / $(adb shell getprop ro.product.cpu.abi | tr -d '\r')"
  echo "leanback: $(adb shell pm has-feature android.software.leanback | tr -d '\r')"
} | tee emulator-results/device.txt

./gradlew :app:connectedDebugAndroidTest --console=plain --stacktrace 2>&1 | tee emulator-results/gradle.log
rc=${PIPESTATUS[0]}

adb logcat -d -v threadtime > emulator-results/logcat.txt || true
exit "$rc"
