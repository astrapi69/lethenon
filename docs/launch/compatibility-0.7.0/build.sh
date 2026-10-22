#!/usr/bin/env bash
# Prepares the command lines measure.sh and roundtrip.sh compare: 0.6.1 and 0.5.0 exactly as they were
# published - the tar.gz of the GitHub release, its .sha256 and its signature checked against the
# release key from keys.openpgp.org - and the commit under test, built with the full gate. Run from a
# clone of lethenon.
#   bash docs/launch/compatibility-0.7.0/build.sh [<commit under test, default HEAD>]
set -uo pipefail
C=$(cd "$(dirname "$0")" && pwd)
UNDER_TEST=${1:-HEAD}
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
published 0.6.1 && published 0.5.0; ok=$?
gpgconf --homedir "$K" --kill gpg-agent; rm -rf "$K"
[ $ok -eq 0 ] || exit 1
[ -d "$C/wt-0.7.0" ] || git worktree add -q --detach "$C/wt-0.7.0" "$UNDER_TEST"
(cd "$C/wt-0.7.0" && ./gradlew clean build installDist --no-watch-fs > "$C/build-0.7.0.log" 2>&1; echo "build 0.7.0 exit=$?" | tee -a "$C/build-0.7.0.log")
grep -E "^test:" "$C/build-0.7.0.log"
