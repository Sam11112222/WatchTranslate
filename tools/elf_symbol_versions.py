# -*- coding: utf-8 -*-
"""解析 .dynsym 全部符号 + .gnu.version / .gnu.version_r，看版本化符号。"""
import struct, sys

def parse(path):
    with open(path, 'rb') as f:
        d = f.read()
    is64 = d[4] == 2
    e_shoff = struct.unpack_from('<Q', d, 0x28)[0] if is64 else struct.unpack_from('<I', d, 0x20)[0]
    e_shentsize = struct.unpack_from('<H', d, 0x3A)[0] if is64 else struct.unpack_from('<H', d, 0x2E)[0]
    e_shnum = struct.unpack_from('<H', d, 0x3C)[0] if is64 else struct.unpack_from('<H', d, 0x30)[0]
    e_shstrndx = struct.unpack_from('<H', d, 0x3E)[0] if is64 else struct.unpack_from('<H', d, 0x32)[0]
    secs = []
    for i in range(e_shnum):
        off = e_shoff + i*e_shentsize
        if is64:
            name, typ, flags, addr, offset, size, link, info, align, entsize = struct.unpack_from('<IIQQQQIIQQ', d, off)
        else:
            name, typ, flags, addr, offset, size, link, info, align, entsize = struct.unpack_from('<IIIIIIIIII', d, off)
        secs.append(dict(name=name, typ=typ, offset=offset, size=size, link=link, entsize=entsize))
    shstr = secs[e_shstrndx]
    def sname(o):
        e = d.index(b'\0', shstr['offset']+o)
        return d[shstr['offset']+o:e].decode()
    for s in secs:
        s['sname'] = sname(s['name'])
    return d, secs, is64

def syms_of(path):
    d, secs, is64 = parse(path)
    dynsym = next(s for s in secs if s['sname'] == '.dynsym')
    dynstr = next(s for s in secs if s['sname'] == '.dynstr')
    n = dynsym['size'] // dynsym['entsize']
    out = []
    for i in range(n):
        off = dynsym['offset'] + i*dynsym['entsize']
        if is64:
            st_name, st_info, st_other, st_shndx, st_value, st_size = struct.unpack_from('<IBBHQQ', d, off)
        else:
            st_name, st_value, st_size, st_info, st_other, st_shndx = struct.unpack_from('<IIIBBH', d, off)
        if st_name == 0:
            out.append(('', st_shndx, st_info))
            continue
        e = d.index(b'\0', dynstr['offset']+st_name)
        nm = d[dynstr['offset']+st_name:e].decode('ascii', 'replace')
        out.append((nm, st_shndx, st_info))
    return out, d, secs, is64

def verdefs(path):
    """读 .gnu.version_d 里定义的版本名。"""
    try:
        d, secs, is64 = parse(path)
    except Exception as ex:
        return []
    sv = next((s for s in secs if s['sname'] == '.gnu.version_d'), None)
    if not sv:
        return []
    od = next((s for s in secs if s['sname'] == '.dynstr'), None)
    names = []
    off = sv['offset']
    end = off + sv['size']
    while off < end:
        vd_version, vd_flags, vd_ndx, vd_cnt, vd_hash, vd_aux, vd_next = struct.unpack_from('<HHHHIII', d, off)
        if vd_version == 0:
            break
        aux = off + vd_aux
        name_off = struct.unpack_from('<I', d, aux)[0]
        e = d.index(b'\0', od['offset']+name_off)
        names.append(d[od['offset']+name_off:e].decode('ascii', 'replace'))
        if vd_next == 0:
            break
        off += vd_next
    return names

for p in sys.argv[1:]:
    print(f"===== {p.split('/')[-1]} =====")
    ss, d, secs, is64 = syms_of(p)
    defined = [(n, sh, info) for n, sh, info in ss if n and sh != 0]
    print(f"  DEFINED 符号 ({len(defined)}):")
    for n, sh, info in defined:
        vis = info & 0x3
        vismap = {0: 'DEFAULT', 1: 'INTERNAL', 2: 'HIDDEN', 3: 'PROTECTED'}
        print(f"    {n}  (visibility={vismap.get(vis, vis)})")
    und = [n for n, sh, info in ss if n and sh == 0]
    print(f"  UNDEFINED 符号 ({len(und)}): {und[:25]}")
    vd = verdefs(p)
    print(f"  VERDEF: {vd}")
    print()
