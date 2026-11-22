/* Replays tools/fuzz's random input for a seed up to frame N, saves the state there and
 * writes the per-frame button masks, so a fuzz failure can be studied with fm2play. */
#include <stdio.h>
#include <stdlib.h>
#include "gbcplayer.h"
#include "game.h"
int nestest_b_reads;
int main(int argc, char** argv) {
  FILE* f = fopen(argv[1], "rb"); fseek(f, 0, SEEK_END); long n = ftell(f); rewind(f);
  unsigned char* rom = malloc(n); fread(rom, 1, n, f); fclose(f);
  long stop = atol(argv[2]); unsigned seed = atoi(argv[3]); srand(seed);
  printf("module %s\n", hub_detect(rom, n) ? hub_game()->id : "generic");
  char err[128]; GbcEmu* e = gbc_open(rom, n, 48000, err, sizeof err);
  static uint32_t px[GBC_SCREEN_PIXELS]; static int16_t pcm[8192];
  uint32_t held = 0; int hold = 0; FILE* log = fopen(argv[5], "w");
  for (long fr = 0; fr < stop; ++fr) {
    if (hold-- <= 0) {
      held = 0; hold = 4 + rand() % 36;
      int d = rand() % 10;
      if (d < 5) held |= GBC_BUTTON_RIGHT; else if (d < 6) held |= GBC_BUTTON_LEFT;
      if (rand() % 3 == 0) held |= GBC_BUTTON_UP; else if (rand() % 4 == 0) held |= GBC_BUTTON_DOWN;
      if (rand() % 2) held |= GBC_BUTTON_TURBO_B;
      if (rand() % 3 == 0) held |= GBC_BUTTON_A;
      if (rand() % 60 == 0) held |= GBC_BUTTON_START;
      if (rand() % 200 == 0) held |= GBC_BUTTON_SELECT;
    }
    uint32_t b = held;
    if (fr % 600 < 3) b |= GBC_BUTTON_START;
    fprintf(log, "%ld %03x\n", fr, b);
    gbc_set_buttons(e, b); gbc_run_frame(e, px, pcm, 4096);
    if (getenv("SHOT") && fr % atol(getenv("SHOT")) == 0 && fr >= 1700) { char p[600]; snprintf(p, sizeof p, "%s/f%06ld.ppm", getenv("OUT"), fr);
      FILE* o = fopen(p, "wb"); fprintf(o, "P6 256 224 255\n"); for (int i = 0; i < GBC_SCREEN_PIXELS; ++i) { fputc(px[i]>>16&255,o); fputc(px[i]>>8&255,o); fputc(px[i]&255,o); } fclose(o); }
  }
  size_t sz = gbc_state_size(); unsigned char* s = malloc(sz); gbc_state_save(e, s, sz);
  FILE* o = fopen(argv[4], "wb"); fwrite(s, 1, sz, o); fclose(o); fclose(log);
  return 0;
}
