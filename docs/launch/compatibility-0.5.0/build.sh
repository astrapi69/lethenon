#!/usr/bin/env bash
# Builds the two command lines measure.sh compares: 0.4.0 from its release tag, with its lethenon jar
# replaced by the one on Maven Central and checked against Central's .sha1, and the commit under test
# with the full gate. Run from a clone of lethenon; the worktrees go next to this script.
#   bash docs/launch/compatibility-0.5.0/build.sh [<commit under test, default HEAD>]
set -uo pipefail
C=$(cd "$(dirname "$0")" && pwd)
UNDER_TEST=${1:-HEAD}
v=0.4.0
[ -d "$C/wt-$v" ] || git worktree add -q --detach "$C/wt-$v" "RELEASE-$v"
(cd "$C/wt-$v" && ./gradlew installDist -x test --no-watch-fs -q > "$C/build-$v.log" 2>&1; echo "installDist $v exit=$?" | tee -a "$C/build-$v.log")
lib="$C/wt-$v/build/install/lethenon/lib"
url="https://repo1.maven.org/maven2/io/github/astrapi69/lethenon/$v/lethenon-$v.jar"
curl -sSf -o "$lib/lethenon-$v.jar.central" "$url" && expected=$(curl -sSf "$url.sha1" | cut -c1-40)
actual=$(sha1sum "$lib/lethenon-$v.jar.central" | cut -c1-40)
if [ "${expected:-}" = "$actual" ]; then
  mv "$lib/lethenon-$v.jar.central" "$lib/lethenon-$v.jar"
  echo "$v: lethenon-$v.jar from Maven Central, sha1 $actual" | tee -a "$C/build-$v.log"
else
  echo "$v: Central's jar does not match its .sha1 (expected ${expected:-none}, got $actual)" | tee -a "$C/build-$v.log"; exit 1
fi
[ -d "$C/wt-0.5.0" ] || git worktree add -q --detach "$C/wt-0.5.0" "$UNDER_TEST"
(cd "$C/wt-0.5.0" && ./gradlew clean build installDist --no-watch-fs > "$C/build-0.5.0.log" 2>&1; echo "build 0.5.0 exit=$?" | tee -a "$C/build-0.5.0.log")
grep -E "^test:" "$C/build-0.5.0.log"
