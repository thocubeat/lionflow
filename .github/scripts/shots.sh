#!/bin/bash
# Runs inside the emulator job. Opens demo mode and screenshots each tab in a few screen sizes.
set -x
APK=Android/app/build/outputs/apk/debug/app-debug.apk
PKG=no.lionflow.app.debug
adb install -r "$APK"
# Let the emulator settle, then close "isn't responding" dialogs from the first boot.
sleep 45
adb shell am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS
adb shell input keyevent KEYCODE_ENTER
adb logcat -c
for p in android.permission.ACCESS_FINE_LOCATION android.permission.ACCESS_COARSE_LOCATION android.permission.POST_NOTIFICATIONS; do adb shell pm grant $PKG $p || true; done
adb emu geo fix 2.3522 48.8566
# Clean status bar
adb shell settings put global sysui_demo_allowed 1
adb shell am broadcast -a com.android.systemui.demo -e command enter
adb shell am broadcast -a com.android.systemui.demo -e command clock -e hhmm 0930
adb shell am broadcast -a com.android.systemui.demo -e command battery -e level 100 -e plugged false
adb shell am broadcast -a com.android.systemui.demo -e command network -e wifi show -e level 4
adb shell am broadcast -a com.android.systemui.demo -e command notifications -e visible false
mkdir -p shots
shoot() { # name size density orientation(0 portrait,1 landscape)
  local name=$1 size=$2 dens=$3 rot=$4
  adb shell settings put system accelerometer_rotation 0
  adb shell settings put system user_rotation $rot
  adb shell wm size $size; adb shell wm density $dens
  sleep 3
  mkdir -p shots/$name
  adb shell am force-stop $PKG
  for tab in Home Charging Climate Trips Service Settings; do
    adb shell am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS >/dev/null
    adb shell am start -W -n $PKG/no.lionflow.app.MainActivity --es demo_tab $tab
    sleep 4
    adb exec-out screencap -p > shots/$name/$tab.png
  done
}
shoot phone 1080x1920 420 0
shoot tablet7 1080x1920 240 0
shoot tablet10 1440x2560 320 1
adb shell wm size reset; adb shell wm density reset
adb logcat -d -v brief AndroidRuntime:E ActivityManager:W '*:S' > shots/log.txt || true
ls -lR shots
