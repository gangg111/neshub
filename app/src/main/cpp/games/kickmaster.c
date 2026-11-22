/*
 * Kick Master (U) + Polish translation by NAVI (patch.ips), mapper 4.
 *
 * RAM map (tools/kmprobe.c on the translated ROM; addresses from published Action
 * Replay codes, checked in the emulator):
 *   $030E        lives; the HUD shows value-1 as one digit (10 -> "9", above = garbage)
 *   $06B9        energy, $40 = full
 *   $00EA-$00EC  magic points, three digit tiles ($60 = "0")
 *   $00ED-$00F0  experience, four digit tiles
 *
 * Invincibility and infinite magic are Game Genie ROM patches (no RAM is touched, so
 * save states stay clean). Their compare bytes match exactly one PRG bank each and
 * the translation patch does not change those bytes (checked against the US dump).
 * No core options: no slowdown (0.01 % lag frames with or without overclock).
 * No stage select on purpose: skipping stages skips the power-ups collected in them.
 */
#include <string.h>
#include "game.h"

/* Kick Master (U), with or without NAVI's translation: PRG banks the patch leaves alone
 * (it rewrites text in 2-3, 5-7, 9, 14-15 and font tiles in CHR). */
static const GameBankCrc sig_banks[] = {{0, 0x510a6a1fu}, {1, 0xf63bbd9au}, {4, 0xfa843c91u}, {8, 0x08fbd554u},
                                        {10, 0xb359f387u}, {11, 0xfdd612a9u}, {12, 0xde97e365u}, {13, 0x01d031e3u}};
const GameSig game_sig = {4, 128, 128, sig_banks, 8};

const GameOption game_options[] = {
    {0, 0},
};

static uint8_t* g_ram;
static int invincible, infinite_magic;

/* Invincibility: APOSXTSA+SZOIOVSE+TAOSUTXI ($DE11/$DE1A/$DE1B, bank 14).
 * Infinite magic: SUELOISP ($BD81, bank 9). */
static void apply_cheats(void) {
  char codes[64] = "";
  if (invincible) strcat(codes, "APOSXTSA+SZOIOVSE+TAOSUTXI");
  if (infinite_magic) { if (codes[0]) strcat(codes, "+"); strcat(codes, "SUELOISP"); }
  core_cheats(codes);
}

void game_pre_frame(uint8_t* ram, uint32_t* b1, uint32_t* b2) { g_ram = ram; (void)b1; (void)b2; }
void game_post_frame(uint8_t* ram) { (void)ram; }
void game_after_load(uint8_t* ram) { g_ram = ram; }
/* The core's power cycle keeps the cheat list; re-apply anyway so it never depends on it. */
void game_on_power(uint8_t* ram) { g_ram = ram; apply_cheats(); }

static int lives_get(void) { return g_ram ? g_ram[0x30E] : 0; }
static void lives_set(int n) { if (g_ram) g_ram[0x30E] = (uint8_t)(n < 1 ? 1 : n > 10 ? 10 : n); }

static int inv_get(void) { return invincible; }
static void inv_set(int on) { invincible = on; apply_cheats(); }

static int mp_get(void) { return infinite_magic; }
static void mp_set(int on) { infinite_magic = on; apply_cheats(); }

const GameMod game_mods[] = {
    {"lives", "Życia", MOD_STEPPER, 1, 10, 0, 0, lives_get, lives_set},
    {"invincible", "Nieśmiertelność", MOD_TOGGLE, 0, 1, 1, 0, inv_get, inv_set},
    {"infinite_magic", "Nieskończona magia", MOD_TOGGLE, 0, 1, 1, 0, mp_get, mp_set},
};
const int game_mod_count = sizeof game_mods / sizeof game_mods[0];
