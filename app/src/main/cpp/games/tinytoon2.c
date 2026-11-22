/*
 * Tiny Toon Adventures 2: Trouble in Wackyland (USA, Konami 1992), mapper 4 (MMC3) --
 * the user's dump (iNES header bytes 8-15 had junk, zeroed in assets).
 *
 * No lives: every ride costs tickets, and the game is over when they run out.
 * RAM (tools/fm2play RAMLOG + pokes checked at the ticket booth):
 *   $07D2/$07D1/$07D0  tickets, decimal digits hundreds/tens/ones (new game 0/1/0 = 10;
 *                      the booth and the ride HUD show three digits, 999 shows fine)
 *   $07C0  hearts in a ride (3)
 *   $0709/$0708/$0707  ride timer minutes / seconds / hundredths (BCD)
 * Hearts are taken by one DEC $07C0 per ride (published Game Genie "protection against
 * hits" codes, DEC -> LDA): fun house bank 0 $8153, train bank 6 $8558, roller coaster
 * bank 8 $83C7, bumper cars bank 8 $9A1B, log ride bank 9 $AABE. Each compare pair
 * (CE C0) occurs only in its own bank. Invincibility = all five. Falling off the track
 * still ends the ride (checked: hearts stay 3, the fall still sends you back to the
 * park). The "Invincibility" pair SAOAZASZ/EIOAGAEY did not stop hits in the rides.
 * Infinite tickets: the hook puts back any drop of the ticket count.
 * Slowdown: wait loop $FE10-$FE1B. Roller coaster and log ride 2-3 late frames per
 * 11,100 (transitions), but the BUMPER CARS lag on 12% of frames (729 of 6000, found by
 * tools/fuzz). With 2x-VBlank: 0 late frames, no flicker (same frames as without),
 * so the overclock is on for the whole game.
 */
#include <string.h>
#include "game.h"

/* PRG 8K banks 6, 8 (ride code) and 15 (fixed). */
static const GameBankCrc sig_banks[] = {{6, 0x80e42ff3u}, {8, 0x897175f2u}, {15, 0x6538affcu}};
const GameSig game_sig = {4, 128, 128, sig_banks, 3};

const GameOption game_options[] = {
    {"fceumm_overclocking", "2x-VBlank"},   /* bumper cars: 12% late frames -> 0 */
    {0, 0},
};

static uint8_t* g_ram;
static int invincible, infinite_tickets, kept = -1;

static int tickets(void) { return g_ram[0x7D2] % 10 * 100 + g_ram[0x7D1] % 10 * 10 + g_ram[0x7D0] % 10; }
static void put_tickets(int n) {
  n = n < 0 ? 0 : n > 999 ? 999 : n;
  g_ram[0x7D2] = (uint8_t)(n / 100); g_ram[0x7D1] = (uint8_t)(n / 10 % 10); g_ram[0x7D0] = (uint8_t)(n % 10);
}

static void apply_cheats(void) {
  core_cheats(invincible ? "8153?CE:AD+8558?CE:AD+83C7?CE:AD+9A1B?CE:AD+AABE?CE:AD" : "");
}

void game_pre_frame(uint8_t* ram, uint32_t* b1, uint32_t* b2) {
  g_ram = ram; (void)b1; (void)b2;
  if (!infinite_tickets) { kept = -1; return; }
  int t = tickets();
  if (t < kept) put_tickets(kept);           /* a ride was paid for */
  else kept = t;                             /* tickets won in a ride count */
}
void game_post_frame(uint8_t* ram) { (void)ram; }
void game_after_load(uint8_t* ram) { g_ram = ram; kept = -1; }
void game_on_power(uint8_t* ram) { g_ram = ram; kept = -1; apply_cheats(); }

static int tickets_get(void) { return g_ram ? tickets() : 0; }
static void tickets_set(int n) { if (g_ram) { put_tickets(n); kept = -1; } }
static int inv_get(void) { return invincible; }
static void inv_set(int on) { invincible = on; apply_cheats(); }
static int inf_get(void) { return infinite_tickets; }
static void inf_set(int on) { infinite_tickets = on; kept = -1; }

const GameMod game_mods[] = {
    {"tickets", "Bilety", MOD_STEPPER, 0, 999, 0, 0, tickets_get, tickets_set},
    {"invincible", "Nieśmiertelność", MOD_TOGGLE, 0, 1, 1, 0, inv_get, inv_set},
    {"infinite_tickets", "Nieskończone bilety", MOD_TOGGLE, 0, 1, 1, 0, inf_get, inf_set},
};
const int game_mod_count = sizeof game_mods / sizeof game_mods[0];
