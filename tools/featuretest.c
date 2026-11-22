#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "gbcplayer.h"
#include "libretro.h"
int nestest_b_reads;
static uint32_t script(int fr) {
  if ((fr >= 300 && fr < 306) || (fr >= 420 && fr < 426) || (fr >= 540 && fr < 546)) return GBC_BUTTON_START;
  if (fr >= 700 && fr < 706) return GBC_BUTTON_A;
  if (fr >= 760 && fr < 766) return GBC_BUTTON_START;
  if (fr >= 1000 && fr < 1006) return GBC_BUTTON_RIGHT;
  if (fr >= 1060 && fr < 1066) return GBC_BUTTON_A;
  if (fr >= 1120 && fr < 1126) return GBC_BUTTON_START;
  if (fr > 1300) return GBC_BUTTON_RIGHT | GBC_BUTTON_TURBO_B;
  return 0;
}
static uint32_t hashf(const uint32_t* p) { uint32_t h = 2166136261u; for (int i = 0; i < GBC_SCREEN_PIXELS; ++i) h = (h ^ p[i]) * 16777619u; return h; }
static void ppm(const char* p, const uint32_t* px) { FILE* f = fopen(p, "wb"); fprintf(f, "P6\n256 224\n255\n"); for (int i = 0; i < GBC_SCREEN_PIXELS; ++i) { fputc(px[i]>>16&255,f); fputc(px[i]>>8&255,f); fputc(px[i]&255,f);} fclose(f); }
int main(int argc, char** argv) {
  FILE* f = fopen(argv[1], "rb"); fseek(f, 0, SEEK_END); long n = ftell(f); rewind(f);
  uint8_t* rom = malloc(n); fread(rom, 1, n, f); fclose(f);
  char err[128]; GbcEmu* e = gbc_open(rom, n, 48000, err, sizeof err);
  static uint32_t px[GBC_SCREEN_PIXELS]; static int16_t pcm[8192]; static uint32_t hist[2000];
  uint8_t* ram = retro_get_memory_data(RETRO_MEMORY_SYSTEM_RAM);
  for (int fr = 1; fr <= 1700; ++fr) { gbc_set_buttons(e, script(fr)); gbc_run_frame(e, px, pcm, 4096); hist[fr] = hashf(px); }
  /* 1) rewind 60 steps (= 120 frames) */
  gbc_set_rewind(e, 1);
  int match = -1;
  for (int s = 0; s < 60; ++s) gbc_run_frame(e, px, pcm, 4096);
  uint32_t h = hashf(px); for (int fr = 1700; fr > 1400; --fr) if (hist[fr] == h) { match = fr; break; }
  gbc_set_rewind(e, 0);
  printf("1) rewind 60 steps from frame 1700: screen equals frame %d (expected ~1580)\n", match);
  /* 2) best weapon must not stay in RAM */
  uint8_t before[4]; memcpy(before, ram + 0x7F, 4);
  gbc_set_best_weapon(e, 1);
  for (int t = 0; t < 120; ++t) { gbc_set_buttons(e, GBC_BUTTON_RIGHT | GBC_BUTTON_TURBO_B); gbc_run_frame(e, px, pcm, 4096); }
  printf("2) best weapon on, RAM $7F..$82 between frames: %02X %02X %02X %02X (before %02X %02X %02X %02X) -> %s\n",
         ram[0x7F], ram[0x80], ram[0x81], ram[0x82], before[0], before[1], before[2], before[3],
         memcmp(before, ram + 0x7F, 4) == 0 ? "OK, zapis stanu czysty" : "ZMIENIONE");
  if (argc > 2) { char p[512]; snprintf(p, sizeof p, "%s/feat_weapon.ppm", argv[2]); ppm(p, px); }
  gbc_set_best_weapon(e, 0);
  /* 4) controller 2 reaches the game: the core asks for port 1 */
  gbc_set_buttons2(e, GBC_BUTTON_START);
  for (int t = 0; t < 5; ++t) gbc_run_frame(e, px, pcm, 4096);
  gbc_set_buttons2(e, 0);
  for (int t = 0; t < 200; ++t) gbc_run_frame(e, px, pcm, 4096);
  if (argc > 2) { char p[512]; snprintf(p, sizeof p, "%s/feat_p2start.ppm", argv[2]); ppm(p, px); }
  printf("4) stage-1 gameplay, START on controller 2 only: pause flag $45=%d -> %s\n", ram[0x45], ram[0x45] ? "gra czyta pad 2" : "BRAK");
  /* 3) reset into stage 4 */
  gbc_reset(e, 4);
  for (int fr = 1; fr <= 1500; ++fr) { gbc_set_buttons(e, script(fr)); gbc_run_frame(e, px, pcm, 4096); }
  printf("3) reset(stage 4) + normal start: $75=%d (expected 3)\n", ram[0x75]);
  if (argc > 2) { char p[512]; snprintf(p, sizeof p, "%s/feat_stage4.ppm", argv[2]); ppm(p, px); }
  return 0;
}
