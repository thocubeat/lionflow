#!/bin/bash
# Takes pictures of LionFlow in the iPhone, iPad and Apple Watch simulators, in demo mode (a sample car, no account).
# Usage: ios-screenshots.sh <LionFlow.app built for the simulator> <folder for the pictures> [<LionFlowWatch.app>]
set -uo pipefail
APP="$1"
OUT="$2"
WATCH_APP="${3:-}"
BUNDLE="no.lionflow.app"
WATCH_BUNDLE="no.lionflow.app.watchkitapp"
mkdir -p "$OUT"

# Runs a command, and stops it when it takes longer than the given number of seconds (a simulator can hang).
limit() { local seconds="$1"; shift; perl -e 'alarm shift; exec @ARGV' "$seconds" "$@"; }
stamp() { echo "[$(date +%H:%M:%S)] $*"; }

# Which simulators: an iPhone with an Apple Watch paired to it (the newest pair there is), and the newest iPad.
# Without a pair: the newest iPhone and the newest watch on their own.
# (The program goes into a file first: the old bash on a Mac garbles one that is written inside "$( )".)
PICK="$(mktemp)"
cat > "$PICK" <<'PY'
import json, re, subprocess

def run(*args):
    try:
        return json.loads(subprocess.run(["xcrun", "simctl", "list", *args, "-j"], capture_output=True, text=True, timeout=120).stdout)
    except Exception:
        return {}

def version(runtime):
    return tuple(int(n) for n in re.findall(r"\d+", runtime.rsplit(".", 1)[-1]))

devices = {}
for runtime, items in run("devices", "available").get("devices", {}).items():
    for d in items:
        devices[d["udid"]] = (version(runtime), d["name"], runtime)

def newest(kind, skip=()):
    found = [(v, i, udid) for i, (udid, (v, name, runtime)) in enumerate(devices.items())
             if name.startswith(kind) and not any(s in name for s in skip)]
    return max(found)[2] if found else ""

phone, watch = "", ""
pairs = []
for p in run("pairs").get("pairs", {}).values():
    w, ph = p.get("watch", {}).get("udid", ""), p.get("phone", {}).get("udid", "")
    if w in devices and ph in devices and "unavailable" not in p.get("state", ""):
        pairs.append((devices[ph][0], devices[w][0], ph, w))
if pairs:
    _, _, phone, watch = max(pairs)
paired = "yes" if phone else "no"
phone = phone or newest("iPhone", ("mini", " SE "))
watch = watch or newest("Apple Watch")
pad = newest("iPad", ("mini",))
for key, value in (("PHONE", phone), ("WATCH", watch), ("PAD", pad), ("PAIRED", paired)):
    print(f'{key}="{value}"')
for key, udid in (("PHONE_NAME", phone), ("WATCH_NAME", watch), ("PAD_NAME", pad)):
    name = devices.get(udid, ((), "none", ""))
    print(f'{key}="{name[1]}, {name[2].rsplit(".", 1)[-1]}"')
PY
eval "$(python3 "$PICK")"
# Another workflow can name the devices itself (the run on the oldest iOS does).
if [ -n "${PHONE_UDID:-}${PAD_UDID:-}" ]; then
  PHONE="${PHONE_UDID:-}"; PHONE_NAME="chosen by the workflow"
  PAD="${PAD_UDID:-}"; PAD_NAME="chosen by the workflow"
  PAIRED="no"
fi
if [ "${SKIP_WATCH:-}" = "1" ]; then WATCH=""; WATCH_NAME="left out"; fi
# Three simulators at once is more than the build machine has memory for: the iPad can be left to another job.
if [ "${SKIP_PAD:-}" = "1" ]; then PAD=""; PAD_NAME="left out"; fi
echo "iPhone simulator: $PHONE ($PHONE_NAME)"
echo "iPad simulator:   $PAD ($PAD_NAME)"
echo "Watch simulator:  $WATCH ($WATCH_NAME), paired with the iPhone: $PAIRED"

# All of them start at once: starting one takes minutes.
for device in "$PHONE" "$PAD" "$WATCH"; do
  [ -n "$device" ] && limit 60 xcrun simctl boot "$device" > /dev/null 2>&1
done

ready() { # device, what it is
  [ -n "$1" ] || { stamp "no simulator for the $2"; return 1; }
  limit 600 xcrun simctl bootstatus "$1" > /dev/null 2>&1 || stamp "the $2 simulator took too long to start; trying anyway"
  stamp "the $2 simulator is up"
}

shoot() { # device, file name, arguments for the app...
  local device="$1" name="$2"; shift 2
  limit 20 xcrun simctl terminate "$device" "$BUNDLE" > /dev/null 2>&1
  limit 60 xcrun simctl launch "$device" "$BUNDLE" "$@" > /dev/null || { stamp "could not start the app for $name"; return 1; }
  sleep "${WAIT:-5}"
  snap "$device" "$name" && sips -Z 1400 "$OUT/$name.png" > /dev/null 2>&1
}

snap() { # device, file name: a picture of the screen, tried a few times (a simulator that just started can refuse)
  local try
  for try in 1 2 3 4; do
    limit 30 xcrun simctl io "$1" screenshot "$OUT/$2.png" > /dev/null 2>&1
    [ -s "$OUT/$2.png" ] && { stamp "  $2"; return 0; }
    sleep 4
  done
  stamp "  $2: NO PICTURE ($(limit 30 xcrun simctl io "$1" screenshot "$OUT/$2.png" 2>&1 | tail -n 1))"
  return 1
}

run() { # device, prefix
  local device="$1" prefix="$2"
  ready "$device" "$prefix" || return 0
  limit 180 xcrun simctl install "$device" "$APP" || { stamp "could not install the app on the $prefix simulator"; return 1; }
  # A simulator that has only just started can refuse apps for a while (an old system on its first start, for
  # minutes): try until the app starts. The app asks where the phone is: answered in advance each time, so the
  # question doesn't cover the pictures.
  local try
  for try in 1 2 3 4 5 6 7 8; do
    limit 30 xcrun simctl privacy "$device" grant location "$BUNDLE" > /dev/null 2>&1
    limit 90 xcrun simctl launch "$device" "$BUNDLE" -signedout > /dev/null 2>&1 && break
    stamp "the $prefix simulator does not start apps yet (try $try)"
    sleep 15
  done
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
}

watch_shot() { # file name, seconds to wait, arguments for the watch app...
  local name="$1" wait="$2"; shift 2
  limit 20 xcrun simctl terminate "$WATCH" "$WATCH_BUNDLE" > /dev/null 2>&1
  limit 60 xcrun simctl launch "$WATCH" "$WATCH_BUNDLE" "$@" > /dev/null || { stamp "could not start the watch app for $name"; return 1; }
  sleep "$wait"
  snap "$WATCH" "$name"
}

watch_run() {
  [ -n "$WATCH_APP" ] && [ -d "$WATCH_APP" ] || { stamp "no watch app to take pictures of"; return 0; }
  ready "$WATCH" "watch" || return 0
  limit 180 xcrun simctl install "$WATCH" "$WATCH_APP" || { stamp "could not install the watch app"; return 0; }
  # The watch app with a sample car of its own (no phone needed).
  watch_shot watch-electric 6 -preview
  watch_shot watch-fuel 6 -preview -fuel
  watch_shot watch-electric-nb 6 -preview -AppleLanguages "(nb)"
  # And for real: the watch asks the phone app. The phone is signed out (the first screen), so the answer to expect
  # on the watch is "Open LionFlow to sign in": then the question and the answer both made the trip.
  if [ "$PAIRED" = "yes" ]; then
    limit 20 xcrun simctl terminate "$PHONE" "$BUNDLE" > /dev/null 2>&1
    limit 60 xcrun simctl launch "$PHONE" "$BUNDLE" -signedout > /dev/null
    sleep 6
    watch_shot watch-asks-phone 25
    sleep 35
    snap "$WATCH" watch-asks-phone-a-minute-later
    stamp "what the watch app wrote down:"
    limit 90 xcrun simctl spawn "$WATCH" log show --last 3m --style compact --predicate 'eventMessage CONTAINS "LionFlow watch"' 2>/dev/null | tail -n 12
    stamp "what the phone app wrote down:"
    limit 90 xcrun simctl spawn "$PHONE" log show --last 3m --style compact --predicate 'eventMessage CONTAINS "LionFlow phone"' 2>/dev/null | tail -n 12
  else
    stamp "no iPhone and watch paired with each other: the watch could not ask the phone"
  fi
}

run "$PHONE" phone
watch_run
limit 60 xcrun simctl shutdown "$PHONE" > /dev/null 2>&1
limit 60 xcrun simctl shutdown "$WATCH" > /dev/null 2>&1
run "$PAD" pad
limit 60 xcrun simctl shutdown "$PAD" > /dev/null 2>&1
ls -la "$OUT" | head -80
