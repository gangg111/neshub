/*
 * Template for a new NES game: no core options, no fixes, no mods. The game runs as
 * is. Add a RAM map here as it is found (tools/ramfind.c & co.), then hooks and mods
 * on top of it -- see games/contraforce.c for a complete example.
 */
#include "game.h"

/* Fill in to have the hub pick this plug-in by itself (tools/romsig.py prints the CRCs). */
const GameSig game_sig = {0, 0, 0, 0, 0};

const GameOption game_options[] = {
    /* e.g. {"fceumm_overclocking", "2x-VBlank"} if the game has slowdown
     * (measure first: tools/lagtest.c) */
    {0, 0},
};

void game_pre_frame(uint8_t* ram, uint32_t* b1, uint32_t* b2) { (void)ram; (void)b1; (void)b2; }
void game_post_frame(uint8_t* ram) { (void)ram; }
void game_after_load(uint8_t* ram) { (void)ram; }
void game_on_power(uint8_t* ram) { (void)ram; }

/* The app shows no "Modyfikacje" entry while this list is empty. */
const GameMod game_mods[] = {{0}};
const int game_mod_count = 0;
