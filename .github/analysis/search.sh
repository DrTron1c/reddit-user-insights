#!/usr/bin/env bash
SRC="$1"
cd "$SRC" || exit 1
show() { echo; echo "===== $1"; }
show "xx9 lambda"
grep -nE "case |n55\.|public xx9\(|this\.[a-z] = " defpackage/xx9.java | head -40 | cut -c1-240
show "new xx9( with v6a"
grep -rn "new xx9(" --include=*.java defpackage | head -20 | cut -c1-260
for L in $(grep -rn "new xx9(v6a" --include=*.java defpackage | head -3 | cut -d: -f1,2); do
  f=$(echo "$L" | cut -d: -f1); n=$(echo "$L" | cut -d: -f2)
  S=$(awk -v l="$n" 'NR<=l && /public static final void [a-zA-Z0-9_]+\(/ {s=NR} END {print s}' "$f")
  show "Context $f:$n (method at $S)"
  sed -n "${S}p" "$f" | cut -c1-300
  sed -n "$((n-50)),$((n+40))p" "$f" | cut -c1-260
done
show "Text calls with v6a string fields"
grep -rnE "ynl0\.b\(v6aVar\.[a-z]|ynl0\.b\([a-z0-9]+\.[a-z]\(v6aVar" --include=*.java defpackage | head -20 | cut -c1-300
show "v6a field reads of d/e/h"
grep -rnE "v6aVar\.(d|e|h|a|b)\b" --include=*.java defpackage | head -30 | cut -c1-220
