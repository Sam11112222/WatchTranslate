# -*- coding: utf-8 -*-
"""
补丁 libonnxruntime4j_jni.so 的符号版本需求（完整版）：
  1. .dynstr 中 "VERS_1.26.0" -> "VERS_1.28.2"（等长 11 字节，原地替换）
  2. .gnu.version_r 中该条目的 vna_hash 更新为 "VERS_1.28.2" 的 ELF hash
     （这是关键：linker 先按 hash 定位版本节点，hash 不对就直接判为
      "cannot locate symbol"，光改字符串没用）

动机：APK 内只能存在一份 libonnxruntime.so，它只提供单一符号版本；
  libsherpa-onnx-jni.so    需要 OrtGetApiBase@VERS_1.28.2
  libonnxruntime4j_jni.so  需要 OrtGetApiBase@VERS_1.26.0
统一改用 sherpa 提供的 1.28.2，让 4j 的 JNI 也声明要 1.28.2。
"""
import struct
import shutil
import sys

SRC = sys.argv[1]
DST = sys.argv[2]
OLD = b"VERS_1.26.0"
NEW = b"VERS_1.28.2"
assert len(OLD) == len(NEW)


def elf_hash(name: bytes) -> int:
    h = 0
    for c in name:
        h = (h << 4) + c
        g = h & 0xF0000000
        if g:
            h ^= g >> 24
        h &= ~g
    return h & 0xffffffff


def sections(d):
    is64 = d[4] == 2
    e_shoff = struct.unpack_from('<Q', d, 0x28)[0] if is64 else struct.unpack_from('<I', d, 0x20)[0]
    e_shentsize = struct.unpack_from('<H', d, 0x3A)[0] if is64 else struct.unpack_from('<H', d, 0x2E)[0]
    e_shnum = struct.unpack_from('<H', d, 0x3C)[0] if is64 else struct.unpack_from('<H', d, 0x30)[0]
    e_shstrndx = struct.unpack_from('<H', d, 0x3E)[0] if is64 else struct.unpack_from('<H', d, 0x32)[0]
    secs = []
    for i in range(e_shnum):
        off = e_shoff + i * e_shentsize
        if is64:
            name, typ, flags, addr, offset, size, link, info, align, entsize = struct.unpack_from('<IIQQQQIIQQ', d, off)
        else:
            name, typ, flags, addr, offset, size, link, info, align, entsize = struct.unpack_from('<IIIIIIIIII', d, off)
        secs.append(dict(name=name, typ=typ, offset=offset, size=size, link=link, entsize=entsize))
    shstr = secs[e_shstrndx]
    def sname(o):
        e = d.index(b'\0', shstr['offset'] + o)
        return d[shstr['offset'] + o:e].decode()
    for s in secs:
        s['sn'] = sname(s['name'])
    return secs, is64


shutil.copyfile(SRC, DST)
with open(DST, 'rb') as f:
    d = f.read()
secs, is64 = sections(d)
dynstr = next(s for s in secs if s['sn'] == '.dynstr')
verr = next(s for s in secs if s['sn'] == '.gnu.version_r')

with open(DST, 'r+b') as f:
    # 1) 替换 .dynstr 里的版本名字符串（限定在该段内）
    start, size = dynstr['offset'], dynstr['size']
    cnt = 0
    pos = start
    while True:
        i = d.find(OLD, pos, start + size)
        if i < 0:
            break
        f.seek(i)
        f.write(NEW)
        cnt += 1
        pos = i + 1
    print(f"[1] .dynstr: {OLD.decode()} -> {NEW.decode()}  替换 {cnt} 处")

    # 2) 修正 .gnu.version_r 中指向该名的条目的 vna_hash
    target_hash = elf_hash(NEW)
    fixed = 0
    off = verr['offset']
    while True:
        vn_version, vn_cnt, vn_file, vn_aux, vn_next = struct.unpack_from('<HHIII', d, off)
        aux = off + vn_aux
        for k in range(vn_cnt):
            h, flags, other, name_off, nxt = struct.unpack_from('<IHHII', d, aux)
            e = d.index(b'\0', dynstr['offset'] + name_off)
            nm = d[dynstr['offset'] + name_off:e]
            # 注意：d 是补丁前的字节，此处仍读到旧名 OLD
            if nm in (OLD, NEW) and h != target_hash:
                print(f"[2] version_r @0x{aux:x}: vna_hash 0x{h:08x} -> 0x{target_hash:08x}  ({nm.decode()})")
                f.seek(aux)
                f.write(struct.pack('<I', target_hash))
                fixed += 1
            aux += 16
        if vn_next == 0:
            break
        off += vn_next
    print(f"[2] 共修正 {fixed} 处 vna_hash")

print(f"已写入 {DST}")
