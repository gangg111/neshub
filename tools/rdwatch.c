/* Log CPU PCs that read a RAM address while replaying an .fm2: rdwatch rom movie addr from to */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "gbcplayer.h"
#include "game.h"
#include "libretro.h"
#include "fceu-types.h"
#include "x6502struct.h"
#include "fceu.h"
extern X6502 X;
int nestest_b_reads;
static readfunc orig; static long g_fr, g_from, g_to; static int pcs[65536];
static DECLFR(hook) { if (g_fr >= g_from && g_fr <= g_to) pcs[X.PC]++; return orig(A); }
int main(int argc, char** argv) {
  FILE* f = fopen(argv[1], "rb"); fseek(f, 0, SEEK_END); long n = ftell(f); rewind(f);
  uint8_t* rom = malloc(n); fread(rom, 1, n, f); fclose(f);
  char err[128]; GbcEmu* e = gbc_open(rom, n, 48000, err, sizeof err);
  unsigned a = strtoul(argv[3], 0, 16); g_from = atol(argv[4]); g_to = atol(argv[5]);
  orig = ARead[a]; ARead[a] = hook;
  static const uint32_t map[8] = {GBC_BUTTON_RIGHT, GBC_BUTTON_LEFT, GBC_BUTTON_DOWN, GBC_BUTTON_UP, GBC_BUTTON_START, GBC_BUTTON_SELECT, GBC_BUTTON_B, GBC_BUTTON_A};
  static uint32_t px[GBC_SCREEN_PIXELS]; static int16_t pcm[8192];
  FILE* m = fopen(argv[2], "r"); char line[256];
  while (fgets(line, sizeof line, m)) {
    if (line[0] != '|') continue; char* p = strchr(line + 1, '|') + 1; uint32_t b = 0;
    for (int i = 0; i < 8; ++i) if (p[i] != '.') b |= map[i];
    gbc_set_buttons(e, b); gbc_run_frame(e, px, pcm, 4096); ++g_fr;
  }
  for (int i = 0; i < 65536; ++i) if (pcs[i]) printf("PC %04X: %d\n", i, pcs[i]);
  return 0;
}
