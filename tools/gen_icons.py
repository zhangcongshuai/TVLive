#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""生成 TV 端图标与 banner（Leanback 首页需要 320x180 banner）"""
import os
from PIL import Image, ImageDraw, ImageFont

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
FONT = "/System/Library/Fonts/STHeiti Medium.ttc"

ACCENT = (47, 107, 255)
BG_TOP = (12, 20, 38)
BG_BOT = (24, 38, 66)


def gradient(w, h):
    img = Image.new("RGB", (w, h))
    d = ImageDraw.Draw(img)
    for y in range(h):
        t = y / max(1, h - 1)
        c = tuple(int(BG_TOP[i] + (BG_BOT[i] - BG_TOP[i]) * t) for i in range(3))
        d.line([(0, y), (w, y)], fill=c)
    return img


def round_rect(d, box, r, outline=None, width=1, fill=None):
    d.rounded_rectangle(box, radius=r, outline=outline, width=width, fill=fill)


def tv_glyph(d, cx, cy, w, h, scale):
    """电视外框 + 播放三角"""
    bw, bh = w, h
    x0, y0 = cx - bw // 2, cy - bh // 2
    round_rect(d, (x0, y0, x0 + bw, y0 + bh), int(6 * scale), outline=(255, 255, 255), width=max(2, int(2 * scale)))
    # 天线
    ax, ay = cx, y0
    d.line([(ax, ay), (ax - int(10 * scale), ay - int(9 * scale))], fill=(255, 255, 255), width=max(2, int(2 * scale)))
    d.line([(ax, ay), (ax + int(10 * scale), ay - int(9 * scale))], fill=(255, 255, 255), width=max(2, int(2 * scale)))
    # 播放三角
    s = int(bh * 0.26)
    d.polygon([(cx - s // 2, cy - s), (cx - s // 2, cy + s), (cx + s, cy)], fill=ACCENT)


def make_banner(path):
    W, H = 320, 180
    img = gradient(W, H)
    d = ImageDraw.Draw(img)
    tv_glyph(d, 84, 92, 96, 62, 1.6)
    try:
        f1 = ImageFont.truetype(FONT, 40, index=0)
        f2 = ImageFont.truetype(FONT, 15, index=0)
    except Exception:
        f1 = f2 = ImageFont.load_default()
    d.text((160, 66), "极简电视", font=f1, fill=(255, 255, 255))
    d.text((162, 118), "LIVE  ·  上下键换台", font=f2, fill=(150, 170, 200))
    img.save(path)


def make_icon(path, size):
    img = gradient(size, size)
    d = ImageDraw.Draw(img)
    tv_glyph(d, size // 2, int(size * 0.56), int(size * 0.66), int(size * 0.44), size / 96.0 * 1.0)
    img.save(path)


if __name__ == "__main__":
    banner = os.path.join(ROOT, "res", "drawable-xhdpi", "banner.png")
    icon = os.path.join(ROOT, "res", "mipmap-xhdpi", "ic_launcher.png")
    make_banner(banner)
    make_icon(icon, 96)
    print("banner:", banner)
    print("icon  :", icon)
