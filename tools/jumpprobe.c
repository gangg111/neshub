/* Pause-jump glitch probe using the player's Y ($0010; ground 168, normal apex 115). */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "gbcplayer.h"
#include "libretro.h"
int nestest_b_reads;
static uint32_t script(int fr) {
  if ((fr >= 300 && fr < 306) || (fr >= 420 && fr < 426) || (fr >= 540 && fr < 546)) return GBC_BUTTON_START;
  if (fr >= 700 && fr < 706) return GBC_BUTTON_A;
  if (fr >= 760 && fr < 766) return GBC_BUTTON_START;
  if (fr >= 1000 && fr < 1006) return GBC_BUTTON_RIGHT;
  if (fr >= 1060 && fr < 1066) return GBC_BUTTON_A;
  if (fr >= 1120 && fr < 1126) return GBC_BUTTON_START;
  return 0;
}
int main(int argc, char** argv) {
  FILE* f = fopen(argv[1], "rb"); fseek(f, 0, SEEK_END); long n = ftell(f); rewind(f);
  uint8_t* rom = malloc(n); fread(rom, 1, n, f); fclose(f);
  char err[128]; GbcEmu* e = gbc_open(rom, n, 48000, err, sizeof err);
  static uint32_t px[GBC_SCREEN_PIXELS]; static int16_t pcm[8192];
  for (int fr = 1; fr <= 1500; ++fr) { gbc_set_buttons(e, script(fr)); gbc_run_frame(e, px, pcm, 4096); }
  size_t sz = gbc_state_size(); uint8_t* s0 = malloc(sz); gbc_state_save(e, s0, sz);
  uint8_t* ram = retro_get_memory_data(RETRO_MEMORY_SYSTEM_RAM);
  int glitches = 0, trials = 0;
  /* pause at frame P of the jump (mid-air), unpause at U; A held from U+off for H frames */
  for (int P = 30; P <= 42; P += 6)
  for (int off = -60; off <= 6; off += (off < -6 ? 18 : 1)) {
    gbc_state_load(e, s0, sz);
    int yAtUnpause = -1, minAfter = 999, U = 200, back = -1;
    for (int t = 0; t < 420; ++t) {
      uint32_t b = 0;
      if (t < 20) b |= GBC_BUTTON_A;
      if (t >= P && t < P + 3) b |= GBC_BUTTON_START;
      if (t >= U && t < U + 3) b |= GBC_BUTTON_START;
      if (t >= U + off && t < U + 170) b |= GBC_BUTTON_A;  /* hold A through the unpause */
      gbc_set_buttons(e, b);
      gbc_run_frame(e, px, pcm, 4096);
      int y = ram[0x668];
      if (t > U && back < 0 && ram[0x10] != 0) { }
      if (t == P) yAtUnpause = y;   /* height when paused (falling) */
      if (t > U && y > 8 && y < minAfter) minAfter = y;
      if (getenv("TRAJ") && P == 36 && off == -3 && t >= 30 && t % 6 == 0) printf("t=%d y=%d\n", t, y);
    }
    ++trials;
    int rose = yAtUnpause - minAfter;  /* >0: went UP after unpausing = new jump mid-air */
    if (rose > 4) ++glitches;
    if (argc > 2) printf("P=%2d A_from=U%+3d  y@unpause=%3d  highest after=%3d  rose=%d\n", P, off, yAtUnpause, minAfter, rose);
  }
  { /* legit jump after resuming: release A, then a fresh press from the ground */
    gbc_state_load(e, s0, sz); int minY = 999;
    for (int t = 0; t < 520; ++t) {
      uint32_t b = 0;
      if (t < 20) b |= GBC_BUTTON_A;
      if (t >= 36 && t < 39) b |= GBC_BUTTON_START;
      if (t >= 200 && t < 203) b |= GBC_BUTTON_START;
      if (t >= 150 && t < 360) b |= GBC_BUTTON_A;         /* held through resume (~t=306) */
      if (t >= 420 && t < 440) b |= GBC_BUTTON_A;         /* fresh press after release */
      gbc_set_buttons(e, b); gbc_run_frame(e, px, pcm, 4096);
      int y = ram[0x668]; if (t >= 420 && y > 8 && y < minY) minY = y;
    }
    printf("fresh A after resume: jump apex %d (ground 168) -> %s\n", minY, minY < 130 ? "skok dziala" : "BRAK SKOKU");
  }
  printf("%s: %d/%d trials started a new jump in mid-air after unpausing\n",
         getenv("NES_NOJUMPFIX") ? "BEZ poprawki" : "Z poprawka  ", glitches, trials);
  return 0;
}
