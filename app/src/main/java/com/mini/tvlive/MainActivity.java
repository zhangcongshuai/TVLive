package com.mini.tvlive;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy;
import androidx.media3.ui.AspectRatioFrameLayout;
import androidx.media3.ui.PlayerView;

import java.util.ArrayList;
import java.util.List;

/**
 * 极简电视直播 v2
 *
 * 交互没变：打开就播 / ▲▼ 换台 / OK 列表 / 数字键跳台。
 * 变的是"播不动的时候会发生什么"：
 *
 *  1. 播放器从系统 MediaPlayer 换成 ExoPlayer，HLS 直播窗口滚动时不会再崩
 *  2. 播放中断（onPlayerError）、流被掐（STATE_ENDED）、画面卡死（position 不动）
 *     三种情况都能自愈：自动换下一个源
 *  3. 每个源独立记健康度，上次能播的源下次优先用；403/404 直接判死不再浪费时间
 *  4. 一个台全挂就自动跳下一个台，不让老人对着黑屏
 */
public class MainActivity extends Activity {

    static final String UA = "okhttp/3.12.0";

    private static final int C_ACCENT = 0xFF2F6BFF;
    private static final int C_CURRENT = 0xFFFFC53D;
    private static final int C_DIM = 0xFFAEBACB;
    private static final String PREF = "tv";
    /** 记台名而不是下标：列表顺序会因为补源/排序变化，下标对不上 */
    private static final String KEY_NAME = "last_name";
    private static final String KEY_INDEX = "last_index";

    /** 卡死判定：连续多少次采样 position 不动（每次 3 秒） */
    private static final int STALL_LIMIT = 4;
    /** 首帧看门狗：prepare 之后多少毫秒还没出画面就判这个源不行 */
    private static final int FIRST_FRAME_TIMEOUT = 12000;
    /** 一个台所有源都失败后，最多自动跳几个台 */
    private static final int AUTO_HOP_LIMIT = 3;

    private FrameLayout root;
    private PlayerView videoView;
    private LinearLayout bar;
    private TextView numText;
    private TextView nameText;
    private TextView tipText;
    private TextView badgeText;

    private FrameLayout panel;
    private ScrollView catScroll;
    private ScrollView chScroll;
    private LinearLayout catBox;
    private LinearLayout chBox;

    private ExoPlayer player;
    private SourceStore store;
    private PlaylistLoader.Result data;
    private final List<PlaylistLoader.Group> cats = new ArrayList<PlaylistLoader.Group>();

    private int index = 0;
    private int autoHop = 0;
    private int retryAll = 0;
    /** 当前源原地重连了几次（换源/换台时清零） */
    private int reconnect = 0;

    private boolean panelVisible = false;
    private boolean playing = false;
    private boolean ready = false;
    private long lastPos = -1;
    private int stall = 0;
    private boolean markedOk = false;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final StringBuilder numBuf = new StringBuilder();

    // ---------------------------------------------------------------- 生命周期

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        hideSystemUi();

        store = new SourceStore(this);
        data = store.load();

        buildUi();
        buildPlayer();

        if (data.all.isEmpty()) {
            showEmpty();
            return;
        }

        SharedPreferences sp = getSharedPreferences(PREF, MODE_PRIVATE);
        index = sp.getInt(KEY_INDEX, 0);
        String lastName = sp.getString(KEY_NAME, null);
        if (lastName != null) {
            for (int i = 0; i < data.all.size(); i++) {
                if (lastName.equals(data.all.get(i).name)) {
                    index = i;
                    break;
                }
            }
        }
        if (index < 0 || index >= data.all.size()) index = 0;

        fillCats();
        updateBar();
        tipText.setText("正在打开…");

        // 后台补源，不阻塞播放
        new Thread(() -> {
            int added = store.refresh(data, null);
            if (added > 0) {
                handler.post(() -> {
                    fillCats();
                    tipText.setText("已更新 " + added + " 个备用源");
                    showBar();
                });
            }
        }).start();
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (data == null || data.all.isEmpty()) return;
        // 从后台回来：已经装载过节目就直接续播，别重新 prepare 一遍
        // （重新 prepare 会让画面从头缓冲，看着像"卡了"）
        if (player != null && player.getMediaItemCount() > 0
                && player.getPlaybackState() != Player.STATE_IDLE) {
            player.setPlayWhenReady(true);
            // onStop 里清掉了所有回调，卡死监控要重新挂上
            if (player.getPlaybackState() == Player.STATE_READY) {
                ready = true;
                startStallWatch();
            } else {
                armFirstFrameWatchdog();
            }
            showBar();
        } else {
            startChannel(false);
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        handler.removeCallbacksAndMessages(null);
        if (player != null) player.pause();
        if (data != null) store.persist(data);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (player != null) {
            player.release();
            player = null;
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemUi();
    }

    private void hideSystemUi() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    // ---------------------------------------------------------------- 播放器

    private void buildPlayer() {
        DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
                .setUserAgent(UA)
                .setConnectTimeoutMs(6000)
                .setReadTimeoutMs(9000)
                .setAllowCrossProtocolRedirects(true);

        DefaultLoadControl loadControl = new DefaultLoadControl.Builder()
                .setBufferDurationsMs(15000, 60000, 1500, 3000)
                .build();

        DefaultRenderersFactory renderers = new DefaultRenderersFactory(this)
                .setEnableDecoderFallback(true)          // 硬解失败自动换解码器
                .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON);

        // 只让 ExoPlayer 内部重试 1 次：默认策略在源已死时会反复重试，
        // 表现为"正在打开"卡几十秒。重试交给自己的换源逻辑更快。
        DefaultMediaSourceFactory msf = new DefaultMediaSourceFactory(http)
                .setLoadErrorHandlingPolicy(new DefaultLoadErrorHandlingPolicy(1));

        player = new ExoPlayer.Builder(this)
                .setMediaSourceFactory(msf)
                .setLoadControl(loadControl)
                .setRenderersFactory(renderers)
                .build();

        // 交给 PlayerView 自己管 surface 生命周期，比自己 setVideoSurfaceView 稳
        videoView.setPlayer(player);
        player.setPlayWhenReady(true);
        player.addListener(listener);
    }

    private final Player.Listener listener = new Player.Listener() {
        @Override
        public void onPlaybackStateChanged(int state) {
            if (state == Player.STATE_READY) {
                ready = true;
                playing = true;
                stall = 0;
                lastPos = -1;
                handler.removeCallbacks(firstFrameRun);
                tipText.setText("播放中");
                showBar();
                startStallWatch();
            } else if (state == Player.STATE_BUFFERING) {
                if (ready) tipText.setText("缓冲中…");
            } else if (state == Player.STATE_ENDED) {
                // 直播流不该"播完"。播完只有两种可能：
                //   1) 这是个伪直播 / 缓存片段（就是"每次都是同一个片段"的根源）
                //   2) 直播真的结束了
                // 无论哪种都不能原地重连（重连只会再播一遍同一段），直接换源。
                long played = player == null ? 0 : player.getCurrentPosition();
                Channel c = current();
                if (c != null && played < 90_000L) {
                    c.markShort();      // 记住这个源是"播一会儿就完"的，往后排
                }
                onSourceDead(played < 90_000L ? "这个源只有一小段" : "这个源播完了", false, false);
            }
        }

        @Override
        public void onPlayerError(PlaybackException e) {
            boolean hard = isHardError(e);
            String reason = shortReason(e);
            // 软错误（超时/断连）先原地重连一次：直播网关掐一下是常事，
            // 重连往往就接上了，比换到一个陌生的源更靠谱
            onSourceDead(reason, hard, !hard);
        }
    };

    private static boolean isHardError(PlaybackException e) {
        switch (e.errorCode) {
            case PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS:
            case PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND:
            case PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE:
            case PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED:
            case PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED:
            case PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED:
                return true;
            default:
                return false;
        }
    }

    private static String shortReason(PlaybackException e) {
        switch (e.errorCode) {
            case PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS:
                return "源失效(HTTP)";
            case PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND:
                return "源不存在";
            case PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT:
                return "连接超时";
            case PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED:
                return "连不上";
            case PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED:
            case PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED:
                return "格式不支持";
            case PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED:
                return "解码器不支持";
            case PlaybackException.ERROR_CODE_DECODING_FAILED:
                return "解码失败";
            default:
                return "播放出错";
        }
    }

    /** 播放中每秒检查一次：position 不动就是卡死了 */
    private void startStallWatch() {
        handler.removeCallbacks(stallRun);
        handler.postDelayed(stallRun, 3000);
    }

    /**
     * 首帧看门狗。
     * prepare 之后如果源是"连得上但不出画面"（比如网关返回空 playlist、
     * 分片 404 但 ExoPlayer 还在内部重试），onPlayerError 可能很久都不来，
     * 界面就一直停在"正在打开…"。这个定时器兜住这种情况。
     */
    private void armFirstFrameWatchdog() {
        handler.removeCallbacks(firstFrameRun);
        handler.postDelayed(firstFrameRun, FIRST_FRAME_TIMEOUT);
    }

    private final Runnable firstFrameRun = new Runnable() {
        public void run() {
            if (!ready) {
                // 12 秒连首帧都出不来，这个源基本可以判死。
                // 把 retryAll 顶满，跳过"整轮重试"，直接换源或跳台 ——
                // 否则老人要对着黑屏等 24 秒以上。
                retryAll = 2;
                onSourceDead("打开超时", false, false);
            }
        }
    };

    private final Runnable stallRun = new Runnable() {
        public void run() {
            if (player == null || !ready) return;
            long pos = player.getCurrentPosition();
            if (pos == lastPos) {
                stall++;
            } else {
                stall = 0;
                lastPos = pos;
                // 画面真的在走，才认为这个源是可用的
                if (!markedOk && pos > 2000) {
                    markedOk = true;
                    Channel c = current();
                    if (c != null) {
                        c.markOk();
                        autoHop = 0;
                        retryAll = 0;
                        reconnect = 0;
                    }
                }
            }
            if (stall >= STALL_LIMIT) {
                stall = 0;
                lastPos = -1;
                onSourceDead("画面卡住", false, true);
                return;
            }
            handler.postDelayed(this, 3000);
        }
    };

    // ---------------------------------------------------------------- 播放流程

    private Channel current() {
        if (data == null || data.all.isEmpty()) return null;
        return data.all.get(index);
    }

    /** 记住"上次看到哪个台"——存名字，列表顺序变了也认得出 */
    private void saveLast() {
        Channel c = current();
        if (c == null) return;
        getSharedPreferences(PREF, MODE_PRIVATE).edit()
                .putInt(KEY_INDEX, index)
                .putString(KEY_NAME, c.name)
                .apply();
    }

    /** 切到某个台并开始播 */
    private void playChannel(int ni, boolean force) {
        if (data == null || data.all.isEmpty()) return;
        int n = data.all.size();
        if (ni < 0) ni = n - 1;
        if (ni >= n) ni = 0;

        // 同一个台已经在播，不重新连（避免按上下键重复黑屏）
        if (ni == index && !force && player != null && ready) {
            updateBar();
            showBar();
            if (panelVisible) hidePanel();
            return;
        }

        index = ni;
        saveLast();
        // 用户主动换台才重置"自动跳台"计数；放在这里而不是 startChannel，
        // 否则 hopNext 里的 ++ 会被立刻清零，变成无限跳台
        autoHop = 0;
        retryAll = 0;
        startChannel(true);
    }

    /** 从当前台的第一个（最健康的）源开始播 */
    private void startChannel(boolean resort) {
        Channel c = current();
        if (c == null) return;

        handler.removeCallbacks(stallRun);
        handler.removeCallbacks(firstFrameRun);
        playing = false;
        ready = false;
        markedOk = false;
        stall = 0;
        lastPos = -1;

        if (resort) {
            c.sortByHealth();
        }
        reconnect = 0;

        if (!c.alive()) {
            // 这个台所有源都被判死了，试一下下一个台
            hopNext("这个台暂时没有可用的源");
            return;
        }

        updateBar();
        tipText.setText("正在打开 " + c.name + " …");
        showBar();

        Channel.Source s = c.current();
        if (s == null) return;

        MediaItem mi = new MediaItem.Builder()
                .setUri(s.url)
                .setMimeType(guessMime(s.url))
                .setLiveConfiguration(new MediaItem.LiveConfiguration.Builder()
                        .setTargetOffsetMs(4000)      // 追到距直播边缘 4 秒
                        .setMinPlaybackSpeed(0.95f)
                        .setMaxPlaybackSpeed(1.05f)
                        .build())
                .build();

        player.setMediaItem(mi);
        player.prepare();
        player.setPlayWhenReady(true);
        armFirstFrameWatchdog();
    }

    private static String guessMime(String url) {
        String u = url.toLowerCase();
        int q = u.indexOf('?');
        if (q >= 0) u = u.substring(0, q);
        if (u.endsWith(".m3u8") || u.endsWith(".m3u")) return MimeTypes.APPLICATION_M3U8;
        if (u.endsWith(".mpd")) return MimeTypes.APPLICATION_MPD;
        if (u.endsWith(".mp4")) return MimeTypes.VIDEO_MP4;
        if (u.endsWith(".flv")) return MimeTypes.VIDEO_FLV;
        return null;   // 让 ExoPlayer 自己嗅探（live.php 这类无扩展名的地址靠这个）
    }

    /**
     * 当前源废了：先原地重连（只对网络类软错误），再换下一个源；
     * 都试完就重来一轮；还不行就跳台。
     *
     * @param hard      true = 403/404/格式不支持这类，本次运行内不再碰这个源
     * @param retryable true = 值得先原地重连一次（流结束/短命源传 false，
     *                  否则重连只会把同一段再播一遍 —— 那正是要避免的）
     */
    private void onSourceDead(String reason, boolean hard, boolean retryable) {
        Channel c = current();
        if (c == null) return;

        handler.removeCallbacks(stallRun);
        handler.removeCallbacks(firstFrameRun);
        playing = false;
        ready = false;
        markedOk = false;

        if (hard) c.markDead(reason);
        else c.markFail();

        if (player != null) {
            try {
                player.stop();
            } catch (Throwable ignored) {
            }
        }

        // 网络类软错误：先给这个源一次机会
        if (retryable && !hard && reconnect < 1) {
            reconnect++;
            tipText.setText("重新连接…");
            showBar();
            handler.postDelayed(() -> startCurrentSource(), 1500);
            return;
        }
        reconnect = 0;

        if (c.hasNext()) {
            c.next();
            tipText.setText("换个源… " + c.sourceLabel());
            showBar();
            handler.postDelayed(() -> startCurrentSource(), 400);
            return;
        }

        // 一轮试完了，公共网关限流的情况隔两秒往往就好
        if (retryAll < 2) {
            retryAll++;
            if (c.restart()) {
                tipText.setText(retryAll == 1 ? "连不上，正在重试…" : "再试一次…");
                showBar();
                handler.postDelayed(() -> startCurrentSource(), 2000);
                return;
            }
        }

        hopNext("这个台打不开（" + reason + "）");
    }

    /** 只重连当前源，不改 index */
    private void startCurrentSource() {
        Channel c = current();
        if (c == null) return;
        Channel.Source s = c.current();
        if (s == null) {
            hopNext("没有可用的源");
            return;
        }
        ready = false;
        playing = false;
        markedOk = false;
        stall = 0;
        lastPos = -1;
        tipText.setText("正在打开 " + c.name + " …");
        showBar();

        MediaItem mi = new MediaItem.Builder()
                .setUri(s.url)
                .setMimeType(guessMime(s.url))
                .build();
        player.setMediaItem(mi);
        player.prepare();
        player.setPlayWhenReady(true);
        armFirstFrameWatchdog();
    }

    /** 自动跳到下一个能播的台，最多跳 AUTO_HOP_LIMIT 个 */
    private void hopNext(String why) {
        if (autoHop >= AUTO_HOP_LIMIT) {
            tipText.setText(why + "，按 ▲▼ 换个台");
            showBar();
            return;
        }
        autoHop++;
        tipText.setText(why + "，自动换台…");
        showBar();
        handler.postDelayed(() -> {
            if (data == null || data.all.isEmpty()) return;
            index = (index + 1) % data.all.size();
            saveLast();
            startChannel(true);
        }, 1500);
    }

    // ---------------------------------------------------------------- 界面

    private void buildUi() {
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        videoView = new PlayerView(this);
        videoView.setUseController(false);
        videoView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT);
        videoView.setBackgroundColor(Color.BLACK);
        root.addView(videoView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        buildBar();
        buildPanel();
        setContentView(root);
    }

    private void buildBar() {
        bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackground(rounded(0xD90A1020, 18));
        bar.setPadding(dp(26), dp(14), dp(34), dp(14));

        numText = new TextView(this);
        numText.setTextColor(Color.WHITE);
        numText.setTypeface(null, android.graphics.Typeface.BOLD);
        numText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 52);
        numText.setGravity(Gravity.CENTER);
        numText.setMinWidth(dp(96));
        bar.addView(numText);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams colLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        colLp.leftMargin = dp(20);
        bar.addView(col, colLp);

        LinearLayout nameRow = new LinearLayout(this);
        nameRow.setOrientation(LinearLayout.HORIZONTAL);
        nameRow.setGravity(Gravity.CENTER_VERTICAL);

        nameText = new TextView(this);
        nameText.setTextColor(Color.WHITE);
        nameText.setTypeface(null, android.graphics.Typeface.BOLD);
        nameText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 30);
        nameRow.addView(nameText);

        badgeText = new TextView(this);
        badgeText.setTextColor(0xFF7BE3A0);
        badgeText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        badgeText.setPadding(dp(10), dp(2), dp(10), dp(2));
        badgeText.setVisibility(View.GONE);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.leftMargin = dp(10);
        nameRow.addView(badgeText, blp);

        col.addView(nameRow);

        tipText = new TextView(this);
        tipText.setTextColor(C_DIM);
        tipText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        tipText.setPadding(0, dp(4), 0, 0);
        col.addView(tipText);

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.BOTTOM | Gravity.START;
        lp.leftMargin = dp(44);
        lp.bottomMargin = dp(40);
        root.addView(bar, lp);
    }

    private void buildPanel() {
        panel = new FrameLayout(this);
        // 完全不透明：半透明时背后的画面会透成一层残影，老人会以为电视坏了
        panel.setBackgroundColor(0xFF0B111C);
        panel.setVisibility(View.GONE);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(48), dp(30), dp(48), dp(24));
        panel.addView(col, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = new TextView(this);
        title.setText("频道列表");
        title.setTextColor(Color.WHITE);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);
        title.setPadding(dp(8), 0, 0, dp(16));
        head.addView(title);

        TextView sub = new TextView(this);
        sub.setText("　" + (data == null ? 0 : data.all.size()) + " 个台");
        sub.setTextColor(C_DIM);
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        sub.setPadding(0, 0, 0, dp(16));
        head.addView(sub);

        col.addView(head);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        col.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        catScroll = new ScrollView(this);
        catScroll.setFocusable(false);
        catScroll.setVerticalScrollBarEnabled(false);
        catBox = new LinearLayout(this);
        catBox.setOrientation(LinearLayout.VERTICAL);
        catBox.setPadding(0, 0, dp(16), 0);
        catScroll.addView(catBox);
        row.addView(catScroll, new LinearLayout.LayoutParams(
                dp(300), ViewGroup.LayoutParams.MATCH_PARENT));

        chScroll = new ScrollView(this);
        chScroll.setFocusable(false);
        chScroll.setVerticalScrollBarEnabled(false);
        chBox = new LinearLayout(this);
        chBox.setOrientation(LinearLayout.VERTICAL);
        chBox.setPadding(dp(16), 0, 0, 0);
        chScroll.addView(chBox);
        row.addView(chScroll, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));

        TextView hint = new TextView(this);
        hint.setText("▲▼ 选择      OK 播放      返回 收起      菜单 刷新源");
        hint.setTextColor(C_DIM);
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        hint.setPadding(dp(8), dp(14), 0, 0);
        col.addView(hint);

        root.addView(panel, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private void fillCats() {
        cats.clear();
        if (data == null) return;
        PlaylistLoader.Group all = new PlaylistLoader.Group("全部频道");
        all.channels.addAll(data.all);
        cats.add(all);
        cats.addAll(data.groups);
    }

    private void buildCats() {
        catBox.removeAllViews();
        for (int i = 0; i < cats.size(); i++) {
            final int ci = i;
            PlaylistLoader.Group g = cats.get(i);
            TextView t = makeItem(g.name + "  " + g.channels.size(), 22);
            t.setOnClickListener(v -> selectCat(ci, true));
            t.setOnFocusChangeListener((v, hasFocus) -> {
                if (hasFocus) selectCat(ci, false);
            });
            catBox.addView(t, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
    }

    private void selectCat(int ci, boolean focusChannel) {
        if (ci < 0 || ci >= cats.size()) return;
        chBox.removeAllViews();

        PlaylistLoader.Group g = cats.get(ci);
        View target = null;
        for (int i = 0; i < g.channels.size(); i++) {
            final Channel c = g.channels.get(i);
            boolean cur = (c.no - 1) == index;
            String label = (cur ? "▶  " : "     ") + c.display();
            if (c.aliveCount() == 0) label += "   （源失效）";
            TextView t = makeItem(label, 24);
            if (cur) {
                t.setTextColor(C_CURRENT);
                t.setTypeface(null, android.graphics.Typeface.BOLD);
            }
            if (c.aliveCount() == 0) t.setTextColor(0xFF6B7A90);
            t.setOnClickListener(v -> {
                hidePanel();
                playChannel(c.no - 1, true);
            });
            chBox.addView(t, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            if (cur) target = t;
        }

        if (focusChannel) {
            if (target == null && chBox.getChildCount() > 0) target = chBox.getChildAt(0);
            focusAndScroll(target);
        }
    }

    private void focusAndScroll(final View t) {
        if (t == null) return;
        handler.post(() -> {
            t.requestFocus();
            chScroll.smoothScrollTo(0, Math.max(0, t.getTop() - dp(90)));
        });
    }

    private TextView makeItem(String text, int sp) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(Color.WHITE);
        t.setPadding(dp(18), dp(13), dp(18), dp(13));
        t.setBackground(focusBg());
        t.setFocusable(true);
        t.setClickable(true);
        t.setSingleLine(true);
        return t;
    }

    private StateListDrawable focusBg() {
        StateListDrawable sd = new StateListDrawable();
        GradientDrawable on = rounded(C_ACCENT, 12);
        sd.addState(new int[]{android.R.attr.state_focused}, on);
        sd.addState(new int[]{android.R.attr.state_pressed}, on);
        sd.addState(new int[]{}, rounded(0x00000000, 12));
        return sd;
    }

    private GradientDrawable rounded(int color, int radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radiusDp));
        return d;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void updateBar() {
        Channel c = current();
        if (c == null) return;
        numText.setText(String.valueOf(c.no));
        nameText.setText(c.name);
        tipText.setText(c.group);
        if (c.aliveCount() > 0) {
            badgeText.setVisibility(View.GONE);
        } else {
            badgeText.setText("源全部失效");
            badgeText.setTextColor(0xFFFF8A8A);
            badgeText.setVisibility(View.VISIBLE);
        }
    }

    private void showBar() {
        handler.removeCallbacks(hideRun);
        if (panelVisible) {
            bar.setVisibility(View.GONE);
            return;
        }
        bar.setVisibility(View.VISIBLE);
        bar.setAlpha(1f);
        if (playing) handler.postDelayed(hideRun, 4000);
    }

    private final Runnable hideRun = new Runnable() {
        public void run() {
            bar.animate().alpha(0f).setDuration(400).start();
        }
    };

    private void showPanel() {
        if (data == null || data.all.isEmpty()) return;
        panelVisible = true;
        handler.removeCallbacks(hideRun);
        bar.setVisibility(View.GONE);
        panel.setVisibility(View.VISIBLE);
        buildCats();
        selectCat(0, true);
        handler.postDelayed(() -> {
            if (panelVisible && getCurrentFocus() == null) {
                if (chBox.getChildCount() > 0) chBox.getChildAt(0).requestFocus();
                else if (catBox.getChildCount() > 0) catBox.getChildAt(0).requestFocus();
            }
        }, 350);
    }

    private void hidePanel() {
        panelVisible = false;
        panel.setVisibility(View.GONE);
        showBar();
    }

    private void showEmpty() {
        TextView t = new TextView(this);
        t.setText("没有频道\n\n请把 playlist.m3u 放到\nAndroid/data/com.mini.tvlive/files/ 目录下\n或按菜单键联网更新");
        t.setTextColor(Color.WHITE);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 28);
        t.setGravity(Gravity.CENTER);
        root.addView(t, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    // ---------------------------------------------------------------- 按键

    private final Runnable numRun = new Runnable() {
        public void run() {
            String s = numBuf.toString();
            numBuf.setLength(0);
            if (data == null || data.all.isEmpty()) return;
            try {
                int n = Integer.parseInt(s);
                if (n >= 1 && n <= data.all.size()) {
                    playChannel(n - 1, true);
                    return;
                }
            } catch (Throwable ignored) {
            }
            updateBar();
            tipText.setText("没有 " + s + " 这个台");
            showBar();
        }
    };

    private void refreshSources() {
        tipText.setText("正在联网更新源…");
        showBar();
        new Thread(() -> {
            int added = store.refresh(data, null);
            handler.post(() -> {
                for (Channel c : data.all) c.sortByHealth();
                fillCats();
                tipText.setText(added > 0 ? "已更新 " + added + " 个源" : "没有拉到新源（网络可能不通）");
                showBar();
            });
        }).start();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int kc = e.getKeyCode();
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;

        if (down) {
            if (kc >= KeyEvent.KEYCODE_0 && kc <= KeyEvent.KEYCODE_9) {
                numBuf.append((char) ('0' + (kc - KeyEvent.KEYCODE_0)));
                if (numBuf.length() > 4) numBuf.deleteCharAt(0);
                nameText.setText("输入 " + numBuf.toString());
                tipText.setText("稍等，正在跳转…");
                showBar();
                handler.removeCallbacks(numRun);
                handler.postDelayed(numRun, 1800);
                return true;
            }

            if (kc == KeyEvent.KEYCODE_BACK) {
                if (panelVisible) hidePanel();
                else showPanel();
                return true;
            }

            if (kc == KeyEvent.KEYCODE_MENU) {
                refreshSources();
                return true;
            }

            if (!panelVisible) {
                if (kc == KeyEvent.KEYCODE_DPAD_CENTER || kc == KeyEvent.KEYCODE_ENTER
                        || kc == KeyEvent.KEYCODE_NUMPAD_ENTER) {
                    showPanel();
                    return true;
                }
                if (kc == KeyEvent.KEYCODE_DPAD_UP || kc == KeyEvent.KEYCODE_CHANNEL_UP) {
                    if (e.getRepeatCount() == 0) playChannel(index - 1, false);
                    return true;
                }
                if (kc == KeyEvent.KEYCODE_DPAD_DOWN || kc == KeyEvent.KEYCODE_CHANNEL_DOWN) {
                    if (e.getRepeatCount() == 0) playChannel(index + 1, false);
                    return true;
                }
                if (kc == KeyEvent.KEYCODE_DPAD_LEFT || kc == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    if (e.getRepeatCount() == 0) showPanel();
                    return true;
                }
            }
        }
        return super.dispatchKeyEvent(e);
    }
}
