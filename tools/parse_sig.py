import struct,sys,hashlib
d=open(sys.argv[1],'rb').read()
off=len(d)-22
while off>=0:
    if d[off:off+4]==b'PK\x05\x06': break
    off-=1
eocd=off
cd_off=struct.unpack_from('<I',d,eocd+16)[0]
mk=d.find(b'RPK Sig Block 42')
print(f"文件 {len(d)}B  EOCD@{eocd}  中央目录@{cd_off}  SigMagic@{mk}")
print(f"magic 结尾 = {mk+16}  (== cd_off ? {mk+16==cd_off})")

# 找 signchunk 起点: pos 处 size 满足 size == (mk+16-pos)-8
start=None
for pos in range(mk-8, max(mk-6000,0), -1):
    v=struct.unpack_from('<I',d,pos)[0]
    if v == (mk+16-pos)-8 and struct.unpack_from('<I',d,pos+4)[0]==0:
        start=pos; break
print(f"signchunk 起点 = {start}, 总长 = {mk+16-start}, 开头 size 字段 = {struct.unpack_from('<I',d,start)[0]}")
q=start+8
kv_size,z, kv_id,kv_val_size = struct.unpack_from('<IIII', d, q)
print(f"kvBlock#{0}: size={kv_size} id={hex(kv_id)} value.size={kv_val_size}")
q+=16
if kv_id==0x01000101:
    blk_size = struct.unpack_from('<I',d,q)[0]; q+=4
    print(f"  signBlock.size={blk_size}")
    sd_size = struct.unpack_from('<I',d,q)[0]; q+=4
    sd = d[q:q+sd_size]; q+=sd_size
    print(f"  signdata.size={sd_size}")
    o=4
    di_size = struct.unpack_from('<I',sd,0)[0]
    dl,did,dh = struct.unpack_from('<III', sd, o)
    print(f"  digests.size={di_size}  digest: len={dl} id={hex(did)} hashlen={dh}")
    print(f"    hash={sd[o+12:o+12+dh].hex()}")
    o+=dl
    ce_size = struct.unpack_from('<I',sd,o)[0]; o+=4
    cl = struct.unpack_from('<I',sd,o)[0]
    print(f"  certs.size={ce_size} cert.len={cl}")
    o+=4+cl
    print(f"  additional={struct.unpack_from('<I',sd,o)[0]}")
    sg_size = struct.unpack_from('<I',d,q)[0]; q+=4
    s_sz,s_id,s_len = struct.unpack_from('<III', d, q); q+=12
    print(f"  signatures.size={sg_size} sig.size={s_sz} id={hex(s_id)} len={s_len}")
    q+=s_len
    pk_size = struct.unpack_from('<I',d,q)[0]; q+=4
    print(f"  pubkey.size={pk_size}")
    q+=pk_size
    print(f"  解析到 q={q}, 应等于 {mk-8} → {q==mk-8}")
    if q != mk-8:
        # 还有第二个 kv
        kv_size2,z2,kv_id2,kv_val2 = struct.unpack_from('<IIII', d, q)
        print(f"kvBlock#1: size={kv_size2} id={hex(kv_id2)} value.size={kv_val2}")
        print(f"  下一 8B: {d[q+16:q+24].hex()}")

# 复算三块 hash
def blk(s,e):
    chk=d[s:e]
    return hashlib.sha256(bytes([0xA5])+struct.pack('<i',len(chk))+chk).digest()
h=blk(0,start); c=blk(cd_off,eocd); f=blk(eocd,len(d))
whole=bytes([0x5A])+struct.pack('<i',3)+h+c+f
print(f"\n复算 header.sign = {h.hex()}")
print(f"复算 central.sign= {c.hex()}")
print(f"复算 footer.sign = {f.hex()}")
print(f"复算 SHA256(whole)= {hashlib.sha256(whole).hexdigest()}")
