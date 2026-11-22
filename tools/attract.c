/* Run a ROM with no input (attract mode: intro/demo) and dump every Nth frame as PPM.
 * attract rom outdir [frames] [every] */
#include <stdio.h>
#include <stdlib.h>
#include "gbcplayer.h"
int nestest_b_reads;
int main(int argc, char** argv) {
  FILE* f = fopen(argv[1], "rb"); fseek(f, 0, SEEK_END); long n = ftell(f); rewind(f);
  uint8_t* rom = malloc(n); fread(rom, 1, n, f); fclose(f);
  int frames = argc > 3 ? atoi(argv[3]) : 3000, every = argc > 4 ? atoi(argv[4]) : 60;
  char err[128]; GbcEmu* e = gbc_open(rom, n, 48000, err, sizeof err);
  if (!e) { puts(err); return 1; }
  static uint32_t px[GBC_SCREEN_PIXELS]; static int16_t pcm[8192];
  for (int fr = 1; fr <= frames; ++fr) {
    gbc_set_buttons(e, 0); gbc_run_frame(e, px, pcm, 4096);
    if (fr % every) continue;
    char p[512]; snprintf(p, sizeof p, "%s/f%05d.ppm", argv[2], fr);
    FILE* o = fopen(p, "wb"); fprintf(o, "P6 256 240 255\n");
    for (int i = 0; i < 256 * 240; ++i) { uint32_t c = px[i]; fputc(c >> 16 & 255, o); fputc(c >> 8 & 255, o); fputc(c & 255, o); }
    fclose(o);
  }
  return 0;
}
