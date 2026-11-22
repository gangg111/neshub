/* Iron Tank: auto-play north (the game scrolls up) with the plug-in's mods, count frames
 * that end outside the wait loops ($C0DE-$C0E0, $C10E-$C110), track armour/lives/score.
 *   itsurvey outdir frames inv weapons     env NES_OC=disabled|2x-VBlank */
#include <stdio.h>
#include <stdlib.h>
#include "gbcplayer.h"
#include "game.h"
#include "libretro.h"
#include "fceu-types.h"
#include "x6502struct.h"
extern X6502 X;
int nestest_b_reads;
static void dump(const char* dir, long fr, const uint32_t* px, const char* tag) {
  char p[512]; snprintf(p, sizeof p, "%s/%s%07ld.ppm", dir, tag, fr);
  FILE* o = fopen(p, "wb"); fprintf(o, "P6 256 224 255\n");
  for (int i = 0; i < GBC_SCREEN_PIXELS; ++i) { fputc(px[i] >> 16 & 255, o); fputc(px[i] >> 8 & 255, o); fputc(px[i] & 255, o); }
  fclose(o);
}
static uint32_t script(long fr) {
  if (fr < 1500) return (fr % 120 >= 100 && fr % 120 < 104) ? GBC_BUTTON_START : 0;
  static const uint32_t d[] = {GBC_BUTTON_UP, GBC_BUTTON_UP | GBC_BUTTON_LEFT, GBC_BUTTON_UP, GBC_BUTTON_UP | GBC_BUTTON_RIGHT,
                               GBC_BUTTON_UP, GBC_BUTTON_LEFT, GBC_BUTTON_UP, GBC_BUTTON_RIGHT, GBC_BUTTON_DOWN | GBC_BUTTON_RIGHT, GBC_BUTTON_UP};
  long ph = fr / 90;
  uint32_t b = d[(ph * 7) % 10];
  if ((fr / 6) & 1) b |= GBC_BUTTON_A; else b |= GBC_BUTTON_B;
  if (fr % 4000 < 3) b |= GBC_BUTTON_START;               /* past any message/pause screen */
  if (fr % 4000 >= 60 && fr % 4000 < 63) b |= GBC_BUTTON_START;
  return b;
}
int main(int argc, char** argv) {
  FILE* f = fopen("app/src/main/assets/games/irontank.nes", "rb"); fseek(f, 0, SEEK_END); long n = ftell(f); rewind(f);
  uint8_t* rom = malloc(n); fread(rom, 1, n, f); fclose(f);
  long frames = atol(argv[2]);
  char err[128]; GbcEmu* e = gbc_open(rom, n, 48000, err, sizeof err);
  if (!e) { puts(err); return 1; }
  uint8_t* r = retro_get_memory_data(RETRO_MEMORY_SYSTEM_RAM);
  gbc_mod_set(gbc_mod_find("invincible"), atoi(argv[3]));
  gbc_mod_set(gbc_mod_find("all_weapons"), atoi(argv[4]));
  static uint32_t px[GBC_SCREEN_PIXELS]; static int16_t pcm[8192];
  long slow = 0, run = 0, maxRun = 0, counted = 0, wslow = 0; int minA = 999;
  for (long fr = 1; fr <= frames; ++fr) {
    gbc_set_buttons(e, script(fr)); gbc_run_frame(e, px, pcm, 4096);
    if (fr <= 1500) continue;
    ++counted;
    int busy = (X.PC < 0xC0DE || X.PC > 0xC0E0) && (X.PC < 0xC10E || X.PC > 0xC110);
    if (busy) { ++slow; ++wslow; if (++run > maxRun) maxRun = run; } else run = 0;
    if (r[0x306] < minA) minA = r[0x306];
    if (fr % 600 == 0) { if (wslow >= 6) { printf("  frames %ld-%ld: %ld slow\n", fr - 599, fr, wslow); dump(argv[1], fr, px, "slow"); } wslow = 0; }
    if (fr % 3000 == 0) { printf("fr %ld lives %d armour %02X score %02X%02X%02X\n", fr, r[0x1D1], r[0x306], r[0x1D4], r[0x1D3], r[0x1D2]); dump(argv[1], fr, px, "t"); }
  }
  printf("slow frames: %ld of %ld (%.2f%%), longest run %ld; lowest armour %02X\n", slow, counted, 100.0 * slow / counted, maxRun, minA);
  return 0;
}
