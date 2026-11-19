import sys, struct, hashlib, zipfile
def u32(b,o): return struct.unpack_from('<I',b,o)[0]
def u64(b,o): return struct.unpack_from('<Q',b,o)[0]
def lp(b,o): n=u32(b,o); return b[o+4:o+4+n], o+4+n
def v2certs(path):
    d=open(path,'rb').read()
    e=d.rfind(b'PK\x05\x06'); cd=u32(d,e+16)
    if d[cd-16:cd]!=b'APK Sig Block 42': return []
    sz=u64(d,cd-24); start=cd-sz-8; o=start+8; end=cd-24; out=[]
    while o<end:
        ln=u64(d,o); bid=u32(d,o+8); val=d[o+12:o+8+ln]; o+=8+ln
        if bid in (0x7109871a,0xf05368c0):
            signers,_=lp(val,0); p=0
            while p<len(signers):
                s,p=lp(signers,p); sd,_=lp(s,0)
                dig,q=lp(sd,0); certs,q=lp(sd,q); c=0
                while c<len(certs):
                    cert,c=lp(certs,c); out.append((hex(bid),hashlib.sha256(cert).hexdigest()[:24]))
    return out
for f in [a for a in sys.argv[1:] if a.endswith('.apk')]:
    print(f, v2certs(f))
    try:
        z=zipfile.ZipFile(f)
        for n in z.namelist():
            if n.startswith('META-INF/') and n.endswith('.RSA'): print('  v1 RSA present')
    except Exception as ex: print(ex)

# mode cek: python3 tools/apkcert.py file.apk EXPECTED_PREFIX
if len(sys.argv) == 3 and not sys.argv[2].endswith('.apk'):
    got = [c for _, c in v2certs(sys.argv[1])]
    if not got or not got[0].startswith(sys.argv[2]):
        print('TANDA TANGAN SALAH:', got, '!= ', sys.argv[2]); sys.exit(1)
    print('Tanda tangan OK:', got[0])
