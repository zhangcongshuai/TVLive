package com.mini.tvlive;

import android.content.Context;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 源自检：批量探测播放列表里每个地址能不能拉起来，结果写到
 * /sdcard/Android/data/com.mini.tvlive/files/probe_result.txt
 *
 * 触发方式：在 files 目录下放一个 dotest.marker 文件，然后启动 App。
 * 目的：源会失效，定期跑一次自检就能知道哪些台还能用，把结果喂给
 * tools/make_playlist.py 重新生成列表即可。
 */
public class SelfTest {

    private static final int THREADS = 2;   // 并发别开大：这些源会因并发过高临时封 IP
    private static final int TIMEOUT_MS = 8000;

    public static boolean shouldRun(Context ctx) {
        File f = new File(ctx.getExternalFilesDir(null), "dotest.marker");
        return f.exists() || new File(ctx.getExternalFilesDir(null), "dodump.marker").exists();
    }

    public static void run(final Context ctx, final PlaylistLoader.Result data) {
        final File out = new File(ctx.getExternalFilesDir(null), "probe_result.txt");
        final File marker = new File(ctx.getExternalFilesDir(null), "dotest.marker");
        final File dumpMarker = new File(ctx.getExternalFilesDir(null), "dodump.marker");
        marker.delete();

        // dump 模式：把前几个频道的 m3u8 原文写出来，用来查"200 但播不了"的原因
        if (dumpMarker.exists()) {
            dumpMarker.delete();
            StringBuilder sb = new StringBuilder();
            int n = Math.min(3, data.all.size());
            for (int i = 0; i < n; i++) {
                Channel c = data.all.get(i);
                String u = c.currentUrl();
                if (u == null) continue;
                sb.append("===== ").append(c.name).append(" =====\n");
                sb.append("URL: ").append(u).append("\n");
                String body = body(u, 4096);
                sb.append(body).append("\n");
                // 再把第一段 ts 拉一下：播放列表能拿不代表分片能拿
                String seg = firstSegment(body, u);
                if (seg != null) {
                    sb.append("[SEG] ").append(seg).append("\n");
                    sb.append(segBody(seg, 3000)).append("\n");
                }
                sb.append("\n");
            }
            try {
                BufferedWriter bw = new BufferedWriter(new FileWriter(new File(
                        ctx.getExternalFilesDir(null), "dump.txt"), false));
                bw.write(sb.toString());
                bw.close();
            } catch (Throwable ignored) {
            }
            return;
        }

        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        final List<String> lines = new ArrayList<String>();
        final Object lock = new Object();

        for (final Channel c : data.all) {
            for (final String u : c.urls) {
                pool.execute(new Runnable() {
                    public void run() {
                        String res = probe(u);
                        synchronized (lock) {
                            lines.add(res + "|" + c.group + "|" + c.name + "|" + u);
                            flush(out, lines);
                        }
                    }
                });
            }
        }
        pool.shutdown();
        try {
            pool.awaitTermination(10, TimeUnit.MINUTES);
        } catch (InterruptedException ignored) {
        }
        synchronized (lock) {
            flush(out, lines);
            append(out, "# DONE " + lines.size());
        }
    }

    private static void flush(File out, List<String> lines) {
        BufferedWriter bw = null;
        try {
            bw = new BufferedWriter(new FileWriter(out, false));
            for (String l : lines) {
                bw.write(l);
                bw.write('\n');
            }
        } catch (Throwable ignored) {
        } finally {
            try {
                if (bw != null) bw.close();
            } catch (Throwable ignored) {
            }
        }
    }

    private static void append(File out, String line) {
        BufferedWriter bw = null;
        try {
            bw = new BufferedWriter(new FileWriter(out, true));
            bw.write(line);
            bw.write('\n');
        } catch (Throwable ignored) {
        } finally {
            try {
                if (bw != null) bw.close();
            } catch (Throwable ignored) {
            }
        }
    }

    /** 从播放列表里取第一段分片地址，相对路径按播放列表的目录解析 */
    private static String firstSegment(String playlist, String playlistUrl) {
        try {
            for (String line : playlist.split("\n")) {
                String s = line.trim();
                if (s.length() == 0 || s.startsWith("#")) continue;
                if (s.startsWith("http")) return s;
                int cut = playlistUrl.lastIndexOf('/');
                return playlistUrl.substring(0, cut + 1) + s;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** 拉分片只看状态码和类型，不下整段 */
    private static String segBody(String url, int n) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            conn.setRequestProperty("User-Agent", "okhttp/3.12.0");
            int code = conn.getResponseCode();
            String ct = conn.getContentType();
            InputStream in = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            byte[] buf = new byte[n];
            int total = 0;
            if (in != null) {
                while (total < n) {
                    int r = in.read(buf, total, n - total);
                    if (r <= 0) break;
                    total += r;
                }
            }
            return "  -> HTTP " + code + " type=" + ct + " bytes=" + total
                    + " head=" + (total > 0 ? new String(buf, 0, Math.min(8, total)) : "");
        } catch (Throwable t) {
            return "  -> FAIL " + t;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** 抓取正文前 n 字节 */
    private static String body(String url, int n) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            conn.setRequestProperty("User-Agent", "okhttp/3.12.0");
            int code = conn.getResponseCode();
            InputStream in = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            if (in == null) return "HTTP " + code + " (no body)";
            byte[] buf = new byte[n];
            int total = 0;
            while (total < n) {
                int r = in.read(buf, total, n - total);
                if (r <= 0) break;
                total += r;
            }
            return "HTTP " + code + "\n" + new String(buf, 0, Math.max(total, 0), "UTF-8");
        } catch (Throwable t) {
            return "FAIL " + t;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** 返回 状态码:类型，例如 200:m3u8 / 403:html / 000:timeout */
    private static String probe(String url) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", "okhttp/3.12.0");
            int code = conn.getResponseCode();
            InputStream in = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            byte[] buf = new byte[512];
            int n = (in == null) ? 0 : in.read(buf);
            String head = n > 0 ? new String(buf, 0, n, "UTF-8") : "";
            String type = "bin";
            if (head.startsWith("#EXTM3U")) type = "m3u8";
            else if (head.startsWith("#EXT-X")) type = "m3u8";
            else if (head.contains("<html") || head.contains("<!DOCTYPE")) type = "html";
            return code + ":" + type;
        } catch (Throwable t) {
            String name = t.getClass().getSimpleName();
            return "000:" + name;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }
}
