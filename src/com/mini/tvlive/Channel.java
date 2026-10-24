package com.mini.tvlive;

import java.util.ArrayList;
import java.util.List;

/** 一个频道：编号（从 1 开始）、名称、所属分组、多个备用播放地址 */
public class Channel {

    public final int no;
    public final String name;
    public final String group;
    public final List<String> urls = new ArrayList<String>();

    /** 当前正在用第几个地址 */
    public int urlIdx = 0;

    public Channel(int no, String name, String group) {
        this.no = no;
        this.name = name;
        this.group = group;
    }

    public String currentUrl() {
        if (urls.isEmpty()) return null;
        if (urlIdx < 0 || urlIdx >= urls.size()) urlIdx = 0;
        return urls.get(urlIdx);
    }

    public boolean hasNextUrl() {
        return urlIdx + 1 < urls.size();
    }

    /** 列表里显示的名字，带 3 位编号，方便老人按数字直接跳 */
    public String display() {
        return String.format("%03d  %s", no, name);
    }
}
