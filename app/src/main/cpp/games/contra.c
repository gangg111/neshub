/*
 * Contra (USA, Konami 1988), mapper 2 -- the user's dump (identical to the one the
 * nes-contra-us disassembly builds from).
 *
 * Bug fixes = the built-in patch assets/patches/contra/Poprawki błędów.ips, built from
 * the disassembly (tools/contra_fix/src, "(NES Hub fix)" comments; docs/Bugs.md of
 * github.com/vermiceli/nes-contra-us):
 *   1. falling into water: the splash/puddle frame (sprite_18) was never shown
 *   2. snow field UFO boss: a stray DMC "sample" (code bytes) played on defeat
 *   3. base 4 boss Gemini: a hit on one exact frame left the split helmets vulnerable
 *   4. waterfall dragon: the game froze (endless loop) when the left hand died on the
 *      frame the arm advanced its routine (2 players)
 *   5. hangar mine cart: the other player was dragged 1px/frame while one rode a cart
 * Code in banks 0 and 7 moved by a few bytes, so the cheats below exist twice: for the
 * patched ROM and for the original (each compare byte matches only its own ROM).
 *
 * RAM: $32 P1 lives (0 = last life), $AA P1 weapon (low nibble 0-4 R/M/F/S/L, bit 4
 * rapid fire), $30 level. No slowdown (all 8 levels, 1 late frame in 148,000) -> no
 * overclock.
 */
#include <string.h>
#include "game.h"

/* PRG 8K banks 2, 6 and 8: the patch does not touch them. */
static const GameBankCrc sig_banks[] = {{2, 0x9dc48b78u}, {6, 0x68bd84d4u}, {8, 0xc3fcea1du}};
const GameSig game_sig = {2, 128, 0, sig_banks, 3};

const GameOption game_options[] = {
    {0, 0},
};

static uint8_t* g_ram;
static int invincible, infinite_lives;

static void apply_cheats(void) {
  char codes[96] = "";
  /* invincibility: the JSR kill_player after an enemy/bullet collision becomes LDA
   * (SXKVPZAX). Falling off the bottom of the screen still kills, as in the game. */
  if (invincible) strcat(codes, "E2CF?20:AD+E2C9?20:AD");
  /* infinite lives: DEC P1_NUM_LIVES,x becomes LDA (SZEIUZVI) */
  if (infinite_lives) { if (codes[0]) strcat(codes, "+"); strcat(codes, "DA09?D6:A5+DA03?D6:A5"); }
  core_cheats(codes);
}

void game_pre_frame(uint8_t* ram, uint32_t* b1, uint32_t* b2) { g_ram = ram; (void)b1; (void)b2; }
void game_post_frame(uint8_t* ram) { (void)ram; }
void game_after_load(uint8_t* ram) { g_ram = ram; }
void game_on_power(uint8_t* ram) { g_ram = ram; apply_cheats(); }

static int lives_get(void) { return g_ram ? g_ram[0x32] + 1 : 0; }
static void lives_set(int n) { if (g_ram) g_ram[0x32] = (uint8_t)((n < 1 ? 1 : n > 99 ? 99 : n) - 1); }
static int inv_get(void) { return invincible; }
static void inv_set(int on) { invincible = on; apply_cheats(); }
static int inf_get(void) { return infinite_lives; }
static void inf_set(int on) { infinite_lives = on; apply_cheats(); }
static int weapon_get(void) { return 0; }
static void weapon_set(int v) { if (v && g_ram) g_ram[0xAA] = 0x13; }   /* spread gun, rapid fire */

/* P1 score: 16-bit at $07E2/$07E3, shown with two zeros appended */
static int score_get(void) { return g_ram ? (g_ram[0x7E2] | g_ram[0x7E3] << 8) * 100 : 0; }
static void score_set(int v) { (void)v; }

const GameMod game_mods[] = {
    {"score", "Wynik", MOD_STAT, 0, 0, 0, 0, score_get, score_set},
    {"lives", "Życia", MOD_STEPPER, 1, 99, 0, 0, lives_get, lives_set},
    {"invincible", "Nieśmiertelność", MOD_TOGGLE, 0, 1, 1, 0, inv_get, inv_set},
    {"infinite_lives", "Nieskończone życia", MOD_TOGGLE, 0, 1, 1, 0, inf_get, inf_set},
    {"best_weapon", "Najlepsza broń (S)", MOD_ACTION, 0, 1, 0, "Gracz 1 dostaje karabin S z szybkim ogniem", weapon_get, weapon_set},
};
const int game_mod_count = sizeof game_mods / sizeof game_mods[0];
