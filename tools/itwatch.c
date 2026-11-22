/* Iron Tank boss watch: load a state, hold a pad script, log every write to the boss
 * parts' X/Y bytes with the CPU PC that did it.  itwatch state frames from to */
#include <stdio.h>
#include <stdlib.h>
#include "gbcplayer.h"
#include "game.h"
#include "libretro.h"
#include "fceu-types.h"
#include "x6502struct.h"
#include "fceu.h"
extern X6502 X;
int nestest_b_reads;
static writefunc orig[0x800]; static long g_fr, g_from, g_to;
static void FP_FASTAPASS(2) hook(uint32_t a, uint8_t v) {
  if (g_fr >= g_from && g_fr <= g_to) printf("fr %ld PC %04X  $%04X <- %02X\n", g_fr, X.PC, a, v);
  orig[a](a, v);
}
int main(int argc, char** argv) {
  FILE* f = fopen(getenv("ROM") ? getenv("ROM") : "/sdcard/Download/Iron Tank/dump.nes", "rb"); fseek(f, 0, SEEK_END); long n = ftell(f); rewind(f);
  uint8_t* rom = malloc(n); fread(rom, 1, n, f); fclose(f);
  char err[128]; GbcEmu* e = gbc_open(rom, n, 48000, err, sizeof err);
  FILE* q = fopen(argv[1], "rb"); size_t sz = gbc_state_size(); uint8_t* b = malloc(sz); fread(b, 1, sz, q); fclose(q); gbc_state_load(e, b, sz);
  uint8_t* r = retro_get_memory_data(RETRO_MEMORY_SYSTEM_RAM);
  long frames = atol(argv[2]); g_from = atol(argv[3]); g_to = atol(argv[4]);
  static const int watch[] = {0x8A, 0x9A, 0x20, 0x91, 0x99, 0xA1};
  for (int i = 0; i < 6; ++i) { orig[watch[i]] = BWrite[watch[i]]; BWrite[watch[i]] = hook; }
  static uint32_t px[GBC_SCREEN_PIXELS]; static int16_t pcm[8192];
  for (g_fr = 0; g_fr < frames; ++g_fr) {
    r[0x306] = 0x20;
    uint32_t pad = g_fr < 150 ? GBC_BUTTON_RIGHT : g_fr < 300 ? GBC_BUTTON_DOWN : 0;
    gbc_set_buttons(e, pad); gbc_run_frame(e, px, pcm, 4096);
  }
  return 0;
}
