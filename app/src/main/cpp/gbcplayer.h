/*
 * GBC Player -- plain-C wrapper around the binjgb emulator core.
 *
 * This header is deliberately free of any JNI/Android dependency so the exact
 * same code that runs inside the app can be linked into a host test binary
 * (tools/nativetest.c).  gbcplayer.c is nothing but the JNI marshalling layer
 * on top of this API.
 *
 * Everything here is single-threaded: one GbcEmu belongs to one thread.
 */
#ifndef GBCPLAYER_H_
#define GBCPLAYER_H_

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#define GBC_SCREEN_WIDTH 256
#define GBC_SCREEN_HEIGHT 224
#define GBC_SCREEN_PIXELS (GBC_SCREEN_WIDTH * GBC_SCREEN_HEIGHT)
/* Widest line the NTSC filter makes (602 for 256 px), rounded up. */
#define GBC_WIDE_MAX 640

/* Button mask bits.  Same order as binjgb's joypad_pack_buttons(). */
#define GBC_BUTTON_A      (1u << 0)
#define GBC_BUTTON_B      (1u << 1)
#define GBC_BUTTON_SELECT (1u << 2)
#define GBC_BUTTON_START  (1u << 3)
#define GBC_BUTTON_RIGHT  (1u << 4)
#define GBC_BUTTON_LEFT   (1u << 5)
#define GBC_BUTTON_UP     (1u << 6)
#define GBC_BUTTON_DOWN   (1u << 7)
#define GBC_BUTTON_TURBO_A (1u << 8)
#define GBC_BUTTON_TURBO_B (1u << 9)

typedef struct GbcEmu GbcEmu;

/* Creates an emulator from a ROM image held in |rom| (the bytes are copied, the
 * caller keeps ownership).  |sample_rate| is the audio rate in Hz, e.g. 48000.
 * Returns NULL on failure and, when |err| is non-NULL, fills it with a
 * human-readable reason. */
GbcEmu* gbc_open(const uint8_t* rom, size_t rom_size, int sample_rate,
                 char* err, size_t err_size);
void gbc_close(GbcEmu* emu);

/* Latches the currently held buttons.  Cheap; call once per frame. */
void gbc_set_buttons(GbcEmu* emu, uint32_t mask);

/* Runs exactly one video frame.
 *
 * |argb_out|, when non-NULL, receives GBC_SCREEN_PIXELS pixels in Android
 * ARGB_8888 int order (0xAARRGGBB).  Pass NULL to skip the conversion and read
 * the raw frame through gbc_framebuffer() instead.
 *
 * |pcm_out|, when non-NULL, receives signed 16-bit stereo interleaved samples,
 * DC-centred and ready for AudioTrack.  |pcm_capacity_frames| is its size in
 * *stereo frames* (i.e. shorts / 2).  Allocate at least sample_rate / 50
 * frames; a video frame is ~1/59.7 s.
 *
 * Returns the number of stereo frames written to |pcm_out| (0 when it is NULL).
 * Never allocates. */
int gbc_run_frame(GbcEmu* emu, uint32_t* argb_out, int16_t* pcm_out,
                  int pcm_capacity_frames);

/* Raw binjgb frame buffer: GBC_SCREEN_PIXELS words, byte order R,G,B,A -- which
 * is exactly what Bitmap.copyPixelsFromBuffer() wants for an ARGB_8888 bitmap,
 * so this is the zero-conversion path.  Valid until gbc_close(). */
const uint32_t* gbc_framebuffer(GbcEmu* emu);

/* Frames in which the game read the joypad, i.e. finished its own logic.
 * Frames where it did not are slowdown (lag) frames.  Cumulative. */
uint64_t gbc_game_frames(GbcEmu* emu);

/* Power-cycle the console (RAM cleared): a fresh game. */
void gbc_reset(GbcEmu* emu);

/* The game's modifications (games/<name>.c, see game.h): count, description, value. */
struct GameMod;
int gbc_mod_count(void);
const struct GameMod* gbc_mod(int i);
int gbc_mod_get(int i);
void gbc_mod_set(int i, int value);
int gbc_mod_find(const char* key);   /* -1 if the game has no such mod */

/* Controller 2 (a Bluetooth pad as player 2). Same bits as gbc_set_buttons. */
void gbc_set_buttons2(GbcEmu* emu, uint32_t mask);

/* Zapper (light gun) on port 2, for games whose plug-in sets nes_zapper=port2. Aim at
 * frame pixel x, y (0..255, 0..223; outside = pointing away); pressed = trigger. */
int gbc_uses_zapper(void);
void gbc_set_pointer(GbcEmu* emu, int x, int y, int pressed);

/* Rewind: while on, every frame steps back ~2 frames (up to ~20 s), silent. */
void gbc_set_rewind(GbcEmu* emu, int on);
/* FNV-1a of the 2 KiB work RAM (netplay: both sides compare it). */
uint32_t gbc_ram_hash(GbcEmu* emu);
/* A core option chosen by the player (palette, NTSC filter); the core applies it on its next frame. */
void gbc_set_option(const char* key, const char* value);
/* With the NTSC filter on: copies the whole filtered frame (width x 224) and returns its width; 0 = off. */
unsigned gbc_wide_frame(GbcEmu* emu, uint32_t* out);

/* Battery-backed cartridge RAM.  gbc_sram_size() returns 0 for a cartridge
 * without a battery, in which case there is nothing to persist. */
size_t gbc_sram_size(GbcEmu* emu);
int gbc_sram_read(GbcEmu* emu, uint8_t* dst, size_t size);  /* 1 = ok */
int gbc_sram_write(GbcEmu* emu, const uint8_t* src, size_t size);
/* True once since the last call if the game wrote to cartridge RAM. */
int gbc_sram_dirty(GbcEmu* emu);

/* Save states.  binjgb supports these natively (emulator_read/write_state);
 * the blob is a flat copy of its EmulatorState and is only valid for the same
 * ROM and the same build of the core. */
size_t gbc_state_size(void);
int gbc_state_save(GbcEmu* emu, uint8_t* dst, size_t size);
int gbc_state_load(GbcEmu* emu, const uint8_t* src, size_t size);

#ifdef __cplusplus
} /* extern "C" */
#endif

#endif /* GBCPLAYER_H_ */
