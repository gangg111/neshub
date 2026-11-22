/* renderState() detour must not disturb the game: compare 120 frames after a detour
 * (save cur -> load other -> run 1 frame -> load cur) with 120 frames without it. */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "gbcplayer.h"
int nestest_b_reads;
int main(int argc, char** argv) {
  FILE* f = fopen(argv[1], "rb"); fseek(f, 0, SEEK_END); long n = ftell(f); rewind(f);
  uint8_t* rom = malloc(n); fread(rom, 1, n, f); fclose(f);
  char err[128]; GbcEmu* e = gbc_open(rom, n, 48000, err, sizeof err);
  static uint32_t px[GBC_SCREEN_PIXELS], a[GBC_SCREEN_PIXELS], shot[GBC_SCREEN_PIXELS]; static int16_t pcm[8192];
  size_t sz = gbc_state_size(); uint8_t *other = malloc(sz), *cur = malloc(sz), *base = malloc(sz);
  for (int i = 0; i < 400; ++i) { gbc_set_buttons(e, (i > 300 && i < 306) ? GBC_BUTTON_START : 0); gbc_run_frame(e, px, pcm, 4096); }
  gbc_state_save(e, other, sz);                       /* the "old save" */
  for (int i = 0; i < 300; ++i) gbc_run_frame(e, px, pcm, 4096);
  gbc_state_save(e, base, sz);
  for (int i = 0; i < 120; ++i) gbc_run_frame(e, a, pcm, 4096);   /* reference */
  gbc_state_load(e, base, sz);
  gbc_state_save(e, cur, sz); gbc_state_load(e, other, sz); gbc_run_frame(e, shot, NULL, 0); gbc_state_load(e, cur, sz);
  for (int i = 0; i < 120; ++i) gbc_run_frame(e, px, pcm, 4096);
  int nonblack = 0; for (int i = 0; i < GBC_SCREEN_PIXELS; ++i) nonblack += (shot[i] & 0xFFFFFF) != 0;
  printf("detour_identical=%d preview_nonblack_px=%d\n", !memcmp(a, px, sizeof a), nonblack);
  return 0;
}
