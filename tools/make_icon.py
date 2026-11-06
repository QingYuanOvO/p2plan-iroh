#!/usr/bin/env python3
"""生成 p2plan 的 mod 图标。

设计意图：Minecraft 的方块语言 + 点对点直连。两个方块（你和朋友）之间一条实心连线，
不画服务器、不画云——那正是这个 mod 去掉的东西。

产出：
  icon.png        128×128   Modrinth / 部分启动器
  icon-512.png    512×512   高清（Modrinth gallery、README、宣传用）
  用最近邻缩小，避免糊边。
"""
from PIL import Image, ImageDraw

GREEN = (70, 224, 160)
GREEN_D = (32, 150, 104)
GREEN_X = (24, 104, 74)
BLUE = (91, 140, 255)
INK = (238, 244, 248)
INK_L = (198, 212, 224)
INK_D = (140, 158, 176)
DARK = (10, 20, 32)

S = 512
img = Image.new("RGBA", (S, S), (0, 0, 0, 0))
d = ImageDraw.Draw(img)

# ---- 背景板：圆角深色
pad = 14
d.rounded_rectangle([pad, pad, S - pad, S - pad], radius=104, fill=DARK + (255,))

# ---- 中心柔光：逐圈用不透明色由外向内微亮，避免半透明叠加成死白
cx = cy = S // 2
for r in range(196, 0, -4):
    k = (1 - r / 196) ** 2.2
    col = (int(DARK[0] + 16 * k), int(DARK[1] + 38 * k), int(DARK[2] + 52 * k), 255)
    d.ellipse([cx - r, cy - r, cx + r, cy + r], fill=col)

# ---- 等距方块（顶面亮 / 左面中 / 右面暗）
def cube(px, py, half, top, left, right):
    pts_top = [(px, py - half), (px + half, py - half // 2), (px, py), (px - half, py - half // 2)]
    pts_left = [(px - half, py - half // 2), (px, py), (px, py + half), (px - half, py + half // 2)]
    pts_right = [(px + half, py - half // 2), (px, py), (px, py + half), (px + half, py + half // 2)]
    d.polygon(pts_top, fill=top)
    d.polygon(pts_left, fill=left)
    d.polygon(pts_right, fill=right)

HALF = 66
LX, RX = 150, S - 150
YY = 290

cube(LX, YY, HALF, INK, INK_L, INK_D)
cube(RX, YY, HALF, GREEN, GREEN_D, GREEN_X)

# ---- 直连光线：外发光 + 芯线
ly = YY - 28
x1, x2 = LX + HALF - 4, RX - HALF + 4
for w, a in ((28, 42), (17, 108), (10, 255)):
    d.line([(x1, ly), (x2, ly)], fill=BLUE + (a,), width=w)

# 线上两个跑动的光点（数据在流）
for t in (0.36, 0.64):
    px = x1 + (x2 - x1) * t
    d.ellipse([px - 9, ly - 9, px + 9, ly + 9], fill=(255, 255, 255, 245))

img.save("icon-512.png")
img.resize((128, 128), Image.LANCZOS).save("icon.png")
print("wrote icon.png (128) and icon-512.png (512)")
