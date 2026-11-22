# NES Hub

An Android app that plays NES games: a hub menu (an HTML page in a WebView) with a
cartridge shelf and a Famicom-style console, and a native player built on the
[FCEUmm](https://github.com/libretro/libretro-fceumm) core.

**No games are included.** Add your own cartridge dumps (`.nes`) with the "+"
cartridge on the shelf. The hub only accepts games it has a module for (see below),
and it makes the cartridge itself: the logo, the title screen and a gameplay clip are
rendered on the phone from your dump by recipes in `hub/art.json` (the NES is
deterministic, so the same buttons give the same frames on every phone;
`tools/artgen.c` runs a recipe on a PC). Descriptions, controls and tags are in
`hub/catalog.js`.

## What it does

- **Game modules** (`app/src/main/cpp/games/*.c`): each one recognises its game by
  the CRC32 of code banks and adds fixes (slowdown removed, game bugs patched) and
  modifications (lives, invincibility, ...) shown in the in-game menu. Modules exist
  for Chip 'n Dale Rescue Rangers, Contra, Contra Force, Duck Hunt, DuckTales 2,
  Iron Tank, Kick Master, Micro Machines, Tiny Toon Adventures 1 & 2 and Tom & Jerry.
- **Two players over the network** — Wi-Fi (same router or hotspot) or Wi-Fi Direct.
  Player 1 creates a game in the hub, player 2 joins and its hub follows player 1's
  shelf; both run the same deterministic emulation in lockstep, with RAM-hash checks
  and automatic state resync. Protocol after
  [t3native](https://github.com/gangg111/t3native)'s network mode.
- **Picture**: the crt-lottes CRT shader (GPU, AGSL), Blargg's NTSC filter and the
  palettes built into FCEUmm, an optional TV bezel.
- **Touch overlay** in the style of the NES pad, movable and resizable, save-state
  slots with thumbnails, rewind, fast-forward, turbo, IPS/BPS patches from the phone.

## Building (on the phone, in Termux)

```
tools/build_native.sh so          # FCEUmm (tools/libfceumm.a) + game modules -> libgbcplayer.so
python3 tools/hub_assets.py       # hub/menu.html -> app/src/main/assets/hub/
./gradlew assembleDebug
```

Built-in games for a private build go in `app/src/main/assets/games/<id>.nes` with
their pictures in `hub/games.js` (`window.NESHUB_GAMES = [...]`); both are kept out of
this repository. A private build's own app id and key go in `local.conf` (see `game.conf`).

## Licences

See [THIRD_PARTY_LICENSES.md](THIRD_PARTY_LICENSES.md). FCEUmm is GPL v2; the
crt-lottes shader and the Bedstead teletext font are public domain.
