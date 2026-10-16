#!/usr/bin/env bash
# Write load of a chain file before and after #202: the same chain written one block at a time by
# the ChainFile of 0.5.0 and by this build's, every write and sync recorded by iolog.c.
# Usage: measure-write-load.sh <chain in canonical encoding> <lib dir of 0.5.0> <lib dir of this build> <work dir>
set -euo pipefail
chain=$1 before=$2 after=$3 work=$4
here=$(cd "$(dirname "$0")" && pwd)
repo=$(cd "$here/../../.." && pwd)
mkdir -p "$work"
cc -shared -fPIC -O2 -o "$work/iolog.so" "$repo/src/test/native/iolog.c" -ldl -lpthread
for side in before after; do
  lib=$([ $side = before ] && echo "$before" || echo "$after")
  rm -rf "$work/$side" "$work/$side.iolog" "$work/$side-classes"; mkdir -p "$work/$side" "$work/$side-classes"
  javac -d "$work/$side-classes" -cp "$lib/*" "$here/WriteLoad.java"
  echo "== $side ($(ls "$lib" | grep lethenon))"
  LD_PRELOAD="$work/iolog.so" IOLOG_DIR="$work/$side/" IOLOG_FILE="$work/$side.iolog" \
    java --enable-native-access=ALL-UNNAMED -cp "$lib/*:$work/$side-classes" WriteLoad "$chain" "$work/$side/chain.lethenon"
  python3 -I "$here/iolog-sum.py" "$work/$side.iolog"
  ls -l "$work/$side"
done
