/* Generic: run a ROM with a simple input script (START taps, then walk right + A/B),
 * dump a frame every N frames. romshots rom outdir prefix [frames] [every] */
#include <stdio.h>
#include <stdlib.h>
#include "gbcplayer.h"
int nestest_b_reads;
int main(int argc, char** argv) {
  FILE* f = fopen(argv[1], "rb"); fseek(f, 0, SEEK_END); long n = ftell(f); rewind(f);
  uint8_t* rom = malloc(n); fread(rom, 1, n, f); fclose(f);
  int frames = argc > 4 ? atoi(argv[4]) : 3000, every = argc > 5 ? atoi(argv[5]) : 60;
  char err[128]; GbcEmu* e = gbc_open(rom, n, 48000, err, sizeof err);
  if (!e) { puts(err); return 1; }
  static uint32_t px[GBC_SCREEN_PIXELS]; static int16_t pcm[8192];
  for (int fr = 1; fr <= frames; ++fr) {
    uint32_t b = 0;
    if (fr % 240 >= 200 && fr % 240 < 206) b = GBC_BUTTON_START;   /* advance text / menus */
    if (fr % 240 >= 100 && fr % 240 < 104) b = GBC_BUTTON_A;
    gbc_set_buttons(e, b);
    gbc_run_frame(e, px, pcm, 4096);
    if (fr % every == 0) {
      char p[512]; snprintf(p, sizeof p, "%s/%s_%05d.ppm", argv[2], argv[3], fr);
      FILE* o = fopen(p, "wb"); fprintf(o, "P6\n256 224\n255\n");
      for (int i = 0; i < GBC_SCREEN_PIXELS; ++i) { fputc(px[i]>>16&255,o); fputc(px[i]>>8&255,o); fputc(px[i]&255,o); }
      fclose(o);
    }
  }
  return 0;
}
