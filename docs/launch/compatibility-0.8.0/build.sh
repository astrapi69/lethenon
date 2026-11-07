#!/usr/bin/env bash
# Prepares the command lines measure.sh compares: 0.7.0 exactly as it was
# published - the tar.gz of the GitHub release, its .sha256 and its signature checked against the
# release key from keys.openpgp.org - and the commit under test, built with the full gate. Run from a
# clone of lethenon.
#   bash docs/launch/compatibility-0.8.0/build.sh [<commit under test, default HEAD>]
set -uo pipefail
C=$(cd "$(dirname "$0")" && pwd)
# the commit under test as a commit id, read in this clone: the worktree below resolves no name
UNDER_TEST=$(git -C "$C" rev-parse --verify --quiet "${1:-HEAD}^{commit}") || { echo "not a commit: ${1:-HEAD}"; exit 1; }
PRIMARY=5B2FA6B11E298BC09287F423D8C403518C49CA75
SUBKEY=6D67F8442A6FCC96BD7C1B5E9FCF7C9710E2BD8D
O=$C/old; rm -rf "$O"; mkdir -p "$O"
K=$(mktemp -d); chmod 700 "$K"
curl -sSf "https://keys.openpgp.org/vks/v1/by-fingerprint/$PRIMARY" | gpg --homedir "$K" --batch --import 2> /dev/null
published() { # version: download, check, unpack into old/
  local v=$1 base="https://github.com/astrapi69/lethenon/releases/download/RELEASE-$1"
  for f in "lethenon-$v.tar.gz" "lethenon-$v.tar.gz.sha256" "lethenon-$v.tar.gz.asc"; do
    curl -sSfL -o "$O/$f" "$base/$f" || { echo "$v: cannot download $f"; return 1; }
  done
  (cd "$O" && sha256sum -c "lethenon-$v.tar.gz.sha256") | tee "$C/build-$v.log" || return 1
  local sig; sig=$(gpg --homedir "$K" --batch --status-fd 1 --verify "$O/lethenon-$v.tar.gz.asc" "$O/lethenon-$v.tar.gz" 2> /dev/null | awk '/VALIDSIG/ {print $3}')
  echo "$v: signature by ${sig:-nobody}" | tee -a "$C/build-$v.log"
  [ "$sig" = "$SUBKEY" ] || { echo "$v: not signed by the release subkey"; return 1; }
  tar -xzf "$O/lethenon-$v.tar.gz" -C "$O"
}
published 0.7.0; ok=$?
gpgconf --homedir "$K" --kill gpg-agent; rm -rf "$K"
[ $ok -eq 0 ] || exit 1
# a worktree left by an earlier run is moved to the commit under test: kept as it was, a second
# run built the first run's commit and reported it under the one asked for
if [ -d "$C/wt-0.8.0" ]; then
  git -C "$C/wt-0.8.0" checkout -q --detach "$UNDER_TEST" || exit 1
else
  git worktree add -q --detach "$C/wt-0.8.0" "$UNDER_TEST" || exit 1
fi
echo "under test: $UNDER_TEST" | tee "$C/build-0.8.0.log"
(cd "$C/wt-0.8.0" && ./gradlew clean build installDist --no-watch-fs >> "$C/build-0.8.0.log" 2>&1)
built=$?
echo "build 0.8.0 exit=$built" | tee -a "$C/build-0.8.0.log"
grep -E "^test:" "$C/build-0.8.0.log"
# a failed build fails the run: the script used to end with grep's exit code, 0 whatever the build did
[ $built -eq 0 ] || exit 1
