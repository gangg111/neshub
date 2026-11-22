#!/usr/bin/env python3
"""Apply an IPS patch: ips.py rom patch.ips out [--list]"""
import sys
rom = bytearray(open(sys.argv[1], 'rb').read())
p = open(sys.argv[2], 'rb').read()
assert p[:5] == b'PATCH', 'not an IPS file'
i = 5; recs = []
while p[i:i+3] != b'EOF':
    off = int.from_bytes(p[i:i+3], 'big'); size = int.from_bytes(p[i+3:i+5], 'big'); i += 5
    if size == 0:
        n = int.from_bytes(p[i:i+2], 'big'); data = bytes([p[i+2]]) * n; i += 3
    else:
        data = p[i:i+size]; i += size
    if off + len(data) > len(rom): rom.extend(b'\0' * (off + len(data) - len(rom)))
    rom[off:off+len(data)] = data
    recs.append((off, len(data)))
open(sys.argv[3], 'wb').write(rom)
print(f"{len(recs)} records, {sum(n for _, n in recs)} bytes changed, size {len(rom)}")
if '--list' in sys.argv:
    for off, n in recs:
        bank = (off - 16) // 0x4000 if off - 16 < 0x20000 else None
        where = f"PRG bank {bank}" if bank is not None else f"CHR {(off - 16 - 0x20000) // 0x400}"
        print(f"  {off:06X} +{n:<5} {where}")
