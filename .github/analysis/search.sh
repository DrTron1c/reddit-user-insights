#!/usr/bin/env bash
SRC="$1"
cd "$SRC" || exit 1
show() { echo; echo "===== $1"; }

F=$(grep -rlE '"comment_author"' --include=*.java defpackage | xargs grep -l '"role_indicator"' | head -1)
show "Author composable file: $F"
grep -nE "public static final [a-zA-Z0-9_]+ [a-zA-Z0-9_]+\(" "$F" | head -60
L=$(grep -n '"comment_author"' "$F" | head -1 | cut -d: -f1)
START=$(awk -v l="$L" 'NR<=l && /public static final void [a-zA-Z0-9_]+\(/ {s=NR} END {print s}' "$F")
show "Method around comment_author ($START..$L)"
sed -n "${START},$((L+30))p" "$F" | cut -c1-300

NAME=$(sed -n "${START}p" "$F" | grep -oE "void [a-zA-Z0-9_]+\(" | sed 's/void //;s/(//')
CLS=$(basename "$F" .java)
show "Callers of $CLS.$NAME"
grep -rn "$CLS\.$NAME(" --include=*.java . | head -10 | cut -c1-300
C=$(grep -rln "$CLS\.$NAME(" --include=*.java . | grep -v "$F" | head -1)
if [ -n "$C" ]; then
  CL=$(grep -n "$CLS\.$NAME(" "$C" | head -1 | cut -d: -f1)
  show "Caller context $C:$CL"
  sed -n "$((CL-60)),$((CL+60))p" "$C" | cut -c1-300
fi

show "comment_age usages"
grep -rn -B3 -A12 '"comment_age"' --include=*.java defpackage | cut -c1-260 | head -120

show "Text composable"
T=$(sed -n "$L,$((L+8))p" "$F" | grep -oE "[a-z0-9]+\.b\(str" | head -1 | cut -d. -f1)
echo "class=$T"
grep -nE "public static final void b\(" "defpackage/$T.java" | cut -c1-400

show "Role indicator composable"
R=$(sed -n "$L,$((L+10))p" "$F" | grep -oE "[a-z0-9]+\.[A-Za-z]+\([^;]*role_indicator" | head -1)
echo "$R"
RC=$(echo "$R" | cut -d. -f1); RM=$(echo "$R" | cut -d. -f2 | cut -d'(' -f1)
grep -nE "public static final void $RM\(" "defpackage/$RC.java" | cut -c1-300

show "Header model"
M=$(grep -rlE '"CommentHeader' --include=*.java defpackage | head -3)
for m in $M; do echo "--- $m"; grep -nE "public final |return \"" "$m" | head -40 | cut -c1-260; done
for m in defpackage/v6a.java; do echo "--- $m"; sed -n 1,60p "$m" | cut -c1-200; grep -n 'return "' "$m" | cut -c1-400; done
