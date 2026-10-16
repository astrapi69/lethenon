#!/usr/bin/env bash
# The migration acceptance of #202 against a real chain: sync a chain file from a seed node with a
# build of 0.5.0 or earlier, then migrate a copy with this build and compare block by block.
# Usage: migrate-seed-chain.sh <lethenon launcher of 0.5.0> <lib dir of this build> <work dir> [peer]
set -euo pipefail
old=$1 lib=$2 work=$3 peer=${4:-185.112.144.29:18480}
here=$(cd "$(dirname "$0")" && pwd)
mkdir -p "$work/legacy" "$work/migrated" "$work/classes"
"$old" sync --chain "$work/legacy/seed.lethenon" --peer "$peer"
javac -d "$work/classes" -cp "$lib/*" "$here/MigrationCheck.java"
java --enable-native-access=ALL-UNNAMED -cp "$lib/*:$work/classes" MigrationCheck "$work/legacy/seed.lethenon" "$work/migrated"
