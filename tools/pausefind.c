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
  /* timeline: play 0..60 (walk+jump), pause START @60, menu up, unpause START @200, play to 420 */
  static uint8_t R[420][2048];
  for (int t = 0; t < 420; ++t) {
    uint32_t b = (t % 50 < 10) ? GBC_BUTTON_A : 0; b |= (t % 30 < 15) ? GBC_BUTTON_RIGHT : GBC_BUTTON_LEFT;
    if ((t >= 60 && t < 63) || (t >= 200 && t < 203)) b = GBC_BUTTON_START;
    else if (t >= 60 && t < 320) b = 0;
    gbc_set_buttons(e, b); gbc_run_frame(e, px, pcm, 4096); memcpy(R[t], ram, 2048);
  }
  /* candidate: constant over gameplay frames 0..55 and 330..419, constant (different) over 100..190 */
  for (int a = 0; a < 2048; ++a) {
    int g = R[0][a], ok = 1;
    for (int t = 0; t < 56 && ok; ++t) if (R[t][a] != g) ok = 0;
    for (int t = 330; t < 420 && ok; ++t) if (R[t][a] != g) ok = 0;
    int p = R[100][a];
    for (int t = 100; t < 190 && ok; ++t) if (R[t][a] != p) ok = 0;
    if (ok && p != g) {
      int first = -1, last = -1; for (int t = 56; t < 330; ++t) if (R[t][a] != g) { if (first < 0) first = t; last = t; }
      printf("$%03X play=%02X pause=%02X  differs t=%d..%d\n", a, g, p, first, last);
    }
  }
  return 0;
}
