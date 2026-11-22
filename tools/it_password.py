"""Iron Tank passwords: dec(pw) -> (hi, lo, valid); enc(hi, bit) -> password (code $FFB9/$FF6A)."""
import sys
def dec(pw):
    N=int(pw[1:6]); lo=N&0xff; hi=(N>>8)&0xff
    ok=(lo&0xfe)==((((lo&1)^hi)<<1)&0xff)
    s=(lo+hi+0xab)&0xff
    ok=ok and int(pw[0])==((s&0x70)>>4) and int(pw[6])==(s&7)
    return hi,lo,ok
def enc(hi,bit):
    lo=((bit^hi)<<1 | bit)&0xff
    N=hi*256+lo
    if N>99999: return None
    s=(lo+hi+0xab)&0xff
    return '%d%05d%d'%((s&0x70)>>4,N,s&7)
if __name__=='__main__':
    for pw in sys.argv[1:]:
        hi,lo,ok=dec(pw); print(pw,'hi %02X lo %02X'%(hi,lo),ok)
