/*
 * Contra Force (U), mapper 4 -- everything specific to this cartridge.
 *
 * RAM map (found with tools/ramfind.c, livesfind.c, pausefind.c, deathtrace.c +
 * dis6502.py; $75/$7F confirmed by published Action Replay codes):
 *   $0045        pause flag (1 from the START press until play resumes ~100 frames later)
 *   $0075        stage index 0..4
 *   $007F-$0082  weapon of Burns/Smith/Iron/Beans: high nibble status, bit 2 speed,
 *                bits 0-1 weapon 0..2
 *   $00B3-$00B6  lives of Burns/Smith/Iron/Beans, packed BCD, shown minus one
 *   $0668/$0669  player 1/2 Y (0 = no player; pit death at >= $D0)
 *   $07C8/$07C9  player 1/2 status; bit $20 = hit -> death animation -> life lost
 */
#include <stdlib.h>
#include "game.h"
#include "gbcplayer.h"

/* Contra Force (U): PRG banks 0, 8 and 15 (engine and fixed bank). */
static const GameBankCrc sig_banks[] = {{0, 0x8ed8a316u}, {8, 0xb1b0767cu}, {15, 0x44899d2eu}};
const GameSig game_sig = {4, 128, 128, sig_banks, 3};

const GameOption game_options[] = {
    /* The game's own code overruns the NES CPU budget and drops to ~41 fps when the
     * screen gets busy (measured: tools/lagtest.c). 266 extra scanlines inside vblank
     * give it twice the CPU time per frame without changing video timing. */
    {"fceumm_overclocking", "2x-VBlank"},
    {0, 0},
};

static uint8_t* g_ram;               /* last RAM pointer seen (single game instance) */
static int a_block[2];
static int invincible, best_weapon;
static uint8_t lives_lock[4];
static int stage_pick = 1;           /* menu stepper; not remembered (back to 1 after use) */
static int force_stage, force_seen_title;
static uint8_t weapon_real[4], weapon_forced[4];
static int weapons_forced;

/* ------------------------------------------------------------------- hooks -- */

void game_pre_frame(uint8_t* ram, uint32_t* b1, uint32_t* b2) {
  g_ram = ram;
  /* Pause-jump fix: the game starts a fresh jump if A is held at the moment it comes
   * back from pause, even in mid-air (infinite jumps). A does nothing on the pause
   * screen (verified), so it is hidden while paused and after resuming until released. */
  uint32_t* b[2] = {b1, b2};
  const uint32_t a_bits = GBC_BUTTON_A | GBC_BUTTON_TURBO_A;
  for (int p = 0; p < 2; ++p) {
    if (ram[0x45] != 0) a_block[p] = 1;
    else if (a_block[p] && !(*b[p] & a_bits)) a_block[p] = 0;
#ifdef GBC_HOST_TEST
    if (getenv("NES_NOJUMPFIX")) a_block[p] = 0;
#endif
    if (a_block[p]) *b[p] &= ~a_bits;
  }
  /* Stage select (only after "Zacznij od etapu"): hold the stage index until the player
   * is on screen, then let the game run its stages as usual. */
  if (force_stage) {
    ram[0x75] = (uint8_t)(force_stage - 1);
    if (ram[0x668] == 0) force_seen_title = 1;
    else if (force_seen_title && ram[0x45] == 0) force_stage = 0;
  }
  /* Best weapon: forced for this frame only, so RAM (and save states) keep the real one. */
  weapons_forced = best_weapon;
  if (weapons_forced) for (int i = 0; i < 4; ++i) {
    weapon_real[i] = ram[0x7F + i];
    weapon_forced[i] = (uint8_t)((weapon_real[i] & 0xF8) | 0x06);
    ram[0x7F + i] = weapon_forced[i];
  }
}

void game_post_frame(uint8_t* ram) {
  if (invincible) {
    /* A hit sets bit $20 and the next frames play the death. Cleared every frame, a
     * hit never turns into dying -- but not below the pit line, where the game needs
     * the bit to respawn the character. */
    if (ram[0x668] < 0xD0) ram[0x7C8] &= ~0x20;
    if (ram[0x669] < 0xD0) ram[0x7C9] &= ~0x20;
    /* Pits kill through a separate path: the life they take is given back. */
    for (int i = 0; i < 4; ++i) {
      if (ram[0xB3 + i] < lives_lock[i]) ram[0xB3 + i] = lives_lock[i];
      lives_lock[i] = ram[0xB3 + i];
    }
  }
  if (weapons_forced) for (int i = 0; i < 4; ++i) {
    /* keep anything the game itself changed this frame (pickup, death) */
    if (ram[0x7F + i] != weapon_forced[i]) weapon_real[i] = ram[0x7F + i];
    ram[0x7F + i] = weapon_real[i];
  }
}

void game_after_load(uint8_t* ram) {
  g_ram = ram;
  /* Invincibility is an app setting, never part of a state: re-take the life counters
   * from the loaded game so the pit refund cannot give back lives it does not have. */
  for (int i = 0; i < 4; ++i) lives_lock[i] = ram[0xB3 + i];
  /* a loaded game is never pushed to another stage */
  force_stage = 0;
}

void game_on_power(uint8_t* ram) {
  g_ram = ram;
  a_block[0] = a_block[1] = 0;
  /* A plain power-on starts on stage 1 by itself (the game clears $75 for a new game).
   * Holding stage 1 here broke normal play: after KONTYNUUJ the hold was still active at
   * the stage-clear talk and sent the player back to stage 1 (tools/cfstage.c). */
  force_stage = 0;
  force_seen_title = 0;
}

/* -------------------------------------------------------------------- mods -- */

static int bcd_to_int(uint8_t v) { return (v >> 4) * 10 + (v & 15); }

static int lives_get(void) {
  if (!g_ram) return 0;
  int m = 0;
  for (int i = 0; i < 4; ++i) { int v = bcd_to_int(g_ram[0xB3 + i]); if (v > m) m = v; }
  return m < 1 ? 1 : m;
}
static void lives_set(int n) {
  if (!g_ram) return;
  if (n < 1) n = 1;
  if (n > 99) n = 99;
  uint8_t bcd = (uint8_t)(((n / 10) << 4) | (n % 10));
  for (int i = 0; i < 4; ++i) { g_ram[0xB3 + i] = bcd; lives_lock[i] = bcd; }
}

static int inv_get(void) { return invincible; }
static void inv_set(int on) {
  invincible = on;
  if (g_ram) for (int i = 0; i < 4; ++i) lives_lock[i] = g_ram[0xB3 + i];
}

static int weapon_get(void) { return best_weapon; }
static void weapon_set(int on) { best_weapon = on; }

static int stage_get(void) { return stage_pick; }
static void stage_set(int s) { stage_pick = s < 1 ? 1 : s > 5 ? 5 : s; }

static void start_stage_run(int unused) {
  (void)unused;
  core_power_cycle();               /* calls game_on_power: stage 1 ... */
  force_stage = stage_pick;         /* ... overridden with the chosen one */
  stage_pick = 1;
}

const GameMod game_mods[] = {
    {"lives", "Życia", MOD_STEPPER, 1, 99, 0, 0, lives_get, lives_set},
    {"invincible", "Nieśmiertelność", MOD_TOGGLE, 0, 1, 1, 0, inv_get, inv_set},
    {"best_weapon", "Najlepsza broń", MOD_TOGGLE, 0, 1, 1, 0, weapon_get, weapon_set},
    {"stage", "Etap", MOD_STEPPER, 1, 5, 0, 0, stage_get, stage_set},
    {"start_stage", "Zacznij od etapu", MOD_ACTION, 0, 0, 0,
     "Nowa gra od etapu %d (wybierz postać jak zwykle)", stage_get, start_stage_run},
};
const int game_mod_count = sizeof game_mods / sizeof game_mods[0];
