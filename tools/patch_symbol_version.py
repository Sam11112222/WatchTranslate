# -*- coding: utf-8 -*-
"""
把某个 .so 对 OrtGetApiBase 的**符号版本需求**从一个版本改写为另一个版本。

通用工具：OLD/NEW 通过命令行指定，二者必须等长（版本名长度一致时可直接原地替换）。

典型用法（本次修复方向）：
  python patch_symbol_version.py libsherpa-onnx-jni.so out.so VERS_1.28.2 VERS_1.26.0

核心原理与两个必改点见 README 的「原生库符号版本冲突」一节：
  1. .dynstr 中的版本名字符串
  2. .gnu.version_r 中该条目的 vna_hash（linker 先按 hash 查找，漏改则仍然报
     cannot locate symbol）
"""
import struct
import shutil
import sys


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


def main():
    if len(sys.argv) != 5:
        print("用法: patch_symbol_version.py <src.so> <dst.so> <OLD_VER> <NEW_VER>")
        sys.exit(1)
    src, dst, old_s, new_s = sys.argv[1:5]
    OLD, NEW = old_s.encode(), new_s.encode()
    assert len(OLD) == len(NEW), f"长度必须相同才能原地替换: {len(OLD)} vs {len(NEW)}"

    shutil.copyfile(src, dst)
    with open(dst, 'rb') as f:
        d = f.read()
    secs, is64 = sections(d)
    dynstr = next(s for s in secs if s['sn'] == '.dynstr')
    verr = next(s for s in secs if s['sn'] == '.gnu.version_r')
    start, size = dynstr['offset'], dynstr['size']
    target_hash = elf_hash(NEW)

    with open(dst, 'r+b') as f:
        cnt, pos = 0, start
        while True:
            i = d.find(OLD, pos, start + size)
            if i < 0:
                break
            f.seek(i)
            f.write(NEW)
            cnt += 1
            pos = i + 1
        print(f"[1] .dynstr: {old_s} -> {new_s}  替换 {cnt} 处")

        fixed, off = 0, verr['offset']
        while True:
            vn_version, vn_cnt, vn_file, vn_aux, vn_next = struct.unpack_from('<HHIII', d, off)
            aux = off + vn_aux
            for _ in range(vn_cnt):
                h, flags, other, name_off, nxt = struct.unpack_from('<IHHII', d, aux)
                e = d.index(b'\0', dynstr['offset'] + name_off)
                nm = d[dynstr['offset'] + name_off:e]
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
    print(f"已写入 {dst}")


if __name__ == '__main__':
    main()
