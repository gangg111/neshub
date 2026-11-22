/* After "start from stage 4", a plain restart must begin on stage 1. */
#include <stdio.h>
#include <stdlib.h>
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
  return 0;
}
int main(int argc, char** argv) {
  FILE* f = fopen(argv[1], "rb"); fseek(f, 0, SEEK_END); long n = ftell(f); rewind(f);
  uint8_t* rom = malloc(n); fread(rom, 1, n, f); fclose(f);
  char err[128]; GbcEmu* e = gbc_open(rom, n, 48000, err, sizeof err);
  static uint32_t px[GBC_SCREEN_PIXELS]; static int16_t pcm[8192];
  uint8_t* ram = retro_get_memory_data(RETRO_MEMORY_SYSTEM_RAM);
  gbc_reset(e, 4);
  for (int fr = 1; fr <= 1500; ++fr) { gbc_set_buttons(e, script(fr)); gbc_run_frame(e, px, pcm, 4096); }
  printf("after 'start from stage 4': stage $75=%d\n", ram[0x75] + 1);
  gbc_reset(e, 0);
  for (int fr = 1; fr <= 1500; ++fr) { gbc_set_buttons(e, script(fr)); gbc_run_frame(e, px, pcm, 4096); }
  printf("after plain restart:        stage $75=%d -> %s\n", ram[0x75] + 1, ram[0x75] == 0 ? "OK" : "PAMIETA ETAP");
  /* game over path: from stage 4 with 1 life, idle until game over, pick END, new game */
  gbc_reset(e, 4);
  for (int fr = 1; fr <= 1500; ++fr) { gbc_set_buttons(e, script(fr)); gbc_run_frame(e, px, pcm, 4096); }
  gbc_set_lives(e, 1);
  int over = -1;
  for (int t = 0; t < 20000 && over < 0; ++t) { gbc_set_buttons(e, 0); gbc_run_frame(e, px, pcm, 4096); if (ram[0xB3] == 0 && ram[0xB4] == 0 && ram[0xB5] == 0 && ram[0xB6] == 0) over = t; }
  printf("game over reached: %s (stage was %d)\n", over >= 0 ? "yes" : "no", ram[0x75] + 1);
  for (int t = 0; t < 400; ++t) gbc_run_frame(e, px, pcm, 4096);
  /* END = DOWN then START on the CONTINUE/END screen, then a new game from the title */
  for (int t = 0; t < 600; ++t) {
    uint32_t b = 0;
    if (t >= 10 && t < 16) b = GBC_BUTTON_DOWN;
    if (t >= 40 && t < 46) b = GBC_BUTTON_START;
    gbc_set_buttons(e, b); gbc_run_frame(e, px, pcm, 4096);
  }
  for (int fr = 1; fr <= 1500; ++fr) { gbc_set_buttons(e, script(fr)); gbc_run_frame(e, px, pcm, 4096); }
  { FILE* o = fopen(argv[2], "wb"); fprintf(o, "P6\n256 224\n255\n"); for (int i = 0; i < GBC_SCREEN_PIXELS; ++i) { fputc(px[i]>>16&255,o); fputc(px[i]>>8&255,o); fputc(px[i]&255,o);} fclose(o); }
  printf("after game over -> END -> new game: stage $75=%d -> %s\n", ram[0x75] + 1, ram[0x75] == 0 ? "OK" : "PAMIETA ETAP");
  return 0;
}
