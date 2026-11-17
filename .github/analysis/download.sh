#!/usr/bin/env bash
# Downloads the latest Reddit app and puts its base APK at work/base.apk.
set -u
mkdir -p work
cd work
UA="Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"
ok=0
for url in \
  "https://d.apkpure.com/b/XAPK/com.reddit.frontpage?version=latest" \
  "https://d.apkpure.net/b/XAPK/com.reddit.frontpage?version=latest" \
  "https://d.apkpure.com/b/APK/com.reddit.frontpage?version=latest"; do
  echo "Trying $url"
  if curl -sSL --fail -A "$UA" -o reddit.bin "$url"; then
    size=$(stat -c %s reddit.bin)
    echo "Downloaded $size bytes"
    if [ "$size" -gt 10000000 ]; then ok=1; break; fi
  fi
done
if [ "$ok" != 1 ]; then echo "DOWNLOAD FAILED"; exit 1; fi
file reddit.bin
mkdir -p x
if unzip -l reddit.bin | grep -q "AndroidManifest.xml"; then
  cp reddit.bin base.apk
else
  unzip -o -q reddit.bin -d x
  ls -la x
  base=$(ls x/*.apk | grep -E "com.reddit.frontpage.apk|base.apk" | head -1)
  [ -z "$base" ] && base=$(ls -S x/*.apk | head -1)
  cp "$base" base.apk
fi
AAPT=$(ls "$ANDROID_HOME"/build-tools/*/aapt 2>/dev/null | tail -1)
"$AAPT" dump badging base.apk 2>/dev/null | grep -E "^package|sdkVersion" || true
unzip -l base.apk | grep -c "classes.*dex" || true
