#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""生成宣传海报 PNG：把真机截图 + 新 logo 内联进 banner.html，用 Edge 无头渲染后降采样到 1920x1080。
用法: python build_banner.py [截图路径] [输出路径]
"""
import base64, io, os, subprocess, sys

ROOT  = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))          # assets/
PROJ  = os.path.dirname(ROOT)                                               # 工程根
SRC   = os.path.join(PROJ, 'assets', 'banner-src')
EDGE  = r'C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe'

shot_path = sys.argv[1] if len(sys.argv) > 1 else os.path.join(SRC, 'shots', 'now.png')
out_path  = sys.argv[2] if len(sys.argv) > 2 else os.path.join(PROJ, 'assets', 'images', 'banner.png')

from PIL import Image, ImageDraw

# ---------- 1. 表盘截图：裁圆 + 内缩，去掉表壳与侧键 ----------
im = Image.open(shot_path).convert('RGB')
W, H = im.size
cx, cy = W / 2, H / 2
R = 222                                   # 466 原图里显示区半径约 224，取 222 避开边框高光
box = (int(cx - R), int(cy - R), int(cx + R), int(cy + R))
crop = im.crop(box)
size = crop.size[0]
ss = 4
mask = Image.new('L', (size * ss, size * ss), 0)
ImageDraw.Draw(mask).ellipse([0, 0, size * ss - 1, size * ss - 1], fill=255)
crop.putalpha(mask.resize((size, size), Image.LANCZOS))
crop = crop.resize((size * 2, size * 2), Image.LANCZOS)      # 2x 供 1.5 倍渲染
buf = io.BytesIO(); crop.save(buf, 'PNG')
shot_uri = 'data:image/png;base64,' + base64.b64encode(buf.getvalue()).decode()

# ---------- 2. logo：圆角方砖 ----------
logo = Image.open(os.path.join(SRC, 'icon-master-1024.png')).convert('RGBA').resize((320, 320), Image.LANCZOS)
buf = io.BytesIO(); logo.save(buf, 'PNG')
logo_uri = 'data:image/png;base64,' + base64.b64encode(buf.getvalue()).decode()

# ---------- 3. 注入模板 ----------
tpl_name = sys.argv[3] if len(sys.argv) > 3 else 'banner.html'
tpl = open(os.path.join(SRC, tpl_name), encoding='utf-8').read()
html = tpl.replace('{{SHOT}}', shot_uri).replace('{{LOGO}}', logo_uri)
render_html = os.path.join(SRC, '_render.html')
open(render_html, 'w', encoding='utf-8').write(html)

# ---------- 4. Edge 无头渲染 ----------
raw = os.path.join(SRC, '_raw.png')
subprocess.run([EDGE, '--headless=new', '--disable-gpu', '--hide-scrollbars', '--no-sandbox',
                '--force-device-scale-factor=1.5', '--window-size=1920,1080',
                '--screenshot=' + raw, 'file:///' + render_html.replace('\\', '/')],
               capture_output=True)

# ---------- 5. 降采样到 1920x1080 ----------
img = Image.open(raw).convert('RGB')
print('渲染原始尺寸:', img.size)
img = img.resize((1920, 1080), Image.LANCZOS)
img.save(out_path)
print('已输出:', out_path, img.size)
