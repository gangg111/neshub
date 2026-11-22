/* Kick Master slowdown survey: invincible auto-play through as much of the game as it
 * gets, lag frames (joypad never read) counted per 600-frame window; windows with lag
 * and every 3600th frame are dumped as PPM to see where it happened.
 *   kmlag rom outdir frames [stage round]   (stage/round: via the DEBUG ROOM, GG EYXUZYEI) */
#include <stdio.h>
#include <stdlib.h>
#include "gbcplayer.h"
#include "game.h"
#include "libretro.h"
#include "fceu-types.h"
#include "x6502struct.h"
extern X6502 X;
int nestest_b_reads;
extern unsigned fceu_joy_reads;
static int stage = 0, round_ = 1;
/* title -> OPCJE -> KOD -> cursor to END -> A = DEBUG ROOM; R x (stage-1), D, R x (round-1), START */
static uint32_t nav(long fr) {
  static const struct { int at; uint32_t b; } k[] = {
    {240, GBC_BUTTON_RIGHT}, {280, GBC_BUTTON_START}, {360, GBC_BUTTON_START},
    {440, GBC_BUTTON_DOWN}, {490, GBC_BUTTON_DOWN}, {540, GBC_BUTTON_DOWN}, {590, GBC_BUTTON_DOWN}, {640, GBC_BUTTON_DOWN},
    {700, GBC_BUTTON_RIGHT}, {740, GBC_BUTTON_RIGHT}, {780, GBC_BUTTON_RIGHT}, {820, GBC_BUTTON_RIGHT}, {860, GBC_BUTTON_RIGHT},
    {920, GBC_BUTTON_A}};
  for (unsigned i = 0; i < sizeof k / sizeof k[0]; ++i) if (fr >= k[i].at && fr < k[i].at + 4) return k[i].b;
  long t = fr - 1000;                       /* debug room open */
  if (t >= 0 && t < 40L * (stage - 1) && t % 40 < 4) return GBC_BUTTON_RIGHT;
  t -= 40L * (stage - 1);
  if (t >= 0 && t < 4) return GBC_BUTTON_DOWN;
  t -= 40;
  if (t >= 0 && t < 40L * (round_ - 1) && t % 40 < 4) return GBC_BUTTON_RIGHT;
  t -= 40L * (round_ - 1);
  if (t >= 0 && t < 4) return GBC_BUTTON_START;
  return 0;
}
static uint32_t script(long fr) {
  if (stage && fr < 3000) return nav(fr);
  if (fr < 3000) { int m = fr % 120; return (m >= 100 && m < 104) ? GBC_BUTTON_START : 0; }
  int m = fr % 90; uint32_t b = GBC_BUTTON_RIGHT;
  if (m < 8) b |= GBC_BUTTON_A; if (m >= 30 && m < 34) b |= GBC_BUTTON_B; if (m >= 60 && m < 64) b |= GBC_BUTTON_B;
  if ((fr / 900) % 4 == 3) b = (b & ~GBC_BUTTON_RIGHT) | GBC_BUTTON_LEFT;
  if ((fr / 450) % 7 == 5) b |= GBC_BUTTON_UP;
  if (!stage && fr % 1500 < 3) b |= GBC_BUTTON_START;
  return b;
}
static void dump(const char* dir, long fr, const uint32_t* px, const char* tag) {
  char p[512]; snprintf(p, sizeof p, "%s/%s%07ld.ppm", dir, tag, fr);
  FILE* o = fopen(p, "wb"); fprintf(o, "P6 256 240 255\n");
  for (int i = 0; i < 256 * 240; ++i) { fputc(px[i] >> 16 & 255, o); fputc(px[i] >> 8 & 255, o); fputc(px[i] & 255, o); }
  fclose(o);
}
int main(int argc, char** argv) {
  FILE* f = fopen(argv[1], "rb"); fseek(f, 0, SEEK_END); long n = ftell(f); rewind(f);
  uint8_t* rom = malloc(n); fread(rom, 1, n, f); fclose(f);
  long frames = atol(argv[3]);
  if (argc > 5) { stage = atoi(argv[4]); round_ = atoi(argv[5]); }
  char err[128]; GbcEmu* e = gbc_open(rom, n, 48000, err, sizeof err);
  if (!e) { puts(err); return 1; }
  /* invincibility/infinite magic are cheats too: one set with the debug-room code */
  if (stage) core_cheats("EYXUZYEI+APOSXTSA+SZOIOVSE+TAOSUTXI+SUELOISP");
  else { gbc_mod_set(gbc_mod_find("invincible"), 1); gbc_mod_set(gbc_mod_find("infinite_magic"), 1); }
  static uint32_t px[GBC_SCREEN_PIXELS]; static int16_t pcm[8192];
  /* RAMLOG=file: hash of work RAM every 60 frames, to compare runs (e.g. NES_OC on/off) */
  FILE* rl = getenv("RAMLOG") ? fopen(getenv("RAMLOG"), "w") : NULL;
  uint8_t* wram = retro_get_memory_data(RETRO_MEMORY_SYSTEM_RAM);
  /* Where the CPU is when a frame's picture is done (just before the next NMI): in the
   * game's wait-for-NMI loop = it finished this frame's work; anywhere else = it is still
   * busy and the NMI will cut in = a slow frame. Histogram of PCs, play frames only. */
  static long pcs[65536]; long slow = 0, srun = 0, maxSrun = 0;
  long lag = 0, wlag = 0, total = 0, worst = 0, worstAt = 0, run = 0, maxRun = 0;
  for (long fr = 0; fr < frames; ++fr) {
    gbc_set_buttons(e, script(fr)); fceu_joy_reads = 0;
    gbc_run_frame(e, px, pcm, 4096);
    if (fr > 3000) { pcs[X.PC]++;
      int busy = X.PC < 0xD910 || X.PC > 0xD91B;     /* Kick Master's wait loop */
      if (busy) { ++slow; ++srun; if (srun > maxSrun) maxSrun = srun; if (srun == 3) dump(argv[2], fr, px, "slow"); } else srun = 0; }
    if (fceu_joy_reads == 0) { ++wlag; ++run; if (run > maxRun) maxRun = run; } else run = 0;
    if (fr % 3600 == 0) dump(argv[2], fr, px, "t");
    if (rl && fr % 60 == 0) { uint32_t h = 2166136261u; for (int i = 0; i < 0x800; ++i) h = (h ^ wram[i]) * 16777619u; fprintf(rl, "%ld %08x\n", fr, h); }
    if (fr % 600 == 599) {
      if (fr > 3000) { total += wlag; if (wlag > worst) { worst = wlag; worstAt = fr; } }
      if (wlag >= 6 && fr > 3000) { printf("frames %ld-%ld: %ld lag (%.1f%%)\n", fr - 599, fr, wlag, wlag / 6.0); dump(argv[2], fr, px, "lag"); }
      wlag = 0;
    }
  }
  { long tot = 0; for (int i = 0; i < 65536; ++i) tot += pcs[i];
    for (int k = 0; k < 6; ++k) { int best = 0; for (int i = 0; i < 65536; ++i) if (pcs[i] > pcs[best]) best = i;
      if (!pcs[best]) break; printf("  PC %04X: %5.2f%%\n", best, 100.0 * pcs[best] / tot); pcs[best] = 0; } }
  printf("slow frames (CPU busy at frame end): %ld of %ld (%.3f%%), longest run %ld\n", slow, frames - 3001, 100.0 * slow / (frames - 3001), maxSrun);
  printf("total lag %ld of %ld play frames (%.3f%%), worst window %ld at %ld, longest run %ld\n",
         total, frames - 3000, 100.0 * total / (frames - 3000), worst, worstAt, maxRun);
  return 0;
}
