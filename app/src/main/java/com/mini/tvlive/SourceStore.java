package com.mini.tvlive;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/**
 * 源管理：内置列表打底 + 在线订阅增量补源 + 健康度持久化。
 *
 * 关键设计：在线更新**只给已有的台补源**，不新增台。
 * 因为公共订阅里塞着几万个世界各地的台，全灌进来只会让频道号乱跳、
 * 老人找不到 CCTV-1。台表以内置的为准，源则持续从线上刷新。
 */
public class SourceStore {

    private static final String PREF = "src_health";

    /**
     * 在线订阅。按可靠性排序，逐个尝试，能拉到哪个算哪个。
     *
     * GitHub raw 在国内经常不通，所以同一个列表给几个镜像；
     * iptv-org 走的是自家 CDN，通常比 raw 稳。
     * 注意：这里只补源不新增台，拉到世界各地的台也没用。
     */
    public static final String[] FEEDS = {
            "https://iptv-org.github.io/iptv/countries/cn.m3u",
            "https://cdn.jsdelivr.net/gh/vbskycn/iptv@master/tv/iptv4.m3u",
            "https://raw.gitmirror.com/vbskycn/iptv/master/tv/iptv4.m3u",
            "https://raw.githubusercontent.com/vbskycn/iptv/master/tv/iptv4.m3u",
    };

    private final Context ctx;

    public SourceStore(Context ctx) {
        this.ctx = ctx;
    }

    public PlaylistLoader.Result load() {
        PlaylistLoader.Result r = PlaylistLoader.load(ctx);
        applyHealth(r);
        return r;
    }

    // ------------------------------------------------------------ 健康度持久化

    private SharedPreferences sp() {
        return ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    private static String hkey(String name, String url) {
        return name + "|" + Integer.toHexString(url.hashCode());
    }

    private void applyHealth(PlaylistLoader.Result r) {
        SharedPreferences p = sp();
        long now = System.currentTimeMillis();
        for (Channel c : r.all) {
            for (Channel.Source s : c.sources) {
                String v = p.getString(hkey(c.name, s.url), null);
                if (v == null) continue;
                // 格式：fails,lastOkAt
                try {
                    String[] a = v.split(",");
                    s.fails = Integer.parseInt(a[0]);
                    s.lastOkAt = Long.parseLong(a[1]);
                } catch (Throwable ignored) {
                }
            }
            c.sortByHealth();
        }
    }

    public void persist(PlaylistLoader.Result r) {
        SharedPreferences.Editor e = sp().edit();
        for (Channel c : r.all) {
            for (Channel.Source s : c.sources) {
                // 没试过的源不写，省得把默认值也存一遍
                if (s.fails == 0 && s.lastOkAt == 0) continue;
                e.putString(hkey(c.name, s.url), s.fails + "," + s.lastOkAt);
            }
        }
        e.apply();
    }

    // ------------------------------------------------------------ 在线更新

    public interface Callback {
        /** 补到了新源就回调（在后台线程） */
        void onUpdated(int addedSources);
    }

    /**
     * 后台拉订阅，只给已有的台补源。
     * 返回补到的源数量；全部失败返回 0。
     */
    public int refresh(PlaylistLoader.Result r, Callback cb) {
        List<Channel> snapshot = new ArrayList<Channel>(r.all);
        int added = 0;
        StringBuilder cacheOut = new StringBuilder("#EXTM3U\n");

        for (String feed : FEEDS) {
            String text = http(feed);
            if (text == null || text.length() < 100) continue;

            PlaylistLoader.Result tmp = new PlaylistLoader.Result();
            PlaylistLoader.mergeText(tmp, text);

            for (Channel nc : tmp.all) {
                // 只认已有的台（名字完全一致，或去掉空格后一致）
                Channel target = find(snapshot, nc.name);
                if (target == null) continue;
                // 后台线程改 sources，UI 线程可能正在遍历同一个列表，加锁保护
                synchronized (target) {
                    for (Channel.Source s : nc.sources) {
                        if (!target.has(s.url)) {
                            target.addUrl(s.url);
                            added++;
                        }
                    }
                }
            }
        }

        // 把当前所有源写进缓存，下次启动直接可用（不需要再联网）
        for (Channel c : snapshot) {
            for (Channel.Source s : c.sources) {
                if (s.dead) continue;
                cacheOut.append("#EXTINF:-1 group-title=\"").append(c.group)
                        .append("\",").append(c.name).append('\n')
                        .append(s.url).append('\n');
            }
        }
        try {
            FileOutputStream fo = new FileOutputStream(new File(ctx.getFilesDir(), "cache.m3u"));
            fo.write(cacheOut.toString().getBytes("UTF-8"));
            fo.close();
        } catch (Throwable ignored) {
        }

        if (cb != null) cb.onUpdated(added);
        return added;
    }

    private static Channel find(List<Channel> list, String name) {
        String a = norm(name);
        for (Channel c : list) {
            if (norm(c.name).equals(a)) return c;
        }
        return null;
    }

    private static String norm(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == ' ' || ch == '\u3000' || ch == '-' || ch == '_') continue;
            b.append(Character.toLowerCase(ch));
        }
        return b.toString();
    }

    private static String http(String urlStr) {
        HttpURLConnection c = null;
        try {
            URL u = new URL(urlStr);
            c = (HttpURLConnection) u.openConnection();
            c.setConnectTimeout(6000);
            c.setReadTimeout(10000);
            c.setRequestProperty("User-Agent", MainActivity.UA);
            c.setInstanceFollowRedirects(true);
            int code = c.getResponseCode();
            if (code < 200 || code >= 300) return null;
            InputStream in = c.getInputStream();
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            int total = 0;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > 8 * 1024 * 1024) break;   // 单个订阅最多 8MB
                bo.write(buf, 0, n);
            }
            in.close();
            return new String(bo.toByteArray(), "UTF-8");
        } catch (Throwable t) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }
}
