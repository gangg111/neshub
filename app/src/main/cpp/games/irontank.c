/*
 * Iron Tank: The Invasion of Normandy (USA), SNK 1988, mapper 1 -- the user's dump.
 *
 * RAM (published Action Replay codes, checked in the emulator):
 *   $01D1  lives        $0306  player's armour (object 0 of the $0306,X table)
 *   $0039-$003E  special ammo (V, F, B, L, nuclear, fuel)
 * Wait-for-frame loops: $C0DE-$C0E0 (main) and $C10E-$C110 -- tools/itsurvey.c.
 *
 * Invincibility = Game Genie SZUVKESO ($E83C, fixed bank: the armour subtraction stops
 * being stored). All weapons = AXSGEAAE ($C8D0). ROM patches, so saves stay clean.
 *
 * Slowdown: 0.34 % of frames with many soldiers on screen (tools/itsurvey.c), none with
 * 2x-VBlank -> overclock on.
 * Boss corner bug (bosses freeze half off screen in the bottom-right corner, the fight
 * never ends): fixed by the built-in patch assets/patches/irontank/*.ips from
 * tools/it_bossfix.py (patches PRG bank 3 and $D7A9 -> those 8K banks, 7 and 14, are
 * kept out of the signature).
 */
#include <string.h>
#include "game.h"

/* PRG 8K banks 0, 2 (switchable code) and 15 (fixed: wait loops, the cheat sites). Not 7
 * or 14: the boss fix patch changes those. */
static const GameBankCrc sig_banks[] = {{0, 0x88efffb3u}, {2, 0x946456eeu}, {15, 0xdc4e7f3fu}};
const GameSig game_sig = {1, 128, 128, sig_banks, 3};

const GameOption game_options[] = {
    {"fceumm_overclocking", "2x-VBlank"},
    {0, 0},
};

static uint8_t* g_ram;
static int invincible, all_weapons;

static void apply_cheats(void) {
  char codes[32] = "";
  if (invincible) strcat(codes, "SZUVKESO");
  if (all_weapons) { if (codes[0]) strcat(codes, "+"); strcat(codes, "AXSGEAAE"); }
  core_cheats(codes);
}

void game_pre_frame(uint8_t* ram, uint32_t* b1, uint32_t* b2) { g_ram = ram; (void)b1; (void)b2; }
void game_post_frame(uint8_t* ram) { (void)ram; }
void game_after_load(uint8_t* ram) { g_ram = ram; }
void game_on_power(uint8_t* ram) { g_ram = ram; apply_cheats(); }

static int lives_get(void) { return g_ram ? g_ram[0x1D1] : 0; }
static void lives_set(int n) { if (g_ram) g_ram[0x1D1] = (uint8_t)(n < 1 ? 1 : n > 9 ? 9 : n); }
static int inv_get(void) { return invincible; }
static void inv_set(int on) { invincible = on; apply_cheats(); }
static int wpn_get(void) { return all_weapons; }
static void wpn_set(int on) { all_weapons = on; apply_cheats(); }

/* score: 24-bit at $01D2-$01D4, shown times ten */
static int score_get(void) { return g_ram ? (g_ram[0x1D2] | g_ram[0x1D3] << 8 | g_ram[0x1D4] << 16) * 10 : 0; }
static void score_set(int v) { (void)v; }

const GameMod game_mods[] = {
    {"score", "Wynik", MOD_STAT, 0, 0, 0, 0, score_get, score_set},
    {"lives", "Życia", MOD_STEPPER, 1, 9, 0, 0, lives_get, lives_set},
    {"invincible", "Nieśmiertelność", MOD_TOGGLE, 0, 1, 1, 0, inv_get, inv_set},
    {"all_weapons", "Wszystkie bronie", MOD_TOGGLE, 0, 1, 1, 0, wpn_get, wpn_set},
};
const int game_mod_count = sizeof game_mods / sizeof game_mods[0];
