/* Pause-jump glitch: jump, pause, hold A, unpause.  Scenario holds A through the
 * unpause; control releases A on the very frame START is pressed to unpause.
 * With the fix the game must see identical input from then on -> identical frames. */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "gbcplayer.h"
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
static void ppm(const char* p, const uint32_t* px) {
  FILE* f = fopen(p, "wb"); fprintf(f, "P6\n256 224\n255\n");
  for (int i = 0; i < GBC_SCREEN_PIXELS; ++i) { fputc(px[i]>>16&255,f); fputc(px[i]>>8&255,f); fputc(px[i]&255,f); }
  fclose(f);
}
int main(int argc, char** argv) {
  FILE* f = fopen(argv[1], "rb"); fseek(f, 0, SEEK_END); long n = ftell(f); rewind(f);
  uint8_t* rom = malloc(n); fread(rom, 1, n, f); fclose(f);
  char err[128]; GbcEmu* e = gbc_open(rom, n, 48000, err, sizeof err);
  static uint32_t px[GBC_SCREEN_PIXELS], ra[420][GBC_SCREEN_PIXELS / 64]; static int16_t pcm[8192];
  for (int fr = 1; fr <= 1400; ++fr) { gbc_set_buttons(e, script(fr)); gbc_run_frame(e, px, pcm, 4096); }
  size_t sz = gbc_state_size(); uint8_t* s0 = malloc(sz); gbc_state_save(e, s0, sz);
  long diff[2] = {0, 0}; int firstdiff = -1;
  for (int run = 0; run < 2; ++run) {           /* 0 = scenario, 1 = control */
    gbc_state_load(e, s0, sz);
    for (int t = 0; t < 420; ++t) {
      uint32_t b = 0;
      if (t < 6) b |= GBC_BUTTON_A;                         /* jump */
      if (t >= 12 && t < 15) b |= GBC_BUTTON_START;         /* pause mid-air */
      if (t >= 120 && t < (run == 0 ? 260 : 150)) b |= GBC_BUTTON_A;  /* hold A while paused */
      if (t >= 150 && t < 153) b |= GBC_BUTTON_START;         /* unpause */
      gbc_set_buttons(e, b);
      gbc_run_frame(e, px, pcm, 4096);
      for (int i = 0; i < GBC_SCREEN_PIXELS / 64; ++i) {
        uint32_t v = px[i * 64 + (t % 64)];
        if (run == 0) ra[t][i] = v; else if (ra[t][i] != v) { diff[1]++; if (firstdiff < 0) firstdiff = t; }
      }
      if (argc > 2 && (t == 100 || t == 190 || t == 215 || t == 240)) { char p[512]; snprintf(p, sizeof p, "%s/jump_%s_%d_%d.ppm", argv[2], getenv("NES_NOJUMPFIX") ? "nofix" : "fix", run, t); ppm(p, px); }
    }
  }
  printf("%s: scenario vs control differing samples after unpause = %ld (first at t=%d)\n",
         getenv("NES_NOJUMPFIX") ? "BEZ poprawki" : "Z poprawka  ", diff[1], firstdiff);
  return 0;
}
