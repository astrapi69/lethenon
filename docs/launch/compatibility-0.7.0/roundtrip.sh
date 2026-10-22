#!/usr/bin/env bash
# The way back of 0.7.0 (#224), as a round trip without a deviation: a chain database of the published
# 0.6.1 -> 0.7.0's export -> a chain file of 0.5.0, which the published 0.5.0 reads -> 0.7.0 migrates it
# into a database again. The measurement that was the condition for mystic-crypt-ui 8.10, now with the
# export in place of 0.5.0's chain writer. Run build.sh first.
#
# For each chain:
#   stage 1   0.6.1 reads the database through its own reader and replays it (ProbeDb state)
#   export    0.7.0's own command line exports it: lethenon export --chain <database> --to <file>
#   stage 2   0.5.0 replays the exported file (Probe05 state), and 0.5.0's own command line reads it
#   migrate   0.7.0's own command line migrates a copy of the exported file on first use
#   stage 3   0.7.0 reads and replays the second database
#   stage 4   0.6.1 reads and replays the second database
# and checks
#   (1) the exported file is, byte for byte, what 0.5.0's own chain writer writes for the same blocks
#       and waiting transfers (Probe05 export), pending file included; for a chain that 0.5.0 wrote
#       itself, also the file 0.5.0 wrote
#   (2) the second database is the first, row for row (ProbeDb rows), and the export changed nothing
#       in the first
#   (3) all four stages replay to the same balances, nonces and supply
#   (4) the transfers waiting in the pool are there at every step: in the stage listings, in the
#       exported pending file, in the .pending.legacy file the migration leaves, and in both databases
#
#   bash docs/launch/compatibility-0.7.0/roundtrip.sh [<a running chain database of 0.6.1> [<a chain file 0.5.0 wrote>]]
# The running database is copied with SQLite's backup, which reads it while its node goes on; the
# chain file of 0.5.0 is migrated by 0.6.1 first. Writes results-roundtrip.tsv and roundtrip.log.
set -uo pipefail
C=$(cd "$(dirname "$0")" && pwd)
LIVE=${1:-}
BORN050=${2:-}
JAVA=${JAVA_HOME:+$JAVA_HOME/bin/}java
L5=$C/old/lethenon-0.5.0/lib
L61=$C/old/lethenon-0.6.1/lib
L7=$C/wt-0.7.0/build/install/lethenon/lib
SQL=$(ls "$L61"/sqlite-jdbc-*.jar)
declare -A CLI=( [0.5.0]=$C/old/lethenon-0.5.0/bin/lethenon [0.6.1]=$C/old/lethenon-0.6.1/bin/lethenon [0.7.0]=$C/wt-0.7.0/build/install/lethenon/bin/lethenon )
PW=correct-horse-battery-staple
W=$C/run-roundtrip
OUT=$C/results-roundtrip.tsv
LOG=$C/roundtrip.log
failures=0

P=$C/probes
rm -rf "$P/out05" "$P/out061" "$P/out070"
javac -d "$P/out05" -cp "$L5/*:$SQL" "$P/Probe05.java" 2>> "$LOG" || { echo "Probe05 does not compile"; exit 1; }
javac -d "$P/out061" -cp "$L61/*" "$P/io/github/astrapi69/lethenon/ProbeDb.java" 2>> "$LOG" || { echo "ProbeDb does not compile against 0.6.1"; exit 1; }
javac -d "$P/out070" -cp "$L7/*" "$P/io/github/astrapi69/lethenon/ProbeDb.java" 2>> "$LOG" || { echo "ProbeDb does not compile against 0.7.0"; exit 1; }
p05() { "$JAVA" --enable-native-access=ALL-UNNAMED -cp "$P/out05:$L5/*:$SQL" Probe05 "$@"; }
pdb() { local v=$1; shift; "$JAVA" --enable-native-access=ALL-UNNAMED -cp "$P/out${v//./}:$([ "$v" = 0.6.1 ] && echo "$L61" || echo "$L7")/*" io.github.astrapi69.lethenon.ProbeDb "$@"; }

lc() { local v=$1; shift; printf '%s\n' "$PW" | "${CLI[$v]}" "$@" > "$W/out.txt" 2> "$W/err.txt"; local rc=$?
  { echo "### $v $*  -> exit $rc"; cat "$W/out.txt" "$W/err.txt"; } >> "$LOG"; return $rc; }
row() { printf '%s\t%s\t%s\n' "$@" | tee -a "$OUT"; }
check() { # chain what condition-result
  if [ "$3" = yes ]; then row "$1" "$2" "yes"; else row "$1" "$2" "NO: $3"; failures=$((failures + 1)); fi; }
same() { cmp -s "$1" "$2" && echo yes || echo "differs ($(basename "$1") vs $(basename "$2"))"; }
same_or_both_absent() { if [ ! -e "$1" ] && [ ! -e "$2" ]; then echo yes; elif [ -e "$1" ] && [ -e "$2" ]; then same "$1" "$2"; else echo "only one of $(basename "$1"), $(basename "$2") exists"; fi; }
sha() { sha256sum "$1" | cut -c1-16; }
kind() { head -c 15 "$1" | grep -q "SQLite format 3" && echo "SQLite database" || echo "0.5.0 chain file"; }
acct() { grep -m1 -oE "account \($1\): [0-9a-f]+" "$W/out.txt" | awk '{print $3}'; }
addr() { grep -m1 -oE "address \(publish this\): [0-9a-f]+:[0-9a-f]+" "$W/out.txt" | awk '{print $4}'; }
counts() { echo "$(grep -c '^block' "$1") blocks, $(grep -c '^waiting' "$1") waiting"; }

roundtrip() { # name database [chain file 0.5.0 wrote, which the database was migrated from]
  local n=$1 d="$W/$1" db=$2 born=${3:-}
  mkdir -p "$d"
  row "$n" "database 1" "$(kind "$db"), $(stat -c %s "$db") bytes"
  pdb 0.6.1 state "$db" > "$d/state-1.txt" 2>> "$LOG"; check "$n" "stage 1: 0.6.1 replays the database" "$([ -s "$d/state-1.txt" ] && echo yes || echo "no listing")"
  row "$n" "stage 1 listing" "$(head -2 "$d/state-1.txt" | tr '\n' ' ')$(grep -c '^waiting' "$d/state-1.txt") waiting"
  pdb 0.6.1 rows "$db" > "$d/rows-1.txt" 2>> "$LOG"
  row "$n" "database 1 rows" "$(counts "$d/rows-1.txt"), $(grep -E '^(application_id|user_version)' "$d/rows-1.txt" | tr '\n' ' ')"

  lc 0.7.0 export --chain "$db" --to "$d/x.lethenon"; check "$n" "export by 0.7.0, exit $?" "$([ "$(kind "$d/x.lethenon" 2> /dev/null)" = "0.5.0 chain file" ] && echo yes || echo "no chain file of 0.5.0")"
  row "$n" "exported" "$(stat -c %s "$d/x.lethenon") bytes, sha256 $(sha "$d/x.lethenon"), pending file: $([ -e "$d/x.lethenon.pending" ] && echo "$(stat -c %s "$d/x.lethenon.pending") bytes" || echo none)"
  pdb 0.6.1 rows "$db" > "$d/rows-1-after-export.txt" 2>> "$LOG"
  check "$n" "(2) the export changed nothing in database 1" "$(same "$d/rows-1-after-export.txt" "$d/rows-1.txt")"

  p05 export "$db" "$d/w.lethenon" >> "$LOG" 2>&1; check "$n" "0.5.0's own chain writer wrote the same blocks, exit $?" "$([ -s "$d/w.lethenon" ] && echo yes || echo "no file")"
  check "$n" "(1) the export is what 0.5.0 writes, byte for byte" "$(same "$d/x.lethenon" "$d/w.lethenon")"
  check "$n" "(1, 4) and so is its pending file" "$(same_or_both_absent "$d/x.lethenon.pending" "$d/w.lethenon.pending")"
  if [ -n "$born" ]; then
    check "$n" "(1) the export is the file 0.5.0 wrote before the migration, byte for byte" "$(same "$d/x.lethenon" "$born")"
    check "$n" "(1, 4) and so is its pending file" "$(same_or_both_absent "$d/x.lethenon.pending" "$born.pending")"
  fi

  p05 state "$d/x.lethenon" > "$d/state-2.txt" 2>> "$LOG"
  local before; before=$(cat "$d/x.lethenon" "$d/x.lethenon.pending" 2> /dev/null | sha256sum)
  lc 0.5.0 balance --chain "$d/x.lethenon" --wallet "$W/0.5.0-reader.wallet"; local rc=$?
  check "$n" "stage 2: the published 0.5.0's command line reads the export, exit $rc: $(grep -m1 -oE 'replayed [0-9]+ blocks' "$W/out.txt")" "$([ $rc -eq 0 ] && echo yes || echo "$(head -1 "$W/err.txt")")"
  check "$n" "0.5.0 left the export and its pending file as they were" "$([ "$before" = "$(cat "$d/x.lethenon" "$d/x.lethenon.pending" 2> /dev/null | sha256sum)" ] && echo yes || echo "they changed")"

  cp "$d/x.lethenon" "$d/m2.lethenon"; [ -e "$d/x.lethenon.pending" ] && cp "$d/x.lethenon.pending" "$d/m2.lethenon.pending"
  lc 0.7.0 balance --chain "$d/m2.lethenon" --wallet "$W/0.7.0-reader.wallet"; check "$n" "migration by 0.7.0, exit $?" "$([ "$(kind "$d/m2.lethenon")" = "SQLite database" ] && echo yes || echo "not a database")"
  check "$n" "the migration left the export as .legacy, byte for byte" "$(same "$d/m2.lethenon.legacy" "$d/x.lethenon")"
  check "$n" "(4) and the pool as .pending.legacy, byte for byte" "$(same_or_both_absent "$d/m2.lethenon.pending.legacy" "$d/x.lethenon.pending")"
  pdb 0.7.0 rows "$d/m2.lethenon" > "$d/rows-2.txt" 2>> "$LOG"
  check "$n" "(2) the second database is the first, row for row ($(counts "$d/rows-2.txt"))" "$(same "$d/rows-2.txt" "$d/rows-1.txt")"
  pdb 0.7.0 state "$d/m2.lethenon" > "$d/state-3.txt" 2>> "$LOG"
  pdb 0.6.1 state "$d/m2.lethenon" > "$d/state-4.txt" 2>> "$LOG"

  local accounts; accounts=$(grep -c '^account' "$d/state-1.txt")
  for s in 2 3 4; do
    check "$n" "(3, 4) stage $s replays to stage 1: $accounts accounts, supply, nonces, $(grep -c '^waiting' "$d/state-$s.txt") waiting" "$(same "$d/state-$s.txt" "$d/state-1.txt")"
  done
  if [ -n "$born" ]; then
    p05 state "$born" > "$d/state-0.txt" 2>> "$LOG"
    check "$n" "(3, 4) 0.5.0 on the file it wrote replays to stage 1 as well" "$(same "$d/state-0.txt" "$d/state-1.txt")"
  fi
}

synthetic() { # database, extra: "pending" leaves transfers waiting; written by the published 0.6.1
  local f=$1 h="$W/0.6.1-H.wallet" a p hpq
  lc 0.6.1 mine --testnet --chain "$f" --wallet "$h"
  lc 0.6.1 mine --chain "$f" --wallet "$h"
  lc 0.6.1 balance --chain "$f" --wallet "$W/0.6.1-A.wallet"; a=$(acct ed25519)
  lc 0.6.1 balance --chain "$f" --wallet "$W/0.6.1-P.wallet"; p=$(addr)
  lc 0.6.1 balance --chain "$f" --wallet "$h"; hpq=$(acct ml-dsa-65)
  lc 0.6.1 send --chain "$f" --wallet "$h" --to "$a" --amount 12.5 --memo "no permanent record"
  lc 0.6.1 send --chain "$f" --wallet "$h" --to-address "$p" --amount 20
  lc 0.6.1 send --chain "$f" --wallet "$h" --to "$hpq" --amount 100
  lc 0.6.1 mine --chain "$f" --wallet "$h"
  lc 0.6.1 sweep --chain "$f" --wallet "$W/0.6.1-P.wallet"
  lc 0.6.1 send --chain "$f" --wallet "$h" --suite ml-dsa-65 --to "$a" --amount 5
  lc 0.6.1 mine --chain "$f" --wallet "$h"
  if [ "${2:-}" = pending ]; then
    lc 0.6.1 send --chain "$f" --wallet "$h" --to "$a" --amount 3.25 --memo "waits in the pool"
    lc 0.6.1 send --chain "$f" --wallet "$h" --suite ml-dsa-65 --to "$a" --amount 1.5
    lc 0.6.1 send --chain "$f" --wallet "$W/0.6.1-P.wallet" --to "$a" --amount 0.5
  fi
}

rm -rf "$W"; mkdir -p "$W"; : > "$OUT"
for w in H A P; do lc 0.6.1 wallet create --wallet "$W/0.6.1-$w.wallet"; done
lc 0.5.0 wallet create --wallet "$W/0.5.0-reader.wallet"
lc 0.7.0 wallet create --wallet "$W/0.7.0-reader.wallet"
lc 0.6.1 wallet create --wallet "$W/0.6.1-reader.wallet"

if [ -n "$LIVE" ]; then
  python3 -I -c 'import sqlite3, sys
source = sqlite3.connect("file:" + sys.argv[1] + "?mode=ro", uri=True)
copy = sqlite3.connect(sys.argv[2])
source.backup(copy)
copy.close(); source.close()' "$LIVE" "$W/live.lethenon" 2>> "$LOG"
  row "live" "copied with SQLite's backup from $(basename "$LIVE")" "$([ -s "$W/live.lethenon" ] && echo yes || echo NO)"
  roundtrip live "$W/live.lethenon"
fi

if [ -n "$BORN050" ]; then
  cp "$BORN050" "$W/born050.original"; [ -e "$BORN050.pending" ] && cp "$BORN050.pending" "$W/born050.original.pending"
  cp "$W/born050.original" "$W/born050.lethenon"; [ -e "$W/born050.original.pending" ] && cp "$W/born050.original.pending" "$W/born050.lethenon.pending"
  lc 0.6.1 balance --chain "$W/born050.lethenon" --wallet "$W/0.6.1-reader.wallet"
  check "born-0.5.0" "0.6.1 migrated the chain file 0.5.0 wrote, exit $?" "$([ "$(kind "$W/born050.lethenon")" = "SQLite database" ] && echo yes || echo "not a database")"
  roundtrip born-0.5.0 "$W/born050.lethenon" "$W/born050.original"
fi

synthetic "$W/synthetic.lethenon"
roundtrip synthetic "$W/synthetic.lethenon"

synthetic "$W/waiting.lethenon" pending
roundtrip waiting "$W/waiting.lethenon"

echo "failures: $failures" | tee -a "$OUT"
exit $((failures > 0))
