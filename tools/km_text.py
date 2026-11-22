#!/usr/bin/env python3
"""Dump the text of the Kick Master translation: every PRG region the IPS changed,
decoded with the game's charset (A=0x01, space=0x00, Polish letters from the font)."""
import sys
SRC = '/sdcard/Download/Kick Master/dump.nes'
PATCH = sys.argv[1] if len(sys.argv) > 1 else 'app/src/main/assets/patches/kickmaster/Spolszczenie (NAVI).ips'
src = open(SRC, 'rb').read(); p = open(PATCH, 'rb').read()
out = bytearray(src); i = 5; recs = []
while p[i:i+3] != b'EOF':
    off = int.from_bytes(p[i:i+3], 'big'); n = int.from_bytes(p[i+3:i+5], 'big'); i += 5
    if n == 0: rl = int.from_bytes(p[i:i+2], 'big'); out[off:off+rl] = bytes([p[i+2]])*rl; i += 3; recs.append((off, rl))
    else: out[off:off+n] = p[i:i+n]; i += n; recs.append((off, n))
CH = {0: ' ', 0x11: 'Ó', 0x1B: 'Ą', 0x1C: '.', 0x1D: ',', 0x1E: '?', 0x1F: '!', 0x2A: 'Ę', 0x2B: 'Ć', 0x2C: ':',
      0x2F: 'Ś', 0x30: 'Ł', 0x32: 'Ń', 0x33: 'Ż'}
for k in range(1, 27):
    if k not in CH: CH[k] = chr(64 + k)
for k in range(10): CH[0x20 + k] = str(k)
def dec(b):
    s = ''
    for x in b:
        if x in CH: s += CH[x]
        elif x & 0x80 and (x & 0x7F) in CH: s += '·' + CH[x & 0x7F]   # high bit: flagged char
        else: s += f'[{x:02x}]'
    return s
prg_end = 16 + src[4] * 16384
for off, n in sorted(recs):
    if off >= prg_end: continue
    print(f'{off:06x} +{n:<4} {dec(out[off:off+n])}')
