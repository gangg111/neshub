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
  if (fr > 1300) return GBC_BUTTON_RIGHT | GBC_BUTTON_TURBO_B;
  return 0;
}
static void ppm(const char* p, const uint32_t* px) { FILE* f = fopen(p, "wb"); fprintf(f, "P6\n256 224\n255\n"); for (int i = 0; i < GBC_SCREEN_PIXELS; ++i) { fputc(px[i]>>16&255,f); fputc(px[i]>>8&255,f); fputc(px[i]&255,f);} fclose(f); }
int main(int argc, char** argv) {
  FILE* f = fopen(argv[1], "rb"); fseek(f, 0, SEEK_END); long n = ftell(f); rewind(f);
  uint8_t* rom = malloc(n); fread(rom, 1, n, f); fclose(f);
  int mode = atoi(argv[3]); /* 0 plain, 1 stage=$75<-2 at player select, 2 weapon max */
  char err[128]; GbcEmu* e = gbc_open(rom, n, 48000, err, sizeof err);
  static uint32_t px[GBC_SCREEN_PIXELS]; static int16_t pcm[8192];
  uint8_t* ram = retro_get_memory_data(RETRO_MEMORY_SYSTEM_RAM);
  for (int fr = 1; fr <= 1700; ++fr) {
    if (mode == 1 && fr == 1100) ram[0x75] = 2;
    if (mode == 2 && fr >= 1400) for (int c = 0; c < 4; ++c) ram[0x7F + c] = (ram[0x7F + c] & 0xF0) | 0x06;
    gbc_set_buttons(e, script(fr)); gbc_run_frame(e, px, pcm, 4096);
    if (fr == 1399) printf("mode %d: $75=%02X $7F..$82=%02X %02X %02X %02X $B1=%02X\n", mode, ram[0x75], ram[0x7F], ram[0x80], ram[0x81], ram[0x82], ram[0xB1]);
    if (fr == 1500 || fr == 1700) { char p[512]; snprintf(p, sizeof p, "%s/mod%d_%d.ppm", argv[2], mode, fr); ppm(p, px); }
  }
  return 0;
}
