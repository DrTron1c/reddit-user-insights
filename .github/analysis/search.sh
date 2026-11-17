#!/usr/bin/env bash
# Prints short search results from the decompiled code (no whole files).
SRC="$1"
cd "$SRC" || exit 1
show() { echo; echo "===== $1"; }

show "Packages with comment UI"
find . -type d | grep -iE "comment" | grep -viE "test|preview" | head -80

show "Data class strings mentioning author (toString)"
grep -rhoE '"[A-Za-z]*(Comment|Header|Author|Metadata)[A-Za-z]*\((author|authorName|username|userName|displayName)[A-Za-z]*=' --include=*.java . | sort | uniq -c | sort -rn | head -60

show "Files with comment header toString"
grep -rlE '"Comment[A-Za-z]*Header[A-Za-z]*\(' --include=*.java . | head -40

show "Test tags / keys with comment_ or author"
grep -rhoE '"(comment_[a-z_]+|[a-z_]*author[a-z_]*)"' --include=*.java . | sort | uniq -c | sort -rn | head -80

show "Compose state classes"
grep -rlE '"MutableState\(value=|StructuralEqualityPolicy' --include=*.java . | head -10
