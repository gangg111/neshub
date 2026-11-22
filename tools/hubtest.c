/* Hub check: open each game in turn in ONE process (as the app does when the player
 * switches games), run 600 frames, print its mods and a frame checksum. */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "gbcplayer.h"
#include "game.h"
static uint8_t* slurp(const char* p, size_t* n) {
  FILE* f = fopen(p, "rb"); if (!f) return NULL; fseek(f, 0, SEEK_END); *n = ftell(f); rewind(f);
  uint8_t* b = malloc(*n); fread(b, 1, *n, f); fclose(f); return b;
}
int nestest_b_reads;
int main(int argc, char** argv) {
  static uint32_t px[256 * 240]; static int16_t pcm[4096];
  for (int a = 1; a + 1 < argc; a += 2) {
    size_t n; uint8_t* rom = slurp(argv[a + 1], &n); char err[128] = "";
    if (!strcmp(argv[a], "auto")) { const char* id = hub_detect(rom, n); printf("%s -> %s\n", argv[a + 1], id ? id : "(generic)"); }
    else if (!hub_select(argv[a])) { printf("%s: unknown id\n", argv[a]); return 1; }
    GbcEmu* e = gbc_open(rom, n, 48000, err, sizeof err); free(rom);
    if (!e) { printf("%s: open failed %s\n", argv[a], err); return 1; }
    for (int i = 0; i < 600; ++i) { gbc_set_buttons(e, (i / 30) % 4 == 0 ? GBC_BUTTON_START : 0); gbc_run_frame(e, px, pcm, 4096); }
    uint32_t h = 2166136261u; for (int i = 0; i < 256 * 240; ++i) h = (h ^ px[i]) * 16777619u;
    printf("  %-12s mods=%d:", hub_game()->id, gbc_mod_count());
    for (int i = 0; i < gbc_mod_count(); ++i) printf(" %s", gbc_mod(i)->key);
    printf("  frame=%08x\n", h);
    gbc_close(e);
  }
  return 0;
}
