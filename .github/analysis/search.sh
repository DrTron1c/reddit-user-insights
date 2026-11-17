#!/usr/bin/env bash
# Prints short search results from the decompiled code.
SRC="$1"
cd "$SRC" || exit 1
show() { echo; echo "===== $1"; }

H=com/reddit/postdetail/comment/refactor/header
show "Header files"
find "$H" -name "*.java" -printf "%s %p\n" | sort -k2 | head -60

for f in $(find "$H" -name "*.java" | sort | head -40); do
  show "SIGNATURES $f"
  grep -nE "^(public |final |static |abstract )*(class|interface|enum) |^\s+(public|private|protected)?\s*(static )?(final )?[A-Za-z0-9_<>\[\], .]+ [A-Za-z0-9_]+\(.*\) ?\{|\"[^\"]{2,}\"" "$f" | cut -c1-260 | head -70
done

show "comment_author usages"
grep -rn -B4 -A30 '"comment_author"' --include=*.java . | cut -c1-240 | head -260

show "Comment(authorLine= class"
f=$(grep -rlE '"Comment\(authorLine=' --include=*.java . | head -1); echo "$f"; head -80 "$f" | cut -c1-220

show "AuthorHeader class"
f=$(grep -rlE '"AuthorHeader\(authorAvatarUrl=' --include=*.java . | head -1); echo "$f"; head -60 "$f" | cut -c1-220

show "Compose state"
for f in defpackage/bhi0.java defpackage/wfp.java; do echo "--- $f"; grep -nE "class |MutableState\(value=|StructuralEqualityPolicy|public .*\(" "$f" | head -30; done
