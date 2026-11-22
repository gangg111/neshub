/* Soak test: random play for many frames. Reports CPU jam, frozen screen (identical
 * frames for 10 s while inputs change), lag frames, and sprite overflow (>8 sprites on
 * one scanline = hardware flicker). Restarts at game over by pressing START. */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "gbcplayer.h"
#include "game.h"
#include "fceu-types.h"
#include "x6502struct.h"
extern X6502 X;
extern uint8_t SPRAM[0x100], PPU[4];
extern unsigned fceu_joy_reads;
int nestest_b_reads;
static uint32_t hashf(const uint32_t* p) { uint32_t h = 2166136261u; for (int i = 0; i < GBC_SCREEN_PIXELS; i += 7) h = (h ^ p[i]) * 16777619u; return h; }
int main(int argc, char** argv) {
  FILE* f = fopen(argv[1], "rb"); fseek(f, 0, SEEK_END); long n = ftell(f); rewind(f);
  uint8_t* rom = malloc(n); fread(rom, 1, n, f); fclose(f);
  long frames = atol(argv[2]); unsigned seed = argc > 3 ? atoi(argv[3]) : 1; srand(seed);
#ifdef NES_HUB
  { const char* id = hub_detect(rom, n); fprintf(stderr, "module %s\n", id ? id : "generic"); }
#endif
  char err[128]; GbcEmu* e = gbc_open(rom, n, 48000, err, sizeof err);
  static uint32_t px[GBC_SCREEN_PIXELS]; static int16_t pcm[8192];
  int lagdump = 0, ovfdump = 0;
  long lag = 0, maxlag = 0, run = 0, jams = 0, freezes = 0, same = 0, ovfFrames = 0, ovfLinesMax = 0;
  uint32_t last = 0, held = 0; int hold = 0;
  for (long fr = 0; fr < frames; ++fr) {
    if (hold-- <= 0) {           /* new random chord, held 4-40 frames, biased to play */
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
    if (fr % 600 < 3) b |= GBC_BUTTON_START;   /* keeps menus / game over moving */
    gbc_set_buttons(e, b);
    fceu_joy_reads = 0;
    gbc_run_frame(e, px, pcm, 4096);
    if (getenv("DUMPAT") && fr == atol(getenv("DUMPAT"))) { char p[512]; snprintf(p, sizeof p, "%s/dump_%s.ppm", getenv("OUT"), getenv("NES_SPRLIM") ? getenv("NES_SPRLIM") : "enabled"); FILE* o = fopen(p, "wb"); fprintf(o, "P6\n256 224\n255\n"); for (int i = 0; i < GBC_SCREEN_PIXELS; ++i) { fputc(px[i]>>16&255,o); fputc(px[i]>>8&255,o); fputc(px[i]&255,o);} fclose(o); return 0; }
    if (X.jammed) { jams++; printf("CPU JAM at frame %ld PC=%04X\n", fr, X.PC); break; }
    if (fceu_joy_reads == 0 && getenv("LAGLOG")) printf("LAG %ld\n", fr);
    if (fceu_joy_reads == 0) { lag++; if (++run > maxlag) maxlag = run; } else { if (run >= 3 && lagdump++ < 6) { char p[512]; snprintf(p, sizeof p, "%s/lag_%u_%ld.ppm", getenv("OUT"), seed, fr); FILE* o = fopen(p, "wb"); fprintf(o, "P6\n256 224\n255\n"); for (int i = 0; i < GBC_SCREEN_PIXELS; ++i) { fputc(px[i]>>16&255,o); fputc(px[i]>>8&255,o); fputc(px[i]&255,o);} fclose(o); printf("lag run %ld ending at frame %ld\n", run, fr); } run = 0; }
    uint32_t h = hashf(px);
    if (h == last) { if (++same == 600) { freezes++; printf("frozen screen 10 s at frame %ld\n", fr); } } else same = 0;
    last = h;
    /* sprite overflow from OAM: 8x16 or 8x8 */
    int tall = (PPU[0] & 0x20) ? 16 : 8; int cnt[240] = {0}; int worst = 0;
    int yc[256] = {0}; for (int s = 0; s < 64; ++s) yc[SPRAM[s*4]]++;
    for (int s = 0; s < 64; ++s) { int y = SPRAM[s * 4] + 1; if (y >= 240 || yc[SPRAM[s*4]] > 12) continue; /* parked/hidden stacks */ for (int k = 0; k < tall && y + k < 240; ++k) if (++cnt[y + k] > worst) worst = cnt[y + k]; }
    if (worst > 8 && ovfdump++ < 3) { char p[512]; snprintf(p, sizeof p, "%s/ovf_%u_%ld.ppm", getenv("OUT"), seed, fr); FILE* o = fopen(p, "wb"); fprintf(o, "P6\n256 224\n255\n"); for (int i = 0; i < GBC_SCREEN_PIXELS; ++i) { fputc(px[i]>>16&255,o); fputc(px[i]>>8&255,o); fputc(px[i]&255,o);} fclose(o); }
    if (worst > 8) { ovfFrames++; if (worst > ovfLinesMax) ovfLinesMax = worst; }
  }
  printf("seed=%u frames=%ld (%.1f min) jams=%ld frozen10s=%ld lag=%.2f%% longest_lag=%ld sprite_overflow_frames=%.1f%% max_sprites_on_line=%ld\n",
    seed, frames, frames / 3606.0, jams, freezes, 100.0 * lag / frames, maxlag, 100.0 * ovfFrames / frames, ovfLinesMax);
  return 0;
}
