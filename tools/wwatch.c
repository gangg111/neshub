/* Generic RAM write watch: ROM=<rom> wwatch <state> <frames> <padhex:n,...> <addr,addr,...>
 * logs every CPU write to the given RAM addresses with frame and PC. */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "gbcplayer.h"
#include "libretro.h"
#include "fceu-types.h"
#include "x6502struct.h"
#include "fceu.h"
extern X6502 X;
int nestest_b_reads;
static writefunc orig[0x800]; static long g_fr;
static void FP_FASTAPASS(2) hook(uint32_t a, uint8_t v) { printf("fr %ld PC %04X  $%04X <- %02X\n", g_fr, X.PC, a, v); orig[a & 0x7FF](a, v); }
int main(int argc, char** argv) {
  FILE* f = fopen(getenv("ROM"), "rb"); fseek(f, 0, SEEK_END); long n = ftell(f); rewind(f);
  uint8_t* rom = malloc(n); fread(rom, 1, n, f); fclose(f);
  char err[128]; GbcEmu* e = gbc_open(rom, n, 48000, err, sizeof err);
  FILE* q = fopen(argv[1], "rb"); size_t sz = gbc_state_size(); uint8_t* b = malloc(sz); fread(b, 1, sz, q); fclose(q); gbc_state_load(e, b, sz);
  long frames = atol(argv[2]);
  uint32_t pm[64]; long pn[64], tot = 0; int cnt = 0;
  for (const char* p = argv[3]; p && *p && cnt < 64; ) { char* end; pm[cnt] = strtoul(p, &end, 16); pn[cnt] = strtol(end + 1, &end, 10); tot += pn[cnt++]; p = *end == ',' ? end + 1 : NULL; }
  int wa[64], wn = 0; for (char* p = argv[4]; p && *p && wn < 64; ) { char* end; wa[wn++] = strtol(p, &end, 16); p = *end == ',' ? end + 1 : NULL; }
  static uint32_t px[GBC_SCREEN_PIXELS]; static int16_t pcm[8192];
  for (g_fr = 0; g_fr < frames; ++g_fr) {
    if (g_fr == 1) for (int i = 0; i < wn; ++i) { orig[wa[i]] = BWrite[wa[i]]; BWrite[wa[i]] = hook; }
    long t = g_fr % tot; int i = 0; while (t >= pn[i]) t -= pn[i++];
    gbc_set_buttons(e, pm[i]); gbc_run_frame(e, px, pcm, 4096);
  }
  return 0;
}
