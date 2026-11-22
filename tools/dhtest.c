/* Duck Hunt via the touch Zapper: START (game A), then every few frames look for a
 * duck in the picture (pixels unlike sky/grass/tree) and tap on it. Dumps frames.
 *   dhtest rom outdir frames */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "gbcplayer.h"
#include "game.h"
#include "libretro.h"
int nestest_b_reads;
#include "fceu-types.h"
#include "fceu.h"
static readfunc o4017; static long g_fr;
static DECLFR(r4017) { uint8_t v = o4017(A); if (v & 0x18) { static long lf = -1; if (lf != g_fr) printf("   4017 fr %ld -> %02X\n", g_fr, v); lf = g_fr; } return v; }
static void dump(const char* dir, long fr, const uint32_t* px) {
  char p[512]; snprintf(p, sizeof p, "%s/d%06ld.ppm", dir, fr);
  FILE* o = fopen(p, "wb"); fprintf(o, "P6 256 224 255\n");
  for (int i = 0; i < GBC_SCREEN_PIXELS; ++i) { fputc(px[i] >> 16 & 255, o); fputc(px[i] >> 8 & 255, o); fputc(px[i] & 255, o); }
  fclose(o);
}
int main(int argc, char** argv) {
  FILE* f = fopen(argv[1], "rb"); fseek(f, 0, SEEK_END); long n = ftell(f); rewind(f);
  uint8_t* rom = malloc(n); fread(rom, 1, n, f); fclose(f);
  char err[128]; GbcEmu* e = gbc_open(rom, n, 48000, err, sizeof err);
  if (!e) { puts(err); return 1; }
  printf("zapper %d\n", gbc_uses_zapper());
  o4017 = ARead[0x4017]; ARead[0x4017] = r4017;
  uint8_t* r = retro_get_memory_data(RETRO_MEMORY_SYSTEM_RAM);
  static uint32_t px[GBC_SCREEN_PIXELS]; static int16_t pcm[8192];
  long frames = atol(argv[3]); int taps = 0, cool = 0;
  for (long fr = 1; fr <= frames; ++fr) {
    gbc_set_buttons(e, (fr > 240 && fr < 250) ? GBC_BUTTON_START : 0);
    int tx = -1, ty = -1;
    /* background: a pixel that kept its colour 40 frames (ducks never stand still) */
    static uint32_t bg[GBC_SCREEN_PIXELS], last[GBC_SCREEN_PIXELS]; static uint8_t still[GBC_SCREEN_PIXELS];
    for (int i = 0; i < GBC_SCREEN_PIXELS; ++i) {
      if (px[i] == last[i]) { if (still[i] < 255) still[i]++; } else still[i] = 0;
      last[i] = px[i];
      if (still[i] >= 40) bg[i] = px[i];
    }
    if (fr > 330 && --cool <= 0) {
      uint32_t sky = bg[40 * 256 + 128]; long sx = 0, sy = 0, cnt = 0;
      if (px[40 * 256 + 128] == sky)   /* not a flash frame */
        for (int y = 8; y < 128; ++y) for (int x = 8; x < 248; ++x)
          if (px[y * 256 + x] != bg[y * 256 + x]) { sx += x; sy += y; cnt++; }
      if (fr % 60 == 0) printf("  fr %ld cnt %ld sky %d\n", fr, cnt, px[40 * 256 + 128] == sky);
      if (cnt > 20 && cnt < 500) { tx = sx / cnt; ty = sy / cnt; }
    }
    static int lastx, lasty;
    if (tx >= 0) { lastx = tx; lasty = ty; gbc_set_pointer(e, tx, ty, 1); taps++; cool = 30; printf("fr %ld tap %d,%d\n", fr, tx, ty); }
    else if (taps) gbc_set_pointer(e, lastx, lasty, 0);   /* finger lifted: the gun keeps pointing there */
    g_fr = fr; if (getenv("ZAPDBG2")) fprintf(stderr, "FRAME %ld\n", fr); gbc_run_frame(e, px, pcm, 4096);
    if (getenv("DHRAM") && (fr == 760 || fr == 800 || fr == 2900)) { char pth[256]; snprintf(pth, sizeof pth, "%s/ram%ld.bin", argv[2], fr); FILE* o = fopen(pth, "wb"); fwrite(r, 1, 2048, o); fclose(o); }
    if (tx >= 0 || fr % 120 == 0 || (fr >= 766 && fr <= 774)) dump(argv[2], fr, px);
  }
  printf("taps %d\n", taps);
  return 0;
}
