/* Contra Force: per-stage health check with the app's settings (2x-VBlank, no sprite
 * limit, jump fix) -- invincible auto-play from a chosen stage; reports CPU jam, frozen
 * picture (same frame 10 s while inputs change), where the CPU is at frame end (PC
 * histogram -> wait loop; outside = frame not finished in time), stage reached; dumps
 * a frame every 1800 and at every anomaly.   cfsurvey rom outdir stage frames */
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
static uint32_t menu(int fr) {   /* title -> 1 player -> pick a character (as tools/nestest.c) */
  if ((fr >= 300 && fr < 306) || (fr >= 420 && fr < 426) || (fr >= 540 && fr < 546)) return GBC_BUTTON_START;
  if (fr >= 700 && fr < 706) return GBC_BUTTON_A;
  if (fr >= 760 && fr < 766) return GBC_BUTTON_START;
  if (fr >= 1000 && fr < 1006) return GBC_BUTTON_RIGHT;
  if (fr >= 1060 && fr < 1066) return GBC_BUTTON_A;
  if (fr >= 1120 && fr < 1126) return GBC_BUTTON_START;
  return 0;
}
static uint32_t play(long fr) {
  /* no START (it pauses); directions cycle so side-view and top-down stages both move */
  static const uint32_t dirs[] = {GBC_BUTTON_RIGHT, GBC_BUTTON_RIGHT | GBC_BUTTON_UP, GBC_BUTTON_UP,
    GBC_BUTTON_RIGHT, GBC_BUTTON_UP | GBC_BUTTON_LEFT, GBC_BUTTON_RIGHT | GBC_BUTTON_DOWN, GBC_BUTTON_UP, GBC_BUTTON_LEFT};
  uint32_t b = dirs[(fr / 240) % 8] | ((fr / 6) & 1 ? GBC_BUTTON_B : 0);
  if (fr % 100 < 10) b |= GBC_BUTTON_A;
  return b;
}
int main(int argc, char** argv) {
  FILE* f = fopen(argv[1], "rb"); fseek(f, 0, SEEK_END); long n = ftell(f); rewind(f);
  uint8_t* rom = malloc(n); fread(rom, 1, n, f); fclose(f);
  int stage = atoi(argv[3]); long frames = atol(argv[4]);
  char err[128]; GbcEmu* e = gbc_open(rom, n, 48000, err, sizeof err);
  if (!e) { puts(err); return 1; }
  uint8_t* ram = retro_get_memory_data(RETRO_MEMORY_SYSTEM_RAM);
  gbc_mod_set(gbc_mod_find("invincible"), 1);
  gbc_mod_set(gbc_mod_find("stage"), stage);
  gbc_mod_set(gbc_mod_find("start_stage"), 1);
  static uint32_t px[GBC_SCREEN_PIXELS]; static int16_t pcm[8192]; static long pcs[65536];
  uint32_t lasth = 0; long same = 0, freezes = 0, jams = 0, maxStage = 0, playFrames = 0;
  for (long fr = 1; fr <= frames; ++fr) {
    uint32_t b = fr < 1300 ? menu(fr) : play(fr);
    gbc_set_buttons(e, b);
    gbc_run_frame(e, px, pcm, 4096);
    if (X.jammed && !jams++) { printf("CPU JAM at %ld PC=%04X\n", fr, X.PC); dump(argv[2], fr, px, "jam"); }
    uint32_t h = 2166136261u; for (int i = 0; i < GBC_SCREEN_PIXELS; i += 3) h = (h ^ px[i]) * 16777619u;
    if (h == lasth) { if (++same == 600) { ++freezes; printf("FROZEN picture 10 s at %ld (stage %d)\n", fr, ram[0x75] + 1); dump(argv[2], fr, px, "frozen"); } } else same = 0;
    lasth = h;
    if (fr > 1400 && ram[0x668]) { pcs[X.PC]++; ++playFrames; }    /* player on screen */
    if (ram[0x75] + 1 > maxStage) maxStage = ram[0x75] + 1;
    if (fr % 1800 == 0) dump(argv[2], fr, px, "t");
  }
  printf("stage %d: furthest stage index %ld, jams %ld, frozen %ld, play frames %ld\n", stage, maxStage, jams, freezes, playFrames);
  for (int k = 0; k < 6; ++k) { int best = 0; for (int i = 0; i < 65536; ++i) if (pcs[i] > pcs[best]) best = i;
    if (!pcs[best]) break; printf("  PC %04X: %5.2f%%\n", best, 100.0 * pcs[best] / playFrames); pcs[best] = 0; }
  return 0;
}
