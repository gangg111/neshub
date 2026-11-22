/*
 * Tiny Toon Adventures (USA, Konami 1991), mapper 4 (MMC3) -- the user's dump
 * (iNES header bytes 8-15 had junk, zeroed in assets; game code untouched).
 *
 * RAM ($07E0 checked with tools/fm2play RAMLOG, the rest from Data Crystal / AR codes):
 *   $07E0  spare lives as shown in the HUD ("x2" at a new game, $CF2B LDA #2).
 *          The HUD has one digit and the game cuts anything above 9 back to 9 on
 *          the next life (poked 15/99 -> 9 after a death), so the mod stops at 9+1.
 *   $07E1 carrots, $07E3 power, $07E4 heart (one free hit), $004C game state
 *   (08 = playing), $0053 level, $0031 area, $0625 blink timer after (re)spawn.
 * Bank 0 $815E-$8169: no blink ($0624) and player hit ($67 = 1) -> JMP $9DFA, which
 * takes the heart or kills. Invincibility = Game Genie EYSEGPEI (BNE $815C -> BEQ):
 * the hit is never taken. Pits and time-up still kill (their own path, $06CF/$9F98).
 * Infinite lives = SZNOUNVK ($9F7B DEC $07E0 -> LDA). Both compare bytes occur only
 * in bank 0. B held = run (max speed $73 24 -> 40).
 * Known bug (TASVideos / NESdev "Game bugs"): LEFT+RIGHT together while moving skips
 * the speed cap ($83A9: the brake path adds the RIGHT acceleration, up to 68). The hub
 * core already drops opposite directions (fceumm_up_down_allowed off) -> cannot happen;
 * checked: 24 -> 68 only with that option enabled. Soak test tools/fuzz 4x100k frames:
 * 0 jams, 0 frozen screens.
 * No slowdown in play (level 1, 12,000 frames: every late frame is in
 * state 06, the black transition) -> no overclock.
 */
#include <string.h>
#include "game.h"

/* PRG 8K banks 0 (player code, cheats), 14 and 15 (fixed). */
static const GameBankCrc sig_banks[] = {{0, 0xb2ef4861u}, {14, 0x14780969u}, {15, 0x815a3f99u}};
const GameSig game_sig = {4, 128, 128, sig_banks, 3};

const GameOption game_options[] = {
    {0, 0},
};

static uint8_t* g_ram;
static int invincible, infinite_lives;

static void apply_cheats(void) {
  char codes[32] = "";
  if (invincible) strcat(codes, "EYSEGPEI");
  if (infinite_lives) { if (codes[0]) strcat(codes, "+"); strcat(codes, "SZNOUNVK"); }
  core_cheats(codes);
}

void game_pre_frame(uint8_t* ram, uint32_t* b1, uint32_t* b2) { g_ram = ram; (void)b1; (void)b2; }
void game_post_frame(uint8_t* ram) { (void)ram; }
void game_after_load(uint8_t* ram) { g_ram = ram; }
void game_on_power(uint8_t* ram) { g_ram = ram; apply_cheats(); }

/* lives shown to the user = spare lives + 1 (the one being played) */
static int lives_get(void) { return g_ram ? g_ram[0x7E0] + 1 : 0; }
static void lives_set(int n) { if (g_ram) g_ram[0x7E0] = (uint8_t)((n < 1 ? 1 : n > 10 ? 10 : n) - 1); }
static int inv_get(void) { return invincible; }
static void inv_set(int on) { invincible = on; apply_cheats(); }
static int inf_get(void) { return infinite_lives; }
static void inf_set(int on) { infinite_lives = on; apply_cheats(); }

const GameMod game_mods[] = {
    {"lives", "Życia", MOD_STEPPER, 1, 10, 0, 0, lives_get, lives_set},
    {"invincible", "Nieśmiertelność", MOD_TOGGLE, 0, 1, 1, 0, inv_get, inv_set},
    {"infinite_lives", "Nieskończone życia", MOD_TOGGLE, 0, 1, 1, 0, inf_get, inf_set},
};
const int game_mod_count = sizeof game_mods / sizeof game_mods[0];
