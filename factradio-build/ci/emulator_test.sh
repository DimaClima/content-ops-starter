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
adb shell dumpsys package com.factradio.app | grep -q 'versionName=0.7.0'

adb shell settings put system accelerometer_rotation 0
adb shell settings put system user_rotation 0
adb emu sensor set acceleration 0:9.81:0
sleep 2
adb emu sensor set acceleration 9.81:0:0

landscape=0
attempt=1
while [ "$attempt" -le 12 ]; do
  adb emu sensor set acceleration 9.81:0:0 >/dev/null
  adb shell uiautomator dump /sdcard/factradio-rotation.xml >/dev/null 2>&1 || true
  rotation=$(adb shell cat /sdcard/factradio-rotation.xml 2>/dev/null | sed -n 's/.*<hierarchy rotation="\([0-9]\)".*/\1/p')
  if [ "$rotation" = "1" ] || [ "$rotation" = "3" ]; then
    landscape=1
    break
  fi
  sleep 1
  attempt=$((attempt + 1))
done
test "$landscape" -eq 1

adb shell uiautomator dump /sdcard/factradio-window.xml >/dev/null 2>&1
adb shell cat /sdcard/factradio-window.xml | grep -q 'Версия 0.7.0'

adb shell am instrument -w \
  com.factradio.app.test/androidx.test.runner.AndroidJUnitRunner \
  | tee /tmp/factradio-instrumentation.txt
grep -q 'OK (7 tests)' /tmp/factradio-instrumentation.txt
