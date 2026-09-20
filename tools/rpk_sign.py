#!/usr/bin/env python3
"""复刻 @aiot-toolkit/aiotpack 的 RPK 签名 (SignUtil.js)"""
import struct, hashlib, zlib, io, zipfile, sys, base64, re

def i32(v): return struct.pack('<i', v)
def u32(v): return struct.pack('<I', v & 0xffffffff)
def i16(v): return struct.pack('<h', v)

def pem_to_der(pem: bytes) -> bytes:
    b = re.sub(rb'-----[A-Z ]+-----', b'', pem)
    return base64.b64decode(re.sub(rb'\s+', b'', b))

def rsa_sha256_sign(data: bytes, key_pem: bytes) -> bytes:
    try:
        from cryptography.hazmat.primitives import hashes, serialization
        from cryptography.hazmat.primitives.asymmetric import padding
        k = serialization.load_pem_private_key(key_pem, password=None)
        return k.sign(data, padding.PKCS1v15(), hashes.SHA256())
    except ImportError:
        from Crypto.Signature import pkcs1_15
        from Crypto.Hash import SHA256
        from Crypto.PublicKey import RSA
        return pkcs1_15.new(RSA.import_key(key_pem)).sign(SHA256.new(data))

def rsa_pub_der(key_pem: bytes) -> bytes:
    try:
        from cryptography.hazmat.primitives import serialization
        k = serialization.load_pem_private_key(key_pem, password=None)
        return k.public_key().public_bytes(serialization.Encoding.DER,
                                           serialization.PublicFormat.SubjectPublicKeyInfo)
    except ImportError:
        from Crypto.PublicKey import RSA
        return RSA.import_key(key_pem).publickey().export_key(format='DER')

def section_hash(b: bytes) -> bytes:
    return hashlib.sha256(b'\xa5' + i32(len(b)) + b).digest()

def sign_rpk(zip_bytes: bytes, key_pem: bytes, cert_pem: bytes) -> bytes:
    d = zip_bytes
    eocd = len(d) - 22
    while eocd >= 0 and d[eocd:eocd+4] != b'PK\x05\x06':
        eocd -= 1
    assert eocd >= 0, "EOCD not found"
    cd_off = struct.unpack_from('<I', d, eocd + 16)[0]
    header, central, footer = d[:cd_off], d[cd_off:eocd], d[eocd:]

    # ① 三块摘要 → wholeData → sign
    h, c, f = section_hash(header), section_hash(central), section_hash(footer)
    sign = hashlib.sha256(b'\x5a' + i32(3) + h + c + f).digest()   # 32B

    # ② signdata
    digestBuf = i32(len(sign) + 8) + i32(0x0103) + i32(len(sign)) + sign
    cert_der = pem_to_der(cert_pem)
    certBuf = i32(len(cert_der)) + cert_der
    signdata = i32(len(digestBuf)) + digestBuf + i32(len(certBuf)) + certBuf + i32(0)

    # ③ RSA-SHA256 签名
    sig = rsa_sha256_sign(signdata, key_pem)
    pub_der = rsa_pub_der(key_pem)

    # ④ kv#0 (0x01000101) 主签名块
    sigsec = i32(len(sig) + 12) + i32(len(sig) + 8) + i32(0x0103) + i32(len(sig)) + sig
    value0 = (i32(24 + len(pub_der) + len(signdata) + len(sig))   # block.size
              + i32(len(signdata)) + signdata
              + sigsec
              + i32(len(pub_der)) + pub_der)
    kv0 = i32(len(value0) + 8) + i32(0) + i32(0x01000101) + i32(len(value0)) + value0

    # ⑤ kv#1 (0x01000201) 文件表
    z = zipfile.ZipFile(io.BytesIO(d))
    ents = []
    for e in z.infolist():
        if e.filename.endswith('/'): continue
        content = z.read(e.filename)
        ents.append(u32(zlib.crc32(e.filename.encode())) + i16(32) + hashlib.sha256(content).digest())
    blob = i32(0x0103) + b''.join(ents)
    fsig = rsa_sha256_sign(blob, key_pem)
    value1 = i32(len(blob)) + blob + i32(len(fsig) + 8) + i32(0x0103) + i32(len(fsig)) + fsig
    kv1 = i32(len(value1) + 8) + i32(0) + i32(0x01000201) + i32(len(value1)) + value1

    body = kv0 + kv1
    total = 8 + len(body) + 8 + 16
    magic = b'RPK Sig Block 42'
    signchunk = i32(total - 8) + i32(0) + body + i32(total - 8) + i32(0) + magic
    assert len(signchunk) == total

    # ⑥ 拼接: header + signchunk + central + footer(修正中央目录偏移)
    footer = bytearray(footer)
    struct.pack_into('<I', footer, 16, cd_off + len(signchunk))
    return header + signchunk + central + bytes(footer)

if __name__ == '__main__':
    src, out = sys.argv[1], sys.argv[2]
    key = open('private.pem','rb').read()
    cert = open('certificate.pem','rb').read()
    r = sign_rpk(open(src,'rb').read(), key, cert)
    open(out,'wb').write(r)
    print(f"已签名: {src} ({len(open(src,'rb').read())}B) → {out} ({len(r)}B)")
