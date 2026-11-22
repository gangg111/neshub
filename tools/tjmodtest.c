/* Tom & Jerry mods through the plug-in: lives stepper, invincibility on/off. */
#include <stdio.h>
#include <stdlib.h>
#include "gbcplayer.h"
#include "game.h"
#include "libretro.h"
int nestest_b_reads;
static uint32_t script(long fr) {
  if (fr < 1500) return (fr % 120 >= 100 && fr % 120 < 104) ? GBC_BUTTON_START : 0;
  static const uint32_t d[] = {GBC_BUTTON_RIGHT, GBC_BUTTON_RIGHT, GBC_BUTTON_LEFT, GBC_BUTTON_RIGHT | GBC_BUTTON_UP};
  uint32_t b = d[(fr / 300) % 4] | ((fr / 8) & 1 ? GBC_BUTTON_B : 0);
  if (fr % 70 < 12) b |= GBC_BUTTON_A;
  return b;
}
static void run(int inv) {
  FILE* f = fopen("app/src/main/assets/games/tomjerry.nes", "rb"); fseek(f, 0, SEEK_END); long n = ftell(f); rewind(f);
  uint8_t* rom = malloc(n); fread(rom, 1, n, f); fclose(f);
  char err[128]; GbcEmu* e = gbc_open(rom, n, 48000, err, sizeof err); free(rom);
  uint8_t* r = retro_get_memory_data(RETRO_MEMORY_SYSTEM_RAM);
  gbc_mod_set(gbc_mod_find("invincible"), inv);
  static uint32_t px[GBC_SCREEN_PIXELS]; static int16_t pcm[8192];
  int minE = 99;
  for (long fr = 1; fr <= 12000; ++fr) {
    gbc_set_buttons(e, script(fr)); gbc_run_frame(e, px, pcm, 4096);
    if (fr == 2000) { gbc_mod_set(gbc_mod_find("lives"), 7); printf("  lives set 7 -> RAM %d, get %d\n", r[0x4A2], gbc_mod_get(gbc_mod_find("lives"))); }
    if (fr > 1300 && r[0x4A3] < minE) minE = r[0x4A3];
  }
  printf("invincible=%d: lowest energy %d (full 3), lives at end %d\n", inv, minE, r[0x4A2]);
  gbc_close(e);
}
int main(void) { run(0); run(1); run(0); return 0; }
