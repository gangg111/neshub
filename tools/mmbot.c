/* Micro Machines: drives the boat for the shelf clip and prints the buttons as an art
 * script (hub/art.json). Greedy with lookahead from save states: every 8 frames it tries
 * accelerate straight / right / left followed by each of them again, and keeps the first
 * move of the pair that ends with the most open water around the boat (the screen centre;
 * the camera follows the boat) and the most scrolling (the boat at speed, not stuck in
 * the bubbles). The NES is deterministic, so the app replays the printed script exactly.
 *   mmbot rom.nes "<lead-in script>" race_frames > script.txt
 * Build like tools/artgen.c. */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "gbcplayer.h"
const char* hub_detect(const uint8_t* rom, size_t size);
int nestest_b_reads;

static uint32_t px[GBC_SCREEN_PIXELS], prev[GBC_SCREEN_PIXELS]; static int16_t pcm[8192];
static const uint32_t WATER = 0x4CA0FF;
static const uint32_t ACT[3] = {GBC_BUTTON_A, GBC_BUTTON_A | GBC_BUTTON_RIGHT, GBC_BUTTON_A | GBC_BUTTON_LEFT};
static const int STEP = 8;

static double score(GbcEmu* e, uint32_t a, uint32_t b) {
  double moved = 0;
  for (int f = 0; f < 2 * STEP; ++f) {
    gbc_set_buttons(e, f < STEP ? a : b); gbc_run_frame(e, px, pcm, 4096);
    if (f) for (int i = 0; i < GBC_SCREEN_PIXELS; i += 97) moved += (px[i] & 0xFFFFFF) != (prev[i] & 0xFFFFFF);
    memcpy(prev, px, sizeof px);
  }
  int water = 0, n = 0;
  for (int y = 112 - 28; y < 112 + 28; ++y) for (int x = 128 - 28; x < 128 + 28; ++x, ++n)
    water += (px[y * 256 + x] & 0xFFFFFF) == WATER;
  return (double)water / n + moved / (2 * STEP * (GBC_SCREEN_PIXELS / 97)) * 2;
}

int main(int argc, char** argv) {
  FILE* f = fopen(argv[1], "rb"); fseek(f, 0, SEEK_END); long n = ftell(f); rewind(f);
  uint8_t* rom = malloc(n); fread(rom, 1, n, f); fclose(f);
  hub_detect(rom, n);
  char err[128]; GbcEmu* e = gbc_open(rom, n, 48000, err, sizeof err); if (!e) return 1;
  /* lead-in: "mask:frames,..." */
  const char* s = argv[2];
  while (*s) { char* end; uint32_t b = strtoul(s, &end, 16); int k = strtol(end + 1, &end, 10);
    for (int i = 0; i < k; ++i) { gbc_set_buttons(e, b); gbc_run_frame(e, px, pcm, 4096); }
    s = *end == ',' ? end + 1 : end; }
  int race = atoi(argv[3]);
  size_t sz = gbc_state_size(); uint8_t* st = malloc(sz);
  printf("%s", argv[2]);
  uint32_t last = 0xFFFFFFFF; int run = 0;
  for (int t = 0; t < race; t += STEP) {
    gbc_state_save(e, st, sz);
    double best = -1; int bi = 0;
    for (int i = 0; i < 3; ++i) for (int j = 0; j < 3; ++j) {
      gbc_state_load(e, st, sz);
      double v = score(e, ACT[i], ACT[j]);
      if (v > best + 1e-9) { best = v; bi = i; }
    }
    gbc_state_load(e, st, sz);
    for (int k = 0; k < STEP; ++k) { gbc_set_buttons(e, ACT[bi]); gbc_run_frame(e, px, pcm, 4096); }
    if (ACT[bi] == last) run += STEP; else { if (run) printf(",%x:%d", last, run); last = ACT[bi]; run = STEP; }
  }
  printf(",%x:%d,*1:30\n", last, run);
  return 0;
}
