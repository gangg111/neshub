/* Play long with invincibility (GG) and record RAM; report bytes that step 0->1->2
 * rarely (stage/round candidates) and dump frames to see progress. */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "gbcplayer.h"
#include "game.h"
#include "libretro.h"
int nestest_b_reads;
uint32_t script(int fr) {
  if (fr < 3000) { int m = fr % 120; return (m >= 100 && m < 104) ? GBC_BUTTON_START : 0; }
  int m = fr % 90; uint32_t b = GBC_BUTTON_RIGHT;
  if (m < 8) b |= GBC_BUTTON_A; if (m >= 30 && m < 34) b |= GBC_BUTTON_B;
  if ((fr / 900) % 4 == 3) b = (b & ~GBC_BUTTON_RIGHT) | GBC_BUTTON_LEFT;   /* unstick */
  if (fr % 1500 < 3) b |= GBC_BUTTON_START;   /* advance any dialog */
  return b;
}
static void ppm(const char* p, const uint32_t* px) { FILE* f = fopen(p, "wb"); fprintf(f, "P6\n256 224\n255\n"); for (int i = 0; i < GBC_SCREEN_PIXELS; ++i) { fputc(px[i]>>16&255,f); fputc(px[i]>>8&255,f); fputc(px[i]&255,f);} fclose(f); }
static uint8_t hist[2048][256];  /* per address: values seen in order (first 8 changes) */
int main(int argc, char** argv) {
  FILE* f = fopen(argv[1], "rb"); fseek(f, 0, SEEK_END); long n = ftell(f); rewind(f);
  uint8_t* rom = malloc(n); fread(rom, 1, n, f); fclose(f);
  char err[128]; GbcEmu* e = gbc_open(rom, n, 48000, err, sizeof err);
  static uint32_t px[GBC_SCREEN_PIXELS]; static int16_t pcm[8192];
  uint8_t* ram = retro_get_memory_data(RETRO_MEMORY_SYSTEM_RAM);
  core_cheats("APOSXTSA+SZOIOVSE+TAOSUTXI");
  int total = atoi(argv[3]);
  static uint8_t prev[2048]; static int changes[2048]; static int seq[2048][8], seqn[2048];
  for (int fr = 1; fr <= total; ++fr) {
    gbc_set_buttons(e, script(fr)); gbc_run_frame(e, px, pcm, 4096);
    if (fr == 3500) memcpy(prev, ram, 2048);
    if (fr > 3500) for (int a = 0; a < 2048; ++a) if (ram[a] != prev[a]) {
      if (changes[a] < 8) { seq[a][seqn[a]++] = ram[a] | (fr << 8); }
      changes[a]++; prev[a] = ram[a];
    }
    if (fr % 3000 == 0) { char p[512]; snprintf(p, sizeof p, "%s/st_%06d.ppm", argv[2], fr); ppm(p, px); }
  }
  for (int a = 0; a < 2048; ++a) if (changes[a] >= 1 && changes[a] <= 4) {
    int ok = 1, v0 = ram[a];
    for (int k = 0; k < seqn[a]; ++k) { int v = seq[a][k] & 255; if (v > 8) ok = 0; }
    if (!ok) continue;
    printf("$%03X changes=%d:", a, changes[a]); for (int k = 0; k < seqn[a]; ++k) printf(" ->%d@%d", seq[a][k] & 255, seq[a][k] >> 8); printf("  now=%d\n", v0);
  }
  return 0;
}
