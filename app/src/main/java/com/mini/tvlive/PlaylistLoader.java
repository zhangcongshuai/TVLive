package com.mini.tvlive;

import android.content.Context;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * M3U 解析 + 多列表合并。
 *
 * 规则：
 *  - 同名 + 同分组 = 同一个台的多个备用源，依次挂到 sources 里
 *  - 分组名做归一化（"CCTV"/"央视"/"央视频道" 统一成"央视频道"），
 *    否则同一个台会因为不同列表里的分组写法不同被拆成两个
 *  - 分组顺序固定：央视 → 卫视 → 地方 → 港澳台 → 其他，避免频道号每次启动乱跳
 */
public class PlaylistLoader {

    public static class Group {
        public final String name;
        public final List<Channel> channels = new ArrayList<Channel>();

        public Group(String name) {
            this.name = name;
        }
    }

    public static class Result {
        public final List<Channel> all = new ArrayList<Channel>();
        public final List<Group> groups = new ArrayList<Group>();
        public String source = "";
    }

    /** 分组顺序权重，小的排前面 */
    private static int groupWeight(String g) {
        if (g.contains("央视") || g.contains("CCTV") || g.contains("CGTN")) return 0;
        if (g.contains("卫视")) return 1;
        if (g.contains("港澳台") || g.contains("香港") || g.contains("台湾") || g.contains("澳门")) return 3;
        if (g.contains("地方") || g.contains("省内") || g.contains("城市")) return 2;
        if (g.contains("其他") || g.contains("未分组")) return 4;
        return 2;
    }

    private static String normalizeGroup(String raw, String name) {
        String g = raw == null ? "" : raw.trim();
        String n = name == null ? "" : name.toLowerCase();
        if (g.length() > 0) {
            if (g.contains("CCTV") || g.contains("央视")) return "央视频道";
            if (g.contains("CGTN")) return "央视频道";
            if (g.contains("卫视")) return "卫视频道";
            if (g.contains("港澳") || g.contains("香港") || g.contains("台湾") || g.contains("澳门"))
                return "港澳台";
        }
        // 分组为空或太杂，按台名猜
        if (n.contains("cctv") || n.contains("cgtn")) return "央视频道";
        if (n.contains("卫视")) return "卫视频道";
        if (g.length() > 0) return g;
        return "其他频道";
    }

    /** 从 InputStream 读全文，自动跳 BOM */
    private static String read(InputStream in) {
        StringBuilder sb = new StringBuilder();
        BufferedReader br = null;
        try {
            br = new BufferedReader(new InputStreamReader(in, "UTF-8"));
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line).append('\n');
            }
        } catch (Throwable ignored) {
        } finally {
            try {
                if (br != null) br.close();
            } catch (Throwable ignored) {
            }
        }
        return sb.toString();
    }

    /**
     * 把一段 m3u 文本合并进 result。
     * 已存在的台只追加新源，不会重复建台；新台按出现顺序编号。
     */
    public static void mergeText(Result r, String text) {
        if (text == null || text.length() < 8) return;

        Map<String, Channel> byKey = new LinkedHashMap<String, Channel>();
        for (Channel c : r.all) {
            byKey.put(c.group + "\u0001" + c.name, c);
        }

        String pendingName = null;
        String pendingGroup = "";

        String[] lines = text.replace("\r\n", "\n").split("\n");
        for (String raw : lines) {
            String line = raw.trim();
            if (line.length() == 0) continue;

            if (line.startsWith("#EXTINF")) {
                // 名字是最后一个逗号之后的部分
                int comma = line.lastIndexOf(',');
                pendingName = (comma >= 0 && comma < line.length() - 1)
                        ? line.substring(comma + 1).trim() : "";
                pendingGroup = attr(line, "group-title");
                if (pendingName.length() == 0) {
                    String tn = attr(line, "tvg-name");
                    pendingName = tn;
                }
                continue;
            }
            if (line.startsWith("#")) continue;

            if (!isPlayableUrl(line)) continue;
            if (pendingName == null || pendingName.length() == 0) continue;

            String group = normalizeGroup(pendingGroup, pendingName);
            String key = group + "\u0001" + pendingName;

            Channel c = byKey.get(key);
            if (c == null) {
                c = new Channel(r.all.size() + 1, pendingName, group);
                byKey.put(key, c);
                r.all.add(c);
            }
            c.addUrl(line);
            // 不清空 pendingName：紧凑写法下一个 EXTINF 后连写多个地址都归这个台
        }

        rebuildGroups(r);
    }

    private static void rebuildGroups(Result r) {
        Map<String, Group> map = new LinkedHashMap<String, Group>();
        List<String> order = new ArrayList<String>();
        for (Channel c : r.all) {
            if (!map.containsKey(c.group)) {
                map.put(c.group, new Group(c.group));
                order.add(c.group);
            }
            map.get(c.group).channels.add(c);
        }
        // 按权重排序分组
        final Map<String, Integer> w = new LinkedHashMap<String, Integer>();
        for (String g : order) w.put(g, groupWeight(g));
        java.util.Collections.sort(order, new java.util.Comparator<String>() {
            public int compare(String a, String b) {
                int d = Integer.compare(w.get(a), w.get(b));
                return d != 0 ? d : a.compareTo(b);
            }
        });

        r.groups.clear();
        for (String g : order) {
            Group grp = map.get(g);
            sortChannels(grp.channels);
            r.groups.add(grp);
        }

        // 全局台号：按"分组顺序 + 组内顺序"重新编，跟列表里看到的顺序一致。
        // 不能沿用解析顺序 —— m3u 里行序是乱的，会出现"002 是 CCTV-10"这种事，
        // 老人按数字键就跳不到想看的台。
        r.all.clear();
        int no = 1;
        for (Group grp : r.groups) {
            for (Channel c : grp.channels) {
                c.no = no++;
                r.all.add(c);
            }
        }
    }

    /** 央视按数字，卫视按常见排序，其余按名字 */
    private static void sortChannels(List<Channel> list) {
        final java.util.Map<String, Integer> satOrder = new java.util.HashMap<String, Integer>();
        for (int i = 0; i < SATELLITE_ORDER.length; i++) satOrder.put(SATELLITE_ORDER[i], i);

        java.util.Collections.sort(list, new java.util.Comparator<Channel>() {
            public int compare(Channel a, Channel b) {
                int ka = cctvNum(a.name), kb = cctvNum(b.name);
                if (ka != kb) return Integer.compare(ka, kb);
                Integer sa = satOrder.get(a.name), sb = satOrder.get(b.name);
                if (sa != null || sb != null) {
                    return Integer.compare(sa == null ? 999 : sa, sb == null ? 999 : sb);
                }
                return a.name.compareTo(b.name);
            }
        });
    }

    /** CCTV-3 → 30；CCTV-5+ → 56（排在 5 和 6 之间）；CCTV-4K/8K 和 CGTN 排到最后 */
    private static int cctvNum(String name) {
        // 连字符和空格都要去掉，"CCTV-1" 去掉后才是 "CCTV1"
        String u = name.toUpperCase().replace(" ", "").replace("-", "").replace("_", "");
        if (u.startsWith("CGTN")) return 900;
        if (u.startsWith("CCTV")) {
            if (u.contains("4K")) return 800;
            if (u.contains("8K")) return 801;
            if (u.contains("5+")) return 56;      // 排在 5 和 6 之间
            int i = 4;   // 跳过 "CCTV"
            int n = 0, digits = 0;
            while (i < u.length() && Character.isDigit(u.charAt(i))) {
                n = n * 10 + (u.charAt(i) - '0');
                i++;
                digits++;
            }
            if (digits > 0) return n * 10;
        }
        return 500;   // 非央视
    }

    /** 卫视常见顺序，编号跳台时好记 */
    private static final String[] SATELLITE_ORDER = {
            "北京卫视", "东方卫视", "浙江卫视", "湖南卫视", "江苏卫视", "广东卫视",
            "深圳卫视", "安徽卫视", "山东卫视", "天津卫视", "四川卫视", "湖北卫视",
            "河南卫视", "河北卫视", "山西卫视", "辽宁卫视", "吉林卫视", "黑龙江卫视",
            "江西卫视", "东南卫视", "广西卫视", "云南卫视", "贵州卫视", "甘肃卫视",
            "青海卫视", "宁夏卫视", "新疆卫视", "西藏卫视", "内蒙古卫视", "陕西卫视",
            "重庆卫视", "海南卫视", "延边卫视", "大湾区卫视", "厦门卫视", "海峡卫视",
            "康巴卫视", "安多卫视", "农林卫视", "兵团卫视", "三沙卫视",
    };

    private static boolean isPlayableUrl(String s) {
        String l = s.toLowerCase();
        return l.startsWith("http://") || l.startsWith("https://") || l.startsWith("rtmp://")
                || l.startsWith("rtsp://") || l.startsWith("udp://");
    }

    private static String attr(String line, String key) {
        String k = key + "=\"";
        int i = line.indexOf(k);
        if (i < 0) return "";
        int start = i + k.length();
        int end = line.indexOf('"', start);
        if (end < 0) return "";
        return line.substring(start, end).trim();
    }

    // ---------------------------------------------------------------- 加载入口

    /**
     * 加载顺序：
     *  1) 用户放在 files/playlist.m3u 的自定义列表（最高优先，覆盖一切）
     *  2) 缓存下来的在线列表 files/cache.m3u
     *  3) APK 内置 assets/playlist.m3u
     *
     * 三者会合并：自定义列表里的台排最前，然后是缓存，最后是内置兜底。
     */
    public static Result load(Context ctx) {
        Result r = new Result();

        String custom = readFile(new File(ctx.getExternalFilesDir(null), "playlist.m3u"));
        String cache = readFile(new File(ctx.getFilesDir(), "cache.m3u"));
        String builtin = null;
        try {
            builtin = read(ctx.getAssets().open("playlist.m3u"));
        } catch (Throwable ignored) {
        }

        if (custom != null && custom.length() > 20) {
            mergeText(r, custom);
            r.source = "自定义列表";
        }
        if (cache != null && cache.length() > 20) {
            mergeText(r, cache);
            if (r.source.length() == 0) r.source = "在线更新";
        }
        if (builtin != null && builtin.length() > 20) {
            mergeText(r, builtin);
            if (r.source.length() == 0) r.source = "内置列表";
        }
        return r;
    }

    private static String readFile(File f) {
        if (f == null || !f.exists() || f.length() == 0) return null;
        try {
            return read(new FileInputStream(f));
        } catch (Throwable t) {
            return null;
        }
    }
}
