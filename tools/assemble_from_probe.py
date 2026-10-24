#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
把电视上跑出来的自检结果（probe_result.txt）汇总成最终 assets/playlist.m3u。

- 只保留 200 且内容是 m3u8 的条目
- 按频道名归并，一个台一个地址（优先 IPv4 的 tsfile 网关，其次 IPv6 OTT）
- 顺序：央视频道 → 卫视频道 → 地方频道

用法： python3 tools/assemble_from_probe.py probe1.txt probe2.txt probe3.txt
"""
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

CCTV_ORDER = ["CCTV-1", "CCTV-2", "CCTV-3", "CCTV-4", "CCTV-5", "CCTV-5+", "CCTV-6", "CCTV-7",
              "CCTV-8", "CCTV-9", "CCTV-10", "CCTV-11", "CCTV-12", "CCTV-13", "CCTV-14",
              "CCTV-15", "CCTV-16", "CCTV-17"]

# 别名 → 标准名
ALIAS = {
    "CCTV1": "CCTV-1 综合", "CCTV-1": "CCTV-1 综合", "CCTV-1 综合": "CCTV-1 综合",
    "CCTV2": "CCTV-2 财经", "CCTV-2": "CCTV-2 财经",
    "CCTV3": "CCTV-3 综艺", "CCTV-3": "CCTV-3 综艺",
    "CCTV4": "CCTV-4 中文国际", "CCTV-4": "CCTV-4 中文国际", "CCTV-4 中文国际": "CCTV-4 中文国际",
    "CCTV5": "CCTV-5 体育", "CCTV-5": "CCTV-5 体育",
    "CCTV5PLUS": "CCTV-5+ 体育赛事", "CCTV5+": "CCTV-5+ 体育赛事",
    "CCTV6": "CCTV-6 电影", "CCTV-6": "CCTV-6 电影",
    "CCTV7": "CCTV-7 国防军事", "CCTV-7": "CCTV-7 国防军事",
    "CCTV8": "CCTV-8 电视剧", "CCTV-8": "CCTV-8 电视剧",
    "CCTV9": "CCTV-9 纪录", "CCTV-9": "CCTV-9 纪录",
    "CCTV10": "CCTV-10 科教", "CCTV-10": "CCTV-10 科教",
    "CCTV11": "CCTV-11 戏曲", "CCTV-11": "CCTV-11 戏曲",
    "CCTV12": "CCTV-12 社会与法", "CCTV-12": "CCTV-12 社会与法",
    "CCTV13": "CCTV-13 新闻", "CCTV-13": "CCTV-13 新闻",
    "CCTV14": "CCTV-14 少儿", "CCTV-14": "CCTV-14 少儿",
    "CCTV15": "CCTV-15 音乐", "CCTV-15": "CCTV-15 音乐",
    "CCTV16": "CCTV-16 奥林匹克", "CCTV-16": "CCTV-16 奥林匹克",
    "CCTV17": "CCTV-17 农业农村", "CCTV-17": "CCTV-17 农业农村",
}


def norm(name):
    n = re.sub(r"\s+", "", name).upper()
    n = n.replace("高清", "").replace("超清", "").replace("HD", "")
    return n


def classify(name):
    n = norm(name)
    if n in ALIAS:
        return "央视频道", ALIAS[n]
    m = re.match(r"^CCTV-?(\d{1,2})\+?$", n)
    if m:
        return "央视频道", ALIAS.get(n, name)
    if "卫视" in name:
        if "4K" in name:          # 4K 变体老盒子解不动，跳过
            return None, None
        return "卫视频道", name.replace("高清", "").strip()
    if any(k in name for k in ("CETV", "中国教育")):
        return "央视频道", name.strip()
    if any(k in name for k in ("CGTN", "CHC", "央视台球")):
        return "其他频道", name.strip()
    return "其他频道", name.strip()


PLACEHOLDER = re.compile(r"^(ts\d+|河北cp\d+)$")


def score(url):
    # 越小越优先：tsfile 网关（IPv4、延迟低）> 北京 OTT（IPv6）> 其他
    if "219.159.147.195" in url:
        return 0
    if "2409:8087:8:21::18" in url:
        return 1
    if "cztv.com" in url:
        return 2
    return 3


def harvest_extra(path, picked, limit=3):
    """从原始列表里补备用源：同一个台挂不同 CDN 的地址，主源被封时能自动换"""
    try:
        lines = open(path, encoding="utf-8", errors="replace").read().splitlines()
    except Exception:
        return
    name = None
    for i, L in enumerate(lines):
        s = L.strip()
        if s.startswith("#EXTINF"):
            comma = s.rfind(",")
            name = s[comma + 1:].strip() if comma > 0 else None
            continue
        if not s.startswith("http") or name is None:
            continue
        url = clean(s)
        name_ = name
        name = None
        if url is None:
            continue
        group, canon = classify(name_)
        if canon is None or PLACEHOLDER.match(name_.strip()):
            continue
        slot = picked.get(canon)
        if slot is None:
            continue
        if len(slot[1]) < limit and all(u != url for _, u in slot[1]):
            slot[1].append((score(url), url))


def clean(url):
    """修掉源列表里常见的脏数据：多个地址用 ; 拼接、带过期时间戳的签名"""
    url = url.split(";")[0].strip()
    if "auth_key=" in url:      # 央视官方签名地址会过期，适合临时抓取不适合长期内置
        return None
    if not url.startswith("http"):
        return None
    return url


def main():
    argv = sys.argv[1:]
    extra = []
    if "--extra" in argv:
        i = argv.index("--extra")
        extra = argv[i + 1:]
        argv = argv[:i]
    files = argv
    picked = {}
    for f in files:
        if not os.path.exists(f):
            continue
        for line in open(f, encoding="utf-8", errors="replace"):
            p = line.rstrip("\n").split("|")
            if len(p) < 4:
                continue
            st, _group, name, url = p[0], p[1], p[2], p[3]
            if not st.startswith("200") or not st.endswith("m3u8"):
                continue
            url = clean(url)
            if url is None:
                continue
            group, canon = classify(name)
            if canon is None or PLACEHOLDER.match(name.strip()):
                continue
            slot = picked.setdefault(canon, (group, []))
            if all(u != url for _, u in slot[1]) and len(slot[1]) < 3:
                slot[1].append((score(url), url))

    order = {"央视频道": 0, "卫视频道": 1, "地方频道": 2, "其他频道": 3}

    # 补备用源：同台不同 CDN，主源被封/失效时 App 会自动换下一个
    for f in extra:
        harvest_extra(f, picked)

    def key(item):
        canon, (group, urls) = item
        if canon.startswith("CCTV-"):
            m = re.match(r"CCTV-(\d+)", canon)
            return (0, int(m.group(1)) if m else 99, 0, canon)
        if group == "央视频道":
            return (0, 90, 0, canon)          # CETV 等排在 CCTV 之后
        return (order.get(group, 4), 0, 0, canon)

    items = sorted(picked.items(), key=key)

    out = os.path.join(ROOT, "assets", "playlist.m3u")
    with open(out, "w", encoding="utf-8") as fp:
        fp.write("#EXTM3U\n")
        fp.write("# 频道源由电视实测筛选生成（tools/assemble_from_probe.py）\n")
        fp.write("# 同名多行 = 同一个台的多个备用源，App 播不动会自动换下一个\n")
        fp.write("# 换源：覆盖 /sdcard/Android/data/com.mini.tvlive/files/playlist.m3u 后重启 App\n\n")
        cur = None
        for canon, (group, urls) in items:
            if group != cur:
                fp.write("\n# ===== %s =====\n" % group)
                cur = group
            for _, url in sorted(urls):
                fp.write('#EXTINF:-1 group-title="%s",%s\n%s\n' % (group, canon, url))

    cnt = {}
    total_urls = 0
    for canon, (group, urls) in items:
        cnt[group] = cnt.get(group, 0) + 1
        total_urls += len(urls)
    print("输出:", out)
    print("频道总数:", len(items), cnt, "地址总数:", total_urls)
    for canon, (group, urls) in items:
        print("  %-20s %d 源  %s" % (canon, len(urls), urls[0][1][:56]))
    return 0


if __name__ == "__main__":
    sys.exit(main())
