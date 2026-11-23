/* Contra Force stage hold after KONTYNUUJ: emu A plays into stage 1 and saves; emu B
 * (fresh power-on, as the app does) loads it, then the game moves on to stage 2 (simulated:
 * the player leaves the screen for the stage-clear talk, $75 := 1, player back).
 * $75 must stay 1. Also: a fresh new game must start on stage 1 by itself. */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "gbcplayer.h"
#include "libretro.h"
#include "game.h"
int nestest_b_reads;
static uint32_t script(int fr) {
  if ((fr >= 300 && fr < 306) || (fr >= 420 && fr < 426) || (fr >= 540 && fr < 546)) return GBC_BUTTON_START;
  if (fr >= 700 && fr < 706) return GBC_BUTTON_A;
  if (fr >= 760 && fr < 766) return GBC_BUTTON_START;
  if (fr >= 1000 && fr < 1006) return GBC_BUTTON_RIGHT;
  if (fr >= 1060 && fr < 1066) return GBC_BUTTON_A;
  if (fr >= 1120 && fr < 1126) return GBC_BUTTON_START;
  return 0;
}
static uint32_t px[GBC_SCREEN_PIXELS]; static int16_t pcm[8192];
int main(int argc, char** argv) {
  FILE* f = fopen(argv[1], "rb"); fseek(f, 0, SEEK_END); long n = ftell(f); rewind(f);
  uint8_t* rom = malloc(n); fread(rom, 1, n, f); fclose(f);
  char err[128]; GbcEmu* e = gbc_open(rom, n, 48000, err, sizeof err);
  uint8_t* ram = retro_get_memory_data(RETRO_MEMORY_SYSTEM_RAM);
  int first = -1;
  for (int fr = 1; fr <= 1600; ++fr) { gbc_set_buttons(e, script(fr)); gbc_run_frame(e, px, pcm, 4096); if (first < 0 && ram[0x668]) first = fr; }
  printf("fresh game: player on screen from frame %d, stage index $75=%d\n", first, ram[0x75]);
  size_t sz = gbc_state_size(); uint8_t* st = malloc(sz); gbc_state_save(e, st, sz);
  gbc_close(e);
  e = gbc_open(rom, n, 48000, err, sizeof err); ram = retro_get_memory_data(RETRO_MEMORY_SYSTEM_RAM);
  gbc_state_load(e, st, sz);
  for (int i = 0; i < 60; ++i) { gbc_set_buttons(e, 0); gbc_run_frame(e, px, pcm, 4096); }
  /* stage clear: the talk screen has no player, then the next stage begins */
  ram[0x668] = 0; gbc_set_buttons(e, 0); gbc_run_frame(e, px, pcm, 4096);
  ram[0x75] = 1; ram[0x668] = 0x80;
  for (int i = 0; i < 30; ++i) { gbc_set_buttons(e, 0); gbc_run_frame(e, px, pcm, 4096); }
  printf("after continue + stage clear: $75=%d -> %s\n", ram[0x75], ram[0x75] == 1 ? "OK (stage 2)" : "BUG (back to stage 1)");
  /* reset in the middle of stage 3: a new game must start on stage 1 */
  ram[0x75] = 2;
  (gbc_reset)(e);
  for (int fr = 1; fr <= 1600; ++fr) { gbc_set_buttons(e, script(fr)); gbc_run_frame(e, px, pcm, 4096); }
  printf("reset during stage 3, new game: $75=%d -> %s\n", ram[0x75], ram[0x75] == 0 ? "OK (stage 1)" : "WRONG");
  /* on request: "Zacznij od etapu" 4 */
  int ks = -1, ka = -1;
  for (int i = 0; i < gbc_mod_count(); ++i) { if (!strcmp(gbc_mod(i)->key, "stage")) ks = i; if (!strcmp(gbc_mod(i)->key, "start_stage")) ka = i; }
  gbc_mod_set(ks, 4); gbc_mod_set(ka, 1);
  for (int fr = 1; fr <= 1600; ++fr) { gbc_set_buttons(e, script(fr)); gbc_run_frame(e, px, pcm, 4096); }
  printf("start from stage 4 on request: $75=%d -> %s\n", ram[0x75], ram[0x75] == 3 ? "OK" : "WRONG");
  ram[0x668] = 0; gbc_run_frame(e, px, pcm, 4096); ram[0x75] = 4; ram[0x668] = 0x80;
  for (int i = 0; i < 30; ++i) gbc_run_frame(e, px, pcm, 4096);
  printf("then stage 4 cleared: $75=%d -> %s\n", ram[0x75], ram[0x75] == 4 ? "OK (stage 5)" : "BUG (held)");
  return 0;
}
