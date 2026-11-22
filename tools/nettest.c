/* Netplay determinism: what the guest relies on. Mode "a": power on, random play to frame
 * 3000, save the state, then 3000 more frames, writing the pads and the RAM hash of each.
 * Mode "b" (another process = another history of the module's own variables): power on,
 * 500 frames of different random play, load the state, replay the pads, compare hashes. */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "gbcplayer.h"
#include "game.h"
int nestest_b_reads;
static uint32_t rnd_pad(void) {
  uint32_t b = 0; int d = rand() % 10;
  if (d < 5) b |= GBC_BUTTON_RIGHT; else if (d < 7) b |= GBC_BUTTON_LEFT;
  if (rand() % 3 == 0) b |= GBC_BUTTON_UP; else if (rand() % 4 == 0) b |= GBC_BUTTON_DOWN;
  if (rand() % 2) b |= GBC_BUTTON_B;
  if (rand() % 3 == 0) b |= GBC_BUTTON_A;
  if (rand() % 90 == 0) b |= GBC_BUTTON_START;
  return b;
}
int main(int argc, char** argv) {
  FILE* f = fopen(argv[1], "rb"); fseek(f, 0, SEEK_END); long n = ftell(f); rewind(f);
  uint8_t* rom = malloc(n); fread(rom, 1, n, f); fclose(f);
  hub_detect(rom, n);
  char err[128]; GbcEmu* e = gbc_open(rom, n, 48000, err, sizeof err);
  static uint32_t px[GBC_SCREEN_PIXELS]; static int16_t pcm[8192];
  size_t sz = gbc_state_size(); uint8_t* st = malloc(sz);
  char p[512]; const char* dir = argv[3];
  if (argv[2][0] == 'a') {
    srand(1);
    uint32_t b1 = 0, b2 = 0;
    for (int i = 0; i < 3000; ++i) { if (i % 8 == 0) { b1 = rnd_pad(); b2 = rnd_pad(); } gbc_set_buttons(e, b1); gbc_set_buttons2(e, b2); gbc_run_frame(e, px, pcm, 4096); }
    gbc_state_save(e, st, sz); snprintf(p, sizeof p, "%s/s.st", dir); FILE* o = fopen(p, "wb"); fwrite(st, 1, sz, o); fclose(o);
    snprintf(p, sizeof p, "%s/log.txt", dir); o = fopen(p, "w");
    for (int i = 0; i < 3000; ++i) {
      if (i % 8 == 0) { b1 = rnd_pad(); b2 = rnd_pad(); }
      gbc_set_buttons(e, b1); gbc_set_buttons2(e, b2); gbc_run_frame(e, px, pcm, 4096);
      fprintf(o, "%x %x %08x\n", b1, b2, gbc_ram_hash(e));
    }
    fclose(o);
  } else {
    srand(7);
    for (int i = 0; i < 500; ++i) { gbc_set_buttons(e, rnd_pad()); gbc_run_frame(e, px, pcm, 4096); }
    snprintf(p, sizeof p, "%s/s.st", dir); FILE* q = fopen(p, "rb"); fread(st, 1, sz, q); fclose(q);
    if (!gbc_state_load(e, st, sz)) { puts("load failed"); return 1; }
    snprintf(p, sizeof p, "%s/log.txt", dir); q = fopen(p, "r");
    unsigned b1, b2, h; int i = 0, bad = 0, first = -1;
    while (fscanf(q, "%x %x %x", &b1, &b2, &h) == 3) {
      gbc_set_buttons(e, b1); gbc_set_buttons2(e, b2); gbc_run_frame(e, px, pcm, 4096);
      if (gbc_ram_hash(e) != h) { if (first < 0) first = i; ++bad; }
      ++i;
    }
    printf("%-14s frames %d, differing %d, first at %d\n", hub_game() ? hub_game()->id : "?", i, bad, first);
  }
  return 0;
}
