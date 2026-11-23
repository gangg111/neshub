/*
 * Monster in My Pocket (USA, Konami 1992), mapper 4 (MMC3) 128K/128K -- the user's dump.
 *
 * RAM (player code in PRG bank 6, $8500-$8720; X = player 0/1):
 *   $0716,X  health, 5 bars                    $0742,X  lives incl. the one being played (HUD "P=")
 *   $0730,X:$072E,X  score, BCD, hi:lo (HUD "355 PTS" = $03:$55)
 *   $0736,X  blink timer after a hit ($90)     $074A,X  stun timer
 * Hit:   $86DD DEC $0716,X -> LDA (SUSEIVVS): knockback and blink stay, no health lost.
 *        Pits still kill ($8680: fell below $D0 -> health 0).
 * Lives: $8550 DEC $0742,X -> LDA (SLSAASVS, both players).
 * Both patches are bank-6-only (compare byte $DE does not occur at those offsets in any
 * other bank). Player 2 joins mid-game with START and takes one of player 1's lives
 * (3 -> 2/1): the game's own rule, not a bug.
 * Slowdown: wait loop $FCBC-$FCC5. 1 player: none in play (the only late frames are the
 * 44-47-frame black loading screens). 2 players: up to 9% late frames per 10 s (boombox
 * fight, stage 2); tools 2P bot 100k frames 0.29% -> 0.00% with 2x-VBlank.
 */
#include <string.h>
#include "game.h"

static const GameBankCrc sig_banks[] = {{6, 0x526f6d42u}, {14, 0xb7eeeb30u}, {15, 0x5bae6c11u}};
const GameSig game_sig = {4, 128, 128, sig_banks, 3};

const GameOption game_options[] = {
    {"fceumm_overclocking", "2x-VBlank"},   /* 2-player slowdown, see above */
    {0, 0},
};

static uint8_t* g_ram;
static int invincible, infinite_lives;

static void apply_cheats(void) {
  char codes[32] = "";
  if (invincible) strcat(codes, "SUSEIVVS");
  if (infinite_lives) { if (codes[0]) strcat(codes, "+"); strcat(codes, "SLSAASVS"); }
  core_cheats(codes);
}

void game_pre_frame(uint8_t* ram, uint32_t* b1, uint32_t* b2) { g_ram = ram; (void)b1; (void)b2; }
void game_post_frame(uint8_t* ram) { (void)ram; }
void game_after_load(uint8_t* ram) { g_ram = ram; }
void game_on_power(uint8_t* ram) { g_ram = ram; apply_cheats(); }

static int lives_get(void) { return g_ram ? g_ram[0x742] : 0; }
static void lives_set(int n) {
  if (!g_ram) return;
  n = n < 1 ? 1 : n > 9 ? 9 : n;
  g_ram[0x742] = (uint8_t)n;
  if (g_ram[0x743]) g_ram[0x743] = (uint8_t)n;   /* player 2 only when in the game */
}
/* player 1 score: digits of the BCD bytes */
static int bcd(uint8_t b) { return (b >> 4) * 10 + (b & 15); }
static int score_get(void) { return g_ram ? bcd(g_ram[0x730]) * 100 + bcd(g_ram[0x72E]) : 0; }
static void score_set(int v) { (void)v; }
static int inv_get(void) { return invincible; }
static void inv_set(int on) { invincible = on; apply_cheats(); }
static int inf_get(void) { return infinite_lives; }
static void inf_set(int on) { infinite_lives = on; apply_cheats(); }

const GameMod game_mods[] = {
    {"lives", "Życia", MOD_STEPPER, 1, 9, 0, 0, lives_get, lives_set},
    {"invincible", "Nieśmiertelność", MOD_TOGGLE, 0, 1, 1, 0, inv_get, inv_set},
    {"infinite_lives", "Nieskończone życia", MOD_TOGGLE, 0, 1, 1, 0, inf_get, inf_set},
    {"score", "Wynik", MOD_STAT, 0, 0, 0, 0, score_get, score_set},
};
const int game_mod_count = sizeof game_mods / sizeof game_mods[0];
