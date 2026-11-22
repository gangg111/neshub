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
  if (fr > 1300) return GBC_BUTTON_RIGHT | GBC_BUTTON_TURBO_B | ((fr % 90) < 10 ? GBC_BUTTON_A : 0);
  return 0;
}
int main(int argc, char** argv) {
  FILE* f = fopen(argv[1], "rb"); fseek(f, 0, SEEK_END); long n = ftell(f); rewind(f);
  uint8_t* rom = malloc(n); fread(rom, 1, n, f); fclose(f);
  char err[128]; GbcEmu* e = gbc_open(rom, n, 48000, err, sizeof err);
  static uint32_t px[GBC_SCREEN_PIXELS]; static int16_t pcm[8192];
  uint8_t* ram = retro_get_memory_data(RETRO_MEMORY_SYSTEM_RAM);
  static uint8_t snap[8000][2048]; int k = 0;
  for (int fr = 1; fr <= 8000; ++fr) { gbc_set_buttons(e, script(fr)); gbc_run_frame(e, px, pcm, 4096); memcpy(snap[fr-1], ram, 2048); }
  /* respawn-invulnerability timer: 0 in the 100 frames before the first death (t=1571),
   * then nonzero for a stretch after respawn, counting down to 0 */
  for (int a = 0; a < 2048; ++a) {
    int ok = 1; for (int t = 1450; t < 1560 && ok; ++t) if (snap[t][a] != 0) ok = 0;
    if (!ok) continue;
    int first = -1, last = -1, down = 0, up = 0;
    for (int t = 1571; t < 1990; ++t) { int v = snap[t][a], pv = snap[t-1][a]; if (v) { if (first < 0) first = t; last = t; } if (v < pv) down++; if (v > pv) up++; }
    if (first > 0 && last - first > 60 && down > 10 && up <= 2)
      printf("$%03X nonzero %d..%d (%d frames) max=%d down=%d up=%d\n", a, first + 1, last + 1, last - first, ({int m=0; for(int t=first;t<=last;++t) if(snap[t][a]>m)m=snap[t][a]; m;}), down, up);
  }
  return 0;
}
