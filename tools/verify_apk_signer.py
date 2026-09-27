#!/usr/bin/env python3
"""读取 APK v2/v3 签名块里的签名证书，用于比较不同构建是否用同一把签名密钥。

用途：确认设备覆盖安装测试包与既有正式包是否同一签名密钥（换签名会导致覆盖安装失败），
以及确认 main 分支 CI 的构建签名与正式发布签名不同。

不依赖 apksigner/build-tools：直接解析 APK Signing Block，取第一张 X.509 证书并输出 SHA-256。

用法：
  python3 tools/verify_apk_signer.py app-release.apk [more.apk ...]
"""

import hashlib
import struct
import sys

MAGIC = b"APK Sig Block 42"
SCHEMES = {0x7109871A: "v2", 0xF05368C0: "v3", 0x1B93AD61: "v3.1"}


def u32(buf, off):
    return struct.unpack_from("<I", buf, off)[0]


def read_len_prefixed(buf, off):
    n = u32(buf, off)
    return buf[off + 4: off + 4 + n], off + 4 + n


def signing_block(data):
    eocd = data.rfind(b"PK\x05\x06")
    if eocd < 0:
        raise ValueError("EOCD not found")
    cd_off = u32(data, eocd + 16)
    size = struct.unpack_from("<Q", data, cd_off - 24)[0]
    if data[cd_off - 16: cd_off] != MAGIC:
        raise ValueError("APK Signing Block magic not found")
    start = cd_off - 8 - size
    pairs = []
    off = start + 8
    while off < cd_off - 24:
        plen = struct.unpack_from("<Q", data, off)[0]
        pid = u32(data, off + 8)
        pairs.append((pid, data[off + 12: off + 8 + plen]))
        off += 8 + plen
    return pairs


def first_certificate(block):
    signers, _ = read_len_prefixed(block, 0)
    signer, _ = read_len_prefixed(signers, 0)
    signed_data, _ = read_len_prefixed(signer, 0)
    _, off = read_len_prefixed(signed_data, 0)        # digests
    certs, _ = read_len_prefixed(signed_data, off)    # certificates
    cert, _ = read_len_prefixed(certs, 0)
    return cert


def describe(path):
    data = open(path, "rb").read()
    print(f"== {path}")
    for pid, payload in signing_block(data):
        name = SCHEMES.get(pid, hex(pid))
        try:
            cert = first_certificate(payload)
        except Exception as exc:  # 结构未识别时只报告，不误报证书
            print(f"   {name}: certificate not parsed ({type(exc).__name__})")
            continue
        print(f"   {name}: cert SHA-256 = {hashlib.sha256(cert).hexdigest()}")
        print(f"   {name}: cert bytes = {len(cert)}")


if __name__ == "__main__":
    for p in sys.argv[1:]:
        describe(p)
