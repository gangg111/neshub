/*
 * Micro Machines (USA, Camerica/Codemasters 1991), mapper 71 -- the user's dump.
 *
 * RAM / code (published Action Replay / Game Genie codes, checked in the ROM):
 *   $0320  lives (Challenge; $F7C5 LDA #3 / STA $0320 at a new game, $A6D2 DEC $0320)
 *   $0308  race number; a new Challenge starts at the operand of $F7CC LDA #$00
 *
 * The user's dump says horizontal mirroring in its iNES header; the board is vertical.
 * With the wrong mirroring the background under sprite 0 (bottom right, line 224) is
 * empty, the hit the race loop waits for ($CA83 BIT $2002 / BVC) never comes and
 * every race freezes on its first frame. The ROM in assets has the header fixed
 * (byte 6 bit 0 = 1); nothing in the game code is changed.
 * The menus time their palette splits with BIT $2004 reads during rendering and
 * change palettes with rendering off; both are emulated in the hub's FCEUmm (ppu.c:
 * A2004/OamBusAt for every game; the background palette hack only for this one, via
 * nes_bg_palette_hack). Checked: menus stable frame to frame,
 * races 1-25 run.
 * No overclock: with 2x-VBlank the background vanishes every other frame in races
 * (the game's frame loop goes out of step). Without it 6-40 late frames per 8000
 * race frames, as on the console.
 * Lines 216-239 are cropped like a TV's overscan: sprites 0 and 1 (Y=$D8, X=$F7)
 * that time the bottom split sit on lines 217-224, below them rendering is off.
 */
#include <stdio.h>
#include <string.h>
#include "game.h"

/* PRG 8K banks 0, 27 and 31 (fixed bank: race loop, new-game code). */
static const GameBankCrc sig_banks[] = {{0, 0xe7ae212eu}, {27, 0x8dd71be1u}, {31, 0xe73d3108u}};
const GameSig game_sig = {71, 256, 0, sig_banks, 3};

const GameOption game_options[] = {
    {"fceumm_overscan_v_bottom", "24"},   /* last line shown 215: hides the timing sprites at 217-224 */
    {"nes_bg_palette_hack", "enabled"},   /* nes_core.c -> FCEUmm ppu.c */
    {0, 0},
};

static uint8_t* g_ram;
static int infinite_lives, start_race = 1;

static void apply_cheats(void) {
  char codes[64] = "";
  if (infinite_lives) strcat(codes, "GXSZZVVK");
  if (start_race > 1) {
    char c[16];
    snprintf(c, sizeof c, "%sF7CD?00:%02X", codes[0] ? "+" : "", start_race - 1);
    strcat(codes, c);
  }
  core_cheats(codes);
}

void game_pre_frame(uint8_t* ram, uint32_t* b1, uint32_t* b2) { g_ram = ram; (void)b1; (void)b2; }
void game_post_frame(uint8_t* ram) { (void)ram; }
void game_after_load(uint8_t* ram) { g_ram = ram; }
void game_on_power(uint8_t* ram) { g_ram = ram; apply_cheats(); }

static int lives_get(void) { return g_ram ? g_ram[0x320] : 0; }
static void lives_set(int n) { if (g_ram) g_ram[0x320] = (uint8_t)(n < 1 ? 1 : n > 9 ? 9 : n); }
static int inf_get(void) { return infinite_lives; }
static void inf_set(int on) { infinite_lives = on; apply_cheats(); }
static int race_get(void) { return start_race; }
static void race_set(int n) { start_race = n < 1 ? 1 : n > 25 ? 25 : n; apply_cheats(); }

const GameMod game_mods[] = {
    {"lives", "Życia", MOD_STEPPER, 1, 9, 0, 0, lives_get, lives_set},
    {"infinite_lives", "Nieskończone życia", MOD_TOGGLE, 0, 1, 1, 0, inf_get, inf_set},
    {"start_race", "Wyścig startowy (nowa gra)", MOD_STEPPER, 1, 25, 1, 0, race_get, race_set},
};
const int game_mod_count = sizeof game_mods / sizeof game_mods[0];
