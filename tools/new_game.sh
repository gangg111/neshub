#!/data/data/com.termux/files/usr/bin/bash
#
# New NES game app from this project:   tools/new_game.sh <name> <rom.nes> ["App name"]
#
#   <name>      short id, a-z0-9 (becomes ~/<name>, com.dklab.<name>, key, apk name)
#   <rom.nes>   the cartridge dump (iNES)
#
# Copies the shared parts (overlay, menu, core, save states, rewind...), gives the new
# app its own applicationId, name, signing key, the NES-pad icon and an empty game
# plug-in (games/<name>.c from games/template.c). Builds nothing; see the printed steps.
set -euo pipefail
SRC="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
NAME="${1:?name}"; ROM="${2:?rom.nes}"; LABEL="${3:-$NAME}"
[[ "$NAME" =~ ^[a-z][a-z0-9]*$ ]] || { echo "name: a-z0-9, starting with a letter" >&2; exit 2; }
[ "$(head -c4 "$ROM" | od -An -c | tr -d ' ')" = 'NES032' ] || { echo "$ROM is not an iNES file" >&2; exit 2; }
DST="$HOME/$NAME"
[ -e "$DST" ] && { echo "$DST already exists" >&2; exit 2; }

rsync -a --exclude build --exclude .gradle --exclude 'app/build' --exclude 'tools/padout' \
  --exclude 'app/src/main/assets/*' --exclude 'app/src/main/jniLibs' \
  --exclude 'tools/nestest' --exclude 'tools/lagtest' --exclude 'tools/*test' --exclude 'tools/fuzz' \
  --exclude 'tools/*probe' --exclude 'tools/*find' --exclude 'tools/deathtrace' --exclude 'tools/livesshow' \
  "$SRC/" "$DST/"
cd "$DST"
cp "$ROM" "app/src/main/assets/$NAME.nes"
cp app/src/main/cpp/games/template.c "app/src/main/cpp/games/$NAME.c"
cat > game.conf <<CONF
# Everything that makes this project one particular game (see tools/new_game.sh):
#   game = plug-in app/src/main/cpp/games/<game>.c (RAM fixes, mods, core options)
#   apk  = file name the signed APK gets in /sdcard/Download
#   key  = signing key ~/.android/<key>-release.jks
game=$NAME
apk=$NAME.apk
key=$NAME
CONF
sed -i "s/applicationId = \"[^\"]*\"/applicationId = \"com.dklab.$NAME\"/; s/versionCode = [0-9]*/versionCode = 1/; s/versionName = \"[^\"]*\"/versionName = \"1.0\"/" app/build.gradle.kts
sed -i "s|<string name=\"app_name\">[^<]*</string>|<string name=\"app_name\">$LABEL</string>|" app/src/main/res/values/strings.xml
cp tools/template-icon/ic_game_*.png app/src/main/res/drawable-nodpi/

A="$HOME/.android"
if [ ! -f "$A/$NAME-release.jks" ]; then
  python3 -c "import secrets;print(secrets.token_urlsafe(18))" > "$A/$NAME-release.pass"; chmod 600 "$A/$NAME-release.pass"
  keytool -genkeypair -keystore "$A/$NAME-release.jks" -alias "$NAME" -keyalg RSA -keysize 2048 -validity 10000 \
    -storepass "$(cat "$A/$NAME-release.pass")" -keypass "$(cat "$A/$NAME-release.pass")" -dname "CN=DK-LAB $LABEL" >/dev/null 2>&1
fi

cat <<NEXT
created $DST  (com.dklab.$NAME, "$LABEL", key $A/$NAME-release.jks)
next:
  cd $DST && tools/build_native.sh && ./gradlew assembleDebug -q && tools/sign_apk.sh
  measure slowdown first:  NES_OC=disabled tools/lagtest ...  (see tools/lagtest.c)
  game-specific fixes/mods: app/src/main/cpp/games/$NAME.c  (example: games/contraforce.c)
  icon: replace app/src/main/res/drawable-nodpi/ic_game_*.png (default: NES pad)
NEXT
