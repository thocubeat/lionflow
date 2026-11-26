#!/bin/bash
# Makes the test fixtures (iOS/CoreTests/Fixtures/*_expected.json) by running the Android app's own Kotlin code on
# the inputs next to them. The Swift tests then check that the iPhone app gives the very same answers.
# Run it again whenever the Android logic or the inputs change. Needs git, a JDK, python3 and kotlinc.
# It only reads the Android sources and writes the *_expected.json files; it never talks to the network
# apart from fetching the JSON library the first time.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
WORK="${ORACLE_WORK:-/tmp/lionflow-oracle}"
KOTLINC="${KOTLINC:-kotlinc}"
JSON_SRC="${OPEN_JSON:-$WORK/open-json}"
mkdir -p "$WORK/json"
rm -rf "$WORK/src" && mkdir -p "$WORK/src"

echo "1/4 Android's own JSON classes (org.json), as an ordinary library"
[ -d "$JSON_SRC" ] || git clone -q --depth 1 https://github.com/tdunning/open-json "$JSON_SRC"
javac -nowarn -d "$WORK/json" "$JSON_SRC"/src/main/java/org/json/*.java

echo "2/4 The app's logic, taken out of the Android sources"
python3 "$HERE/prepare.py" "$ROOT" "$WORK/src"

echo "3/4 Compile"
"$KOTLINC" -nowarn -cp "$WORK/json" "$WORK"/src/*.kt "$HERE"/Oracle*.kt -include-runtime -d "$WORK/oracle.jar"

echo "4/4 Run"
java -Duser.timezone=UTC -Duser.language=en -Duser.country=US -cp "$WORK/oracle.jar:$WORK/json" OracleKt \
  "$ROOT/iOS/CoreTests/Fixtures" "$ROOT/Android/app/src/main/assets/l10n.tsv"
