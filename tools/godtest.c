/* Same scripted run as livesfind (dies at 1571, 1995, 2370, game over ~2419) with
 * invincibility on: lives must not drop, no game over; also lives=42 shows "41". */
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
static void ppm(const char* p, const uint32_t* px) { FILE* f = fopen(p, "wb"); fprintf(f, "P6\n256 224\n255\n"); for (int i = 0; i < GBC_SCREEN_PIXELS; ++i) { fputc(px[i]>>16&255,f); fputc(px[i]>>8&255,f); fputc(px[i]&255,f);} fclose(f); }
int main(int argc, char** argv) {
  FILE* f = fopen(argv[1], "rb"); fseek(f, 0, SEEK_END); long n = ftell(f); rewind(f);
  uint8_t* rom = malloc(n); fread(rom, 1, n, f); fclose(f);
  char err[128]; GbcEmu* e = gbc_open(rom, n, 48000, err, sizeof err);
  static uint32_t px[GBC_SCREEN_PIXELS]; static int16_t pcm[8192];
  uint8_t* ram = retro_get_memory_data(RETRO_MEMORY_SYSTEM_RAM);
  int god = argc > 3 && atoi(argv[3]);
  long hits = 0; int prev = 0;
  for (int fr = 1; fr <= 12000; ++fr) {
    if (fr == 1400) { if (god) gbc_set_invincible(e, 1); printf("lives at start: %d\n", gbc_get_lives(e)); }
    gbc_set_buttons(e, script(fr)); gbc_run_frame(e, px, pcm, 4096);
    int hb = (ram[0x7C8] | ram[0x7C9]) & 0x20; (void)hb;
    if (fr % 2000 == 0) { char p[512]; snprintf(p, sizeof p, "%s/god%d_%d.ppm", argv[2], god, fr); ppm(p, px);
      printf("frame %5d lives=%d  B3..B6=%02X %02X %02X %02X  playerY=%d score=%02X%02X\n", fr, gbc_get_lives(e), ram[0xB3], ram[0xB4], ram[0xB5], ram[0xB6], ram[0x668], ram[0x7E6], ram[0x7E7]); }
    (void)prev;
  }
  gbc_set_lives(e, 42);
  for (int t = 0; t < 200; ++t) { gbc_set_buttons(e, t < 3 ? GBC_BUTTON_START : 0); gbc_run_frame(e, px, pcm, 4096); }
  char p[512]; snprintf(p, sizeof p, "%s/god%d_lives42_pause.ppm", argv[2], god); ppm(p, px);
  printf("after set_lives(42): get_lives=%d B6=%02X\n", gbc_get_lives(e), ram[0xB6]);
  return 0;
}
