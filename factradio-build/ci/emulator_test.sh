#!/bin/sh
set -eu

APK="factradio-build/app/build/outputs/apk/release/app-install-test.apk"
TEST_APK=$(find factradio-build/app/build/outputs/apk/androidTest -name '*-androidTest.apk' | head -1)

test -s "$APK"
test -s "$TEST_APK"
adb install -r "$APK"
adb install -r "$TEST_APK"
adb shell pm grant com.factradio.app android.permission.POST_NOTIFICATIONS || true
adb shell am start -W -n com.factradio.app/com.example.factradio.MainActivity
adb shell dumpsys package com.factradio.app | grep -q 'versionName=0.6.1'

# Simulate the Samsung use case: Android's auto-rotate switch stays locked while
# the app follows the phone sensor itself.
adb shell settings put system accelerometer_rotation 0
adb shell settings put system user_rotation 0
adb emu sensor set acceleration 0:9.81:0
sleep 2
adb emu sensor set acceleration 9.81:0:0

landscape=0
attempt=1
while [ "$attempt" -le 10 ]; do
  adb shell uiautomator dump /sdcard/factradio-window.xml >/dev/null 2>&1 || true
  if adb shell cat /sdcard/factradio-window.xml 2>/dev/null | grep -q 'bounds="\[0,0\]\[640,320\]"'; then
    landscape=1
    break
  fi
  sleep 1
  attempt=$((attempt + 1))
done

if [ "$landscape" -ne 1 ]; then
  adb emu sensor set acceleration -9.81:0:0
  sleep 3
  adb shell uiautomator dump /sdcard/factradio-window.xml >/dev/null 2>&1 || true
  adb shell cat /sdcard/factradio-window.xml 2>/dev/null | grep -q 'bounds="\[0,0\]\[640,320\]"'
fi

adb shell am instrument -w \
  com.factradio.app.test/androidx.test.runner.AndroidJUnitRunner \
  | tee /tmp/factradio-instrumentation.txt
grep -q 'OK (4 tests)' /tmp/factradio-instrumentation.txt
