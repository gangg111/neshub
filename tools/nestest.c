/* Host test: boots the ROM, mashes START, writes frames as PPM and reports audio. */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "gbcplayer.h"
int nestest_b_reads;
static void ppm(const char* p, const uint32_t* px) {
  FILE* f = fopen(p, "wb"); fprintf(f, "P6\n%d %d\n255\n", GBC_SCREEN_WIDTH, GBC_SCREEN_HEIGHT);
  for (int i = 0; i < GBC_SCREEN_PIXELS; ++i) { uint32_t c = px[i]; fputc(c>>16&255,f); fputc(c>>8&255,f); fputc(c&255,f); }
  fclose(f);
}
int main(int argc, char** argv) {
  if (argc < 3) { fprintf(stderr, "nestest rom.nes outdir\n"); return 2; }
  FILE* f = fopen(argv[1], "rb"); fseek(f, 0, SEEK_END); long n = ftell(f); rewind(f);
  uint8_t* rom = malloc(n); fread(rom, 1, n, f); fclose(f);
  char err[128] = "";
  GbcEmu* e = gbc_open(rom, n, 48000, err, sizeof err);
  if (!e) { fprintf(stderr, "open failed: %s\n", err); return 1; }
  static uint32_t px[GBC_SCREEN_PIXELS]; static int16_t pcm[4096 * 2];
  long samples = 0, nonzero = 0;
  for (int fr = 1; fr <= 2400; ++fr) {
    /* tap START at 300 and 400 frames (title -> menu -> game), then walk right + shoot */
    uint32_t b = 0;
    if ((fr >= 300 && fr < 306) || (fr >= 420 && fr < 426) || (fr >= 540 && fr < 546)) b = GBC_BUTTON_START;
    if (fr >= 700 && fr < 706) b = GBC_BUTTON_A;
    if (fr >= 760 && fr < 766) b = GBC_BUTTON_START;
    if (fr >= 1000 && fr < 1006) b = GBC_BUTTON_RIGHT;
    if (fr >= 1060 && fr < 1066) b = GBC_BUTTON_A;
    if (fr >= 1120 && fr < 1126) b = GBC_BUTTON_START;
    if (fr > 1300) b = GBC_BUTTON_RIGHT | ((fr / 8) & 1 ? GBC_BUTTON_B : 0);
    gbc_set_buttons(e, b);
    int k = gbc_run_frame(e, px, pcm, 4096);
    samples += k; for (int i = 0; i < k * 2; ++i) nonzero += pcm[i] != 0;
    if (fr % 100 == 0) { char p[256]; snprintf(p, sizeof p, "%s/f%04d.ppm", argv[2], fr); ppm(p, px); }
  }
  /* save state -> 300 frames -> load -> same 300 frames: images must match */
  size_t sz = gbc_state_size(); uint8_t* st = malloc(sz);
  static uint32_t a[GBC_SCREEN_PIXELS];
  printf("save=%d\n", gbc_state_save(e, st, sz));
  for (int i = 0; i < 300; ++i) { gbc_set_buttons(e, GBC_BUTTON_RIGHT | (i & 16 ? GBC_BUTTON_A : 0)); gbc_run_frame(e, a, pcm, 4096); }
  printf("load=%d\n", gbc_state_load(e, st, sz));
  for (int i = 0; i < 300; ++i) { gbc_set_buttons(e, GBC_BUTTON_RIGHT | (i & 16 ? GBC_BUTTON_A : 0)); gbc_run_frame(e, px, pcm, 4096); }
  printf("replay_identical=%d\n", !memcmp(a, px, sizeof a));
  /* turbo: hold TURBO_B (no plain B) while walking; screenshot shows a stream of shots */
  for (int i = 0; i < 40; ++i) { gbc_set_buttons(e, GBC_BUTTON_LEFT | GBC_BUTTON_TURBO_B); gbc_run_frame(e, px, pcm, 4096); }
  { printf("turbo B pattern:"); for (int i = 0; i < 12; ++i) { nestest_b_reads = 0; gbc_set_buttons(e, GBC_BUTTON_TURBO_B); gbc_run_frame(e, px, pcm, 4096); printf(" %d", nestest_b_reads > 0); } printf("\n"); }
  { char p[256]; snprintf(p, sizeof p, "%s/turbo.ppm", argv[2]); ppm(p, px); }
  for (int i = 0; i < 40; ++i) { gbc_set_buttons(e, GBC_BUTTON_LEFT | GBC_BUTTON_B); gbc_run_frame(e, px, pcm, 4096); }
  { char p[256]; snprintf(p, sizeof p, "%s/held.ppm", argv[2]); ppm(p, px); }
  printf("frames=2400 audio_frames=%ld (%.1f/frame) nonzero=%ld state=%zu sram=%zu\n",
         samples, samples / 2400.0, nonzero, gbc_state_size(), gbc_sram_size(e));
  gbc_close(e);
  return 0;
}
