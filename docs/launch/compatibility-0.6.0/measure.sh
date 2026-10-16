#!/usr/bin/env bash
# Compatibility of lethenon 0.6.0 with 0.5.0, both directions. 0.6.0 keeps the chain in an SQLite
# database (ADR 0006) and migrates a chain file of 0.5.0 on first use; the wallet file, the block
# encoding, HELLO and the peer list are those of 0.5.0, protocol version 3 on both. Measured on chains
# carrying real transfers - Ed25519, a payment to a published address and its sweep, ML-DSA-65 - and a
# transfer still waiting in 0.5.0's pool at the migration. The network part runs inside a network
# namespace of its own (unshare -rn), so nothing reaches a real node.
# Run build.sh first. Optionally pass a copy of the test network's chain file of 0.5.0.
# Writes results.tsv and measure.log next to this script.
#   bash docs/launch/compatibility-0.6.0/measure.sh [<copy of a lethenon-test-2 chain file of 0.5.0>]
set -uo pipefail
C=$(cd "$(dirname "$0")" && pwd)
LIVE=${1:-}
declare -A CLI=( [0.5.0]=$C/wt-0.5.0/build/install/lethenon/bin/lethenon [0.6.0]=$C/wt-0.6.0/build/install/lethenon/bin/lethenon )
W=$C/run; rm -rf "$W"; mkdir -p "$W"
PW=correct-horse-battery-staple
OUT=$C/results.tsv; : > "$OUT"
LOG=$C/measure.log; : > "$LOG"
lc() { local v=$1; shift; printf '%s\n' "$PW" | "${CLI[$v]}" "$@" 2> "$W/err.txt" > "$W/out.txt"; local rc=$?
  { echo "### $v $*  -> exit $rc"; cat "$W/out.txt" "$W/err.txt"; } >> "$LOG"; return $rc; }
rec() { local v=$1 action=$2 chain=$3 rc=$4 msg; msg=$(head -1 "$W/err.txt"); [ -z "$msg" ] && msg=$(grep -m1 -E "holds|mined|signed|replayed|took|migrat" "$W/out.txt")
  printf '%s\t%s\t%s\t%s\t%s\n' "$v" "$action" "$chain" "$rc" "$msg" >> "$OUT"; }
step() { local v=$1 action=$2 chain=$3; shift 3; lc "$v" "$@"; local rc=$?; rec "$v" "$action" "$chain" $rc; return $rc; }
row() { printf '%s\t%s\t%s\t%s\t%s\n' "$@" >> "$OUT"; }
acct() { grep -m1 -oE "account \(ed25519\): [0-9a-f]+" "$W/out.txt" | awk '{print $3}'; }
pq() { grep -m1 -oE "account \(ml-dsa-65\): [0-9a-f]+" "$W/out.txt" | awk '{print $3}'; }
addr() { grep -m1 -oE "address \(publish this\): [0-9a-f]+:[0-9a-f]+" "$W/out.txt" | awk '{print $4}'; }
holds() { grep -m1 -oE "holds [0-9.]+ LETH" "$W/out.txt"; }
sha() { sha256sum "$1" | cut -c1-16; }
kind() { head -c 15 "$1" | grep -q "SQLite format 3" && echo "SQLite database" || echo "0.5.0 chain file"; }

for v in 0.5.0 0.6.0; do for w in H A P; do lc $v wallet create --wallet "$W/$v-$w.wallet"; done; done

write_chain() { # version chainfile
  local v=$1 f=$2 n; n=$(basename "$f")
  step $v "mine (genesis)" "$n" mine --testnet --chain "$f" --wallet "$W/$v-H.wallet"
  step $v "mine" "$n" mine --chain "$f" --wallet "$W/$v-H.wallet"
  lc $v balance --chain "$f" --wallet "$W/$v-A.wallet"; local a; a=$(acct)
  lc $v balance --chain "$f" --wallet "$W/$v-P.wallet"; local p; p=$(addr)
  lc $v balance --chain "$f" --wallet "$W/$v-H.wallet"; local hpq; hpq=$(pq)
  step $v "send ed25519 12.5" "$n" send --chain "$f" --wallet "$W/$v-H.wallet" --to "$a" --amount 12.5 --memo "no permanent record"
  step $v "send to address 20" "$n" send --chain "$f" --wallet "$W/$v-H.wallet" --to-address "$p" --amount 20
  step $v "send to own ml-dsa-65 100" "$n" send --chain "$f" --wallet "$W/$v-H.wallet" --to "$hpq" --amount 100
  step $v "mine" "$n" mine --chain "$f" --wallet "$W/$v-H.wallet"
  step $v "sweep" "$n" sweep --chain "$f" --wallet "$W/$v-P.wallet"
  step $v "send ml-dsa-65 5" "$n" send --chain "$f" --wallet "$W/$v-H.wallet" --suite ml-dsa-65 --to "$a" --amount 5
  step $v "mine" "$n" mine --chain "$f" --wallet "$W/$v-H.wallet"
}
balances() { # version chainfile label
  local v=$1 f=$2 n; n=$(basename "$f")
  step $v "balance A, $3" "$n" balance --chain "$f" --wallet "$W/$4-A.wallet"; row "" "" "" "" "$(holds)"
  step $v "balance P, $3" "$n" balance --chain "$f" --wallet "$W/$4-P.wallet"; row "" "" "" "" "$(holds)"
}

echo "## each version writes its own test chain" >> "$OUT"
write_chain 0.5.0 "$W/t5.lethenon"; write_chain 0.6.0 "$W/t6.lethenon"
balances 0.5.0 "$W/t5.lethenon" "own chain" 0.5.0
balances 0.6.0 "$W/t6.lethenon" "own chain" 0.6.0
row 0.5.0 "file kind" t5.lethenon - "$(kind "$W/t5.lethenon")"
row 0.6.0 "file kind" t6.lethenon - "$(kind "$W/t6.lethenon")"

echo "## 0.6.0 on a chain of 0.5.0, with a transfer still waiting in 0.5.0's pool: migrated on first use" >> "$OUT"
cp "$W/t5.lethenon" "$W/m.lethenon"; old=$(sha "$W/m.lethenon")
# the 0.6.0 recipient's account is read from 0.6.0's own chain: any 0.6.0 command on m.lethenon would
# migrate it before 0.5.0 could leave a transfer waiting in its pool
lc 0.6.0 balance --chain "$W/t6.lethenon" --wallet "$W/0.6.0-A.wallet"; r6=$(acct)
step 0.5.0 "send 7 (waits in 0.5.0's pool)" m.lethenon send --chain "$W/m.lethenon" --wallet "$W/0.5.0-H.wallet" --to "$r6" --amount 7
oldpending=$(sha "$W/m.lethenon.pending")
balances 0.6.0 "$W/m.lethenon" "first use, migrates" 0.5.0
row 0.6.0 "file kind after first use" m.lethenon - "$(kind "$W/m.lethenon")"
row 0.6.0 ".legacy equals the 0.5.0 file" m.lethenon - "$([ -e "$W/m.lethenon.legacy" ] && [ "$(sha "$W/m.lethenon.legacy")" = "$old" ] && echo yes || echo NO)"
row 0.6.0 ".pending.legacy equals 0.5.0's pool" m.lethenon - "$([ -e "$W/m.lethenon.pending.legacy" ] && [ "$(sha "$W/m.lethenon.pending.legacy")" = "$oldpending" ] && echo yes || echo NO)"
step 0.6.0 "mine (takes the waiting transfer)" m.lethenon mine --chain "$W/m.lethenon" --wallet "$W/0.6.0-H.wallet"
step 0.6.0 "balance of the 0.6.0 recipient" m.lethenon balance --chain "$W/m.lethenon" --wallet "$W/0.6.0-A.wallet"; row "" "" "" "" "$(holds)"
cp "$W/m.lethenon.legacy" "$W/legacy-copy.lethenon"
step 0.5.0 "balance on the .legacy copy" legacy-copy.lethenon balance --chain "$W/legacy-copy.lethenon" --wallet "$W/0.5.0-A.wallet"; row "" "" "" "" "$(holds)"

echo "## 0.5.0 on a chain of 0.6.0: refused, file unchanged" >> "$OUT"
before=$(sha "$W/t6.lethenon")
step 0.5.0 "balance" t6.lethenon balance --chain "$W/t6.lethenon" --wallet "$W/0.6.0-A.wallet"
step 0.5.0 "mine" t6.lethenon mine --chain "$W/t6.lethenon" --wallet "$W/0.5.0-H.wallet"
row 0.5.0 "file unchanged" t6.lethenon - "$([ "$before" = "$(sha "$W/t6.lethenon")" ] && echo yes || echo NO)"

echo "## wallets across versions" >> "$OUT"
balances 0.6.0 "$W/m.lethenon" "0.5.0's wallets" 0.5.0
cp "$W/t5.lethenon" "$W/w5.lethenon"
step 0.5.0 "balance with 0.6.0's holder wallet" w5.lethenon balance --chain "$W/w5.lethenon" --wallet "$W/0.6.0-H.wallet"

if [ -n "$LIVE" ]; then
  echo "## the test network's chain of 0.5.0, migrated by 0.6.0" >> "$OUT"
  cp "$LIVE" "$W/live.lethenon"; lold=$(sha "$W/live.lethenon")
  step 0.6.0 "balance (migrates, replays)" live.lethenon balance --chain "$W/live.lethenon" --wallet "$W/0.6.0-A.wallet"
  row 0.6.0 ".legacy equals the live file" live.lethenon - "$([ "$(sha "$W/live.lethenon.legacy")" = "$lold" ] && echo yes || echo NO)"
fi

echo "## the network, inside unshare -rn" >> "$OUT"
cp "$W/t5.lethenon" "$W/n5.lethenon"; cp "$W/t6.lethenon" "$W/n6.lethenon"
unshare -rn bash "$C/net.sh" "$W" "${CLI[0.5.0]}" "${CLI[0.6.0]}" "$PW" >> "$OUT" 2>> "$LOG"
echo done
