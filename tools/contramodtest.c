/* Contra mods through the plug-in: run the same bot with a mod on/off, report lives. */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "gbcplayer.h"
#include "game.h"
#include "libretro.h"
int nestest_b_reads;
static uint32_t script(long fr) {
  if (fr < 520) return (fr > 350 && fr < 354) ? GBC_BUTTON_START : 0;
  uint32_t b = GBC_BUTTON_RIGHT | ((fr / 6) & 1 ? GBC_BUTTON_B : 0);
  if (fr % 50 < 8) b |= GBC_BUTTON_A;
  return b;
}
static void run(const char* rom_path, const char* mod, int on) {
  FILE* f = fopen(rom_path, "rb"); fseek(f, 0, SEEK_END); long n = ftell(f); rewind(f);
  uint8_t* rom = malloc(n); fread(rom, 1, n, f); fclose(f);
  char err[128]; GbcEmu* e = gbc_open(rom, n, 48000, err, sizeof err); free(rom);
  uint8_t* r = retro_get_memory_data(RETRO_MEMORY_SYSTEM_RAM);
  if (mod) gbc_mod_set(gbc_mod_find(mod), on);
  static uint32_t px[GBC_SCREEN_PIXELS]; static int16_t pcm[8192];
  int deaths = 0, last = -1;
  for (long fr = 1; fr <= 8000; ++fr) {
    if (getenv("FREEZEAE") && fr > 700) r[0xAE] = 0x80;
    if (getenv("LVL")) r[0x30] = (uint8_t)atoi(getenv("LVL"));
    gbc_set_buttons(e, script(fr)); gbc_run_frame(e, px, pcm, 4096);
    if (getenv("DEATHPPM") && fr > 700 && last >= 0 && r[0x32] < last) { char p[256]; snprintf(p, sizeof p, "%s/death%ld.ppm", getenv("DEATHPPM"), fr); FILE* o = fopen(p, "wb"); fprintf(o, "P6 256 224 255\n"); for (int i = 0; i < GBC_SCREEN_PIXELS; ++i) { fputc(px[i] >> 16 & 255, o); fputc(px[i] >> 8 & 255, o); fputc(px[i] & 255, o); } fclose(o); }
    if (fr == 700) { gbc_mod_set(gbc_mod_find("lives"), getenv("SETL") ? atoi(getenv("SETL")) : 7); printf("  lives set 7 -> RAM $32=%d, get %d\n", r[0x32], gbc_mod_get(gbc_mod_find("lives"))); }
    if (fr == 720) { gbc_mod_set(gbc_mod_find("best_weapon"), 1); printf("  weapon -> $AA=%02X\n", r[0xAA]); }
    if (fr > 700) { if (last >= 0 && r[0x32] < last) { deaths++; printf("    fr %ld lives %d -> %d (level %d)\n", fr, last + 1, r[0x32] + 1, r[0x30] + 1); } last = r[0x32]; }
    if (fr % 1000 == 0) printf("    fr %ld lives %d level %d\n", fr, r[0x32] + 1, r[0x30] + 1);
  }
  printf("%s %s=%d: lives lost %d, lives left %d\n", rom_path + (strrchr(rom_path, '/') - rom_path) + 1, mod ? mod : "-", on, deaths, r[0x32] + 1);
  gbc_close(e);
}
int main(int argc, char** argv) {
  if (argc > 2) { run(argv[1], argv[2], 1); return 0; }
  run(argv[1], NULL, 0);
  run(argv[1], "invincible", 1);
  run(argv[1], "infinite_lives", 1);
  return 0;
}
