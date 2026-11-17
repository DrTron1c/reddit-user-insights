#!/usr/bin/env bash
SRC="$1"
cd "$SRC" || exit 1
show() { echo; echo "===== $1"; }
B=$(grep -rlE '"MutableState\(value=' --include=*.java . | head -3)
show "Files with MutableState(value= : $B"
for f in $B; do echo "--- $f"; grep -nE "class |public [a-zA-Z0-9_]+\(|toString|MutableState\(value=" "$f" | head -12 | cut -c1-240; done
BASE=$(basename $(echo "$B" | head -1) .java)
show "Classes extending $BASE"
grep -rlE "extends $BASE\b" --include=*.java . | head -10
for f in $(grep -rlE "extends $BASE\b" --include=*.java . | head -5); do echo "--- $f"; grep -nE "class |public [a-zA-Z0-9_]+\(|CREATOR" "$f" | head -10 | cut -c1-240; done
show "new <state>( calls"
for f in $(grep -rlE "extends $BASE\b" --include=*.java . | head -3); do c=$(basename "$f" .java); grep -rhoE "new $c\([^;]{0,80}" --include=*.java . | head -5; done
