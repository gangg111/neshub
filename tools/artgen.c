/* The hub's cartridge art on a PC, exactly as the app makes it (ArtGen.kt): runs a ROM
 * with an input script and writes frames [from, to) as raw RGB (256x224x3 each).
 *   artgen rom.nes "<script>" from to out.raw [every]
 * Script: comma-separated "buttons:frames", buttons as a hex mask of GBC_BUTTON_* (1 A,
 * 2 B, 4 SELECT, 8 START, 10 RIGHT, 20 LEFT, 40 UP, 80 DOWN; as tools/fm2play's BOTPAD);
 * a segment starting with '*' begins the part that repeats forever. The game module is
 * picked by the ROM's signature, as in the app.
 * Build (hub objects from tools/build_native.sh):
 *   clang -O2 -DGBC_HOST_TEST -DNES_HUB -Iapp/src/main/cpp -I$FCEU/src/drivers/libretro/libretro-common/include \
 *     -o tools/artgen tools/artgen.c app/src/main/cpp/nes_core.c tools/.hub/hub_games.c tools/.hub/*.o \
 *     -Wl,--whole-archive tools/libfceumm.a -Wl,--no-whole-archive -lm */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "gbcplayer.h"
const char* hub_detect(const uint8_t* rom, size_t size);
int nestest_b_reads;

typedef struct { int n; uint32_t b; } Seg;
static Seg seg[256]; static int nseg, loop_at = -1;

static void parse(const char* s) {
  while (*s && nseg < 256) {
    if (*s == '*') { loop_at = nseg; s++; }
    char* end; uint32_t b = strtoul(s, &end, 16);
    if (*end != ':') break;
    seg[nseg].b = b; seg[nseg].n = strtol(end + 1, &end, 10); nseg++;
    s = *end == ',' ? end + 1 : end;
  }
}
/* the buttons held on frame f (0-based) */
static uint32_t buttons(long f) {
  long t = 0;
  for (int i = 0; i < nseg; i++) { if (f < t + seg[i].n) return seg[i].b; t += seg[i].n; }
  if (loop_at < 0) return 0;
  long head = 0, len = 0;
  for (int i = 0; i < loop_at; i++) head += seg[i].n;
  for (int i = loop_at; i < nseg; i++) len += seg[i].n;
  if (len <= 0) return 0;
  long r = (f - head) % len;
  for (int i = loop_at; i < nseg; i++) { if (r < seg[i].n) return seg[i].b; r -= seg[i].n; }
  return 0;
}

int main(int argc, char** argv) {
  if (argc < 6) { fprintf(stderr, "artgen rom script from to out.raw [every]\n"); return 2; }
  FILE* f = fopen(argv[1], "rb"); if (!f) return 1;
  fseek(f, 0, SEEK_END); long n = ftell(f); rewind(f);
  uint8_t* rom = malloc(n); fread(rom, 1, n, f); fclose(f);
  const char* id = hub_detect(rom, n); fprintf(stderr, "module %s\n", id ? id : "(none)");
  parse(argv[2]);
  if (getenv("ARTGEN_BUTTONS")) { for (long f = 0; f < atol(argv[4]); f++) printf("%x\n", buttons(f)); return 0; }   /* check the script parser */
  long from = atol(argv[3]), to = atol(argv[4]); int every = argc > 6 ? atoi(argv[6]) : 1;
  char err[128]; GbcEmu* e = gbc_open(rom, n, 48000, err, sizeof err);
  if (!e) { puts(err); return 1; }
  FILE* o = fopen(argv[5], "wb");
  static uint32_t px[GBC_SCREEN_PIXELS]; static int16_t pcm[8192]; static uint8_t rgb[GBC_SCREEN_PIXELS * 3];
  for (long fr = 0; fr < to; ++fr) {
    gbc_set_buttons(e, buttons(fr));
    gbc_run_frame(e, px, pcm, 4096);
    if (fr >= from && (fr - from) % every == 0) {
      for (int i = 0; i < GBC_SCREEN_PIXELS; ++i) { rgb[3*i] = px[i] >> 16; rgb[3*i+1] = px[i] >> 8; rgb[3*i+2] = px[i]; }
      fwrite(rgb, 1, sizeof rgb, o);
    }
  }
  fclose(o); gbc_close(e);
  return 0;
}
