#!/data/data/com.termux/files/usr/bin/bash
# Signs the Gradle APK with the game's key (game.conf: key=, apk=) and copies it to
# /sdcard/Download/<apk>. If ~/.android/<key>.lineage exists (rotated from the old debug
# key), it signs with the rotation lineage so debug-signed installs update in place.
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"; PROJECT="$(cd "$HERE/.." && pwd)"
conf() { sed -n "s/^$1=//p" "$PROJECT/game.conf"; }
KEY="$(conf key)"; APK="$(conf apk)"
A="$HOME/.android"; P="$(cat "$A/$KEY-release.pass")"
IN="${1:-$PROJECT/app/build/outputs/apk/debug/app-debug.apk}"
OUT="${2:-/sdcard/Download/$APK}"
if [ -f "$A/$KEY.lineage" ]; then
  apksigner sign \
    --ks "$A/debug.keystore" --ks-pass pass:android --ks-key-alias androiddebugkey --key-pass pass:android \
    --next-signer --ks "$A/$KEY-release.jks" --ks-pass "pass:$P" --ks-key-alias "$KEY" --key-pass "pass:$P" \
    --lineage "$A/$KEY.lineage" --rotation-min-sdk-version 28 --v4-signing-enabled false --out "$OUT" "$IN"
else
  apksigner sign --ks "$A/$KEY-release.jks" --ks-pass "pass:$P" --ks-key-alias "$KEY" --key-pass "pass:$P" --v4-signing-enabled false --out "$OUT" "$IN"
fi
apksigner verify "$OUT" && echo "signed ($KEY) -> $OUT"
