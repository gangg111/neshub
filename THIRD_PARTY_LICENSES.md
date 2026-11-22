# Third-party code and assets

## FCEUmm (libretro) — NES emulator core

`libgbcplayer.so` statically links FCEUmm (https://github.com/libretro/libretro-fceumm),
licensed under the GNU General Public License v2.0. Source used for the build: `~/fceumm-src`
(plus a 5-line local patch in `src/input.c` counting joypad reads for the FPS/slowdown meter).

## On-screen controller artwork

Colours and proportions of the touch overlay follow `kontroler_nes.svg`, supplied by the
owner of this project.

## CRT shader (crt-lottes)
`app/src/main/java/com/dklab/nes/CrtShader.kt` ports Timothy Lottes' "CRT styled scan-line
shader" from libretro/glsl-shaders (`crt/shaders/crt-lottes.glsl`), released by its author
into the PUBLIC DOMAIN.

## NTSC filter and palettes
Blargg's nes_ntsc (LGPL 2.1) and the colour palettes (FirebrandX, Sony CXA2025AS, NESCAP,
Wavebeam, ...) are part of FCEUmm (GPL v2), see above.

## TV bezel
`app/src/main/assets/bezels/tv.png`: made for this project by its owner (generated with Gemini),
21:9, 2552x1096 (made at 7656x3288, scaled down), transparent opening x 570..1980, y 8..1085.

## Teletext font (hub menu, "Gra przez sieć")
Bedstead Extended by Ben Harris (https://bjh21.me.uk/bedstead/), an outline version of the
Mullard SAA5050 teletext character generator, dedicated to the PUBLIC DOMAIN; embedded in
`hub/menu.html` as a subset (woff2). The TV around that window (`NEShub` frame) was made for
this project by its owner (generated with Gemini).
