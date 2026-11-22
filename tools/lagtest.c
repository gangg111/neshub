/* Fair slowdown measurement.
 *   NES_OC=2x-VBlank lagtest rom dir record   play stage 1, dump a save state every 200 frames
 *   NES_OC=<mode>    lagtest rom dir measure  load each state, run 150 frames, count lag frames
 * A lag frame is one where the game never read the joypad ($4016). */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "gbcplayer.h"
int nestest_b_reads;
extern unsigned fceu_joy_reads;
static uint32_t px[GBC_SCREEN_PIXELS]; static int16_t pcm[8192];
static uint32_t script(int fr) {
  if ((fr >= 300 && fr < 306) || (fr >= 420 && fr < 426) || (fr >= 540 && fr < 546)) return GBC_BUTTON_START;
  if (fr >= 700 && fr < 706) return GBC_BUTTON_A;
  if (fr >= 760 && fr < 766) return GBC_BUTTON_START;
  if (fr >= 1000 && fr < 1006) return GBC_BUTTON_RIGHT;
  if (fr >= 1060 && fr < 1066) return GBC_BUTTON_A;
  if (fr >= 1120 && fr < 1126) return GBC_BUTTON_START;
  if (fr > 1300) return GBC_BUTTON_RIGHT | GBC_BUTTON_TURBO_B | ((fr % 90) < 10 ? GBC_BUTTON_A : 0);
  return 0;
}
int main(int argc, char** argv) {
  FILE* f = fopen(argv[1], "rb"); fseek(f, 0, SEEK_END); long n = ftell(f); rewind(f);
  uint8_t* rom = malloc(n); fread(rom, 1, n, f); fclose(f);
  char err[128], p[512];
  GbcEmu* e = gbc_open(rom, n, 48000, err, sizeof err);
  if (!e) { puts(err); return 1; }
  size_t sz = gbc_state_size(); uint8_t* st = malloc(sz);
  if (!strcmp(argv[3], "record")) {
    int k = 0;
    for (int fr = 1; fr <= 4200; ++fr) {
      gbc_set_buttons(e, script(fr)); gbc_run_frame(e, px, pcm, 4096);
      if (fr >= 1400 && fr % 200 == 0) {
        gbc_state_save(e, st, sz);
        snprintf(p, sizeof p, "%s/st%02d.bin", argv[2], k++);
        FILE* o = fopen(p, "wb"); fwrite(st, 1, sz, o); fclose(o);
      }
    }
    printf("recorded %d states\n", k);
    return 0;
  }
  int lag = 0, total = 0;
  printf("%-14s", getenv("NES_OC"));
  for (int k = 0;; ++k) {
    snprintf(p, sizeof p, "%s/st%02d.bin", argv[2], k);
    FILE* i = fopen(p, "rb"); if (!i) break;
    fread(st, 1, sz, i); fclose(i);
    if (!gbc_state_load(e, st, sz)) { printf("load %d failed\n", k); return 1; }
    int l = 0;
    for (int fr = 0; fr < 150; ++fr) {
      gbc_set_buttons(e, GBC_BUTTON_RIGHT | GBC_BUTTON_TURBO_B | ((fr % 90) < 10 ? GBC_BUTTON_A : 0));
      fceu_joy_reads = 0; gbc_run_frame(e, px, pcm, 4096);
      l += fceu_joy_reads == 0;
    }
    printf(" %3d", l); lag += l; total += 150;
  }
  printf("  | lag %d/%d = %.1f%%  -> effective %.1f fps\n", lag, total, 100.0 * lag / total, 60.0988 * (total - lag) / total);
  return 0;
}
