/*
 * Tom & Jerry (and Tuffy) (U), Hi Tech Expressions 1991, mapper 4 -- the user's dump.
 *
 * RAM (published Action Replay codes, checked in the emulator):
 *   $04A2  lives (3 at start, HUD two digits)   $04A3  energy (3 = full)
 *   $047C  invulnerability flag
 * Wait-for-frame loops: $BFC6 (main) and $E58E (subroutine) -- tools/slowsurvey.c.
 *
 * Slowdown: almost none; world 2 drops a few frames (e.g. bubbles) without overclock,
 * 2x-VBlank removes them. Invincibility = Game Genie EIUXOYEY+SAUZVNSX (ROM patch in
 * bank 2: no RAM poke, saves stay clean; Jerry does not flicker with it).
 */
#include <string.h>
#include "game.h"

/* PRG banks 2 (invincibility code), 14 and 15 (fixed: engine, wait loop). */
static const GameBankCrc sig_banks[] = {{2, 0x9e65ba7au}, {14, 0xc72ae68eu}, {15, 0xcfeb54e2u}};
const GameSig game_sig = {4, 128, 128, sig_banks, 3};

const GameOption game_options[] = {
    {"fceumm_overclocking", "2x-VBlank"},
    {0, 0},
};

static uint8_t* g_ram;
static int invincible;

static void apply_cheats(void) { core_cheats(invincible ? "EIUXOYEY+SAUZVNSX" : ""); }

void game_pre_frame(uint8_t* ram, uint32_t* b1, uint32_t* b2) { g_ram = ram; (void)b1; (void)b2; }
void game_post_frame(uint8_t* ram) { (void)ram; }
void game_after_load(uint8_t* ram) { g_ram = ram; }
void game_on_power(uint8_t* ram) { g_ram = ram; apply_cheats(); }

static int lives_get(void) { return g_ram ? g_ram[0x4A2] : 0; }
static void lives_set(int n) { if (g_ram) g_ram[0x4A2] = (uint8_t)(n < 1 ? 1 : n > 9 ? 9 : n); }
static int inv_get(void) { return invincible; }
static void inv_set(int on) { invincible = on; apply_cheats(); }

const GameMod game_mods[] = {
    {"lives", "Życia", MOD_STEPPER, 1, 9, 0, 0, lives_get, lives_set},
    {"invincible", "Nieśmiertelność", MOD_TOGGLE, 0, 1, 1, 0, inv_get, inv_set},
};
const int game_mod_count = sizeof game_mods / sizeof game_mods[0];
