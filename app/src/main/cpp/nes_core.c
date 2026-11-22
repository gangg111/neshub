/*
 * Generic NES core: gbcplayer.h API on top of the FCEUmm libretro core (linked
 * statically into the same .so). Everything specific to one cartridge lives in
 * games/<name>.c behind game.h.  libretro cores are global, so
 * there is exactly one live GbcEmu at a time; gbc_open() closes any previous.
 *
 * Output: 256x224 (8 overscan lines cut top and bottom, like an NTSC TV),
 * ARGB_8888, stereo s16 at the core rate (48000 Hz).
 */
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "libretro.h"

void FCEUI_PowerNES(void);
#include "gbcplayer.h"
#include "game.h"

#ifdef GBC_HOST_TEST
#define LOG(...) fprintf(stderr, __VA_ARGS__)
#else
#include <android/log.h>
#define LOG(...) __android_log_print(ANDROID_LOG_INFO, "nescore", __VA_ARGS__)
#endif

struct GbcEmu {
  uint8_t* rom;
  size_t rom_size;
  uint32_t buttons;
  uint32_t turbo_tick;
  uint32_t buttons2;
  /* Zapper on port 2 (game option nes_zapper=port2): aim in frame pixels, -1 = none. */
  int zapper, aim_x, aim_y, trigger_held, trigger_frames;
  int rewinding;
  uint8_t* rw_buf;                     /* ring of RW_SLOTS states */
  size_t rw_size;
  int rw_head, rw_count;
  uint32_t rw_tick;
  uint64_t game_frames;
  uint32_t frame[GBC_SCREEN_PIXELS];
  /* Blargg's NTSC filter (fceumm_ntsc_filter) outputs 602 px a line: kept whole here for
   * the screen, while frame[] gets it narrowed to 256 (thumbnails, saves, the zapper). */
  uint32_t wide[GBC_WIDE_MAX * GBC_SCREEN_HEIGHT];
  unsigned wide_w;
  uint32_t* argb_out;
  int16_t* pcm_out;
  int pcm_cap, pcm_len;
  uint8_t* sram_shadow;
  size_t sram_shadow_size;
};

/* $4016 read counter, patched into fceumm's input.c. */
extern unsigned fceu_joy_reads;

#define RW_SLOTS 600   /* one state every 2 frames -> 20 s */

static GbcEmu* g_emu;
static int g_core_inited;

static const struct { const char* k; const char* v; } kVars[] = {
    {"fceumm_overscan_h_left", "0"},  {"fceumm_overscan_h_right", "0"},
    {"fceumm_overscan_v_top", "8"},   {"fceumm_overscan_v_bottom", "8"},
    {"fceumm_sndrate_hint", "48KHz"}, {"fceumm_region", "Auto"},
    {"fceumm_sndquality", "High"},
    /* Lift the 8-sprites-per-scanline hardware limit: no flicker / vanishing sprites
     * when the screen is busy (two players + enemies). */
    {"fceumm_nospritelimit", "enabled"},
};

static void core_log(enum retro_log_level level, const char* fmt, ...) {
  if (level < RETRO_LOG_WARN) return;
  char buf[512];
  va_list ap;
  va_start(ap, fmt);
  vsnprintf(buf, sizeof buf, fmt, ap);
  va_end(ap);
  LOG("fceumm: %s", buf);
}

static struct retro_game_info_ext g_info_ext;

/* Options the player sets in the menu (gbc_set_option), read by the core on its next frame. */
static struct { char k[48]; char v[48]; } g_user[8];
static int g_user_n;
static bool g_vars_dirty;

void gbc_set_option(const char* key, const char* value) {
  int i = 0;
  while (i < g_user_n && strcmp(g_user[i].k, key)) ++i;
  if (i == g_user_n) { if (g_user_n == 8) return; ++g_user_n; }
  snprintf(g_user[i].k, sizeof g_user[i].k, "%s", key);
  snprintf(g_user[i].v, sizeof g_user[i].v, "%s", value);
  g_vars_dirty = true;
}

static bool env_cb(unsigned cmd, void* data) {
  switch (cmd) {
    case RETRO_ENVIRONMENT_SET_PIXEL_FORMAT:
      return *(const enum retro_pixel_format*)data == RETRO_PIXEL_FORMAT_XRGB8888;
    case RETRO_ENVIRONMENT_GET_LOG_INTERFACE:
      ((struct retro_log_callback*)data)->log = core_log;
      return true;
    case RETRO_ENVIRONMENT_GET_CAN_DUPE:
      *(bool*)data = true;
      return true;
    case RETRO_ENVIRONMENT_GET_VARIABLE: {
      struct retro_variable* var = data;
      for (int i = 0; i < g_user_n; ++i)   /* the player's picture settings (palette, NTSC) */
        if (!strcmp(var->key, g_user[i].k)) { var->value = g_user[i].v; return true; }
#ifdef GBC_HOST_TEST
      if (!strcmp(var->key, "fceumm_overclocking") && getenv("NES_OC")) {
        var->value = getenv("NES_OC");
        return true;
      }
      if (getenv("NES_OPT")) {   /* "key=value;key=value" -- any core option, for movie sync tests */
        static char buf[512]; snprintf(buf, sizeof buf, "%s", getenv("NES_OPT"));
        size_t kl = strlen(var->key);
        for (char* t = buf; t && *t; ) {
          char* end = strchr(t, ';');
          if (!strncmp(t, var->key, kl) && t[kl] == '=') {
            static char val[128]; size_t vl = end ? (size_t)(end - t - kl - 1) : strlen(t + kl + 1);
            snprintf(val, sizeof val, "%.*s", (int)vl, t + kl + 1); var->value = val; return true;
          }
          t = end ? end + 1 : NULL;
        }
      }
      if (!strcmp(var->key, "fceumm_nospritelimit") && getenv("NES_SPRLIM")) {
        var->value = getenv("NES_SPRLIM");
        return true;
      }
#endif
      for (const GameOption* o = game_options; o->key; ++o)   /* the game's own first */
        if (!strcmp(var->key, o->key)) { var->value = o->value; return true; }
      for (size_t i = 0; i < sizeof kVars / sizeof kVars[0]; ++i)
        if (!strcmp(var->key, kVars[i].k)) { var->value = kVars[i].v; return true; }
      var->value = NULL;
      return false;
    }
    case RETRO_ENVIRONMENT_GET_VARIABLE_UPDATE:
      *(bool*)data = g_vars_dirty; g_vars_dirty = 0;
      return true;
    case RETRO_ENVIRONMENT_GET_GAME_INFO_EXT:
      if (!g_emu) return false;
      *(const struct retro_game_info_ext**)data = &g_info_ext;
      return true;
    default:
      return false;
  }
}

static void video_cb(const void* data, unsigned w, unsigned h, size_t pitch) {
  if (!g_emu || !data) return; /* NULL = dupe: keep the previous frame */
  if (w > GBC_SCREEN_WIDTH) {   /* NTSC filter: keep it whole, and a 256 px copy */
    if (w > GBC_WIDE_MAX) w = GBC_WIDE_MAX;
    if (h > GBC_SCREEN_HEIGHT) h = GBC_SCREEN_HEIGHT;
    g_emu->wide_w = w;
    for (unsigned y = 0; y < GBC_SCREEN_HEIGHT; ++y) {
      uint32_t* wd = g_emu->wide + y * w;
      uint32_t* dst = g_emu->frame + y * GBC_SCREEN_WIDTH;
      if (y >= h) { for (unsigned x = 0; x < w; ++x) wd[x] = 0xFF000000u; for (unsigned x = 0; x < GBC_SCREEN_WIDTH; ++x) dst[x] = 0xFF000000u; continue; }
      const uint32_t* src = (const uint32_t*)((const uint8_t*)data + y * pitch);
      for (unsigned x = 0; x < w; ++x) wd[x] = src[x] | 0xFF000000u;
      for (unsigned x = 0; x < GBC_SCREEN_WIDTH; ++x) dst[x] = src[(x * w + w / 2) / GBC_SCREEN_WIDTH] | 0xFF000000u;
    }
    return;
  }
  g_emu->wide_w = 0;
  if (h > GBC_SCREEN_HEIGHT) h = GBC_SCREEN_HEIGHT;
  for (unsigned y = 0; y < h; ++y) {
    const uint32_t* src = (const uint32_t*)((const uint8_t*)data + y * pitch);
    uint32_t* dst = g_emu->frame + y * GBC_SCREEN_WIDTH;
    for (unsigned x = 0; x < w; ++x) dst[x] = src[x] | 0xFF000000u;
  }
  /* A game may crop more overscan (e.g. fceumm_overscan_v_bottom): the rows it leaves
   * out are black, not whatever an earlier frame put there. */
  for (unsigned y = h; y < GBC_SCREEN_HEIGHT; ++y)
    for (unsigned x = 0; x < GBC_SCREEN_WIDTH; ++x) g_emu->frame[y * GBC_SCREEN_WIDTH + x] = 0xFF000000u;
}

static size_t audio_batch_cb(const int16_t* data, size_t frames) {
  GbcEmu* e = g_emu;
  if (!e || !e->pcm_out) return frames;
  size_t room = (size_t)(e->pcm_cap - e->pcm_len);
  size_t n = frames < room ? frames : room;
  memcpy(e->pcm_out + e->pcm_len * 2, data, n * 4);
  e->pcm_len += (int)n;
  return frames;
}

static void audio_cb(int16_t l, int16_t r) {
  int16_t s[2] = {l, r};
  audio_batch_cb(s, 1);
}

static void input_poll_cb(void) {}

/* FCEUmm's crosshair light gun maps SCREEN_X/Y (-0x7FFF..0x7FFF) onto NES pixels with
 * the overscan crop folded in (libretro.c get_mouse_input); invert that here. */
static int16_t zapper_axis(int pixel, int range, int crop, int mul, int bias) {
  long a = 0x7FFF + (long)crop * mul + bias;
  long v = (2L * pixel + 1) * a / range - a;
  return (int16_t)(v < -0x7FFF ? -0x7FFF : v > 0x7FFF ? 0x7FFF : v);
}

static int16_t input_state_cb(unsigned port, unsigned device, unsigned index,
                              unsigned id) {
  (void)index;
  if (g_emu && g_emu->zapper && port == 1 && device == RETRO_DEVICE_LIGHTGUN) {
    int off = g_emu->aim_x < 0;
    switch (id) {
      case RETRO_DEVICE_ID_LIGHTGUN_IS_OFFSCREEN: return off;
      case RETRO_DEVICE_ID_LIGHTGUN_TRIGGER: return g_emu->trigger_held || g_emu->trigger_frames > 0;
      /* frame row 0 is scanline 8 (fceumm_overscan_v_top 8) */
      case RETRO_DEVICE_ID_LIGHTGUN_SCREEN_X: return off ? 0 : zapper_axis(g_emu->aim_x, 256, 0, 0x120, -1);
      case RETRO_DEVICE_ID_LIGHTGUN_SCREEN_Y: return off ? 0 : zapper_axis(g_emu->aim_y + 8, 240, 8, 0x133, 1);
      default: return 0;
    }
  }
  if (!g_emu || port > 1 || device != RETRO_DEVICE_JOYPAD) return 0;
  uint32_t b = port == 0 ? g_emu->buttons : g_emu->buttons2;
  switch (id) {
    case RETRO_DEVICE_ID_JOYPAD_A:      return !!(b & GBC_BUTTON_A);
    case RETRO_DEVICE_ID_JOYPAD_B:
#ifdef GBC_HOST_TEST
      { extern int nestest_b_reads; nestest_b_reads += !!(b & GBC_BUTTON_B); }
#endif
      return !!(b & GBC_BUTTON_B);
    case RETRO_DEVICE_ID_JOYPAD_SELECT: return !!(b & GBC_BUTTON_SELECT);
    case RETRO_DEVICE_ID_JOYPAD_START:  return !!(b & GBC_BUTTON_START);
    case RETRO_DEVICE_ID_JOYPAD_UP:     return !!(b & GBC_BUTTON_UP);
    case RETRO_DEVICE_ID_JOYPAD_DOWN:   return !!(b & GBC_BUTTON_DOWN);
    case RETRO_DEVICE_ID_JOYPAD_LEFT:   return !!(b & GBC_BUTTON_LEFT);
    case RETRO_DEVICE_ID_JOYPAD_RIGHT:  return !!(b & GBC_BUTTON_RIGHT);
    default: return 0;
  }
}

GbcEmu* gbc_open(const uint8_t* rom, size_t rom_size, int sample_rate,
                 char* err, size_t err_size) {
  (void)sample_rate; /* the core picks 48000; Kotlin reads it back */
  if (g_emu) gbc_close(g_emu);
  if (rom_size < 16 || memcmp(rom, "NES\x1a", 4)) {
    if (err) snprintf(err, err_size, "not an iNES image");
    return NULL;
  }
  GbcEmu* e = calloc(1, sizeof *e);
  if (!e || !(e->rom = malloc(rom_size))) {
    free(e);
    if (err) snprintf(err, err_size, "out of memory");
    return NULL;
  }
  memcpy(e->rom, rom, rom_size);
  e->rom_size = rom_size;
  g_emu = e;

  memset(&g_info_ext, 0, sizeof g_info_ext);
  g_info_ext.full_path = "/game.nes";
  g_info_ext.dir = "/";
  g_info_ext.name = "game";
  g_info_ext.ext = "nes";
  g_info_ext.data = e->rom;
  g_info_ext.size = e->rom_size;
  g_info_ext.persistent_data = true;

  retro_set_environment(env_cb);
  retro_set_video_refresh(video_cb);
  retro_set_audio_sample(audio_cb);
  retro_set_audio_sample_batch(audio_batch_cb);
  retro_set_input_poll(input_poll_cb);
  retro_set_input_state(input_state_cb);
  if (!g_core_inited) { retro_init(); g_core_inited = 1; }

  /* Frontend-only PPU quirks a game asks for in game_options (not FCEUmm options). */
  {
    extern int ppu_bg_palette_hack;
    ppu_bg_palette_hack = 0;
    for (const GameOption* o = game_options; o->key; ++o)
      if (!strcmp(o->key, "nes_bg_palette_hack")) ppu_bg_palette_hack = !strcmp(o->value, "enabled");
    /* A dump whose iNES header names the wrong mirroring (the board's is fixed). */
    for (const GameOption* o = game_options; o->key; ++o)
      if (!strcmp(o->key, "nes_mirroring")) e->rom[6] = (uint8_t)((e->rom[6] & ~1) | !strcmp(o->value, "vertical"));
  }
  struct retro_game_info info = {"/game.nes", e->rom, e->rom_size, NULL};
  if (!retro_load_game(&info)) {
    if (err) snprintf(err, err_size, "fceumm rejected the rom");
    g_emu = NULL;
    free(e->rom);
    free(e);
    return NULL;
  }
  retro_set_controller_port_device(0, RETRO_DEVICE_JOYPAD);
  e->zapper = gbc_uses_zapper();
  e->aim_x = e->aim_y = -1;
  /* RETRO_DEVICE_ZAPPER in FCEUmm's libretro.c: a mouse subclass */
  retro_set_controller_port_device(1, e->zapper ? RETRO_DEVICE_SUBCLASS(RETRO_DEVICE_MOUSE, 0) : RETRO_DEVICE_JOYPAD);
  e->rw_size = retro_serialize_size();
  e->rw_buf = malloc(e->rw_size * RW_SLOTS);   /* ~9.5 MB; rewind just stays off if NULL */
  game_on_power(retro_get_memory_data(RETRO_MEMORY_SYSTEM_RAM));
  struct retro_system_av_info av;
  retro_get_system_av_info(&av);
  LOG("loaded: %ux%u %.4f fps %.0f Hz", av.geometry.base_width,
      av.geometry.base_height, av.timing.fps, av.timing.sample_rate);
  return e;
}

void gbc_close(GbcEmu* e) {
  if (!e) return;
  if (e == g_emu) {
    retro_unload_game();
    g_emu = NULL;
  }
  free(e->sram_shadow);
  free(e->rw_buf);
  free(e->rom);
  free(e);
}

void gbc_set_buttons(GbcEmu* e, uint32_t mask) { e->buttons = mask; }

static uint8_t* ram(void) { return retro_get_memory_data(RETRO_MEMORY_SYSTEM_RAM); }

static void sync_after_load(GbcEmu* e) { (void)e; game_after_load(ram()); }

/* One rewind step: newest buffered state, rendered silently, then dropped. */
static int rewind_step(GbcEmu* e, uint32_t* argb_out) {
  if (e->rw_count > 0) {
    e->rw_head = (e->rw_head + RW_SLOTS - 1) % RW_SLOTS;
    e->rw_count--;
    retro_unserialize(e->rw_buf + (size_t)e->rw_head * e->rw_size, e->rw_size);
    sync_after_load(e);
    uint32_t b1 = e->buttons, b2 = e->buttons2;
    e->buttons = e->buttons2 = 0;
    e->pcm_out = NULL; e->pcm_cap = 0; e->pcm_len = 0;
    retro_run();
    e->buttons = b1; e->buttons2 = b2;
    /* the frame just run must not be kept: step back onto the loaded state */
    retro_unserialize(e->rw_buf + (size_t)e->rw_head * e->rw_size, e->rw_size);
  }
  if (argb_out) memcpy(argb_out, e->frame, sizeof e->frame);
  return 0;
}

int gbc_run_frame(GbcEmu* e, uint32_t* argb_out, int16_t* pcm_out,
                  int pcm_capacity_frames) {
  e->pcm_out = pcm_out;
  e->pcm_cap = pcm_out ? pcm_capacity_frames : 0;
  e->pcm_len = 0;
  if (e->rewinding) return rewind_step(e, argb_out);
  uint32_t held = e->buttons;
  uint32_t held2 = e->buttons2;
  uint8_t* r = ram();
  game_pre_frame(r, &e->buttons, &e->buttons2);
  /* Turbo: bits 8/9 pulse A/B, 2 frames held, 2 released (15 Hz). A hook that hid a
   * button cleared its turbo bit too, so nothing is pulsed for it. */
  if ((e->turbo_tick++ & 2) == 0) {
    if (e->buttons & GBC_BUTTON_TURBO_A) e->buttons |= GBC_BUTTON_A;
    if (e->buttons & GBC_BUTTON_TURBO_B) e->buttons |= GBC_BUTTON_B;
    if (e->buttons2 & GBC_BUTTON_TURBO_A) e->buttons2 |= GBC_BUTTON_A;
    if (e->buttons2 & GBC_BUTTON_TURBO_B) e->buttons2 |= GBC_BUTTON_B;
  }
  unsigned reads = fceu_joy_reads;
  retro_run();
  game_post_frame(r);
  if (e->trigger_frames > 0) e->trigger_frames--;
  if (fceu_joy_reads != reads) e->game_frames++;
  e->buttons = held;
  e->buttons2 = held2;
  if (e->rw_buf && (++e->rw_tick & 1) == 0) {
    retro_serialize(e->rw_buf + (size_t)e->rw_head * e->rw_size, e->rw_size);
    e->rw_head = (e->rw_head + 1) % RW_SLOTS;
    if (e->rw_count < RW_SLOTS) e->rw_count++;
  }
  if (argb_out) memcpy(argb_out, e->frame, sizeof e->frame);
  e->pcm_out = NULL;
  return e->pcm_len;
}

const uint32_t* gbc_framebuffer(GbcEmu* e) { return e->frame; }

uint64_t gbc_game_frames(GbcEmu* e) { return e->game_frames; }

size_t gbc_sram_size(GbcEmu* e) {
  (void)e;
  return retro_get_memory_data(RETRO_MEMORY_SAVE_RAM)
             ? retro_get_memory_size(RETRO_MEMORY_SAVE_RAM) : 0;
}

int gbc_sram_read(GbcEmu* e, uint8_t* dst, size_t size) {
  if (size != gbc_sram_size(e)) return 0;
  memcpy(dst, retro_get_memory_data(RETRO_MEMORY_SAVE_RAM), size);
  return 1;
}

int gbc_sram_write(GbcEmu* e, const uint8_t* src, size_t size) {
  if (size == 0 || size != gbc_sram_size(e)) return 0;
  memcpy(retro_get_memory_data(RETRO_MEMORY_SAVE_RAM), src, size);
  return 1;
}

int gbc_sram_dirty(GbcEmu* e) {
  size_t n = gbc_sram_size(e);
  if (!n) return 0;
  const uint8_t* cur = retro_get_memory_data(RETRO_MEMORY_SAVE_RAM);
  if (e->sram_shadow_size != n) {
    free(e->sram_shadow);
    e->sram_shadow = malloc(n);
    if (!e->sram_shadow) { e->sram_shadow_size = 0; return 0; }
    e->sram_shadow_size = n;
    memcpy(e->sram_shadow, cur, n);
    return 0;
  }
  if (!memcmp(e->sram_shadow, cur, n)) return 0;
  memcpy(e->sram_shadow, cur, n);
  return 1;
}

size_t gbc_state_size(void) { return retro_serialize_size(); }

int gbc_state_save(GbcEmu* e, uint8_t* dst, size_t size) {
  (void)e;
  return retro_serialize(dst, size) ? 1 : 0;
}

int gbc_state_load(GbcEmu* e, const uint8_t* src, size_t size) {
  if (!retro_unserialize(src, size)) return 0;
  sync_after_load(e);
  e->rw_count = 0;   /* the rewind history belongs to the previous timeline */
  return 1;
}

void core_power_cycle(void) {
  /* Full power cycle (RAM cleared), not the soft reset: nothing of the previous game
   * may survive into the new one. */
  FCEUI_PowerNES();
  if (g_emu) g_emu->rw_count = 0;
  game_on_power(ram());
}

void core_cheats(const char* codes) {
  retro_cheat_reset();
  if (codes && *codes) retro_cheat_set(0, true, codes);
}

void (gbc_reset)(GbcEmu* e) { (void)e; core_power_cycle(); }

int gbc_mod_count(void) { return game_mod_count; }
const GameMod* gbc_mod(int i) { return i >= 0 && i < game_mod_count ? &game_mods[i] : NULL; }
int gbc_mod_get(int i) { const GameMod* m = gbc_mod(i); return m && m->get ? m->get() : 0; }
void gbc_mod_set(int i, int v) { const GameMod* m = gbc_mod(i); if (m && m->set) m->set(v); }
int gbc_mod_find(const char* key) {
  for (int i = 0; i < game_mod_count; ++i) if (!strcmp(game_mods[i].key, key)) return i;
  return -1;
}

void gbc_set_buttons2(GbcEmu* e, uint32_t mask) { e->buttons2 = mask; }

int gbc_uses_zapper(void) {
  for (const GameOption* o = game_options; o->key; ++o)
    if (!strcmp(o->key, "nes_zapper")) return !strcmp(o->value, "port2");
  return 0;
}

void gbc_set_pointer(GbcEmu* e, int x, int y, int pressed) {
  if (x < 0 || y < 0 || x >= GBC_SCREEN_WIDTH || y >= GBC_SCREEN_HEIGHT) {
    e->aim_x = e->aim_y = -1;
    e->trigger_held = 0;
    return;
  }
  e->aim_x = x; e->aim_y = y;
  /* a tap may start and end within one frame: the trigger stays pulled 4 frames */
  if (pressed && !e->trigger_held) e->trigger_frames = 4;
  e->trigger_held = pressed;
}

void gbc_set_rewind(GbcEmu* e, int on) { e->rewinding = on && e->rw_buf; }

/* Netplay: both phones compare this over the 2 KiB work RAM every few frames; a
 * difference means they drifted apart and the guest asks for the host's state. */
uint32_t gbc_ram_hash(GbcEmu* e) {
  (void)e;
  const uint8_t* r = ram(); uint32_t h = 2166136261u;
  if (r) for (int i = 0; i < 0x800; ++i) h = (h ^ r[i]) * 16777619u;
  return h;
}

unsigned gbc_wide_frame(GbcEmu* e, uint32_t* out) {
  if (!e->wide_w) return 0;
  if (out) memcpy(out, e->wide, (size_t)e->wide_w * GBC_SCREEN_HEIGHT * sizeof(uint32_t));
  return e->wide_w;
}
