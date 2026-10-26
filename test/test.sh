#!/bin/sh
# Host tests of the CarBroker client's wire format (api/broker) against a fake broker, on the
# host JVM with the pinned Kotlin and Android's org.json from api/tools/fetch-tools.sh.
set -e
HERE=$(cd "$(dirname "$0")" && pwd)
ROOT=$HERE/..
JDK=${JAVA_HOME:-$(/usr/libexec/java_home -v 11)}
FT=$(sh "$ROOT/api/tools/fetch-tools.sh")
OUT=$(mktemp -d)
trap 'rm -rf "$OUT"' EXIT
find "$ROOT/api/broker/src" "$HERE/src" -name '*.kt' >"$OUT/sources.txt"
# android.jar for CarBroker's LocalSocket (compiled, never run here); org.json from android-json
JAVA_HOME=$JDK "$FT/kotlinc/bin/kotlinc" -jvm-target 1.8 -no-reflect -Werror \
    -classpath "$FT/android-json.jar:$FT/android-19.jar" -d "$OUT/classes" "@$OUT/sources.txt"
"$JDK/bin/java" -cp "$OUT/classes:$FT/android-json.jar:$FT/kotlinc/lib/kotlin-stdlib.jar" \
    dev.cabinwright.api.broker.BrokerTest
