/*
 * Duck Hunt (World, Nintendo 1984), mapper 0 -- the user's dump.
 *
 * The Zapper sits on port 2 (nes_zapper=port2, handled in nes_core.c): a touch on the
 * picture aims there and pulls the trigger; SELECT/START stay on controller 1 for the
 * game menu. FCEUmm's own crosshair is off -- the finger is the crosshair.
 */
#include "game.h"

/* PRG 8K banks 0 and 1 (the whole 16K program). */
static const GameBankCrc sig_banks[] = {{0, 0x80327d06u}, {1, 0x534e13b0u}};
const GameSig game_sig = {0, 16, 8, sig_banks, 2};

const GameOption game_options[] = {
    {"nes_zapper", "port2"},
    {"fceumm_show_crosshair", "disabled"},
    {"fceumm_zapper_tolerance", "6"},
    {0, 0},
};

static uint8_t* g_ram;
void game_pre_frame(uint8_t* ram, uint32_t* b1, uint32_t* b2) { g_ram = ram; (void)b1; (void)b2; }
void game_post_frame(uint8_t* ram) { (void)ram; }
void game_after_load(uint8_t* ram) { g_ram = ram; }
void game_on_power(uint8_t* ram) { g_ram = ram; }

/* score: packed BCD, $00C6 (ten-thousands) $00C5 (hundreds) $00C4 (units) */
static int bcd(uint8_t b) { return (b >> 4) * 10 + (b & 15); }
static int score_get(void) { return g_ram ? bcd(g_ram[0xC6]) * 10000 + bcd(g_ram[0xC5]) * 100 + bcd(g_ram[0xC4]) : 0; }
static void score_set(int v) { (void)v; }

const GameMod game_mods[] = {
    {"score", "Wynik", MOD_STAT, 0, 0, 0, 0, score_get, score_set},
};
const int game_mod_count = 1;
