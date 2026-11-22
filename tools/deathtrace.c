/* Who decrements the lives counter $00B6? Hook RAM writes, log PC + call stack at the first
 * decrement, and dump the CPU view of $8000-$FFFF at that moment for disassembly. */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "gbcplayer.h"
#include "fceu-types.h"
#include "fceu.h"
#include "x6502struct.h"
extern X6502 X;
extern uint8_t RAM[0x800];
int nestest_b_reads;
static writefunc orig; static int hit; static const char* outdir;
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
static void FP_FASTAPASS(2) hook(uint32_t A, uint8_t V) {
  if (A == 0xB6 && V < RAM[0xB6] && !hit) {
    hit = 1;
    printf("write $B6: %02X -> %02X  PC(after)=%04X  S=%02X  A=%02X X=%02X Y=%02X\n", RAM[0xB6], V, X.PC, X.S, X.A, X.X, X.Y);
    printf("stack:"); for (int i = X.S + 1; i <= 0xFF && i < X.S + 17; ++i) printf(" %02X", RAM[0x100 + i]); printf("\n");
    char p[512]; snprintf(p, sizeof p, "%s/cpu8000.bin", outdir); FILE* o = fopen(p, "wb");
    for (uint32_t a = 0x8000; a <= 0xFFFF; ++a) fputc(ARead[a](a), o);
    fclose(o);
  }
  orig(A, V);
}
int main(int argc, char** argv) {
  outdir = argv[2];
  FILE* f = fopen(argv[1], "rb"); fseek(f, 0, SEEK_END); long n = ftell(f); rewind(f);
  uint8_t* rom = malloc(n); fread(rom, 1, n, f); fclose(f);
  char err[128]; GbcEmu* e = gbc_open(rom, n, 48000, err, sizeof err);
  static uint32_t px[GBC_SCREEN_PIXELS]; static int16_t pcm[8192];
  for (int fr = 1; fr <= 2000 && !hit; ++fr) {
    if (fr == 1400) { orig = GetWriteHandler(0xB6); SetWriteHandler(0xB6, 0xB6, hook); }
    gbc_set_buttons(e, script(fr)); gbc_run_frame(e, px, pcm, 4096);
    if (hit) printf("frame %d\n", fr);
  }
  return 0;
}
