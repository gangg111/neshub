/* Kick Master mods through the mod API: invincibility keeps energy/lives over the run
 * that otherwise dies; turning it off restores normal damage; lives set to 10. */
#include <stdio.h>
#include <stdlib.h>
#include "gbcplayer.h"
#include "libretro.h"
int nestest_b_reads;
uint32_t script(int fr) {
  if (fr < 3000) { int m = fr % 120; return (m >= 100 && m < 104) ? GBC_BUTTON_START : 0; }
  int m = fr % 90; uint32_t b = GBC_BUTTON_RIGHT;
  if (m < 8) b |= GBC_BUTTON_A; if (m >= 30 && m < 34) b |= GBC_BUTTON_B;
  return b;
}
static void run(GbcEmu* e, uint8_t* ram, int on, const char* what) {
  static uint32_t px[GBC_SCREEN_PIXELS]; static int16_t pcm[8192];
  gbc_reset(e);
  gbc_mod_set(gbc_mod_find("invincible"), on);
  int minhp = 255;
  for (int fr = 1; fr <= 9000; ++fr) {
    gbc_set_buttons(e, script(fr)); gbc_run_frame(e, px, pcm, 4096);
    if (fr > 3500 && ram[0x6B9] < minhp) minhp = ram[0x6B9];
  }
  printf("%-26s lowest energy %3d/64, lives %d\n", what, minhp, ram[0x30E]);
}
int main(int argc, char** argv) {
  FILE* f = fopen(argv[1], "rb"); fseek(f, 0, SEEK_END); long n = ftell(f); rewind(f);
  uint8_t* rom = malloc(n); fread(rom, 1, n, f); fclose(f);
  char err[128]; GbcEmu* e = gbc_open(rom, n, 48000, err, sizeof err);
  uint8_t* ram = retro_get_memory_data(RETRO_MEMORY_SYSTEM_RAM);
  printf("mods: %d ->", gbc_mod_count()); for (int i = 0; i < gbc_mod_count(); ++i) printf(" %s", *(const char* const*)gbc_mod(i)); printf("\n");
  run(e, ram, 0, "nieśmiertelność WYŁ:");
  run(e, ram, 1, "nieśmiertelność WŁ:");
  run(e, ram, 0, "znów WYŁ (kody zdjęte):");
  gbc_mod_set(gbc_mod_find("lives"), 10);
  printf("lives set 10 -> $030E=%d, get=%d; set 50 -> ", ram[0x30E], gbc_mod_get(gbc_mod_find("lives")));
  gbc_mod_set(gbc_mod_find("lives"), 50); printf("%d (clamped)\n", ram[0x30E]);
  return 0;
}
