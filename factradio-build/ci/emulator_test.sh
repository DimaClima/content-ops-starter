#!/bin/sh
set -eu

APK="factradio-build/app/build/outputs/apk/release/app-install-test.apk"
TEST_APK=$(find factradio-build/app/build/outputs/apk/androidTest -name '*-androidTest.apk' | head -1)

show_crash_log() {
  status=$?
  if [ "$status" -ne 0 ]; then
    adb logcat -d -t 800 AndroidRuntime:E '*:S' || true
    adb shell dumpsys activity activities | grep -A 15 -B 5 com.factradio.app || true
  fi
  exit "$status"
}
trap show_crash_log EXIT

test -s "$APK"
test -s "$TEST_APK"
adb install -r "$APK"
adb install -r "$TEST_APK"
adb shell pm grant com.factradio.app android.permission.POST_NOTIFICATIONS || true
adb shell am start -W -n com.factradio.app/com.example.factradio.MainActivity
adb shell dumpsys package com.factradio.app | grep -q 'versionName=0.7.1'
sleep 12
adb shell pidof com.factradio.app >/dev/null

adb shell settings put system accelerometer_rotation 0
adb shell settings put system user_rotation 0
adb emu sensor set acceleration 0:9.81:0
sleep 2
adb emu sensor set acceleration 9.81:0:0

landscape=0
attempt=1
while [ "$attempt" -le 12 ]; do
  adb emu sensor set acceleration 9.81:0:0 >/dev/null
  if adb shell dumpsys activity activities |
      grep -q 'mOrientation=SCREEN_ORIENTATION_.*LANDSCAPE'; then
    landscape=1
    break
  fi
  sleep 1
  attempt=$((attempt + 1))
done
test "$landscape" -eq 1

# Reproduce Dmitry's real use: leave the app for navigation, then enter it again.
# The app must stay alive and start a fresh session instead of restoring the old one.
adb shell input keyevent KEYCODE_HOME
sleep 2
adb shell monkey -p com.factradio.app 1 >/dev/null
sleep 12
adb shell pidof com.factradio.app >/dev/null
adb shell dumpsys activity activities |
  grep -q 'mResumedActivity:.*com.factradio.app'

adb shell am instrument -w \
  com.factradio.app.test/androidx.test.runner.AndroidJUnitRunner \
  | tee /tmp/factradio-instrumentation.txt
grep -q 'OK (7 tests)' /tmp/factradio-instrumentation.txt
