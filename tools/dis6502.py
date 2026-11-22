#!/usr/bin/env python3
"""Tiny 6502 disassembler for a 32 KiB dump of CPU $8000-$FFFF. usage: dis6502.py dump start end"""
import sys
M = {}
def op(c, m, mode):
    M[c] = (m, mode)
for m, base in [("ORA",0x00),("AND",0x20),("EOR",0x40),("ADC",0x60),("STA",0x80),("LDA",0xA0),("CMP",0xC0),("SBC",0xE0)]:
    for off, mode in [(1,"izx"),(5,"zp"),(9,"imm"),(0xD,"abs"),(0x11,"izy"),(0x15,"zpx"),(0x19,"aby"),(0x1D,"abx")]:
        if not (m == "STA" and mode == "imm"): op(base+off, m, mode)
for m, base in [("ASL",0x00),("ROL",0x20),("LSR",0x40),("ROR",0x60)]:
    op(base+6,m,"zp"); op(base+0xA,m,"acc"); op(base+0xE,m,"abs"); op(base+0x16,m,"zpx"); op(base+0x1E,m,"abx")
for c,m in [(0xC6,"DEC"),(0xE6,"INC")]: op(c,m,"zp"); op(c+8,m,"abs"); op(c+0x10,m,"zpx"); op(c+0x18,m,"abx")
for c,m,mode in [(0xA2,"LDX","imm"),(0xA6,"LDX","zp"),(0xAE,"LDX","abs"),(0xB6,"LDX","zpy"),(0xBE,"LDX","aby"),
 (0xA0,"LDY","imm"),(0xA4,"LDY","zp"),(0xAC,"LDY","abs"),(0xB4,"LDY","zpx"),(0xBC,"LDY","abx"),
 (0x86,"STX","zp"),(0x8E,"STX","abs"),(0x96,"STX","zpy"),(0x84,"STY","zp"),(0x8C,"STY","abs"),(0x94,"STY","zpx"),
 (0xE0,"CPX","imm"),(0xE4,"CPX","zp"),(0xEC,"CPX","abs"),(0xC0,"CPY","imm"),(0xC4,"CPY","zp"),(0xCC,"CPY","abs"),
 (0x24,"BIT","zp"),(0x2C,"BIT","abs"),(0x4C,"JMP","abs"),(0x6C,"JMP","ind"),(0x20,"JSR","abs"),
 (0x10,"BPL","rel"),(0x30,"BMI","rel"),(0x50,"BVC","rel"),(0x70,"BVS","rel"),(0x90,"BCC","rel"),(0xB0,"BCS","rel"),(0xD0,"BNE","rel"),(0xF0,"BEQ","rel")]:
    op(c,m,mode)
for c,m in [(0x00,"BRK"),(0x40,"RTI"),(0x60,"RTS"),(0x08,"PHP"),(0x28,"PLP"),(0x48,"PHA"),(0x68,"PLA"),(0x88,"DEY"),(0xA8,"TAY"),
 (0xC8,"INY"),(0xE8,"INX"),(0x18,"CLC"),(0x38,"SEC"),(0x58,"CLI"),(0x78,"SEI"),(0x98,"TYA"),(0xB8,"CLV"),(0xD8,"CLD"),(0xF8,"SED"),
 (0x8A,"TXA"),(0x9A,"TXS"),(0xAA,"TAX"),(0xBA,"TSX"),(0xCA,"DEX"),(0xEA,"NOP")]:
    op(c,m,"imp")
L = {"imp":1,"acc":1,"imm":2,"zp":2,"zpx":2,"zpy":2,"izx":2,"izy":2,"rel":2,"abs":3,"abx":3,"aby":3,"ind":3}
d = open(sys.argv[1],"rb").read()
pc = int(sys.argv[2],16); end = int(sys.argv[3],16)
while pc < end:
    b = d[pc-0x8000]
    if b not in M: print(f"{pc:04X}  {b:02X}        .db ${b:02X}"); pc += 1; continue
    m, mode = M[b]; n = L[mode]
    bs = d[pc-0x8000:pc-0x8000+n]
    v = bs[1] if n == 2 else (bs[1] | bs[2] << 8) if n == 3 else 0
    a = {"imp":"","acc":"A","imm":f"#${v:02X}","zp":f"${v:02X}","zpx":f"${v:02X},X","zpy":f"${v:02X},Y","izx":f"(${v:02X},X)",
         "izy":f"(${v:02X}),Y","abs":f"${v:04X}","abx":f"${v:04X},X","aby":f"${v:04X},Y","ind":f"(${v:04X})",
         "rel":f"${(pc+2+(v-256 if v>127 else v))&0xFFFF:04X}"}[mode]
    print(f"{pc:04X}  {' '.join(f'{x:02X}' for x in bs):9} {m} {a}")
    pc += n
