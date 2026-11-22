/* Tiny Toon 2 mods through the hub API: from the park state, set tickets, pay for
 * rides with and without infinite tickets; hearts with invincibility in a ride. */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "gbcplayer.h"
#include "game.h"
int nestest_b_reads;
static uint8_t* slurp(const char* p, size_t* n) { FILE* f = fopen(p, "rb"); fseek(f, 0, SEEK_END); *n = ftell(f); rewind(f); uint8_t* b = malloc(*n); fread(b, 1, *n, f); fclose(f); return b; }
static const GameMod* mod(const char* k) { for (int i = 0; i < gbc_mod_count(); ++i) if (!strcmp(gbc_mod(i)->key, k)) return gbc_mod(i); return 0; }
static uint32_t px[256 * 240]; static int16_t pcm[4096];
static void run(GbcEmu* e, int n, uint32_t b) { for (int i = 0; i < n; ++i) { gbc_set_buttons(e, b); gbc_run_frame(e, px, pcm, 4096); } }
/* park: RIGHT, then A x3, START -> roller coaster (costs 4); idle until back in the park */
static void ride(GbcEmu* e) {
  run(e, 20, 0); run(e, 6, GBC_BUTTON_RIGHT); run(e, 150, 0);
  for (int i = 0; i < 3; ++i) { run(e, 6, GBC_BUTTON_A); run(e, 150, 0); }
  run(e, 6, GBC_BUTTON_START); run(e, 2500, 0);
}
int main(int argc, char** argv) {
  size_t n; uint8_t* rom = slurp(argv[1], &n); char err[128];
  hub_select("tinytoon2"); GbcEmu* e = gbc_open(rom, n, 48000, err, sizeof err);
  size_t sn; uint8_t* st = slurp(argv[2], &sn);
  for (int inf = 0; inf < 2; ++inf) {
    gbc_state_load(e, st, sn); run(e, 1, 0);
    mod("tickets")->set(123); mod("infinite_tickets")->set(inf);
    printf("infinite=%d tickets set 123 -> %d", inf, mod("tickets")->get());
    ride(e); printf(", after a ride %d", mod("tickets")->get());
    ride(e); printf(", after two %d\n", mod("tickets")->get());
  }
  mod("infinite_tickets")->set(0);
  for (int inv = 0; inv < 2; ++inv) {
    gbc_state_load(e, st, sn); run(e, 1, 0); mod("invincible")->set(inv);
    run(e, 20, 0); run(e, 6, GBC_BUTTON_RIGHT); run(e, 150, 0);
    for (int i = 0; i < 3; ++i) { run(e, 6, GBC_BUTTON_A); run(e, 150, 0); }
    run(e, 6, GBC_BUTTON_START); run(e, 400, 0);
    uint8_t* ram = retro_get_memory_data(0 /* unused */ ? 0 : RETRO_MEMORY_SYSTEM_RAM);
    int min = 9; for (int i = 0; i < 2500; ++i) { run(e, 1, 0); if (ram[0x7C0] < min) min = ram[0x7C0]; }
    printf("invincible=%d lowest hearts in 2500 ride frames: %d\n", inv, min);
  }
  return 0;
}
