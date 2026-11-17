#!/usr/bin/env bash
SRC="$1"
cd "$SRC" || exit 1
show() { echo; echo "===== $1"; }
F=defpackage/n55.java
show "v6a toString"
grep -n 'return "' defpackage/v6a.java | cut -c1-900
show "Calls to f( inside n55 with context"
for L in $(grep -nE "[^a-zA-Z0-9_.]f\([^)]*v6a" "$F" | cut -d: -f1 | head -5); do
  S=$(awk -v l="$L" 'NR<=l && /public static final void [a-zA-Z0-9_]+\(/ {s=NR} END {print s}' "$F")
  echo "--- call at $L in method starting $S"
  sed -n "${S}p" "$F" | cut -c1-300
  sed -n "$((L-40)),$((L+60))p" "$F" | cut -c1-260
done
show "Other files calling n55.f or using v6a fields d/e/h"
grep -rlE "v6a" --include=*.java defpackage | head -20
