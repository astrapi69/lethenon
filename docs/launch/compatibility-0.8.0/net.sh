#!/usr/bin/env bash
# The network part of measure.sh, run by it inside unshare -rn: loopback only, nothing else reachable.
#   net.sh <run directory> <0.7.0 command line> <0.8.0 command line> <password>
set -uo pipefail
W=$1; L6=$2; L7=$3; PW=$4
ip link set lo up
row() { printf '%s\t%s\t%s\t%s\t%s\n' "$@"; }
height() { printf '%s\n' "$PW" | "$1" balance --chain "$2" --wallet "$3" 2>/dev/null | grep -m1 -oE "replayed [0-9]+ blocks"; }

# sync: a node of one version serves, a sync of the other takes the whole chain
serve_and_sync() { # server-cli server-version chain port syncer-cli syncer-version
  "$1" node --chain "$3" --listen "$4" --no-discovery --for 30 > "$W/node-$2.out" 2>&1 &
  local pid=$! target="$W/synced-by-$6.lethenon"
  for i in $(seq 1 60); do ss -tln | grep -q "127.0.0.1:$4 \|0.0.0.0:$4 \|\*:$4 " && break; sleep 0.5; done
  "$5" sync --chain "$target" --peer "127.0.0.1:$4" --within 20 > "$W/sync-$6.out" 2>&1; local rc=$?
  row "$6" "sync from a node of $2" "$(basename "$3")" $rc "$(tail -1 "$W/sync-$6.out")"
  # both versions read either file, so the served chain and the synced one are replayed by the same
  # version, the syncer's: the same blocks, transfers, signatures and supply
  local served synced
  served=$(printf '%s\n' "$PW" | "$5" balance --chain "$3" --wallet "$W/$6-A.wallet" 2>/dev/null | grep -m1 "^replayed")
  synced=$(printf '%s\n' "$PW" | "$5" balance --chain "$target" --wallet "$W/$6-A.wallet" 2>/dev/null | grep -m1 "^replayed")
  row "$6" "synced chain replays as the served one" "$(basename "$3")" - "$([ -n "$served" ] && [ "$served" = "$synced" ] && echo "yes: $synced" || echo "NO: served '$served', synced '$synced'")"
  wait $pid
}
serve_and_sync "$L7" 0.8.0 "$W/n7.lethenon" 28701 "$L6" 0.7.0
serve_and_sync "$L6" 0.7.0 "$W/n6.lethenon" 28601 "$L7" 0.8.0

# blocks across versions: a node of one version mines, a node of the other is its peer and takes them;
# both start from the same chain, which either version reads
mine_into() { # miner-cli miner-version peer-cli peer-version chain port wallet
  cp "$5" "$W/miner-$2.lethenon"; cp "$5" "$W/peer-$4.lethenon"
  local before; before=$(height "$3" "$W/peer-$4.lethenon" "$7")
  "$3" node --chain "$W/peer-$4.lethenon" --listen "$6" --no-discovery --no-default-peers --for 40 > "$W/peer-$4.out" 2>&1 &
  local peer=$!
  for i in $(seq 1 60); do ss -tln | grep -q ":$6 " && break; sleep 0.5; done
  printf '%s\n' "$PW" | "$1" node --chain "$W/miner-$2.lethenon" --listen 0 --peer "127.0.0.1:$6" --no-discovery --mine --wallet "$7" --for 25 > "$W/miner-$2.out" 2>&1
  wait $peer
  row "$4" "takes the blocks a $2 miner mines" "$(basename "$5")" - "peer before: $before, after: $(height "$3" "$W/peer-$4.lethenon" "$7"); miner after: $(height "$1" "$W/miner-$2.lethenon" "$7")"
}
mine_into "$L7" 0.8.0 "$L6" 0.7.0 "$W/n7.lethenon" 28702 "$W/0.8.0-H.wallet"
mine_into "$L6" 0.7.0 "$L7" 0.8.0 "$W/n6.lethenon" 28602 "$W/0.7.0-H.wallet"
