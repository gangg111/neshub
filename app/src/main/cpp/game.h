/*
 * Game plug-in interface: everything specific to one cartridge lives in one file,
 * games/<name>.c, chosen by tools/build_native.sh (GAME=<name>). The generic core
 * (nes_core.c) calls the hooks below; the app builds its "Modyfikacje" menu from
 * game_mods[], so it never knows a RAM address.
 *
 * A new game starts from games/template.c: empty hooks, no options, no mods.
 */
#ifndef GAME_H_
#define GAME_H_

#include <stddef.h>
#include <stdint.h>

/* Extra FCEUmm core options, e.g. {"fceumm_overclocking", "2x-VBlank"}; {0,0}-terminated. */
typedef struct { const char* key; const char* value; } GameOption;

/* MOD_STAT: read-only, never in the menu; get() reports a number to the hub. Key
 * "score" = the current score (the hub keeps the best one per game). */
enum { MOD_TOGGLE = 0, MOD_STEPPER = 1, MOD_ACTION = 2, MOD_STAT = 3 };

typedef struct GameMod {
  const char* key;      /* stable id; persisted values are stored under it */
  const char* label;    /* menu label */
  int type;             /* MOD_TOGGLE (0/1), MOD_STEPPER (min..max), MOD_ACTION (set(1) runs it) */
  int min, max;
  int persist;          /* the app saves the value and re-applies it at start */
  const char* caption;  /* ACTION: confirmation text; "%d" is replaced by get() */
  int (*get)(void);
  void (*set)(int value);
} GameMod;

/* Which cartridges the plug-in fits, checked on the ROM after any patches: iNES mapper,
 * PRG/CHR size and CRC32s of chosen 8 KiB PRG banks -- banks with the code the plug-in
 * relies on, not text/graphics, so a translation patch keeps the plug-in active while a
 * different game (or a different revision) does not get RAM pokes meant for another. */
typedef struct { int bank; uint32_t crc; } GameBankCrc;
typedef struct {
  int mapper, prg_kb, chr_kb;
  const GameBankCrc* banks;
  int bank_count;
} GameSig;
extern const GameSig game_sig;     /* bank_count 0 = never auto-detected (template) */

extern const GameOption game_options[];
extern const GameMod game_mods[];
extern const int game_mod_count;

/* Hooks. ram = the 2 KiB NES work RAM. b1/b2 = controller 1/2 buttons about to be fed
 * to the game this frame (GBC_BUTTON_* incl. TURBO bits); a hook may edit them, e.g. to
 * hide a button. The core expands turbo bits afterwards. */
void game_pre_frame(uint8_t* ram, uint32_t* b1, uint32_t* b2);
void game_post_frame(uint8_t* ram);
/* After a state load, a rewind step and a power cycle: re-read anything cached. */
void game_after_load(uint8_t* ram);
/* Right after a power cycle (fresh game). */
void game_on_power(uint8_t* ram);

/* Core services a game may call. */
void core_power_cycle(void);
/* Replace the active cheat set (ROM/RAM patches applied by the core on every read):
 * Game Genie, Pro Action Replay or raw "AAAA:VV" / "AAAA?CC:VV", several joined with
 * '+'. NULL or "" = none. Call again with the full set whenever one changes. */
void core_cheats(const char* codes);

/* ------------------------------------------------------------------ hub --
 * A hub build links several plug-ins into one library. tools/build_native.sh compiles
 * each games/<id>.c with its symbols renamed to <id>_* (-Dgame_mods=<id>_game_mods ...)
 * and generates hub_games.c with the table below; the core (built with -DNES_HUB) then
 * reaches the selected game through hub_game(). */
#ifdef NES_HUB
typedef struct {
  const char* id;
  const GameOption* options;
  const GameMod* mods;
  const int* mod_count;
  void (*pre_frame)(uint8_t* ram, uint32_t* b1, uint32_t* b2);
  void (*post_frame)(uint8_t* ram);
  void (*after_load)(uint8_t* ram);
  void (*on_power)(uint8_t* ram);
  const GameSig* sig;
} HubGame;
const HubGame* hub_game(void);
int hub_select(const char* id);   /* 1 = found; call before gbc_open */
/* Picks the plug-in whose GameSig matches this iNES image (header included) and returns
 * its id, or selects the built-in "generic" plug-in (no fixes, no mods) and returns NULL. */
const char* hub_detect(const uint8_t* rom, size_t size);
#ifndef NES_HUB_TABLE
#define game_options (hub_game()->options)
#define game_mods (hub_game()->mods)
#define game_mod_count (*hub_game()->mod_count)
#define game_pre_frame (hub_game()->pre_frame)
#define game_post_frame (hub_game()->post_frame)
#define game_after_load (hub_game()->after_load)
#define game_on_power (hub_game()->on_power)
#endif
#endif

#endif /* GAME_H_ */
