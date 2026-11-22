#!/data/data/com.termux/files/usr/bin/bash
# Builds libgbcplayer.so = FCEUmm (libretro, ~/fceumm-src) + nes_core.c + JNI shim.
#   tools/build_native.sh        core objects (make) + .so + host test
#   tools/build_native.sh so|test
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT="$(cd "$HERE/.." && pwd)"
FCEU="${FCEU_SRC:-$HOME/fceumm-src}"
CPP="$PROJECT/app/src/main/cpp"
# The cartridge plug-in: app/src/main/cpp/games/$GAME.c (see game.h). Set in game.conf.
GAME="${GAME:-$(sed -n 's/^game=//p' "$PROJECT/game.conf" 2>/dev/null)}"
GAME="${GAME:-template}"
SO="$PROJECT/app/src/main/jniLibs/arm64-v8a/libgbcplayer.so"
CC=aarch64-linux-android-clang
INC=(-I"$CPP" -I"$FCEU/src/drivers/libretro/libretro-common/include")

core() {
  make -C "$FCEU" -f Makefile.libretro platform=unix CC=$CC -j8 >/dev/null
  mapfile -t OBJS < <(find "$FCEU/src" -name '*.o' | sort)
  rm -f "$HERE/libfceumm.a"; llvm-ar rcs "$HERE/libfceumm.a" "${OBJS[@]}"
  echo "core: ${#OBJS[@]} objects"
}
# Hub: games=<id> <id> ... in game.conf links every listed plug-in, each with its
# symbols renamed to <id>_*, plus a generated table (hub_games.c); see game.h.
GAMES="${GAMES:-$(sed -n 's/^games=//p' "$PROJECT/game.conf" 2>/dev/null)}"
hub_sources() {
  local out="$HERE/.hub"; rm -rf "$out"; mkdir -p "$out"
  local tab="$out/hub_games.c" ids=()
  { echo '#define NES_HUB_TABLE'; echo '#include <string.h>'; echo '#include "game.h"'; } > "$tab"
  for g in $GAMES generic; do
    local src="$CPP/games/$g.c"; [ "$g" = generic ] && src="$CPP/games/template.c"
    local D=()
    for s in game_sig game_options game_mods game_mod_count game_pre_frame game_post_frame game_after_load game_on_power; do
      D+=("-D$s=${g}_$s")
    done
    $CC -O2 -fPIC -c "${INC[@]}" "${D[@]}" -o "$out/$g.o" "$src"
    cat >> "$tab" <<C
extern const GameSig ${g}_game_sig; extern const GameOption ${g}_game_options[]; extern const GameMod ${g}_game_mods[]; extern const int ${g}_game_mod_count;
void ${g}_game_pre_frame(uint8_t*, uint32_t*, uint32_t*); void ${g}_game_post_frame(uint8_t*);
void ${g}_game_after_load(uint8_t*); void ${g}_game_on_power(uint8_t*);
C
    ids+=("$g")
  done
  { echo 'static const HubGame games[] = {'
    for g in "${ids[@]}"; do
      echo "  {\"$g\", ${g}_game_options, ${g}_game_mods, &${g}_game_mod_count, ${g}_game_pre_frame, ${g}_game_post_frame, ${g}_game_after_load, ${g}_game_on_power, &${g}_game_sig},"
    done
    echo '};'
    echo 'static const HubGame* cur = &games[0];'
    cat <<'C'
/* "generic" (the template: no fixes, no mods) is last and never matched by a signature. */
static uint32_t crc32(const uint8_t* p, size_t n) {
  uint32_t c = 0xFFFFFFFFu;
  while (n--) { c ^= *p++; for (int k = 0; k < 8; ++k) c = (c >> 1) ^ (0xEDB88320u & -(c & 1)); }
  return ~c;
}
static int sig_match(const GameSig* s, const uint8_t* rom, size_t size) {
  if (!s->bank_count || size < 16 || memcmp(rom, "NES\x1a", 4)) return 0;
  int mapper = (rom[6] >> 4) | (rom[7] & 0xF0), prg = rom[4] * 16, chr = rom[5] * 8;
  size_t off = 16 + ((rom[6] & 4) ? 512 : 0);
  if (mapper != s->mapper || prg != s->prg_kb || chr != s->chr_kb || off + (size_t)prg * 1024 > size) return 0;
  for (int i = 0; i < s->bank_count; ++i) {
    if ((s->banks[i].bank + 1) * 8 > prg) return 0;
    if (crc32(rom + off + (size_t)s->banks[i].bank * 8192, 8192) != s->banks[i].crc) return 0;
  }
  return 1;
}
const char* hub_detect(const uint8_t* rom, size_t size) {
  const unsigned n = sizeof games / sizeof games[0];
  for (unsigned i = 0; i + 1 < n; ++i) if (sig_match(games[i].sig, rom, size)) { cur = &games[i]; return cur->id; }
  cur = &games[n - 1];
  return NULL;
}
C
    echo 'const HubGame* hub_game(void) { return cur; }'
    echo 'int hub_select(const char* id) { for (unsigned i = 0; i < sizeof games / sizeof games[0]; ++i) if (!strcmp(games[i].id, id)) { cur = &games[i]; return 1; } return 0; }'
  } >> "$tab"
  HUB_SRC=("-DNES_HUB" "$tab" "$out"/*.o)
}
build_so() {
  mkdir -p "$(dirname "$SO")"
  local SRC=("$CPP/games/$GAME.c")
  if [ -n "$GAMES" ]; then hub_sources; SRC=(); fi
  $CC -O2 -fPIC -shared "${INC[@]}" ${HUB_SRC[@]+"${HUB_SRC[@]:0:1}"} -Wl,--gc-sections -Wl,-z,max-page-size=16384 \
    -Wl,-soname,libgbcplayer.so -o "$SO" "$CPP/nes_core.c" ${SRC[@]+"${SRC[@]}"} ${HUB_SRC[@]+"${HUB_SRC[@]:1}"} "$CPP/gbcplayer.c" \
    -Wl,--whole-archive "$HERE/libfceumm.a" -Wl,--no-whole-archive -llog -lm
  python3 "$HERE/strip_runpath.py" "$SO"
  readelf -d "$SO" | command grep -E 'RPATH|RUNPATH' && { echo "rpath survived" >&2; exit 1; }
  echo "NEEDED: $(readelf -d "$SO" | sed -n 's/.*NEEDED.*\[\(.*\)\].*/\1/p' | tr '\n' ' ')"
  echo "built $SO ($(stat -c %s "$SO") bytes)"
}
build_test() {
  clang -O2 -DGBC_HOST_TEST -include "$HERE/compat.h" "${INC[@]}" -o "$HERE/nestest" "$CPP/nes_core.c" "$CPP/games/$GAME.c" "$HERE/nestest.c" \
    -Wl,--whole-archive "$HERE/libfceumm.a" -Wl,--no-whole-archive -lm
  clang -O2 -DGBC_HOST_TEST -include "$HERE/compat.h" "${INC[@]}" -o "$HERE/lagtest" "$CPP/nes_core.c" "$CPP/games/$GAME.c" "$HERE/lagtest.c" \
    -Wl,--whole-archive "$HERE/libfceumm.a" -Wl,--no-whole-archive -lm
  echo "built $HERE/nestest $HERE/lagtest"
}
case "${1:-all}" in
  all) core; build_so; build_test ;;
  so) build_so ;; test) build_test ;;
  *) echo "usage: $0 [all|so|test]" >&2; exit 2 ;;
esac
