/* Invincibility must not leak through save states.
 * 1) invincible on, state saved with lives=2, lives raised to 7, state loaded -> lives 2.
 * 2) state saved while invincible, loaded into a run with invincibility off -> the player
 *    can lose lives again (same scripted run that dies at 1571/1995/2370). */
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
int main(int argc, char** argv) {
  FILE* f = fopen(argv[1], "rb"); fseek(f, 0, SEEK_END); long n = ftell(f); rewind(f);
  uint8_t* rom = malloc(n); fread(rom, 1, n, f); fclose(f);
  char err[128]; GbcEmu* e = gbc_open(rom, n, 48000, err, sizeof err);
  static uint32_t px[GBC_SCREEN_PIXELS]; static int16_t pcm[8192];
  uint8_t* ram = retro_get_memory_data(RETRO_MEMORY_SYSTEM_RAM);
  size_t sz = gbc_state_size(); uint8_t* st = malloc(sz);
  for (int fr = 1; fr <= 1400; ++fr) { gbc_set_buttons(e, script(fr)); gbc_run_frame(e, px, pcm, 4096); }
  gbc_set_invincible(e, 1);
  gbc_set_lives(e, 2);
  gbc_state_save(e, st, sz);
  gbc_set_lives(e, 7);
  for (int t = 0; t < 30; ++t) { gbc_set_buttons(e, 0); gbc_run_frame(e, px, pcm, 4096); }
  gbc_state_load(e, st, sz);
  for (int t = 0; t < 30; ++t) { gbc_set_buttons(e, 0); gbc_run_frame(e, px, pcm, 4096); }
  printf("1) invincible on, loaded state with lives 2 (after raising to 7): lives now %d -> %s\n",
         gbc_get_lives(e), gbc_get_lives(e) == 2 ? "OK" : "PRZECIEK");
  /* 2) fresh emulator, invincibility off, load the state saved while invincible */
  gbc_close(e);
  e = gbc_open(rom, n, 48000, err, sizeof err); ram = retro_get_memory_data(RETRO_MEMORY_SYSTEM_RAM);
  gbc_set_invincible(e, 0);   /* the app re-applies its settings at start; here: off */
  for (int fr = 1; fr <= 1400; ++fr) { gbc_set_buttons(e, script(fr)); gbc_run_frame(e, px, pcm, 4096); }
  gbc_state_load(e, st, sz);
  int start = gbc_get_lives(e), lost = 0, prev = ram[0xB6];
  for (int fr = 1401; fr <= 3000; ++fr) { gbc_set_buttons(e, script(fr)); gbc_run_frame(e, px, pcm, 4096); if (ram[0xB6] < prev) lost++; prev = ram[0xB6]; }
  printf("2) state saved while invincible, loaded with invincibility off: start lives %d, lives lost %d -> %s\n",
         start, lost, lost > 0 ? "OK (gracz znow moze ginac)" : "PRZECIEK");
  return 0;
}
