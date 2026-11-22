/* Wide-screen feasibility probe: after each frame, render BOTH nametables (512x240) from
 * PPU memory, locate the visible 240-px window by matching edges against the real frame,
 * and track whether the off-screen columns (ahead / behind) already hold the tiles they
 * will show when they scroll into view. */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "gbcplayer.h"
#include "fceu-types.h"
extern uint8_t NTARAM[0x800], *vnapage[4], PPU[4], PALRAM[0x20], *VPage[8];
extern void (*GameHBIRQHook)(void);
extern int scanline;
static void (*orig_hook)(void);
static uint8_t* capV[8]; static uint8_t capP0; static int captured;
/* Playfield CHR banks: the game swaps banks for its status bar mid-frame, so the
 * end-of-frame banks are the HUD's. Snapshot them on scanline 60. */
static void my_hook(void) { if (scanline == 60) { memcpy(capV, VPage, sizeof capV); capP0 = PPU[0]; captured = 1; } if (orig_hook) orig_hook(); }
int nestest_b_reads;
static uint64_t hash[1400][512]; static long fworld[1400]; static int off[1400]; static int nframes, prev_o = -1; static long world; static double match_sum; static int nmatch;
static int prev_o_dummy(int t) { return 0; }
#define NW 512
#define NH 240
static uint8_t nt[NW * NH];
static void render_nt(void) {
  int bgHi = (capP0 & 0x10) ? 0x1000 : 0;
  for (int q = 0; q < 2; ++q) {
    uint8_t* pg = vnapage[q];
    for (int ty = 0; ty < 30; ++ty) for (int tx = 0; tx < 32; ++tx) {
      int tile = pg[ty * 32 + tx];
      int at = pg[0x3C0 + (ty / 4) * 8 + tx / 4];
      int pal = (at >> (((ty & 2) << 1) | (tx & 2))) & 3;
      int a = bgHi + tile * 16;
      uint8_t* chr = &capV[a >> 10][a];
      for (int r = 0; r < 8; ++r) {
        int lo = chr[r], hi = chr[r + 8];
        for (int c = 0; c < 8; ++c) {
          int v = ((lo >> (7 - c)) & 1) | (((hi >> (7 - c)) & 1) << 1);
          nt[(ty * 8 + r) * NW + q * 256 + tx * 8 + c] = v ? (pal << 2 | v) : 0;
        }
      }
    }
  }
}
int main(int argc, char** argv) {
  FILE* f = fopen(argv[1], "rb"); fseek(f, 0, SEEK_END); long n = ftell(f); rewind(f);
  uint8_t* rom = malloc(n); fread(rom, 1, n, f); fclose(f);
  char err[128]; GbcEmu* e = gbc_open(rom, n, 48000, err, sizeof err);
  static uint32_t px[GBC_SCREEN_PIXELS]; static int16_t pcm[8192];
  for (int fr = 1; fr <= 2600; ++fr) {
    uint32_t b = 0;
    if ((fr >= 300 && fr < 306) || (fr >= 420 && fr < 426) || (fr >= 540 && fr < 546)) b = GBC_BUTTON_START;
    if (fr >= 700 && fr < 706) b = GBC_BUTTON_A;
    if (fr >= 760 && fr < 766) b = GBC_BUTTON_START;
    if (fr >= 1000 && fr < 1006) b = GBC_BUTTON_RIGHT;
    if (fr >= 1060 && fr < 1066) b = GBC_BUTTON_A;
    if (fr >= 1120 && fr < 1126) b = GBC_BUTTON_START;
    if (fr > 1300) b = GBC_BUTTON_RIGHT | GBC_BUTTON_TURBO_B | ((fr % 90) < 10 ? GBC_BUTTON_A : 0);
    gbc_set_buttons(e, b);
    gbc_run_frame(e, px, pcm, 4096);
    if (fr == 1300) { orig_hook = GameHBIRQHook; GameHBIRQHook = my_hook; }
    if (fr <= 1302 || !captured) continue;
    render_nt();
    int best = -1, bestScore = -1;
    for (int o = 0; o < NW; ++o) {
      int score = 0;
      for (int y = 30; y < 150; y += 3) for (int x = 1; x < 256; ++x) {
        int ny = y + 8;
        int e1 = nt[ny * NW + (o + x) % NW] != nt[ny * NW + (o + x - 1) % NW];
        int e2 = px[y * 256 + x] != px[y * 256 + x - 1];
        score += e1 == e2;
      }
      if (score > bestScore) { bestScore = score; best = o; }
    }
    match_sum += 100.0 * bestScore / (40 * 255); nmatch++;
    if (prev_o >= 0) { int d = (best - prev_o + NW) % NW; if (d > NW / 2) d -= NW; world += d; }
    prev_o = best;
    int t = nframes++;
    fworld[t] = world; off[t] = best;
    for (int x = 0; x < NW; ++x) {
      uint64_t h = 1469598103934665603ULL;
      for (int y = 16; y < 184; ++y) h = (h ^ nt[y * NW + x]) * 1099511628211ULL;
      hash[t][x] = h;
    }
    if (fr == 2000 && argc > 2) {
      FILE* o = fopen(argv[2], "wb"); fprintf(o, "P6\n%d %d\n255\n", NW, NH);
      for (int y = 0; y < NH; ++y) for (int x = 0; x < NW; ++x) {
        int v = nt[y * NW + x]; int g = (v & 3) * 70 + (v >> 2) * 12;
        int in = ((x - best + NW) % NW) < 256;
        fputc(in ? g : g / 2 + 40, o); fputc(in ? g : g / 2, o); fputc(in ? g : g / 2, o);
      }
      fclose(o);
    }
  }
  printf("window match avg %.1f%%, frames %d, scrolled %ld px\n", match_sum / nmatch, nframes, world - fworld[0]);
  /* validity: column at world W seen at frame t (off-window) vs its content once visible */
  printf("dist  ahead_valid  behind_valid   (px beyond the 256-px window)\n");
  for (int k = 8; k <= 128; k += 8) {
    long okA = 0, nA = 0, okB = 0, nB = 0;
    for (int t = 0; t < nframes; ++t) for (int side = 0; side < 2; ++side) {
      long W = side ? fworld[t] - k : fworld[t] + 255 + k;
      int x_t = (int)(((W - fworld[t]) + prev_o_dummy(t)) );
      (void)x_t;
      /* nt column for world W at frame t */
      int col_t = (int)((W - fworld[t] + off[t]) % NW + NW) % NW;
      for (int t2 = t + 1; t2 < nframes; ++t2) {
        if (W >= fworld[t2] + 4 && W < fworld[t2] + 252) {
          int col2 = (int)((W - fworld[t2] + off[t2]) % NW + NW) % NW;
          int same = hash[t][col_t] == hash[t2][col2];
          if (side) { okB += same; nB++; } else { okA += same; nA++; }
          break;
        }
      }
    }
    printf("%4d   %5.1f%% (%ld)   %5.1f%% (%ld)\n", k, nA ? 100.0 * okA / nA : -1, nA, nB ? 100.0 * okB / nB : -1, nB);
  }
  return 0;
}
