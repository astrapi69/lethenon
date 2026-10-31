#!/usr/bin/env bash
# Compatibility of lethenon 0.8.0 with the published 0.7.0, both directions. Both keep the chain in the
# SQLite database of ADR 0006; the wallet file, the block encoding, HELLO and the peer list are those of
# 0.7.0. Measured on chains carrying real transfers - Ed25519, a payment to a published address and its
# sweep, ML-DSA-65 - and on transfers left waiting by one version and mined by the other. The network
# part runs inside a network namespace of its own (unshare -rn), so nothing reaches a real node.
# Run build.sh first. Writes results.tsv and measure.log next to
# this script.
#   bash docs/launch/compatibility-0.8.0/measure.sh
set -uo pipefail
C=$(cd "$(dirname "$0")" && pwd)
declare -A CLI=( [0.7.0]=$C/old/lethenon-0.7.0/bin/lethenon [0.8.0]=$C/wt-0.8.0/build/install/lethenon/bin/lethenon )
W=$C/run; rm -rf "$W"; mkdir -p "$W"
PW=correct-horse-battery-staple
OUT=$C/results.tsv; : > "$OUT"
LOG=$C/measure.log; : > "$LOG"
lc() { local v=$1; shift; printf '%s\n' "$PW" | "${CLI[$v]}" "$@" 2> "$W/err.txt" > "$W/out.txt"; local rc=$?
  { echo "### $v $*  -> exit $rc"; cat "$W/out.txt" "$W/err.txt"; } >> "$LOG"; return $rc; }
rec() { local v=$1 action=$2 chain=$3 rc=$4 msg; msg=$(head -1 "$W/err.txt"); [ -z "$msg" ] && msg=$(grep -m1 -E "holds|mined|signed|replayed|took|wrote|export" "$W/out.txt")
  printf '%s\t%s\t%s\t%s\t%s\n' "$v" "$action" "$chain" "$rc" "$msg" >> "$OUT"; }
step() { local v=$1 action=$2 chain=$3; shift 3; lc "$v" "$@"; local rc=$?; rec "$v" "$action" "$chain" $rc; return $rc; }
row() { printf '%s\t%s\t%s\t%s\t%s\n' "$@" >> "$OUT"; }
acct() { grep -m1 -oE "account \(ed25519\): [0-9a-f]+" "$W/out.txt" | awk '{print $3}'; }
pq() { grep -m1 -oE "account \(ml-dsa-65\): [0-9a-f]+" "$W/out.txt" | awk '{print $3}'; }
addr() { grep -m1 -oE "address \(publish this\): [0-9a-f]+:[0-9a-f]+" "$W/out.txt" | awk '{print $4}'; }
holds() { grep -m1 -oE "holds [0-9.]+ LETH" "$W/out.txt"; }
replayed() { grep -m1 -oE "replayed [0-9]+ blocks" "$W/out.txt"; }
sha() { sha256sum "$1" | cut -c1-16; }
kind() { head -c 15 "$1" | grep -q "SQLite format 3" && echo "SQLite database" || echo "chain file of 0.5.0"; }
other() { [ "$1" = 0.7.0 ] && echo 0.8.0 || echo 0.7.0; }

for v in 0.7.0 0.8.0; do for w in H A P; do lc $v wallet create --wallet "$W/$v-$w.wallet"; done; done

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
balances() { # reader-version chainfile label wallet-version
  local v=$1 f=$2 n; n=$(basename "$f")
  step $v "balance A, $3" "$n" balance --chain "$f" --wallet "$W/$4-A.wallet"; row "" "" "" "" "$(holds); $(replayed)"
  step $v "balance P, $3" "$n" balance --chain "$f" --wallet "$W/$4-P.wallet"; row "" "" "" "" "$(holds); $(replayed)"
}

echo "## each version writes its own test chain" >> "$OUT"
write_chain 0.7.0 "$W/t-0.7.0.lethenon"; write_chain 0.8.0 "$W/t-0.8.0.lethenon"
balances 0.7.0 "$W/t-0.7.0.lethenon" "own chain" 0.7.0
balances 0.8.0 "$W/t-0.8.0.lethenon" "own chain" 0.8.0
row 0.7.0 "file kind" t-0.7.0.lethenon - "$(kind "$W/t-0.7.0.lethenon")"
row 0.8.0 "file kind" t-0.8.0.lethenon - "$(kind "$W/t-0.8.0.lethenon")"

for v in 0.8.0 0.7.0; do
  o=$(other $v); src="$W/t-$o.lethenon"; x="$W/x-$v-on-$o.lethenon"; cp "$src" "$x"
  echo "## $v on a chain $o wrote" >> "$OUT"
  before=$(sha "$x")
  balances $v "$x" "chain of $o" "$o"
  row $v "file unchanged by reading" "$(basename "$x")" - "$([ "$before" = "$(sha "$x")" ] && echo yes || echo NO)"
  lc $o balance --chain "$x" --wallet "$W/$o-A.wallet"; r=$(acct)
  step $o "send 7 (left waiting by $o)" "$(basename "$x")" send --chain "$x" --wallet "$W/$o-H.wallet" --to "$r" --amount 7
  step $v "mine (takes $o's waiting transfer)" "$(basename "$x")" mine --chain "$x" --wallet "$W/$v-H.wallet"
  step $o "balance of the recipient, read back by $o" "$(basename "$x")" balance --chain "$x" --wallet "$W/$o-A.wallet"; row "" "" "" "" "$(holds); $(replayed)"
  row $o "file kind after $v wrote" "$(basename "$x")" - "$(kind "$x")"
done

echo "## wallets across versions" >> "$OUT"
step 0.8.0 "balance with 0.7.0's wallet A" t-0.7.0.lethenon balance --chain "$W/t-0.7.0.lethenon" --wallet "$W/0.7.0-A.wallet"; row "" "" "" "" "$(holds)"
step 0.7.0 "balance with 0.8.0's wallet A" t-0.8.0.lethenon balance --chain "$W/t-0.8.0.lethenon" --wallet "$W/0.8.0-A.wallet"; row "" "" "" "" "$(holds)"

echo "## the network, inside unshare -rn" >> "$OUT"
cp "$W/t-0.7.0.lethenon" "$W/n6.lethenon"; cp "$W/t-0.8.0.lethenon" "$W/n7.lethenon"
unshare -rn bash "$C/net.sh" "$W" "${CLI[0.7.0]}" "${CLI[0.8.0]}" "$PW" >> "$OUT" 2>> "$LOG"
echo done
