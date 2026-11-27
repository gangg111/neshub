/*
 * The Flintstones: The Surprise at Dinosaur Peak! (USA, Taito 1994), mapper 4 (MMC3)
 * 128K/256K -- the user's dump. Player code in PRG bank 8 ($8000).
 *
 * RAM:  $0304 lives left (HUD "P=02"; new game $8152 = 2, DEC $808E, game over below 0)
 *       $030D hearts (HUD "E="), $0402 post-hit blink timer, $037A 0 Fred / 1 Barney
 * Hit:  $9966 hearts-1 -> STA $030D at $9984 -> $0402=$FF. Invincibility AVEPKOSA turns
 *       that STA into RTS: no damage, no knockback, no blinking (bank 8 only).
 * Infinite lives: $808E DEC -> LDA (SXEETEVK, bank 8 only).
 * No slowdown (fuzz 0.01% late frames = room loads). One player; SELECT swaps heroes.
 */
#include <string.h>
#include "game.h"

static const GameBankCrc sig_banks[] = {{8, 0x9e00e2aau}, {14, 0xb2820e1bu}, {15, 0x48c91308u}};
const GameSig game_sig = {4, 128, 256, sig_banks, 3};

const GameOption game_options[] = {
    {0, 0},
};

static uint8_t* g_ram;
static int invincible, infinite_lives;

static void apply_cheats(void) {
  char codes[32] = "";
  if (invincible) strcat(codes, "AVEPKOSA");
  if (infinite_lives) { if (codes[0]) strcat(codes, "+"); strcat(codes, "SXEETEVK"); }
  core_cheats(codes);
}

void game_pre_frame(uint8_t* ram, uint32_t* b1, uint32_t* b2) { g_ram = ram; (void)b1; (void)b2; }
void game_post_frame(uint8_t* ram) { (void)ram; }
void game_after_load(uint8_t* ram) { g_ram = ram; }
void game_on_power(uint8_t* ram) { g_ram = ram; apply_cheats(); }

static int lives_get(void) { return g_ram ? g_ram[0x304] : 0; }
static void lives_set(int n) { if (g_ram) g_ram[0x304] = (uint8_t)(n < 0 ? 0 : n > 9 ? 9 : n); }
static int inv_get(void) { return invincible; }
static void inv_set(int on) { invincible = on; apply_cheats(); }
static int inf_get(void) { return infinite_lives; }
static void inf_set(int on) { infinite_lives = on; apply_cheats(); }

const GameMod game_mods[] = {
    {"lives", "Życia", MOD_STEPPER, 0, 9, 0, 0, lives_get, lives_set},
    {"invincible", "Nieśmiertelność", MOD_TOGGLE, 0, 1, 1, 0, inv_get, inv_set},
    {"infinite_lives", "Nieskończone życia", MOD_TOGGLE, 0, 1, 1, 0, inf_get, inf_set},
};
const int game_mod_count = sizeof game_mods / sizeof game_mods[0];
