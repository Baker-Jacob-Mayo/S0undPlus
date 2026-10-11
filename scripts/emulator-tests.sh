#!/usr/bin/env bash
# Runs the instrumented tests on the already-booted emulator.
#
# reactivecircus/android-emulator-runner executes its `script:` input one line at a time, so anything with control flow
# or a pipeline lives here instead. The emulator disappears when that step ends, so logcat is saved before exiting.
set -u
mkdir -p emulator-results
adb logcat -c || true
adb logcat -G 16M || true # the default ring buffer forgets the early tests before the run is over
{
  echo "device: $(adb shell getprop ro.product.model | tr -d '\r') / API $(adb shell getprop ro.build.version.sdk | tr -d '\r') / $(adb shell getprop ro.product.cpu.abi | tr -d '\r')"
  echo "leanback: $(adb shell pm has-feature android.software.leanback | tr -d '\r')"
} | tee emulator-results/device.txt

# SUITE_REPEATS > 1 runs the whole suite several times on the same emulator, to tell a flaky test from a broken one.
rc=0
for i in $(seq 1 "${SUITE_REPEATS:-1}"); do
  echo "=== suite run $i of ${SUITE_REPEATS:-1} ==="
  ./gradlew :app:connectedDebugAndroidTest --rerun --console=plain --stacktrace 2>&1 | tee -a emulator-results/gradle.log
  run_rc=${PIPESTATUS[0]}
  [ "$run_rc" -ne 0 ] && rc=$run_rc
  mkdir -p "emulator-results/run-$i"
  cp -r app/build/outputs/androidTest-results/. "emulator-results/run-$i/" 2>/dev/null || true
  cp -r app/build/outputs/connected_android_test_additional_output/. "emulator-results/run-$i/" 2>/dev/null || true
done

adb logcat -d -v threadtime > emulator-results/logcat.txt || true
exit "$rc"
