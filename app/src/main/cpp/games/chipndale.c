/*
 * Chip 'n Dale Rescue Rangers (USA, Capcom 1990), mapper 1 (MMC1) -- the user's dump.
 *
 * RAM (found with tools/fm2play RAMLOG + tools/wwatch):
 *   $05B4/$05B6  Chip's spare lives, two decimal digits (tens/ones) with bit 7 set
 *                ($80 = 0); new game = $80/$82 (3 lives). Dale: $05E4/$05E6.
 *                $D20C-$D221 subtracts one life digit by digit, so the game itself
 *                handles 0-99 (lives are not drawn in the HUD).
 *   $F8          continues left (2, 1, 0, $FF = game over)
 *   $54,x        action (09 = hit), $5E,x invincibility timer after a hit
 * Every place that puts a player into the "hit" action first checks $5E,x
 * (LDA $5E / BNE skip): bank 6 $8038 (enemy contact), $B1C2 (projectiles), fixed
 * bank $C95F and $CAEC (hazards). Invincibility turns those BNE into BPL ($5E is
 * always < $80, so the skip is always taken). Falling out of the screen still kills.
 * Infinite lives: SBC #1 at $D214 becomes SBC #0.
 * No slowdown measured (zone 0, 12,000 frames: 0 late frames) -> no overclock.
 */
#include <string.h>
#include "game.h"

/* PRG 8K banks 12 (bank 6: collision code), 14 and 15 (fixed bank). */
static const GameBankCrc sig_banks[] = {{12, 0xfcaa734bu}, {14, 0x626a3143u}, {15, 0x379d0215u}};
const GameSig game_sig = {1, 128, 128, sig_banks, 3};

const GameOption game_options[] = {
    {0, 0},
};

static uint8_t* g_ram;
static int invincible, infinite_lives;

static void apply_cheats(void) {
  char codes[96] = "";
  if (invincible) strcat(codes, "8038?D0:10+B1C2?D0:10+C95F?D0:10+CAEC?D0:10");
  if (infinite_lives) { if (codes[0]) strcat(codes, "+"); strcat(codes, "D215?01:00"); }
  core_cheats(codes);
}

void game_pre_frame(uint8_t* ram, uint32_t* b1, uint32_t* b2) { g_ram = ram; (void)b1; (void)b2; }
void game_post_frame(uint8_t* ram) { (void)ram; }
void game_after_load(uint8_t* ram) { g_ram = ram; }
void game_on_power(uint8_t* ram) { g_ram = ram; apply_cheats(); }

/* lives shown to the user = spare lives + 1 (the one being played) */
static int lives_get(void) { return g_ram ? (g_ram[0x5B4] & 0x7F) * 10 + (g_ram[0x5B6] & 0x7F) + 1 : 0; }
static void lives_set(int n) {
  if (!g_ram) return;
  n = (n < 1 ? 1 : n > 99 ? 99 : n) - 1;
  g_ram[0x5B4] = (uint8_t)(0x80 | n / 10); g_ram[0x5B6] = (uint8_t)(0x80 | n % 10);
}
static int inv_get(void) { return invincible; }
static void inv_set(int on) { invincible = on; apply_cheats(); }
static int inf_get(void) { return infinite_lives; }
static void inf_set(int on) { infinite_lives = on; apply_cheats(); }

const GameMod game_mods[] = {
    {"lives", "Życia", MOD_STEPPER, 1, 99, 0, 0, lives_get, lives_set},
    {"invincible", "Nieśmiertelność", MOD_TOGGLE, 0, 1, 1, 0, inv_get, inv_set},
    {"infinite_lives", "Nieskończone życia", MOD_TOGGLE, 0, 1, 1, 0, inf_get, inf_set},
};
const int game_mod_count = sizeof game_mods / sizeof game_mods[0];
