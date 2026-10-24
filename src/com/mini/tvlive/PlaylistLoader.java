package com.mini.tvlive;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 播放列表加载：优先读外部可替换的 m3u，读不到再用 APK 内置的示例列表。
 *
 * 外部路径：/sdcard/Android/data/com.mini.tvlive/files/playlist.m3u
 * 换台不用重装，把这个文件覆盖掉就生效。
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

    public static Result load(Context ctx) {
        String text = null;
        String source = "";
        try {
            File f = new File(ctx.getExternalFilesDir(null), "playlist.m3u");
            if (f.exists() && f.length() > 0) {
                text = read(new FileInputStream(f));
                source = f.getAbsolutePath();
            }
        } catch (Throwable ignored) {
        }
        if (text == null) {
            try {
                text = read(ctx.getAssets().open("playlist.m3u"));
                source = "内置示例列表";
            } catch (Throwable ignored) {
            }
        }
        Result r = parse(text);
        r.source = source;
        return r;
    }

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
     * 标准 M3U：#EXTINF:-1 tvg-name="x" group-title="y",显示名  下一行是地址
     * 同名同分组的多条记录视为同一个台的多个备用源，播放失败会自动换下一个。
     *
     * 同时兼容紧凑写法：一个 #EXTINF 下面连着写多个地址，这些地址都归同一个台，
     * 依次作为主源和备用源（pendingName 只在遇到下一个 #EXTINF 时才更新）。
     */
    public static Result parse(String text) {
        Result r = new Result();
        if (text == null) return r;

        Map<String, Group> map = new LinkedHashMap<String, Group>();
        Map<String, Channel> byKey = new LinkedHashMap<String, Channel>();
        String pendingName = null;
        String pendingGroup = "";

        String[] lines = text.split("\\r?\\n");
        for (String raw : lines) {
            String line = raw.trim();
            if (line.length() == 0) continue;

            if (line.startsWith("#EXTINF")) {
                int comma = line.lastIndexOf(',');
                pendingName = comma >= 0 && comma < line.length() - 1
                        ? line.substring(comma + 1).trim() : "";
                pendingGroup = attr(line, "group-title");
                continue;
            }
            if (line.startsWith("#")) continue;

            // 到了地址行
            if (line.startsWith("http://") || line.startsWith("https://")
                    || line.startsWith("rtmp://") || line.startsWith("rtsp://")
                    || line.startsWith("udp://") || line.startsWith("cvod://")) {
                String name = (pendingName == null || pendingName.length() == 0)
                        ? ("频道 " + (r.all.size() + 1)) : pendingName;
                String group = (pendingGroup == null || pendingGroup.length() == 0)
                        ? "未分组" : pendingGroup;

                String key = group + "\u0001" + name;
                Channel c = byKey.get(key);
                if (c == null) {
                    Group g = map.get(group);
                    if (g == null) {
                        g = new Group(group);
                        map.put(group, g);
                        r.groups.add(g);
                    }
                    c = new Channel(r.all.size() + 1, name, group);
                    byKey.put(key, c);
                    r.all.add(c);
                    g.channels.add(c);
                }
                if (!c.urls.contains(line)) c.urls.add(line);

                // 不清空 pendingName：同一个 #EXTINF 下面的连续地址都算这个台的备用源
            }
        }
        return r;
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
}
