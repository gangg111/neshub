/*
 * DuckTales 2 (USA, Capcom 1993), mapper 2 (UxROM, CHR-RAM) -- the user's dump, NES 2.0
 * header kept as it is.
 *
 * RAM (fixed bank code + tools/fm2play RAMLOG):
 *   $CE  spare lives, the HUD "P.n" (new game: $C107 LDA #2). The HUD prints one hex
 *        digit (14 -> "E", 98 -> junk), so the mod stops at 9 spare = 10.
 *   $CF  health, 16 per heart (3 hearts = 48); $F211 subtracts it unless $D8 is set
 *   $AF  blink timer after a hit
 * Invincibility = Game Genie ESOTVAEY ($E896 BEQ -> BNE: the blink timer never runs
 * out, so no hit lands). Pits and spikes still kill. Checked: 0 hearts lost in 8000
 * bot frames (without: game over).
 * Infinite lives = GZXGZGVG ($C422 DEC $CE -> BIT).
 * No slowdown: wait loop $C055-$C057, all five places (Niagara, Bermuda, Egypt, Mu,
 * Scotland) 12,400 frames each: 0 late frames in play; the only frames outside the
 * loop were black respawn screens after falling into a pit.
 */
#include <string.h>
#include "game.h"

/* PRG 8K banks 0, 14 and 15 (fixed bank: lives, health, hit code). */
static const GameBankCrc sig_banks[] = {{0, 0x878c9341u}, {14, 0xc9564cabu}, {15, 0x73f42f6bu}};
const GameSig game_sig = {2, 128, 0, sig_banks, 3};

const GameOption game_options[] = {
    {0, 0},
};

static uint8_t* g_ram;
static int invincible, infinite_lives;

static void apply_cheats(void) {
  char codes[32] = "";
  if (invincible) strcat(codes, "ESOTVAEY");
  if (infinite_lives) { if (codes[0]) strcat(codes, "+"); strcat(codes, "GZXGZGVG"); }
  core_cheats(codes);
}

void game_pre_frame(uint8_t* ram, uint32_t* b1, uint32_t* b2) { g_ram = ram; (void)b1; (void)b2; }
void game_post_frame(uint8_t* ram) { (void)ram; }
void game_after_load(uint8_t* ram) { g_ram = ram; }
void game_on_power(uint8_t* ram) { g_ram = ram; apply_cheats(); }

/* lives shown to the user = spare lives + 1 (the one being played) */
static int lives_get(void) { return g_ram ? g_ram[0xCE] + 1 : 0; }
static void lives_set(int n) { if (g_ram) g_ram[0xCE] = (uint8_t)((n < 1 ? 1 : n > 10 ? 10 : n) - 1); }
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
