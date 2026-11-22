/* Replay an FCEUX .fm2 movie (port 0) on a ROM and dump a frame every N frames.
 *   fm2play rom movie.fm2 outdir every [from] [to]   -- prints frame + a RAM probe line */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "gbcplayer.h"
#include "game.h"
#include "libretro.h"
#include "fceu-types.h"
#include "x6502struct.h"
extern X6502 X;
extern unsigned fceu_joy_reads;
int nestest_b_reads;
#include "fceu.h"
static readfunc lr_orig; static unsigned lr_lo, lr_hi; static long lr_hits;
static writefunc lw_orig; static unsigned lw_ok, lw_late; static long lw_n, lw_l;
static int lw_pcs[65536]; static void FP_FASTAPASS(2) lw_hook(uint32_t a, uint8_t v) { lw_pcs[X.PC]++; if (X.PC == lw_ok) ++lw_n; else if (X.PC == lw_late) ++lw_l; lw_orig(a, v); }
extern int scanline; int dbg_ppu_dot(void) __attribute__((weak));
static writefunc pw_orig[8]; static long pw_frame;
static void FP_FASTAPASS(2) pw_hook(uint32_t a, uint8_t v) {
  if (pw_frame >= 0) printf("  w%04X=%02X sl %d dot %d\n", a, v, scanline, dbg_ppu_dot ? dbg_ppu_dot() : -1);
  pw_orig[a & 7](a, v);
}
static long lr_all; static unsigned lr_pc[8]; static int lr_h[65536]; static DECLFR(lr_hook) { lr_h[X.PC]++; if (lr_all < 8) lr_pc[lr_all] = X.PC; ++lr_all; if (X.PC >= lr_lo && X.PC <= lr_hi) ++lr_hits; return lr_orig(A); }
static void dump(const char* dir, long fr, const uint32_t* px) {
  char p[512]; snprintf(p, sizeof p, "%s/m%06ld.ppm", dir, fr);
  FILE* o = fopen(p, "wb"); fprintf(o, "P6 256 224 255\n");
  for (int i = 0; i < GBC_SCREEN_PIXELS; ++i) { fputc(px[i] >> 16 & 255, o); fputc(px[i] >> 8 & 255, o); fputc(px[i] & 255, o); }
  fclose(o);
}
int main(int argc, char** argv) {
  FILE* f = fopen(argv[1], "rb"); fseek(f, 0, SEEK_END); long n = ftell(f); rewind(f);
  uint8_t* rom = malloc(n); fread(rom, 1, n, f); fclose(f);
  char err[128]; GbcEmu* e = gbc_open(rom, n, 48000, err, sizeof err);
  if (!e) { puts(err); return 1; }
  uint8_t* r = retro_get_memory_data(RETRO_MEMORY_SYSTEM_RAM);
  if (getenv("LOADST")) {   /* start from a saved state instead of power-on (use an empty movie) */
    FILE* q = fopen(getenv("LOADST"), "rb"); size_t sz = gbc_state_size(); uint8_t* b = malloc(sz);
    fread(b, 1, sz, q); fclose(q); gbc_state_load(e, b, sz); free(b);
  }

  if (getenv("CHEATS0")) core_cheats(getenv("CHEATS0"));
  FILE* m = fopen(argv[2], "r"); char line[256];
  long held = 0; int lastLag = 0, lagRun = 0, holdMin = getenv("HOLD") ? atoi(getenv("HOLD")) : 0; long every = atol(argv[4]), from = argc > 5 ? atol(argv[5]) : 0, to = argc > 6 ? atol(argv[6]) : 1L << 30, fr = 0;
  static const uint32_t map[8] = {GBC_BUTTON_RIGHT, GBC_BUTTON_LEFT, GBC_BUTTON_DOWN, GBC_BUTTON_UP,
                                  GBC_BUTTON_START, GBC_BUTTON_SELECT, GBC_BUTTON_B, GBC_BUTTON_A};
  static uint32_t px[GBC_SCREEN_PIXELS]; static int16_t pcm[8192];
  for (int k = 0, pre = getenv("PRE") ? atoi(getenv("PRE")) : 0; k < pre; ++k) { gbc_set_buttons(e, 0); gbc_run_frame(e, px, pcm, 4096); }
  int skip = getenv("SKIP") ? atoi(getenv("SKIP")) : 0;
  while (fgets(line, sizeof line, m)) {
    if (line[0] == '|' && skip > 0) { --skip; continue; }
    /* INS="movieframe:n,..." -- n extra empty frames before that movie frame (n<0: drop frames) */
    if (line[0] == '|' && getenv("INS")) {
      static long consumed; ++consumed;
      for (const char* q = getenv("INS"); q && *q; ) {
        long at = atol(q); const char* c = strchr(q, ':'); long k = c ? atol(c + 1) : 0;
        if (at == consumed) {
          if (k < 0) { static long drop; drop = -k; while (drop-- > 1 && fgets(line, sizeof line, m)) ++consumed; goto next; }
          for (long j = 0; j < k; ++j) { gbc_set_buttons(e, 0); gbc_run_frame(e, px, pcm, 4096); ++fr; }
        }
        q = strchr(q, ','); if (q) ++q;
      }
    }
    if (line[0] != '|') continue;
    char* p = strchr(line + 1, '|'); if (!p) continue; ++p;
    uint32_t b = 0; for (int i = 0; i < 8; ++i) if (p[i] != '.' && p[i] != ' ') b |= map[i];
    /* HOLD=1: while the game is in lag (did not read the pad last frame), a frame with
     * buttons waits -- a TAS presses on the first frame the game reads again. */
    if (getenv("HOLD") && b) {
      int guard = 0;
      while (lagRun >= holdMin && guard++ < 120) {   /* the game did not read the pad last frame */
        unsigned r0 = fceu_joy_reads;
        gbc_set_buttons(e, 0); gbc_run_frame(e, px, pcm, 4096); ++fr; ++held;
        lastLag = fceu_joy_reads == r0; lagRun = lastLag ? lagRun + 1 : 0;
      }
    }
    { unsigned r0 = fceu_joy_reads; gbc_set_buttons(e, b); gbc_run_frame(e, px, pcm, 4096); ++fr; lastLag = fceu_joy_reads == r0; lagRun = lastLag ? lagRun + 1 : 0; }
    if (fr >= from && fr <= to && fr % every == 0) {
      dump(argv[3], fr, px);
      printf("fr %ld lives %d armour %02X score %02X%02X%02X\n", fr, r[0x1D1], r[0x306], r[0x1D4], r[0x1D3], r[0x1D2]);
    }
    if (getenv("POKE")) {   /* POKE="frame:addr=val:addr=val" (hex addr/val) applied after that frame */
      const char* q = getenv("POKE");
      if (atol(q) == fr) for (const char* c = strchr(q, ':'); c; c = strchr(c + 1, ':')) {
        unsigned a, v; if (sscanf(c + 1, "%x=%x", &a, &v) == 2) r[a & 0x7FF] = (uint8_t)v;
      }
    }
    if (getenv("PCHIST") && fr >= from && fr <= to) { static int h[65536]; static int done; h[X.PC]++;
      if (fr == to && !done) { done = 1; for (int i = 0; i < 65536; ++i) if (h[i] > 2) printf("PC %04X %d\n", i, h[i]); } }
    if (getenv("TRACE") && fr >= from && fr <= to) { static unsigned last; printf("  %ld PC %04X reads %u in %s\n", fr, X.PC, fceu_joy_reads - last, p); last = fceu_joy_reads; }
    if (fr > to) break;
    continue;
  next: ;
  }
  /* BOT=frames: keep playing after the movie -- north, side steps, both guns. CHEATS=codes */
  if (getenv("CHEATS")) core_cheats(getenv("CHEATS"));
  for (long k = 0, nb = getenv("BOT") ? atol(getenv("BOT")) : 0; k < nb; ++k) {
    static const uint32_t d[] = {GBC_BUTTON_UP, GBC_BUTTON_UP | GBC_BUTTON_LEFT, GBC_BUTTON_UP, GBC_BUTTON_UP | GBC_BUTTON_RIGHT,
                                 GBC_BUTTON_UP, GBC_BUTTON_LEFT, GBC_BUTTON_UP, GBC_BUTTON_RIGHT, GBC_BUTTON_DOWN | GBC_BUTTON_RIGHT, GBC_BUTTON_UP};
    uint32_t b = d[((k / 90) * 7) % 10] | (((k / 6) & 1) ? GBC_BUTTON_A : GBC_BUTTON_B);
    if (k % 4000 == 3000 || k % 4000 == 3060) b = GBC_BUTTON_START;   /* past "call" messages */
    if (getenv("BOTPAT")) {   /* BOTPAT="mask:n,mask:n,..." hex masks, cycled */
      static uint32_t pm[64]; static long pn[64], tot; static int cnt;
      if (!cnt) { const char* q = getenv("BOTPAT"); while (q && *q && cnt < 64) { char* end; pm[cnt] = strtoul(q, &end, 16); pn[cnt] = strtol(end + 1, &end, 10); tot += pn[cnt++]; q = *end == ',' ? end + 1 : NULL; } }
      long t = k % tot; int i = 0; while (t >= pn[i]) t -= pn[i++]; b = pm[i];
    }
    if (k == 1 && getenv("PPUWLOG")) { for (int i = 0; i < 8; ++i) { pw_orig[i] = BWrite[0x2000 + i]; BWrite[0x2000 + i] = pw_hook; } }
    if (getenv("PPUWLOG")) { pw_frame = k; printf(" frame %ld\n", k); }
    if (k == 1) {
  if (getenv("LOOPREAD")) {   /* LOOPREAD=addr:lo-hi -- count reads of addr from PC lo..hi (main loop starts) */
    unsigned a; sscanf(getenv("LOOPREAD"), "%x:%x-%x", &a, &lr_lo, &lr_hi); lr_orig = ARead[a]; ARead[a] = lr_hook;
  }    if (getenv("LOOPWRITE")) {   /* LOOPWRITE=addr:okPC:latePC -- frames done in time vs overrun */
    unsigned a; sscanf(getenv("LOOPWRITE"), "%x:%x:%x", &a, &lw_ok, &lw_late); lw_orig = BWrite[a]; BWrite[a] = lw_hook;
  }
    }
    if (getenv("LOOPWRITE") && k == atol(getenv("BOT")) - 1) { printf("in time %ld, overrun %ld (%.2f%%)\n", lw_n, lw_l, 100.0 * lw_l / (lw_n + lw_l)); for (int i = 0; i < 65536; ++i) if (lw_pcs[i]) printf("  w PC %04X %d\n", i, lw_pcs[i]); }
    if (getenv("LOOPREAD") && k > 0) {
      static long last, lag, run, maxrun, n, hist[8]; long d = lr_hits - last; last = lr_hits; ++n;
      if (d == 0) { ++lag; if (++run > maxrun) maxrun = run; } else run = 0; hist[d > 7 ? 7 : d]++;
      if (getenv("LAGDUMP") && d == 0 && lag % 50 == 1) dump(argv[3], fr, px);
      if (k == atol(getenv("BOT")) - 1) for (int i = 0; i < 65536; ++i) if (lr_h[i]) printf("  r PC %04X %d\n", i, lr_h[i]);
      if (k == atol(getenv("BOT")) - 1) printf("reads %ld pcs %04X %04X %04X %04X; loops/frame hist 0:%ld 1:%ld 2:%ld; lag %ld of %ld (%.2f%%), longest %ld\n", lr_all, lr_pc[0], lr_pc[1], lr_pc[2], lr_pc[3], hist[0], hist[1], hist[2], lag, n, 100.0 * lag / n, maxrun);
    }
    if (getenv("AIM")) {   /* AIM=x,y,pressed for this bot frame: from a file of lines "k x y p" */
      static FILE* af; static long ak = -1; static int ax = -1, ay = -1, ap = 0;
      if (!af) af = fopen(getenv("AIM"), "r");
      while (af && ak < k) { if (fscanf(af, "%ld %d %d %d", &ak, &ax, &ay, &ap) != 4) { fclose(af); af = NULL; ak = 1L << 40; } }
      if (ak == k) gbc_set_pointer(e, ax, ay, ap);
    }
    if (getenv("BOTTRACE")) printf("  bt %ld %04X\n", k, X.PC);
    if (getenv("PCH2") && k > 0) { static int h[65536]; h[X.PC]++; if (k == atol(getenv("BOT")) - 1) for (int i = 0; i < 65536; ++i) if (h[i] > 0) printf("PC %04X %d\n", i, h[i]); }
    if (getenv("WAITPC")) {   /* WAITPC=lo-hi: count frames ending outside (lag) */
      static unsigned lo, hi; static long lag, run, maxrun, n; if (!hi) sscanf(getenv("WAITPC"), "%x-%x", &lo, &hi);
      if (k > 0) { ++n; if (X.PC < lo || X.PC > hi) { ++lag; if (++run > maxrun) maxrun = run; } else run = 0; }
      if (k == atol(getenv("BOT")) - 1) printf("lag %ld of %ld (%.2f%%), longest %ld\n", lag, n, 100.0 * lag / n, maxrun);
    }
    if (getenv("BOTPAD")) {   /* BOTPAD="mask[:frames,mask:frames...]" -- hex masks, last one repeats */
      const char* q = getenv("BOTPAD"); long acc = 0; b = 0;
      while (q && *q) { char* end; b = (uint32_t)strtoul(q, &end, 16); long n = (*end == ':') ? strtol(end + 1, &end, 10) : 1L << 40;
        if (k < acc + n) break; acc += n; q = (*end == ',') ? end + 1 : NULL; }
    }
    if (getenv("RAMLOG") && k % atol(getenv("RAMLOG")) == 0) {
      static FILE* rl; if (!rl) rl = fopen("ramlog.bin", "wb"); fwrite(r, 1, 2048, rl);
    }
    if (getenv("FREEZE")) { unsigned a, v, a2, v2; int nf = sscanf(getenv("FREEZE"), "%x=%x,%x=%x", &a, &v, &a2, &v2);
      r[a & 0x7FF] = (uint8_t)v; if (nf == 4) r[a2 & 0x7FF] = (uint8_t)v2; }
    if (getenv("BPOKE2")) { long at; unsigned a, v; if (sscanf(getenv("BPOKE2"), "%ld:%x=%x", &at, &a, &v) == 3 && k == at) r[a & 0x7FF] = (uint8_t)v; }
    if (getenv("BPOKE")) { long at; unsigned a, v; if (sscanf(getenv("BPOKE"), "%ld:%x=%x", &at, &a, &v) == 3 && k == at) r[a & 0x7FF] = (uint8_t)v; }
    if (getenv("FIREAFTER") && k >= atol(getenv("FIREAFTER"))) b |= ((k / 4) & 1) ? GBC_BUTTON_A : GBC_BUTTON_B;
    gbc_set_buttons(e, b); gbc_run_frame(e, px, pcm, 4096); ++fr;
    if (getenv("SAVEAT") && fr == atol(getenv("SAVEAT"))) {
      size_t sz = gbc_state_size(); uint8_t* sb = malloc(sz); gbc_state_save(e, sb, sz);
      FILE* q = fopen(strchr(getenv("SAVEAT"), ':') + 1, "wb"); fwrite(sb, 1, sz, q); fclose(q); free(sb);
    }
    if (fr % every == 0) { dump(argv[3], fr, px); printf("fr %ld lives %d armour %02X score %02X%02X%02X\n", fr, r[0x1D1], r[0x306], r[0x1D4], r[0x1D3], r[0x1D2]); }
  }
  if (getenv("LASTPPM")) dump(getenv("LASTPPM"), 0, px);
  if (getenv("RAMDUMP")) { FILE* o = fopen(getenv("RAMDUMP"), "wb"); fwrite(r, 1, 2048, o); fclose(o); }
  printf("held %ld; end at frame %ld, lives %d score %02X%02X%02X\n", held, fr, r[0x1D1], r[0x1D4], r[0x1D3], r[0x1D2]);
  return 0;
}
