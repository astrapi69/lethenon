#!/usr/bin/env bash
# Compatibility of lethenon 0.5.0 with 0.4.0, both directions. 0.5.0 changes no format: the chain
# encoding, the wallet file, HELLO and the peer list are the code of 0.4.0, protocol version 3 on
# both. So every step here is expected to succeed, and this measures that it does, on chains carrying
# real transfers: an Ed25519 transfer, a payment to a published address and its sweep, a transfer from
# an ML-DSA-65 account. Wallets, chain files and the network, in both directions; the network inside
# a network namespace of its own (unshare -rn), so nothing reaches a real node.
# Run build.sh first. Optionally pass a copy of the test network's chain file, which 0.4.0 replays.
# Writes results.tsv and measure.log next to this script.
#   bash docs/launch/compatibility-0.5.0/measure.sh [<copy of a lethenon-test-2 chain file>]
set -uo pipefail
C=$(cd "$(dirname "$0")" && pwd)
LIVE=${1:-}
declare -A CLI=( [0.4.0]=$C/wt-0.4.0/build/install/lethenon/bin/lethenon [0.5.0]=$C/wt-0.5.0/build/install/lethenon/bin/lethenon )
W=$C/run; rm -rf "$W"; mkdir -p "$W"
PW=correct-horse-battery-staple
OUT=$C/results.tsv; : > "$OUT"
LOG=$C/measure.log; : > "$LOG"
lc() { local v=$1; shift; printf '%s\n' "$PW" | "${CLI[$v]}" "$@" 2> "$W/err.txt" > "$W/out.txt"; local rc=$?
  { echo "### $v $*  -> exit $rc"; cat "$W/out.txt" "$W/err.txt"; } >> "$LOG"; return $rc; }
rec() { local v=$1 action=$2 chain=$3 rc=$4 msg; msg=$(head -1 "$W/err.txt"); [ -z "$msg" ] && msg=$(grep -m1 -E "holds|mined|signed|replayed|took" "$W/out.txt")
  printf '%s\t%s\t%s\t%s\t%s\n' "$v" "$action" "$chain" "$rc" "$msg" >> "$OUT"; }
step() { local v=$1 action=$2 chain=$3; shift 3; lc "$v" "$@"; local rc=$?; rec "$v" "$action" "$chain" $rc; return $rc; }
acct() { grep -m1 -oE "account \(ed25519\): [0-9a-f]+" "$W/out.txt" | awk '{print $3}'; }
pq() { grep -m1 -oE "account \(ml-dsa-65\): [0-9a-f]+" "$W/out.txt" | awk '{print $3}'; }
addr() { grep -m1 -oE "address \(publish this\): [0-9a-f]+:[0-9a-f]+" "$W/out.txt" | awk '{print $4}'; }
holds() { grep -m1 -oE "holds [0-9.]+ LETH" "$W/out.txt"; }

for v in 0.4.0 0.5.0; do for w in H A P; do lc $v wallet create --wallet "$W/$v-$w.wallet"; done; done

# A test chain written by one version: Ed25519 transfer, payment to a published address and its sweep,
# a transfer from the holder's ML-DSA-65 account, in four blocks
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

# The other version reads the chain with the writer's wallets (wallet compatibility), then appends to a
# copy, and the writer replays that copy
cross() { # writer reader chainfile
  local w=$1 r=$2 f=$3 n before; n=$(basename "$f")
  before=$(sha256sum "$f" | cut -c1-16)
  step $r "balance, writer's recipient wallet" "$n" balance --chain "$f" --wallet "$W/$w-A.wallet"; printf '\t\t\t\t%s\n' "$(holds)" >> "$OUT"
  step $r "balance, writer's address wallet" "$n" balance --chain "$f" --wallet "$W/$w-P.wallet"; printf '\t\t\t\t%s\n' "$(holds)" >> "$OUT"
  printf '%s\t%s\t%s\t%s\t%s\n' $r "file unchanged by reading" "$n" - "$([ "$before" = "$(sha256sum "$f" | cut -c1-16)" ] && echo yes || echo NO)" >> "$OUT"
  cp "$f" "$W/$n.appended"
  lc $r balance --chain "$f" --wallet "$W/$r-A.wallet"; local ra; ra=$(acct)
  step $r "send 7 from the writer's holder wallet" "$n.appended" send --chain "$W/$n.appended" --wallet "$W/$w-H.wallet" --to "$ra" --amount 7
  step $r "mine" "$n.appended" mine --chain "$W/$n.appended" --wallet "$W/$r-H.wallet"
  step $w "balance, reader's recipient after the reader's block" "$n.appended" balance --chain "$W/$n.appended" --wallet "$W/$r-A.wallet"; printf '\t\t\t\t%s\n' "$(holds)" >> "$OUT"
}

echo "## each version writes its own test chain" >> "$OUT"
write_chain 0.4.0 "$W/t4.lethenon"; write_chain 0.5.0 "$W/t5.lethenon"
for v in 0.4.0 0.5.0; do
  f="$W/t${v:2:1}.lethenon"
  step $v "balance A, own chain" "$(basename "$f")" balance --chain "$f" --wallet "$W/$v-A.wallet"; printf '\t\t\t\t%s\n' "$(holds)" >> "$OUT"
  step $v "balance P, own chain" "$(basename "$f")" balance --chain "$f" --wallet "$W/$v-P.wallet"; printf '\t\t\t\t%s\n' "$(holds)" >> "$OUT"
done
echo "## 0.4.0 on the chain of 0.5.0, 0.5.0 on the chain of 0.4.0" >> "$OUT"
cross 0.5.0 0.4.0 "$W/t5.lethenon"
cross 0.4.0 0.5.0 "$W/t4.lethenon"
if [ -n "$LIVE" ]; then
  echo "## 0.4.0 replays the test network's chain, written by 0.5.0 development builds" >> "$OUT"
  cp "$LIVE" "$W/live.lethenon"
  step 0.4.0 "balance (replay)" live.lethenon balance --chain "$W/live.lethenon" --wallet "$W/0.4.0-A.wallet"
  step 0.5.0 "balance (replay)" live.lethenon balance --chain "$W/live.lethenon" --wallet "$W/0.5.0-A.wallet"
fi
echo "## the network, inside unshare -rn" >> "$OUT"
cp "$W/t4.lethenon" "$W/n4.lethenon"; cp "$W/t5.lethenon" "$W/n5.lethenon"
unshare -rn bash "$C/net.sh" "$W" "${CLI[0.4.0]}" "${CLI[0.5.0]}" "$PW" >> "$OUT" 2>> "$LOG"
echo done
