/*
 * The Jetsons: Cogswell's Caper! (USA, Taito/Natsume 1992), mapper 4 (MMC3) 128K/256K
 * -- the user's dump.
 *
 * RAM:  $0142 lives incl. the one being played (HUD "GEORGE n" shows $0142-1; game over
 *             when DEC $0142 at $BE52, bank 13, reaches 0)
 *       $0141 hearts (3; refilled to 3 when a life is lost)
 *       $0127 post-hit invulnerability timer ($20 on a hit, $82D0 bank 10 counts it down;
 *             non-zero = no damage, $85B4 bank 0 makes George blink while it runs)
 *       $0144 / $0145 the two HUD counters (BCD)
 * Invincibility: $0127 held at $FF every frame + $85B4 BEQ -> BMI (AUUAGIEY, bank 0 only)
 *       so a negative timer draws George without blinking. Not ESKETZEY/AUKETZEY: their
 *       compare byte $F0 at $82CE also matches bank 9 (AND #$0F / BEQ).
 * Infinite lives: $BE52 DEC -> LDA (SZSLXVVK, compare byte only in bank 13).
 * Level select (TCRF, VOOOGONU) is not exposed: the list has CHRSET, which hangs.
 */
#include <string.h>
#include "game.h"

static const GameBankCrc sig_banks[] = {{0, 0xe56685cfu}, {13, 0x6478527au}, {15, 0x754ef231u}};
const GameSig game_sig = {4, 128, 256, sig_banks, 3};

const GameOption game_options[] = {
    {0, 0},
};

static uint8_t* g_ram;
static int invincible, infinite_lives;

static void apply_cheats(void) {
  char codes[32] = "";
  if (invincible) strcat(codes, "AUUAGIEY");
  if (infinite_lives) { if (codes[0]) strcat(codes, "+"); strcat(codes, "SZSLXVVK"); }
  core_cheats(codes);
}

void game_pre_frame(uint8_t* ram, uint32_t* b1, uint32_t* b2) {
  g_ram = ram; (void)b1; (void)b2;
  if (invincible) ram[0x127] = 0xFF;
}
void game_post_frame(uint8_t* ram) { (void)ram; }
void game_after_load(uint8_t* ram) { g_ram = ram; }
void game_on_power(uint8_t* ram) { g_ram = ram; apply_cheats(); }

static int lives_get(void) { return g_ram ? g_ram[0x142] : 0; }
static void lives_set(int n) {
  if (!g_ram) return;
  g_ram[0x142] = (uint8_t)(n < 1 ? 1 : n > 9 ? 9 : n);
}
static int inv_get(void) { return invincible; }
static void inv_set(int on) {
  invincible = on; apply_cheats();
  if (!on && g_ram && g_ram[0x127] & 0x80) g_ram[0x127] = 0x20;   /* ordinary blink-out */
}
static int inf_get(void) { return infinite_lives; }
static void inf_set(int on) { infinite_lives = on; apply_cheats(); }

const GameMod game_mods[] = {
    {"lives", "Życia", MOD_STEPPER, 1, 9, 0, 0, lives_get, lives_set},
    {"invincible", "Nieśmiertelność", MOD_TOGGLE, 0, 1, 1, 0, inv_get, inv_set},
    {"infinite_lives", "Nieskończone życia", MOD_TOGGLE, 0, 1, 1, 0, inf_get, inf_set},
};
const int game_mod_count = sizeof game_mods / sizeof game_mods[0];
