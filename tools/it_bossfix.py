"""Iron Tank (U): boss corner fix -> IPS.

Bug (known, e.g. Think Tank C-Type): a boss chases the player; its parts (objects of
type $0D, slots $10-$68) are moved by $D773 with no bounds check. In the bottom-right
corner the right half runs past X=$FF -- the engine then flags those parts as off
screen ($B332 sets bit 7 of the X high byte) -- and the lower parts' Y walks down to
$FF, the "empty slot" marker. The boss freezes half off screen and can never be
finished: the fight never ends and the game is stuck.

Fix: $D7A9 JSR $D773 -> JSR $AF00, a check placed in free space of PRG bank 3 (mapped
at that point: $ED18 has just selected it). Before the boss moves, each part's next
position is checked; a step that would carry a part from inside the screen to outside
(Y $08-$BF, X $08-$EF on the first screen) is cancelled on that axis ($20 = dy,
$21 = dx). Parts already outside are left alone (nothing changes for a boss that
enters from off screen). Then it continues at $D773 as before.
usage: it_bossfix.py rom.nes out.ips [patched.nes]"""
import sys

def assemble(org, src):
    ops = {"TXA": (0x8A,), "PHA": (0x48,), "PLA": (0x68,), "TAX": (0xAA,), "CLC": (0x18,), "RTS": (0x60,),
           "LDX#": 0xA2, "LDA#": 0xA9, "CMP#": 0xC9, "ADC#": 0x69, "CPX#": 0xE0,
           "LDAz": 0xA5, "LDYz": 0xA4, "STAz": 0x85, "ADCz": 0x65,
           "LDAx": 0xBD, "JSR": 0x20, "JMP": 0x4C,
           "BEQ": 0xF0, "BNE": 0xD0, "BMI": 0x30, "BCC": 0x90, "BCS": 0xB0}
    lines = [l.split(";")[0].strip() for l in src.strip().splitlines()]
    def size(ins):
        m = ins.split()[0]
        if len(ins.split()) == 1: return 1
        a = ins.split()[1]
        if m in ("JSR", "JMP") or a.endswith(",X"): return 3
        return 2
    labels, pc, prog = {}, org, []
    for l in lines:
        if not l: continue
        if l.endswith(":"): labels[l[:-1]] = pc; continue
        prog.append((pc, l)); pc += size(l)
    out = bytearray()
    for pc, l in prog:
        p = l.split(); m = p[0]
        if len(p) == 1: out += bytes(ops[m]); continue
        a = p[1]
        if m in ("BEQ", "BNE", "BMI", "BCC", "BCS"):
            d = labels[a] - (pc + 2); assert -128 <= d < 128, l; out += bytes([ops[m], d & 0xFF])
        elif m in ("JSR", "JMP"):
            t = labels[a] if a in labels else int(a.strip("$"), 16); out += bytes([ops[m], t & 0xFF, t >> 8])
        elif a.startswith("#"): out += bytes([ops[m + "#"], int(a[2:], 16)])
        elif a.endswith(",X"): t = int(a[1:-2], 16); out += bytes([ops[m + "x"], t & 0xFF, t >> 8])
        else: out += bytes([ops[m + "z"], int(a[1:], 16)])
    return bytes(out)

SRC = """
    TXA
    PHA
    JSR check          ; the boss object itself
    LDX #$10
loop:
    LDA $0303,X
    CMP #$0D           ; boss part
    BNE next
    JSR check
next:
    TXA
    CLC
    ADC #$08
    TAX
    CPX #$70
    BNE loop
    PLA
    TAX
    JMP $D773
check:
    LDA $0072,X
    CMP #$FF           ; empty slot
    BEQ ok
    LDY $20
    BEQ horiz
    BMI up
    CMP #$C0           ; already below the line: leave it
    BCS horiz
    CLC
    ADC $20
    CMP #$C0
    BCC horiz
    BCS stopv
up:
    CMP #$08
    BCC horiz
    CLC
    ADC $20
    CMP #$08
    BCS horiz
stopv:
    LDA #$00
    STA $20
horiz:
    LDA $0071,X        ; X high byte: not on the first screen -> leave it
    BNE ok
    LDA $0070,X
    LDY $21
    BEQ ok
    BMI left
    CMP #$F0
    BCS ok
    CLC
    ADC $21
    BCS stoph
    CMP #$F0
    BCC ok
    BCS stoph
left:
    CMP #$08
    BCC ok
    CLC
    ADC $21
    CMP #$08
    BCS ok
stoph:
    LDA #$00
    STA $21
ok:
    RTS
"""
ORG = 0xAF00
code = assemble(ORG, SRC)
rom = bytearray(open(sys.argv[1], "rb").read())
H = 16
at = H + 3 * 0x4000 + (ORG - 0x8000)
assert rom[at:at + len(code)] == b"\xff" * len(code), "free space not empty"
jsr = H + 7 * 0x4000 + (0xD7A9 - 0xC000)
assert rom[jsr:jsr + 3] == bytes.fromhex("2073d7"), "unexpected code at $D7A9"
recs = [(at, code), (jsr, bytes([0x20, ORG & 0xFF, ORG >> 8]))]
out = bytearray(b"PATCH")
for off, data in recs:
    out += off.to_bytes(3, "big") + len(data).to_bytes(2, "big") + data
out += b"EOF"
open(sys.argv[2], "wb").write(out)
for off, data in recs: rom[off:off + len(data)] = data
if len(sys.argv) > 3: open(sys.argv[3], "wb").write(rom)
print("ok: %d bytes of code, IPS %d bytes" % (len(code), len(out)))
