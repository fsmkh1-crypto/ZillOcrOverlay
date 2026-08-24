#!/usr/bin/env python3
import argparse, json, struct

ANCHORS = {"0":16, "A":31, "a":60}
TARGETS = ["0","A","a","ア","イ","テ","ム","腑","躙","綺"]

def u16(b,o): return struct.unpack_from('>H',b,o)[0]
def s16(b,o): return struct.unpack_from('>h',b,o)[0]
def u32(b,o): return struct.unpack_from('>I',b,o)[0]

def find_table(data, tag):
    n=u16(data,4)
    for i in range(n):
        o=12+i*16
        if data[o:o+4]==tag.encode('ascii'):
            off=u32(data,o+8); ln=u32(data,o+12)
            if off+ln>len(data): raise ValueError('table past EOF')
            return off
    return None

def choose_cmap(data,cmap):
    count=u16(data,cmap+2); c=[]
    for i in range(count):
        r=cmap+4+i*8; platform=u16(data,r); enc=u16(data,r+2); sub=cmap+u32(data,r+4)
        fmt=u16(data,sub)
        p=100
        if fmt==12 and platform==3 and enc==10: p=0
        elif fmt==12 and platform==0: p=1
        elif fmt==4 and platform==3 and 1<=enc<=10: p=2
        elif fmt==4 and platform==0: p=3
        if p<100: c.append((p,sub,fmt))
    return min(c) if c else None

def gid_fmt12(data,start,cp):
    groups=u32(data,start+12); lo,hi=0,groups-1
    while lo<=hi:
        m=(lo+hi)//2; o=start+16+m*12; a=u32(data,o); z=u32(data,o+4)
        if cp<a: hi=m-1
        elif cp>z: lo=m+1
        else: return u32(data,o+8)+(cp-a)
    return None

def gid_fmt4(data,start,cp):
    if cp>0xffff: return None
    seg=u16(data,start+6)//2; end=start+14; begin=end+seg*2+2; delta=begin+seg*2; ro=delta+seg*2; ln=u16(data,start+2)
    for i in range(seg):
        e=u16(data,end+i*2)
        if cp>e: continue
        b=u16(data,begin+i*2)
        if cp<b: return None
        d=s16(data,delta+i*2); r=u16(data,ro+i*2)
        if r==0: return (cp+d)&0xffff
        pos=ro+i*2+r+2*(cp-b)
        if pos+2>start+ln: return None
        raw=u16(data,pos)
        return None if raw==0 else (raw+d)&0xffff
    return None

def main():
    ap=argparse.ArgumentParser(); ap.add_argument('font'); ap.add_argument('--json',required=True); a=ap.parse_args()
    data=open(a.font,'rb').read(); cmap=find_table(data,'cmap')
    if cmap is None: raise SystemExit('no cmap')
    chosen=choose_cmap(data,cmap)
    if not chosen: raise SystemExit('no supported cmap')
    _,sub,fmt=chosen
    def gid(ch):
        cp=ord(ch)
        return gid_fmt12(data,sub,cp) if fmt==12 else gid_fmt4(data,sub,cp)
    mappings={ch:gid(ch) for ch in TARGETS}
    deltas={ch:(ANCHORS[ch]-mappings[ch] if mappings[ch] is not None else None) for ch in ANCHORS}
    vals=[v for v in deltas.values() if v is not None]
    passed=len(vals)==3 and len(set(vals))==1
    out={"cmap_format":fmt,"mappings":mappings,"anchor_deltas":deltas,"constant_offset_pass":passed}
    if passed:
        off=vals[0]; out['offset']=off; out['predictions']={ch:({'absolute':mappings[ch]+off,'section':(mappings[ch]+off)//1024,'ordinal':(mappings[ch]+off)%1024} if mappings[ch] is not None and 0<=mappings[ch]+off<3072 else None) for ch in TARGETS if ch not in ANCHORS}
    json.dump(out,open(a.json,'w'),ensure_ascii=False,indent=2)
    print(json.dumps(out,ensure_ascii=False,indent=2))

if __name__=='__main__': main()
