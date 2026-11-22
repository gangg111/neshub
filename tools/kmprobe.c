/* Kick Master RAM probe: reach gameplay, print candidate addresses over time. */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "gbcplayer.h"
#include "game.h"
#include "libretro.h"
int nestest_b_reads;
static void ppm(const char* p, const uint32_t* px) { FILE* f = fopen(p, "wb"); fprintf(f, "P6\n256 224\n255\n"); for (int i = 0; i < GBC_SCREEN_PIXELS; ++i) { fputc(px[i]>>16&255,f); fputc(px[i]>>8&255,f); fputc(px[i]&255,f);} fclose(f); }
uint32_t script(int fr) {   /* title -> START, intro skipped with START, then play */
  if (fr < 3000) { int m = fr % 120; return (m >= 100 && m < 104) ? GBC_BUTTON_START : 0; }
  int m = fr % 90; uint32_t b = GBC_BUTTON_RIGHT;
  if (m < 8) b |= GBC_BUTTON_A; if (m >= 30 && m < 34) b |= GBC_BUTTON_B;
  return b;
}
int main(int argc, char** argv) {
  FILE* f = fopen(argv[1], "rb"); fseek(f, 0, SEEK_END); long n = ftell(f); rewind(f);
  uint8_t* rom = malloc(n); fread(rom, 1, n, f); fclose(f);
  char err[128]; GbcEmu* e = gbc_open(rom, n, 48000, err, sizeof err);
  static uint32_t px[GBC_SCREEN_PIXELS]; static int16_t pcm[8192];
  uint8_t* ram = retro_get_memory_data(RETRO_MEMORY_SYSTEM_RAM);
  if (argc > 3 && argv[3][0]) core_cheats(argv[3]);
  int last = argc > 4 ? atoi(argv[4]) : 9000;
  for (int fr = 1; fr <= last; ++fr) {
    if (argc > 5 && fr == 4000) ram[0x30E] = (uint8_t)strtol(argv[5], 0, 0);
    gbc_set_buttons(e, script(fr)); gbc_run_frame(e, px, pcm, 4096);
    if (fr % 600 == 0) {
      printf("fr %5d lives030E=%02X hp06B9=%02X mp00EA..EC=%02X %02X %02X exp00ED..F0=%02X %02X %02X %02X D3=%02X spells=%02X %02X boss0437=%02X\n",
        fr, ram[0x30E], ram[0x6B9], ram[0xEA], ram[0xEB], ram[0xEC], ram[0xED], ram[0xEE], ram[0xEF], ram[0xF0], ram[0xD3], ram[0x6BE], ram[0x6BF], ram[0x437]);
      char p[512]; snprintf(p, sizeof p, "%s/kp_%05d.ppm", argv[2], fr); ppm(p, px);
    }
  }
  return 0;
}
