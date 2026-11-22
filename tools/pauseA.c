/* Does A do anything on the pause (COMMAND SELECT) screen? Compare a pause with A taps
 * (and with DOWN+A, as if picking another character) against the same without A. */
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
  uint8_t* ram = retro_get_memory_data(RETRO_MEMORY_SYSTEM_RAM);
  size_t sz = gbc_state_size(); uint8_t* s0 = malloc(sz); gbc_state_save(e, s0, sz);
  static uint8_t R[2][2048]; static uint32_t P[2][GBC_SCREEN_PIXELS];
  for (int withDown = 0; withDown < 2; ++withDown) {
    for (int run = 0; run < 2; ++run) {
      gbc_state_load(e, s0, sz);
      for (int t = 0; t < 400; ++t) {
        uint32_t b = 0;
        if (t >= 10 && t < 13) b = GBC_BUTTON_START;
        if (withDown && t >= 110 && t < 114) b |= GBC_BUTTON_DOWN;
        if (run == 1 && ((t >= 130 && t < 136) || (t >= 160 && t < 166))) b |= GBC_BUTTON_A;
        if (t >= 200 && t < 203) b = GBC_BUTTON_START;
        gbc_set_buttons(e, b); gbc_run_frame(e, px, pcm, 4096);
      }
      memcpy(R[run], ram, 2048); memcpy(P[run], px, sizeof px);
    }
    int d = 0; for (int i = 0; i < 2048; ++i) d += R[0][i] != R[1][i];
    printf("%s: RAM bytes differing with vs without A taps during pause = %d, screen identical = %d\n",
           withDown ? "pause + DOWN (other character)" : "pause only", d, !memcmp(P[0], P[1], sizeof P[0]));
  }
  return 0;
}
