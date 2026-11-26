#!/bin/bash
# Takes pictures of LionFlow in the iPhone and iPad simulator, in demo mode (a sample car, no account).
# Usage: ios-screenshots.sh <LionFlow.app built for the simulator> <folder for the pictures>
set -uo pipefail
APP="$1"
OUT="$2"
BUNDLE="no.lionflow.app"
mkdir -p "$OUT"

# The newest iPhone and iPad the simulator has.
pick() { xcrun simctl list devices available | grep -E "^[[:space:]]+$1" | grep -v -E "mini| SE " | tail -n 1 | sed -E 's/.*\(([0-9A-F-]{36})\).*/\1/'; }
PHONE="$(pick 'iPhone')"
PAD="$(pick 'iPad')"
echo "iPhone simulator: $PHONE ($(xcrun simctl list devices available | grep "$PHONE" | sed -E 's/^[[:space:]]+//; s/ \(.*//'))"
echo "iPad simulator:   $PAD ($(xcrun simctl list devices available | grep "$PAD" | sed -E 's/^[[:space:]]+//; s/ \(.*//'))"

# Runs a command, and stops it when it takes longer than the given number of seconds (a simulator can hang).
limit() { local seconds="$1"; shift; perl -e 'alarm shift; exec @ARGV' "$seconds" "$@"; }
stamp() { echo "[$(date +%H:%M:%S)] $*"; }

shoot() { # device, file name, arguments for the app...
  local device="$1" name="$2"; shift 2
  limit 20 xcrun simctl terminate "$device" "$BUNDLE" > /dev/null 2>&1
  limit 60 xcrun simctl launch "$device" "$BUNDLE" "$@" > /dev/null || { stamp "could not start the app for $name"; return 1; }
  sleep "${WAIT:-5}"
  limit 30 xcrun simctl io "$device" screenshot "$OUT/$name.png" > /dev/null 2>&1 && sips -Z 1400 "$OUT/$name.png" > /dev/null 2>&1
  stamp "  $name"
}

run() { # device, prefix
  local device="$1" prefix="$2"
  [ -n "$device" ] || { echo "no simulator for $prefix"; return 0; }
  stamp "starting the $prefix simulator"
  limit 60 xcrun simctl boot "$device" > /dev/null 2>&1
  limit 420 xcrun simctl bootstatus "$device" -b > /dev/null 2>&1 || stamp "the $prefix simulator took too long to start; trying anyway"
  stamp "installing the app"
  limit 180 xcrun simctl install "$device" "$APP" || { stamp "could not install the app on the $prefix simulator"; return 1; }
  # The map may ask where the phone is: answered in advance, so no question covers the picture.
  limit 30 xcrun simctl privacy "$device" grant location "$BUNDLE" > /dev/null 2>&1
  limit 30 xcrun simctl status_bar "$device" override --time "9:41" --batteryState charged --batteryLevel 100 > /dev/null 2>&1
  WAIT=9 shoot "$device" "$prefix-01-brand" -signedout
  for tab in Home Charging Climate Trips Map Service Settings; do
    shoot "$device" "$prefix-electric-$tab" -demo electric -tab "$tab" -clean
  done
  shoot "$device" "$prefix-electric-Home-light" -demo electric -tab Home -theme light
  shoot "$device" "$prefix-electric-Charging-light" -demo electric -tab Charging -theme light -clean
  shoot "$device" "$prefix-electric-Home-nb" -demo electric -tab Home -theme dark -lang nb -clean
  shoot "$device" "$prefix-hybrid-Home" -demo hybrid -brand citroen -tab Home -lang en -clean
  shoot "$device" "$prefix-hybrid-Trips" -demo hybrid -brand citroen -tab Trips -clean
  shoot "$device" "$prefix-fuel-Home" -demo fuel -brand opel -tab Home -clean
  shoot "$device" "$prefix-fuel-Trips" -demo fuel -brand opel -tab Trips -clean
  limit 60 xcrun simctl shutdown "$device" > /dev/null 2>&1
}

run "$PHONE" phone
run "$PAD" pad
ls -la "$OUT" | head -80
