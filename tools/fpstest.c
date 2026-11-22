/* gbc_game_frames over 300 frames from a saved busy state. NES_OC=disabled|2x-VBlank */
#include <stdio.h>
#include <stdlib.h>
#include "gbcplayer.h"
int nestest_b_reads;
int main(int argc, char** argv) {
  FILE* f = fopen(argv[1], "rb"); fseek(f, 0, SEEK_END); long n = ftell(f); rewind(f);
  uint8_t* rom = malloc(n); fread(rom, 1, n, f); fclose(f);
  char err[128]; GbcEmu* e = gbc_open(rom, n, 48000, err, sizeof err);
  size_t sz = gbc_state_size(); uint8_t* st = malloc(sz);
  f = fopen(argv[2], "rb"); fread(st, 1, sz, f); fclose(f); gbc_state_load(e, st, sz);
  static uint32_t px[GBC_SCREEN_PIXELS]; static int16_t pcm[8192];
  uint64_t g0 = gbc_game_frames(e);
  for (int i = 0; i < 300; ++i) { gbc_set_buttons(e, GBC_BUTTON_RIGHT | GBC_BUTTON_TURBO_B); gbc_run_frame(e, px, pcm, 4096); }
  printf("%-10s 300 klatek (5 s) -> licznik gry %llu = %.1f fps gry\n", getenv("NES_OC"),
         (unsigned long long)(gbc_game_frames(e) - g0), (gbc_game_frames(e) - g0) / 5.0);
  return 0;
}
