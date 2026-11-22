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
  static uint8_t J[60][2048], I[60][2048];
  gbc_state_load(e, s0, sz);
  for (int t = 0; t < 60; ++t) { gbc_set_buttons(e, t < 20 ? GBC_BUTTON_A : 0); gbc_run_frame(e, px, pcm, 4096); memcpy(J[t], ram, 2048); }
  gbc_state_load(e, s0, sz);
  for (int t = 0; t < 60; ++t) { gbc_set_buttons(e, 0); gbc_run_frame(e, px, pcm, 4096); memcpy(I[t], ram, 2048); }
  for (int a = 0; a < 2048; ++a) {
    int idleConst = 1; for (int t = 1; t < 60; ++t) if (I[t][a] != I[0][a]) idleConst = 0;
    if (!idleConst) continue;
    int mn = 255, mx = 0, tmin = 0; for (int t = 0; t < 60; ++t) { if (J[t][a] < mn) { mn = J[t][a]; tmin = t; } if (J[t][a] > mx) mx = J[t][a]; }
    if (mx - mn >= 25 && mx - mn <= 80 && J[0][a] == I[0][a] && J[59][a] == I[59][a])
      printf("$%03X idle=%d jump min=%d at t=%d max=%d  traj:", a, I[0][a], mn, tmin, mx), ({ for (int t = 0; t < 60; t += 4) printf(" %d", J[t][a]); 0; }), printf("\n");
  }
  return 0;
}
