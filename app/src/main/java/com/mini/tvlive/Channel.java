package com.mini.tvlive;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * 一个频道：编号、名称、分组，以及多个播放源。
 *
 * 每个源独立记录健康度（连续失败次数 / 上次成功时间 / 是否硬判死），
 * 换台时按健康度排序取最好的那个 —— 这样"上次能播的源"下次会被优先用，
 * 而不是每次都从列表第一个开始试。
 */
public class Channel {

    public static class Source {
        public final String url;

        /** 连续失败次数，播放成功满 10 秒清零 */
        public int fails = 0;
        /** 最近一次失败时间，用来做冷却（软失败可能只是网关限流） */
        public long lastFailAt = 0;
        /** 最近一次真的出画面的时间 */
        public long lastOkAt = 0;
        /** 硬死：403/404/格式不支持这类，重试也没意义，本次运行内不再用 */
        public boolean dead = false;
        /** 硬死原因，显示给用户看 */
        public String deadReason = "";
        /** 播一会儿就 EOF 的次数（伪直播/缓存片段） */
        public int shortHits = 0;

        public Source(String url) {
            this.url = url;
        }

        /** 软失败冷却中（2 分钟内失败过 2 次以上就先不碰它） */
        boolean cooling(long now) {
            return fails >= 2 && now - lastFailAt < 120_000L;
        }

        int rank(long now) {
            if (dead) return 1000;
            // 短命源的权重比普通失败重得多：宁可换一个没试过的，也别再看到同一段
            int r = fails * 10 + shortHits * 40;
            if (cooling(now)) r += 100;
            // 最近成功过的往前排
            if (now - lastOkAt < 3_600_000L) r -= 50;
            return r;
        }
    }

    /** 台号从 1 开始。由 PlaylistLoader 排序后统一分配，不是 final */
    public int no;
    public final String name;
    public final String group;
    public final List<Source> sources = new ArrayList<Source>();

    /** 当前正在用第几个源 */
    public int idx = 0;

    public Channel(int no, String name, String group) {
        this.no = no;
        this.name = name;
        this.group = group;
    }

    public void addUrl(String url) {
        for (Source s : sources) {
            if (s.url.equals(url)) return;
        }
        sources.add(new Source(url));
    }

    public boolean has(String url) {
        for (Source s : sources) {
            if (s.url.equals(url)) return true;
        }
        return false;
    }

    /** 按健康度排序，把最可能成功的排到前面，并把 idx 指向它 */
    public void sortByHealth() {
        final long now = System.currentTimeMillis();
        Collections.sort(sources, new Comparator<Source>() {
            public int compare(Source a, Source b) {
                return Integer.compare(a.rank(now), b.rank(now));
            }
        });
        idx = 0;
        skipDead();
    }

    private void skipDead() {
        while (idx < sources.size() && sources.get(idx).dead) idx++;
    }

    public Source current() {
        if (sources.isEmpty()) return null;
        if (idx < 0) idx = 0;
        if (idx >= sources.size()) idx = sources.size() - 1;
        return sources.get(idx);
    }

    public String currentUrl() {
        Source s = current();
        return s == null ? null : s.url;
    }

    /** 还有没试过的源吗（跳过硬死的） */
    public boolean hasNext() {
        for (int i = idx + 1; i < sources.size(); i++) {
            if (!sources.get(i).dead) return true;
        }
        return false;
    }

    public boolean next() {
        for (int i = idx + 1; i < sources.size(); i++) {
            if (!sources.get(i).dead) {
                idx = i;
                return true;
            }
        }
        return false;
    }

    /** 从头再来一遍（只挑没硬死的） */
    public boolean restart() {
        idx = -1;
        return next();
    }

    /** 有没有至少一个没硬判死的源 */
    public boolean alive() {
        for (Source s : sources) {
            if (!s.dead) return true;
        }
        return false;
    }

    public int aliveCount() {
        int n = 0;
        for (Source s : sources) {
            if (!s.dead) n++;
        }
        return n;
    }

    /** 播放成功：清空失败计数，记下时间 */
    public void markOk() {
        Source s = current();
        if (s == null) return;
        s.fails = 0;
        s.lastOkAt = System.currentTimeMillis();
    }

    /** 播放失败：软失败累加计数并进入冷却 */
    public void markFail() {
        Source s = current();
        if (s == null) return;
        s.fails++;
        s.lastFailAt = System.currentTimeMillis();
    }

    /** 硬判死：本次运行内不再尝试这个源 */
    public void markDead(String reason) {
        Source s = current();
        if (s == null) return;
        s.dead = true;
        s.deadReason = reason == null ? "" : reason;
        s.lastFailAt = System.currentTimeMillis();
    }

    /**
     * 短命源：能出画但播一会儿就 EOF（tsfile 那类网关的典型症状，
     * 每次打开都是同一段缓存内容）。不清零、不判死，但狠狠往后排，
     * 免得下次换台又优先挑到它、又看到同一段。
     */
    public void markShort() {
        Source s = current();
        if (s == null) return;
        s.fails += 4;
        s.lastFailAt = System.currentTimeMillis();
        s.shortHits++;
    }

    /** 列表里显示的名字，带 3 位编号，方便老人按数字直接跳 */
    public String display() {
        return String.format("%03d  %s", no, name);
    }

    /** 信息条上的源状态，比如 "源 2/3" */
    public String sourceLabel() {
        return "源 " + (idx + 1) + "/" + sources.size();
    }
}
