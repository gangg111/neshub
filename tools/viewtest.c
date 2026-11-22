/* Picture options at run time (gbc_set_option): palette and Blargg's NTSC filter switched
 * while the game runs; writes the frames as PPM to compare. */
#include <stdio.h>
#include <stdlib.h>
#include "gbcplayer.h"
#include "game.h"
int nestest_b_reads;
static uint32_t px[GBC_SCREEN_PIXELS], wide[GBC_WIDE_MAX * GBC_SCREEN_HEIGHT]; static int16_t pcm[8192];
static void ppm(const char* p, const uint32_t* d, unsigned w) {
  FILE* o = fopen(p, "wb"); fprintf(o, "P6 %u 224 255\n", w);
  for (unsigned i = 0; i < w * 224; ++i) { fputc(d[i] >> 16 & 255, o); fputc(d[i] >> 8 & 255, o); fputc(d[i] & 255, o); } fclose(o);
}
int main(int argc, char** argv) {
  FILE* f = fopen(argv[1], "rb"); fseek(f, 0, SEEK_END); long n = ftell(f); rewind(f);
  uint8_t* rom = malloc(n); fread(rom, 1, n, f); fclose(f); hub_detect(rom, n);
  char err[128], p[600]; GbcEmu* e = gbc_open(rom, n, 48000, err, sizeof err);
  const char* steps[][2] = {{0, 0}, {"fceumm_palette", "sony-cxa2025as-us"}, {"fceumm_ntsc_filter", "composite"}, {"fceumm_ntsc_filter", "svideo"}, {"fceumm_ntsc_filter", "disabled"}};
  for (int s = 0; s < 5; ++s) {
    if (steps[s][0]) gbc_set_option(steps[s][0], steps[s][1]);
    for (int i = 0; i < (s ? 3 : 400); ++i) { gbc_set_buttons(e, 0); gbc_run_frame(e, px, pcm, 4096); }
    unsigned w = gbc_wide_frame(e, wide);
    snprintf(p, sizeof p, "%s/v%d.ppm", argv[2], s); ppm(p, px, 256);
    if (w) { snprintf(p, sizeof p, "%s/v%d_wide.ppm", argv[2], s); ppm(p, wide, w); }
    printf("step %d %s=%s: wide width %u, pixel(128,100)=%06x\n", s, steps[s][0] ? steps[s][0] : "-", steps[s][1] ? steps[s][1] : "-", w, px[100 * 256 + 128] & 0xFFFFFF);
  }
  return 0;
}
