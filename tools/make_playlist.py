#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
把 ngo5/IPTV 仓库的 ipv4.m3u / ipv6.m3u 合并成 App 用的 assets/playlist.m3u。

- 只保留"有地址"的条目（原文件里不少 #EXTINF 后面是空行，属于无地址条目）
- 同名频道优先用 IPv6（移动 IPv6 CDN 更稳），IPv4 作为补充
- 分组顺序：央视频道 → 卫视频道 → 数字频道 → 地方频道

用法： python3 tools/make_playlist.py /tmp/ngo5_ipv4.m3u /tmp/ngo5_ipv6.m3u
"""
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
GROUP_ORDER = ["央视频道", "卫视频道", "数字频道", "上海频道", "浙江频道", "内蒙频道", "地方频道", "未分组"]


def parse(path):
    lines = open(path, encoding="utf-8", errors="replace").read().splitlines()
    out, pend = [], None
    for line in lines:
        s = line.strip()
        if not s or s.startswith("#EXTM3U"):
            continue
        if s.startswith("#EXTINF"):
            g = re.search(r'group-title="([^"]*)"', s)
            comma = s.rfind(",")
            name = s[comma + 1:].strip() if comma > 0 else "(未命名)"
            pend = (name, g.group(1) if g else "未分组")
            continue
        if s.startswith("#"):
            continue
        if "://" in s and pend:
            out.append((pend[0], pend[1], s))
            pend = None
    return out


def main():
    files = sys.argv[1:]
    if not files:
        print("用法：make_playlist.py <m3u...>")
        return 1

    merged = []          # [(name, group, url)]
    seen = set()         # (group, name)
    for f in files:
        for name, group, url in parse(f):
            key = (group, name)
            if key in seen:
                continue
            seen.add(key)
            merged.append((name, group, url))

    def rank(item):
        g = item[1]
        return GROUP_ORDER.index(g) if g in GROUP_ORDER else len(GROUP_ORDER)

    merged.sort(key=rank)

    out_path = os.path.join(ROOT, "assets", "playlist.m3u")
    with open(out_path, "w", encoding="utf-8") as fp:
        fp.write("#EXTM3U\n")
        fp.write("# 来源：https://github.com/ngo5/IPTV （ipv4.m3u + ipv6.m3u 合并去重）\n")
        fp.write("# 想换成自己的源：覆盖 /sdcard/Android/data/com.mini.tvlive/files/playlist.m3u\n\n")
        cur = None
        for name, group, url in merged:
            if group != cur:
                fp.write("\n# ===== %s =====\n" % group)
                cur = group
            fp.write('#EXTINF:-1 group-title="%s",%s\n%s\n' % (group, name, url))

    groups = {}
    for name, group, url in merged:
        groups[group] = groups.get(group, 0) + 1
    print("输出:", out_path)
    print("频道总数:", len(merged))
    for g, c in groups.items():
        print("  %-6s %3d" % (g, c))
    return 0


if __name__ == "__main__":
    sys.exit(main())
