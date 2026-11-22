/* Menu navigation probe: argv[3] = list of "frame:BUTTONS" steps (U D L R A B S T=start),
 * cheats in argv[4]; dumps a frame at every listed step + 30 frames. */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "gbcplayer.h"
#include "game.h"
#include "libretro.h"
int nestest_b_reads;
static void ppm(const char* p, const uint32_t* px) { FILE* f = fopen(p, "wb"); fprintf(f, "P6\n256 224\n255\n"); for (int i = 0; i < GBC_SCREEN_PIXELS; ++i) { fputc(px[i]>>16&255,f); fputc(px[i]>>8&255,f); fputc(px[i]&255,f);} fclose(f); }
int main(int argc, char** argv) {
  FILE* f = fopen(argv[1], "rb"); fseek(f, 0, SEEK_END); long n = ftell(f); rewind(f);
  uint8_t* rom = malloc(n); fread(rom, 1, n, f); fclose(f);
  char err[128]; GbcEmu* e = gbc_open(rom, n, 48000, err, sizeof err);
  static uint32_t px[GBC_SCREEN_PIXELS]; static int16_t pcm[8192];
  uint8_t* ram = retro_get_memory_data(RETRO_MEMORY_SYSTEM_RAM);
  if (argc > 4) core_cheats(argv[4]);
  int fr_at[64]; uint32_t bt[64]; int ns = 0;
  char* s = strdup(argv[3]);
  for (char* t = strtok(s, " "); t; t = strtok(NULL, " ")) {
    fr_at[ns] = atoi(t); uint32_t b = 0;
    for (char* c = strchr(t, ':') + 1; *c; ++c) switch (*c) {
      case 'U': b |= GBC_BUTTON_UP; break; case 'D': b |= GBC_BUTTON_DOWN; break;
      case 'L': b |= GBC_BUTTON_LEFT; break; case 'R': b |= GBC_BUTTON_RIGHT; break;
      case 'A': b |= GBC_BUTTON_A; break; case 'B': b |= GBC_BUTTON_B; break;
      case 'S': b |= GBC_BUTTON_SELECT; break; case 'T': b |= GBC_BUTTON_START; break; }
    bt[ns++] = b;
  }
  int last = fr_at[ns - 1] + 120;
  for (int fr = 1; fr <= last; ++fr) {
    uint32_t b = 0;
    for (int k = 0; k < ns; ++k) if (fr >= fr_at[k] && fr < fr_at[k] + 4) b = bt[k];
    gbc_set_buttons(e, b); gbc_run_frame(e, px, pcm, 4096);
    for (int k = 0; k < ns; ++k) if (fr == fr_at[k] + 40) {
      char p[512]; snprintf(p, sizeof p, "%s/nav_%02d.ppm", argv[2], k); ppm(p, px);
    }
  }
  FILE* o = fopen(argv[5] ? argv[5] : "/dev/null", "wb"); if (o) { fwrite(ram, 1, 2048, o); fclose(o); }
  return 0;
}
