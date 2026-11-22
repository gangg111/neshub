/* Generic slowdown survey for any NES ROM: auto-play (START taps, then right/jump/fire
 * with direction changes), optional cheats (e.g. invincibility, start-on-world codes);
 * histogram of the CPU PC at frame end (the game's wait-for-NMI loop shows up as the
 * top addresses); with WAIT=lo-hi also counts frames that end outside that loop (= the
 * game did not finish its frame) and dumps where they happen.
 *   slowsurvey rom outdir frames [cheats]       env: WAIT=D910-D91B NES_OC=disabled|2x-VBlank */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "gbcplayer.h"
#include "game.h"
#include "libretro.h"
#include "fceu-types.h"
#include "x6502struct.h"
extern X6502 X;
int nestest_b_reads;
static void dump(const char* dir, long fr, const uint32_t* px, const char* tag) {
  char p[512]; snprintf(p, sizeof p, "%s/%s%07ld.ppm", dir, tag, fr);
  FILE* o = fopen(p, "wb"); fprintf(o, "P6 256 224 255\n");
  for (int i = 0; i < GBC_SCREEN_PIXELS; ++i) { fputc(px[i] >> 16 & 255, o); fputc(px[i] >> 8 & 255, o); fputc(px[i] & 255, o); }
  fclose(o);
}
static uint32_t script(long fr) {
  if (fr < 1500) return (fr % 120 >= 100 && fr % 120 < 104) ? GBC_BUTTON_START : 0;
  static const uint32_t dirs[] = {GBC_BUTTON_RIGHT, GBC_BUTTON_RIGHT, GBC_BUTTON_RIGHT | GBC_BUTTON_UP, GBC_BUTTON_LEFT,
                                  GBC_BUTTON_RIGHT, GBC_BUTTON_UP, GBC_BUTTON_RIGHT | GBC_BUTTON_DOWN, GBC_BUTTON_LEFT};
  uint32_t b = dirs[(fr / 300) % 8] | ((fr / 8) & 1 ? GBC_BUTTON_B : 0);
  if (fr % 70 < 12) b |= GBC_BUTTON_A;
  if (fr % 3000 < 3) b |= GBC_BUTTON_START;               /* past any "press start" screen */
  if (fr % 3000 >= 120 && fr % 3000 < 123) b |= GBC_BUTTON_START;
  return b;
}
int main(int argc, char** argv) {
  FILE* f = fopen(argv[1], "rb"); fseek(f, 0, SEEK_END); long n = ftell(f); rewind(f);
  uint8_t* rom = malloc(n); fread(rom, 1, n, f); fclose(f);
  long frames = atol(argv[3]);
  char err[128]; GbcEmu* e = gbc_open(rom, n, 48000, err, sizeof err);
  if (!e) { puts(err); return 1; }
  if (argc > 4 && *argv[4]) core_cheats(argv[4]);
  unsigned lo = 0, hi = 0, lo2 = 1, hi2 = 0;   /* WAIT=lo-hi[,lo2-hi2] */
  if (getenv("WAIT")) sscanf(getenv("WAIT"), "%x-%x,%x-%x", &lo, &hi, &lo2, &hi2);
  static uint32_t px[GBC_SCREEN_PIXELS]; static int16_t pcm[8192]; static long pcs[65536];
  long slow = 0, run = 0, maxRun = 0, counted = 0, windows = 0, wslow = 0;
  for (long fr = 1; fr <= frames; ++fr) {
    if (getenv("LEVEL")) { uint8_t* rr = retro_get_memory_data(RETRO_MEMORY_SYSTEM_RAM); unsigned a, v; sscanf(getenv("LEVEL"), "%x=%x", &a, &v); rr[a & 0x7FF] = (uint8_t)v; }
    gbc_set_buttons(e, script(fr)); gbc_run_frame(e, px, pcm, 4096);
    if (getenv("WATER")) { uint8_t* rr = retro_get_memory_data(RETRO_MEMORY_SYSTEM_RAM); static long n73, n18;
      if (rr[0xCC] > 0 && rr[0xCC] < 0x0C) { if (rr[0xD6] == 0x73) n73++; if (rr[0xD6] == 0x18) n18++; }
      if (fr == frames) printf("water splash frames: sprite_73 %ld, sprite_18 %ld\n", n73, n18); }
    if (fr <= 1500) continue;
    pcs[X.PC]++; ++counted;
    if (hi) {
      int busy = (X.PC < lo || X.PC > hi) && (X.PC < lo2 || X.PC > hi2);
      if (busy) { ++slow; ++wslow; if (++run > maxRun) maxRun = run; } else run = 0;
      if (fr % 600 == 0) { if (wslow >= 30) { ++windows; printf("  frames %ld-%ld: %ld slow (%.0f%%)\n", fr - 599, fr, wslow, wslow / 6.0); dump(argv[2], fr, px, "slow"); } wslow = 0; }
    }
    if (fr % 3600 == 0) dump(argv[2], fr, px, "t");
  }
  for (int k = 0; k < 8; ++k) { int best = 0; for (int i = 0; i < 65536; ++i) if (pcs[i] > pcs[best]) best = i;
    if (!pcs[best]) break; printf("  PC %04X: %5.2f%%\n", best, 100.0 * pcs[best] / counted); pcs[best] = 0; }
  if (hi) printf("slow frames: %ld of %ld (%.2f%%), longest run %ld, windows >=5%%: %ld\n", slow, counted, 100.0 * slow / counted, maxRun, windows);
  return 0;
}
