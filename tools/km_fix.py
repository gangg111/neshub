#!/usr/bin/env python3
"""Corrections to NAVI's Kick Master translation, appended to his IPS as extra records.
   km_fix.py ORIGINAL_ROM NAVI_PATCH OUT_PATCH
Each fix keeps the text block's length: a longer word takes spaces (0x00) that pad the
end of its line/entry (before a 0x6x screen address, 0xFF end, or -- in the story text --
the line's last letter, which carries bit 7); a shorter one gives them back there.
Text: letter = ASCII-64, 0x00 space, bit 7 on a letter = space before it (menus) or end
of line (story); Polish letters live in the translation's font (see CH)."""
import sys
CH = {' ': 0x00, 'Ó': 0x11, 'Ą': 0x1B, '.': 0x1C, ',': 0x1D, '?': 0x1E, '!': 0x1F, 'Ę': 0x2A, 'Ć': 0x2B,
      ':': 0x2C, 'Ś': 0x2F, 'Ł': 0x30, 'Ń': 0x32, 'Ź': 0x33, 'Ż': 0x3B}
for k in range(26): CH[chr(65 + k)] = k + 1
for k in range(10): CH[str(k)] = 0x20 + k
STORY = [(0x4061, 0x4283), (0xda4a, 0xddf8)]          # intro and ending (line-end letter mode)

def enc(s, flag_spaces):
    """flag_spaces: ' X' -> X|0x80 (how the menus/messages store a space before a letter)"""
    out = bytearray(); i = 0
    while i < len(s):
        if flag_spaces and s[i] == ' ' and i + 1 < len(s) and s[i + 1] != ' ':
            out.append(CH[s[i + 1]] | 0x80); i += 2
        else:
            out.append(CH[s[i]]); i += 1
    return bytes(out)

def ips_apply(rom, p):
    out = bytearray(rom); i = 5
    while p[i:i + 3] != b'EOF':
        off = int.from_bytes(p[i:i + 3], 'big'); n = int.from_bytes(p[i + 3:i + 5], 'big'); i += 5
        if n == 0: rl = int.from_bytes(p[i:i + 2], 'big'); out[off:off + rl] = bytes([p[i + 2]]) * rl; i += 3
        else: out[off:off + n] = p[i:i + n]; i += n
    return out

def fix(rom, old, new, nth=1, start=0):
    # the word may follow a space stored in its first letter (bit 7): try both forms
    for flag in (True, False):
        for first in (False, True):
            o = bytearray(enc(old, flag))
            if first: o[0] |= 0x80
            at = -1; pos = start
            for _ in range(nth):
                at = rom.find(o, pos); pos = at + 1
                if at < 0: break
            if at >= 0: break
        if at >= 0: break
    assert at >= 0, f'not found: {old}'
    story = any(a <= at < b for a, b in STORY)
    n = bytearray(enc(new, not story and flag))
    if first: n[0] |= 0x80
    end = at + len(o); delta = len(n) - len(o)
    # the terminator after the edit: where padding spaces may be taken/given
    t = end
    while not (rom[t] == 0xFF or 0x60 <= rom[t] < 0x70 or (story and rom[t] & 0x80)): t += 1
    if delta > 0:
        # spaces before this terminator, or (menus/messages) before a later one in the same
        # entry: the bytes in between, screen addresses included, just move along
        while True:
            z = t
            while rom[z - 1] == 0x00 and z - 1 >= end: z -= 1
            if t - z >= delta or story or rom[t] == 0xFF: break
            t += 2 if 0x60 <= rom[t] < 0x70 else 1
            while not (rom[t] == 0xFF or 0x60 <= rom[t] < 0x70): t += 1
        assert t - z >= delta, f'no room for "{new}" ({t - z} spare, need {delta})'
        rom[at:t] = n + rom[end:t - delta]
    elif delta < 0:
        rom[at:t] = n + rom[end:t] + b'\0' * -delta
    else:
        rom[at:end] = n
    return at

CENTER = {'KOP Z OBROTU'}
FIXES = [  # (old, new, which occurrence) -- spelling first, then clear mistranslations
    ('WYJĆ', 'WYJŚĆ', 1),                 # menu: EXIT
    ('KRÓRE', 'KTÓRE', 1), ('PODCZES', 'PODCZAS', 1), ('WYJĄDKIEM', 'WYJĄTKIEM', 1),
    ('LOWLER', 'LOWREL', 1), ('LOWLER', 'LOWREL', 1),   # the kingdom is Lowrel (3rd: see RAW)
    ('ENERGII.', 'ENERGII', 1), ('POZIMU', 'POZIOMU', 1),
    ('WSTRZĄŚNIERZ', 'WSTRZĄŚNIESZ', 1), ('NIEWARŻLIWY', 'NIEWRAŻLIWY', 1),
    ('MAGIĘ ŚWIATŁA', 'MAGIĘ PIORUNÓW', 1),    # Lightning Spirit
    ('MAGIĘ ŚWIATŁA', 'BICZ BŁYSKAWIC', 1),    # Whip Lightning (the first one is renamed by now)
    ('WYPEŁNIĆ', 'RAZIĆ', 1),
    ('MNIĘ', 'MNIE', 1), ('POKŁAD STAKU', 'POKŁAD STATKU', 1),
    ('TERZECI', 'TRZECI', 1), ('NAJLEPRZEJ', 'NAJLEPSZEJ', 1), ('PRAWDZIEYCH', 'PRAWDZIWYCH', 1),
    ('NIZKI', 'NISKI', 1), ('KOP OKRĄGŁEGO DOMU', 'KOP Z OBROTU', 1),
    ('BŁĘDY', 'TESTERZY', 1),   # DEBUG TEAM (TWÓRCA/PROGRAM stay: no room for PRODUCENT/PROGRAMISTA)
]

if __name__ == '__main__':
    src = open(sys.argv[1], 'rb').read(); navi = open(sys.argv[2], 'rb').read()
    base = ips_apply(src, navi); rom = bytearray(base)
    # raw byte fixes: (old, new, label) -- the intro's LOWLER ends a line (last letter | 0x80)
    RAW = [(enc('LOWLE', False) + bytes([0x80 | CH['R']]), enc('LOWRE', False) + bytes([0x80 | CH['L']]), 'LOWLER -> LOWREL (wstęp)')]
    for old, new, label in RAW:
        at = rom.find(old); assert at >= 0 and rom.find(old, at + 1) < 0, label
        rom[at:at + len(old)] = new; print(f'{at:06x}  {label}')
    for old, new, nth in FIXES:
        at = fix(rom, old, new, nth)
        print(f'{at:06x}  {old} -> {new}')
        if new in CENTER:   # a shorter kick name: re-centre it in its field like the others
            a = at
            while rom[a - 1] == 0x00: a -= 1
            e = a
            while rom[e] != 0xFF: e += 1
            text = bytes(rom[a:e]).strip(b'\0'); pad = e - a - len(text)
            rom[a:e] = b'\0' * (pad // 2) + text + b'\0' * (pad - pad // 2)
    # extra IPS records for every changed run (merged when close)
    recs, i = [], 0
    while i < len(rom):
        if rom[i] != base[i]:
            j = i
            while j < len(rom) and (rom[j] != base[j] or (j + 4 < len(rom) and rom[j:j + 4] != base[j:j + 4])): j += 1
            recs.append((i, bytes(rom[i:j]))); i = j
        else: i += 1
    body = b''.join(o.to_bytes(3, 'big') + len(d).to_bytes(2, 'big') + d for o, d in recs)
    assert navi.endswith(b'EOF')
    open(sys.argv[3], 'wb').write(navi[:-3] + body + b'EOF')
    print(len(recs), 'records,', sum(len(d) for _, d in recs), 'bytes')
