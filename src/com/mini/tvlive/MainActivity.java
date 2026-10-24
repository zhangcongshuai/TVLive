package com.mini.tvlive;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * 极简电视直播：
 *   打开就播（记住上次看的台）
 *   ▲▼ 换台    OK 频道列表    数字键 直接跳台
 */
public class MainActivity extends Activity {

    private static final int C_ACCENT = 0xFF2F6BFF;
    private static final int C_CURRENT = 0xFFFFC53D;
    private static final int C_DIM = 0xFFAEBACB;
    private static final String PREF = "tv";
    private static final String KEY_INDEX = "last_index";

    private FrameLayout root;
    private SurfaceView surface;
    private LinearLayout bar;
    private TextView numText;
    private TextView nameText;
    private TextView tipText;

    private FrameLayout panel;
    private ScrollView catScroll;
    private ScrollView chScroll;
    private LinearLayout catBox;
    private LinearLayout chBox;

    private PlaylistLoader.Result data;
    private final List<PlaylistLoader.Group> cats = new ArrayList<PlaylistLoader.Group>();

    private int index = 0;
    private boolean panelVisible = false;
    private boolean surfaceReady = false;
    private boolean busy = false;
    private boolean playing = false;
    private int retry = 0;

    private MediaPlayer mp;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final StringBuilder numBuf = new StringBuilder();
    private boolean logOn = false;     // files 目录有 statelog.marker 时，把播放状态写进 state.log

    /** 调试用：只在放了 marker 的情况下记录，方便批量验证哪些台真的出画 */
    private void dbg(String s) {
        if (!logOn) return;
        try {
            java.io.File f = new java.io.File(getExternalFilesDir(null), "state.log");
            java.io.FileWriter w = new java.io.FileWriter(f, true);
            w.write(s + "\n");
            w.close();
        } catch (Throwable ignored) {
        }
    }

    // ---------------------------------------------------------------- 生命周期

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        hideSystemUi();

        data = PlaylistLoader.load(this);
        try {
            logOn = new java.io.File(getExternalFilesDir(null), "statelog.marker").exists();
        } catch (Throwable ignored) {
        }
        buildUi();

        if (data.all.isEmpty()) {
            showEmpty();
        } else {
            SharedPreferences sp = getSharedPreferences(PREF, MODE_PRIVATE);
            index = sp.getInt(KEY_INDEX, 0);
            if (index < 0 || index >= data.all.size()) index = 0;
            fillCats();
            updateBar();
            tipText.setText("正在打开…");
            maybeSelfTest();
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        attemptPlay();
    }

    @Override
    protected void onStop() {
        super.onStop();
        handler.removeCallbacks(retryRun);
        releasePlayer();
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

    // ---------------------------------------------------------------- 界面搭建

    private void buildUi() {
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        surface = new SurfaceView(this);
        root.addView(surface, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        surface.getHolder().addCallback(surfaceCb);

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
        bar.addView(numText, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams colLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        colLp.leftMargin = dp(20);
        bar.addView(col, colLp);

        nameText = new TextView(this);
        nameText.setTextColor(Color.WHITE);
        nameText.setTypeface(null, android.graphics.Typeface.BOLD);
        nameText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 30);
        col.addView(nameText);

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
        panel.setBackgroundColor(0xFA0B111C);
        panel.setVisibility(View.GONE);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(48), dp(30), dp(48), dp(24));
        panel.addView(col, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        TextView title = new TextView(this);
        title.setText("频道列表");
        title.setTextColor(Color.WHITE);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);
        title.setPadding(dp(8), 0, 0, dp(16));
        col.addView(title);

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
        hint.setText("▲▼ 选择      OK 播放      返回 收起");
        hint.setTextColor(C_DIM);
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        hint.setPadding(dp(8), dp(14), 0, 0);
        col.addView(hint);

        root.addView(panel, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    /** 分类：第一个是「全部频道」，后面按 m3u 的 group-title 分 */
    private void fillCats() {
        cats.clear();
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
            t.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    selectCat(ci, true);
                }
            });
            // 焦点落到分类上就直接预览该分类，不用按 OK —— 快速定位的关键
            t.setOnFocusChangeListener(new View.OnFocusChangeListener() {
                public void onFocusChange(View v, boolean hasFocus) {
                    if (hasFocus) selectCat(ci, false);
                }
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
            boolean current = (c.no - 1) == index;
            TextView t = makeItem((current ? "▶  " : "     ") + c.display(), 24);
            if (current) {
                // 当前台用琥珀色 + 加粗，避免和焦点蓝底同色看不清
                t.setTextColor(C_CURRENT);
                t.setTypeface(null, android.graphics.Typeface.BOLD);
            }
            t.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    hidePanel();
                    play(c.no - 1);
                }
            });
            chBox.addView(t, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            if (current) target = t;
        }

        if (focusChannel) {
            if (target == null && chBox.getChildCount() > 0) target = chBox.getChildAt(0);
            focusAndScroll(target);
        }
    }

    private void focusAndScroll(final View t) {
        if (t == null) return;
        // 延迟到布局完成后再抢焦点，否则刚 setVisibility 的容器里 requestFocus 会失败
        handler.post(new Runnable() {
            public void run() {
                t.requestFocus();
                chScroll.smoothScrollTo(0, Math.max(0, t.getTop() - dp(90)));
            }
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

    // ---------------------------------------------------------------- 播放

    private final SurfaceHolder.Callback surfaceCb = new SurfaceHolder.Callback() {
        public void surfaceCreated(SurfaceHolder h) {
            surfaceReady = true;
            attemptPlay();
        }

        public void surfaceChanged(SurfaceHolder h, int format, int w, int ch) {
        }

        public void surfaceDestroyed(SurfaceHolder h) {
            surfaceReady = false;
            releasePlayer();
        }
    };

    private void play(int ni) {
        if (data == null || data.all.isEmpty()) return;
        int n = data.all.size();
        if (ni < 0) ni = n - 1;
        if (ni >= n) ni = 0;

        // 已经在播这个台（比如只有一个台时按上下键），不重复重连
        if (ni == index && mp != null) {
            updateBar();
            showBar();
            if (panelVisible) hidePanel();
            return;
        }

        index = ni;
        getSharedPreferences(PREF, MODE_PRIVATE).edit().putInt(KEY_INDEX, index).apply();
        dbg("TRY\t" + data.all.get(index).no + "\t" + data.all.get(index).name);

        handler.removeCallbacks(retryRun);
        retry = 0;
        playing = false;
        data.all.get(index).urlIdx = 0;   // 重新进入这个台，从第一个源开始试
        releasePlayer();
        updateBar();
        tipText.setText("正在打开…");
        showBar();
        attemptPlay();
    }

    private void attemptPlay() {
        if (data == null || data.all.isEmpty()) return;
        if (!surfaceReady || busy) return;

        final Channel c = data.all.get(index);
        final String url = c.currentUrl();
        if (url == null) return;
        busy = true;
        try {
            mp = new MediaPlayer();
            mp.setAudioStreamType(AudioManager.STREAM_MUSIC);
            try {
                mp.setWakeMode(getApplicationContext(), PowerManager.PARTIAL_WAKE_LOCK);
            } catch (Throwable ignored) {
            }
            mp.setDisplay(surface.getHolder());

            mp.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                public void onPrepared(MediaPlayer m) {
                    busy = false;
                    retry = 0;
                    handler.removeCallbacks(watchdog);
                    try {
                        m.start();
                    } catch (Throwable ignored) {
                    }
                    tipText.setText("播放中");
                    playing = true;
                    showBar();
                    dbg("PLAY\t" + data.all.get(index).no + "\t" + data.all.get(index).name);
                }
            });

            mp.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                public boolean onError(MediaPlayer m, int what, int extra) {
                    busy = false;
                    onPlayError();
                    return true;
                }
            });

            // 部分 IPTV 网关对 UA 敏感，带上一个常见的客户端标识
            java.util.Map<String, String> headers = new java.util.HashMap<String, String>();
            headers.put("User-Agent", "okhttp/3.12.0");
            mp.setDataSource(this, Uri.parse(url), headers);
            mp.prepareAsync();

            // 看门狗：15 秒还没出画面就当失败处理，免得一直卡在"正在打开…"
            handler.removeCallbacks(watchdog);
            handler.postDelayed(watchdog, 15000);
        } catch (Throwable t) {
            busy = false;
            onPlayError();
        }
    }

    private final Runnable watchdog = new Runnable() {
        public void run() {
            if (!playing) {
                busy = false;
                onPlayError();
            }
        }
    };

    /** 隐藏的源自检：files 目录存在 dotest.marker 时，启动后台批量探测所有频道 */
    private void maybeSelfTest() {
        if (data == null || data.all.isEmpty()) return;
        try {
            if (!SelfTest.shouldRun(this)) return;
            new Thread(new Runnable() {
                public void run() {
                    SelfTest.run(getApplicationContext(), data);
                }
            }).start();
        } catch (Throwable ignored) {
        }
    }

    private void onPlayError() {
        playing = false;
        final Channel c = data.all.get(index);

        // 这个台还有备用源，先自动换源（老人不用知道发生了什么）
        if (c.hasNextUrl()) {
            c.urlIdx++;
            tipText.setText("换个源试试… " + (c.urlIdx + 1) + "/" + c.urls.size());
            showBar();
            handler.removeCallbacks(retryRun);
            handler.postDelayed(retryRun, 500);
            return;
        }

        // 所有源都试过了：公共网关会按 IP 临时限流，隔一两秒重来往往就好了，
        // 所以多重试两次再判死，老人不用自己反复按。
        if (retry < 2) {
            retry++;
            c.urlIdx = 0;   // 重试时从第一个源重新走一遍
            tipText.setText(retry == 1 ? "连接不上，正在重试…" : "再试一次…");
            showBar();
            handler.removeCallbacks(retryRun);
            handler.postDelayed(retryRun, 2500);
        } else {
            tipText.setText("这个台暂时打不开，按 ▲▼ 换个台");
            showBar();
            dbg("FAIL\t" + c.no + "\t" + c.name);
        }
    }

    private final Runnable retryRun = new Runnable() {
        public void run() {
            releasePlayer();
            attemptPlay();
        }
    };

    private void releasePlayer() {
        playing = false;
        handler.removeCallbacks(watchdog);
        if (mp != null) {
            try {
                mp.reset();
            } catch (Throwable ignored) {
            }
            try {
                mp.release();
            } catch (Throwable ignored) {
            }
            mp = null;
        }
        busy = false;
    }

    // ---------------------------------------------------------------- 信息条

    private void updateBar() {
        if (data == null || data.all.isEmpty()) return;
        Channel c = data.all.get(index);
        numText.setText(String.valueOf(c.no));
        nameText.setText(c.name);
        tipText.setText(c.group);
    }

    private void showBar() {
        handler.removeCallbacks(hideRun);
        // 频道列表打开时一律不显示信息条（否则播放出错时会在面板背后留一层残影）
        if (panelVisible) {
            bar.setVisibility(View.GONE);
            return;
        }
        bar.setVisibility(View.VISIBLE);
        bar.setAlpha(1f);
        // 只有真的在播才自动隐藏；正在打开/打不开时一直留着，避免老人对着黑屏
        if (playing) handler.postDelayed(hideRun, 4000);
    }

    private final Runnable hideRun = new Runnable() {
        public void run() {
            bar.animate().alpha(0f).setDuration(400).start();
        }
    };

    // ---------------------------------------------------------------- 频道列表

    private void showPanel() {
        if (data == null || data.all.isEmpty()) return;
        panelVisible = true;
        handler.removeCallbacks(hideRun);
        bar.setVisibility(View.GONE);   // 列表打开时把信息条收起来，画面更干净
        panel.setVisibility(View.VISIBLE);
        buildCats();
        selectCat(0, true);
        // 兜底：万一焦点没落上，再补一次，保证遥控器能操作
        handler.postDelayed(new Runnable() {
            public void run() {
                if (panelVisible && getCurrentFocus() == null) {
                    if (chBox.getChildCount() > 0) chBox.getChildAt(0).requestFocus();
                    else if (catBox.getChildCount() > 0) catBox.getChildAt(0).requestFocus();
                }
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
        t.setText("没有频道\n\n请把 playlist.m3u 放到\nAndroid/data/com.mini.tvlive/files/ 目录下");
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
            if (data != null && data.all.size() > 0) {
                try {
                    int n = Integer.parseInt(s);
                    if (n >= 1 && n <= data.all.size()) {
                        play(n - 1);
                        return;
                    }
                } catch (Throwable ignored) {
                }
                updateBar();
                tipText.setText("没有 " + s + " 这个台");
                showBar();
            }
        }
    };

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int kc = e.getKeyCode();
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;

        if (down) {
            // 数字键直接跳台
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

            if (!panelVisible) {
                if (kc == KeyEvent.KEYCODE_DPAD_CENTER || kc == KeyEvent.KEYCODE_ENTER
                        || kc == KeyEvent.KEYCODE_NUMPAD_ENTER || kc == KeyEvent.KEYCODE_MENU) {
                    showPanel();
                    return true;
                }
                if (kc == KeyEvent.KEYCODE_DPAD_UP || kc == KeyEvent.KEYCODE_CHANNEL_UP) {
                    if (e.getRepeatCount() == 0) play(index - 1);
                    return true;
                }
                if (kc == KeyEvent.KEYCODE_DPAD_DOWN || kc == KeyEvent.KEYCODE_CHANNEL_DOWN) {
                    if (e.getRepeatCount() == 0) play(index + 1);
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
