package com.juying.medianest.ui;

import com.juying.medianest.app.R;

import com.juying.medianest.app.App;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import com.juying.medianest.core.model.ConfigCodec;
import com.juying.medianest.core.model.ServerConfig;
import com.juying.medianest.domain.repository.AccountRepository;
import com.juying.medianest.domain.repository.ConfigRepository;
import com.juying.medianest.domain.usecase.DiscoveryService;

import java.util.ArrayList;
import java.util.List;

/**
 * 官方飞牛 TV 1.5.1「选择影视服务器」页复刻（账号页 / 登录页的「切换服务器」入口）。
 *
 * 181 实机几何：logo [0,60][501,145]，标题「选择影视服务器」[96,195][460,265]，
 * 网格起点 (96,315)，卡片 320×440、间距 32；卡片含「手动添加」角标、服务器图标、
 * 名称、账号/地址。聚焦时同账号页一样以中心放大到 352×484。
 *
 * 数据来源：本地已登录账号（Manual）+ 配置里的探测记录 + 当前服务器；
 * 点击服务器卡切换（有账号直接切换，无账号进登录页），长按手动添加的服务器可移除。
 */
public final class OfficialScanDeviceActivity extends Activity {

    private static final int COLOR_TEXT = 0xFFFFFFFF;
    private static final int COLOR_MUTED = 0xFF9A9B9E;
    private static final int COLOR_CARD = 0xFF24252A;
    private static final int COLOR_CARD_BORDER = 0x1FFFFFFF;
    private static final int COLOR_BLUE = 0xFF4B7EF7;

    private static final int CARD_W = 320;
    private static final int CARD_H = 440;
    private static final int CARD_GAP = 32;
    private static final int GRID_LEFT = 96;
    /** 网格相对内容区（内容区从 y=285 开始），绝对 315。 */
    private static final int GRID_TOP_INNER = 30;
    private static final int COL_COUNT = 5;

    private float scale;
    private ConfigRepository store;
    private AccountRepository accountStore;
    private ConfigCodec.ConfigBundle bundle;
    private ServerConfig cfg;
    private List<ServerConfig> accounts;
    private int nextViewId = 0x60000;
    private FrameLayout rootView;
    private View dialogOverlay;
    private View pendingFocus;
    private ProgressBar scanSpinner;
    private TextView scanStatus;
    private ScrollView contentScroll;
    private DiscoveryService discovery;

    private static final class Entry {
        String name;
        String sub;
        String baseUrl;
        String mode;
        String method;
        ServerConfig account;
        boolean manual;
    }

    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        scale = Math.max(0.1f, getResources().getDisplayMetrics().widthPixels / 1920f);
        store = App.from(this).config();
        accountStore = App.from(this).accounts();
        bundle = store.load();
        cfg = bundle.server;
        accounts = accountStore.load();
        if (cfg != null && cfg.username.length() > 0 && !accountStore.contains(accounts, cfg)) {
            accounts.add(0, cfg);
        }
        setContentView(build());
        final View pf = pendingFocus;
        if (pf != null) {
            rootView.post(new Runnable() {
                public void run() { pf.requestFocus(); }
            });
        }
        startDiscovery();
    }

    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && pendingFocus != null) pendingFocus.requestFocus();
    }

    private int s(int v) { return Math.round(v * scale); }
    private float sf(int v) { return v * scale; }

    private List<Entry> collect() {
        List<Entry> list = new ArrayList<Entry>();
        List<String> seen = new ArrayList<String>();
        for (ServerConfig a : accounts) {
            Entry e = new Entry();
            e.account = a;
            e.manual = "MANUAL".equalsIgnoreCase(a.sourceMethod);
            e.baseUrl = a.baseUrl;
            e.mode = a.apiMode;
            e.method = a.sourceMethod;
            e.name = a.serverName.length() > 0 ? a.serverName : hostOf(a.baseUrl);
            e.sub = a.username;
            if (!seen.contains(norm(a.baseUrl))) {
                seen.add(norm(a.baseUrl));
                list.add(e);
            }
        }
        if (cfg != null && cfg.baseUrl.length() > 0 && !seen.contains(norm(cfg.baseUrl))) {
            Entry e = new Entry();
            e.manual = "MANUAL".equalsIgnoreCase(cfg.sourceMethod);
            e.baseUrl = cfg.baseUrl;
            e.mode = cfg.apiMode;
            e.method = cfg.sourceMethod;
            e.name = cfg.serverName.length() > 0 ? cfg.serverName : hostOf(cfg.baseUrl);
            e.sub = cfg.username;
            seen.add(norm(cfg.baseUrl));
            list.add(e);
        }
        for (ConfigCodec.ServerInfo info : bundle.discovered) {
            if (info.baseUrl.length() == 0 || seen.contains(norm(info.baseUrl))) continue;
            Entry e = new Entry();
            e.manual = "MANUAL".equalsIgnoreCase(info.method);
            e.baseUrl = info.baseUrl;
            e.mode = info.apiMode();
            e.method = info.method;
            e.name = info.name.length() > 0 ? info.name : hostOf(info.baseUrl);
            e.sub = hostOf(info.baseUrl);
            seen.add(norm(info.baseUrl));
            list.add(e);
        }
        return list;
    }

    private static String norm(String url) {
        if (url == null) return "";
        String v = url.trim().toLowerCase();
        while (v.endsWith("/")) v = v.substring(0, v.length() - 1);
        return v;
    }

    private static String hostOf(String baseUrl) {
        if (baseUrl == null) return "";
        String v = baseUrl.replaceFirst("^[a-zA-Z]+://", "");
        int slash = v.indexOf('/');
        return slash > 0 ? v.substring(0, slash) : v;
    }

    // ---------------- UI ----------------

    private View build() {
        FrameLayout root = new FrameLayout(this);
        rootView = root;
        // 官方该页背景为进入前首页的模糊快照（比账号页略暗），此处复用同一张快照并加暗色蒙层
        root.setBackgroundResource(R.drawable.official_account_bg);
        View dim = new View(this);
        dim.setBackgroundColor(0x24000000);
        root.addView(dim, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.ic_logo);
        logo.setScaleType(ImageView.ScaleType.FIT_START);
        put(root, logo, 80, 60, 501, 85);

        TextView title = new TextView(this);
        title.setText("选择影视服务器");
        title.setTextSize(TypedValue.COMPLEX_UNIT_PX, sf(44));
        title.setTextColor(COLOR_TEXT);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setGravity(Gravity.CENTER_VERTICAL);
        put(root, title, 96, 195, 364, 70);

        scanSpinner = new ProgressBar(this, null, android.R.attr.progressBarStyleSmall);
        scanSpinner.setIndeterminate(true);
        put(root, scanSpinner, 472, 202, 56, 56);

        scanStatus = text("正在搜索局域网服务器…", 26, COLOR_MUTED, Gravity.CENTER_VERTICAL);
        put(root, scanStatus, 548, 202, 980, 56);

        // 内容区 [0,285][1920,1080]
        contentScroll = new ScrollView(this);
        contentScroll.setClipChildren(false);
        FrameLayout.LayoutParams svLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, s(1080 - 285));
        svLp.topMargin = s(285);
        root.addView(contentScroll, svLp);

        List<Entry> entries = collect();
        int cards = entries.size() + 1;
        int rows = (cards + COL_COUNT - 1) / COL_COUNT;
        int innerH = Math.max(1080 - 285, GRID_TOP_INNER + rows * (CARD_H + CARD_GAP));

        FrameLayout grid = new FrameLayout(this);
        grid.setClipChildren(false);
        contentScroll.addView(grid, new ViewGroup.LayoutParams(s(1920), s(innerH)));

        View addCard = addServerCard(grid, null, 0, 0);
        View firstServer = null;
        View prev = addCard;
        int col = 1, row = 0;
        for (Entry e : entries) {
            if (col >= COL_COUNT) { col = 0; row++; }
            View card = addServerCard(grid, e, col, row);
            if (firstServer == null) firstServer = card;
            linkCards(prev, card);
            prev = card;
            col++;
        }
        // 官方默认焦点在「添加服务器」卡，不在已有服务器上
        pendingFocus = addCard;
        return root;
    }

    private void linkCards(View left, View right) {
        left.setNextFocusRightId(right.getId());
        right.setNextFocusLeftId(left.getId());
    }

    private void put(FrameLayout root, View v, int left, int top, int w, int h) {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(s(w), s(h));
        lp.leftMargin = s(left);
        lp.topMargin = s(top);
        root.addView(v, lp);
    }

    /**
     * 添加卡（entry=null）或服务器卡。卡片内部元素使用基础坐标系，
     * 聚焦时整卡以中心放大并让内容跟随平移（官方服务器卡聚焦态未单独测量）。
     */
    private View addServerCard(FrameLayout grid, final Entry entry, int col, int row) {
        final boolean add = entry == null;
        final int left = GRID_LEFT + col * (CARD_W + CARD_GAP);
        final int top = GRID_TOP_INNER + row * (CARD_H + CARD_GAP);

        final FrameLayout box = new FrameLayout(this);
        box.setClipChildren(false);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(s(CARD_W), s(CARD_H));
        lp.leftMargin = s(left);
        lp.topMargin = s(top);
        grid.addView(box, lp);
        box.setId(nextViewId++);
        box.setTag(entry);
        box.setFocusable(true);
        box.setClickable(true);
        box.setBackground(cardBg(false));

        final List<View> parts = new ArrayList<View>();
        final List<int[]> rects = new ArrayList<int[]>();
        final TextView addLabel;

        if (add) {
            TextView plus = new TextView(this);
            plus.setText("+");
            plus.setTextSize(TypedValue.COMPLEX_UNIT_PX, sf(86));
            plus.setTextColor(0xFFD7D8DB);
            plus.setGravity(Gravity.CENTER);
            GradientDrawable circle = new GradientDrawable();
            circle.setShape(GradientDrawable.OVAL);
            circle.setColor(0xFF3A3B40);
            plus.setBackground(circle);
            addPart(box, parts, rects, plus, 89, 96, 143, 143);

            // 官方聚焦时该文案放大（181 实测 168×57 → 230×63，字号约 32→44，仍居中于卡片）
            addLabel = text("添加服务器", 32, 0xFFC9CACC, Gravity.CENTER);
            addLabel.setSingleLine(true);
            addPart(box, parts, rects, addLabel, 76, 287, 168, 57);
            box.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    startActivityForResult(new Intent(OfficialScanDeviceActivity.this,
                            OfficialAddServerActivity.class), 1);
                }
            });
        } else {
            addLabel = null;
            if (entry.manual) {
                TextView tag = text("手动", 22, 0xFFC9CACC, Gravity.CENTER);
                GradientDrawable tg = new GradientDrawable();
                tg.setColor(0xFF32333A);
                tg.setCornerRadius(sf(22));
                tag.setBackground(tg);
                addPart(box, parts, rects, tag, 10, 4, 76, 44);
            }
            TextView typeTag = text(typeLabel(entry.mode), 22, 0xFFC9CACC, Gravity.CENTER);
            GradientDrawable typeBg = new GradientDrawable();
            typeBg.setColor("JELLYFIN".equalsIgnoreCase(entry.mode) ? 0xFF4B2C63 : 0xFF233A66);
            typeBg.setCornerRadius(sf(22));
            typeTag.setBackground(typeBg);
            addPart(box, parts, rects, typeTag, entry.manual ? 96 : 158, 4,
                    entry.manual ? 214 : 154, 44);
            addPart(box, parts, rects, serverIcon(entry.mode), 95, 86, 130, 130);
            addPart(box, parts, rects, text(entry.name, 40, COLOR_TEXT, Gravity.CENTER), 24, 248, 272, 57);
            addPart(box, parts, rects, text(entry.sub, 28, COLOR_MUTED, Gravity.CENTER), 0, 309, 320, 46);

            box.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { openEntry(entry); }
            });
            box.setOnLongClickListener(new View.OnLongClickListener() {
                public boolean onLongClick(View v) {
                    if (entry.account != null) {
                        showRemoveDialog(entry);
                    } else {
                        Ui.toast(OfficialScanDeviceActivity.this, "仅支持移除手动添加的服务器");
                    }
                    return true;
                }
            });
        }

        box.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            public void onFocusChange(View v, boolean hasFocus) {
                FrameLayout.LayoutParams flp = (FrameLayout.LayoutParams) v.getLayoutParams();
                flp.width = s(hasFocus ? CARD_W + 32 : CARD_W);
                flp.height = s(hasFocus ? CARD_H + 44 : CARD_H);
                flp.leftMargin = s(left + (hasFocus ? -16 : 0));
                flp.topMargin = s(top + (hasFocus ? -22 : 0));
                v.setLayoutParams(flp);
                v.setBackground(cardBg(hasFocus));
                for (int i = 0; i < parts.size(); i++) {
                    int[] r = rects.get(i);
                    FrameLayout.LayoutParams plp =
                            (FrameLayout.LayoutParams) parts.get(i).getLayoutParams();
                    plp.leftMargin = s(r[0] + (hasFocus ? 16 : 0));
                    plp.topMargin = s(r[1] + (hasFocus ? 22 : 0));
                    parts.get(i).setLayoutParams(plp);
                }
                if (addLabel != null) {
                    FrameLayout.LayoutParams plp =
                            (FrameLayout.LayoutParams) addLabel.getLayoutParams();
                    plp.width = s(hasFocus ? 230 : 168);
                    plp.height = s(hasFocus ? 63 : 57);
                    plp.leftMargin = s(hasFocus ? 61 : 76);
                    plp.topMargin = s(hasFocus ? 309 : 287);
                    addLabel.setLayoutParams(plp);
                    addLabel.setTextSize(TypedValue.COMPLEX_UNIT_PX, sf(hasFocus ? 44 : 32));
                }
            }
        });
        applyParts(box, parts, rects);
        return box;
    }

    private void addPart(FrameLayout parent, List<View> parts, List<int[]> rects,
                         View v, int l, int t, int w, int h) {
        parent.addView(v, new FrameLayout.LayoutParams(s(w), s(h)));
        parts.add(v);
        rects.add(new int[]{l, t, w, h});
    }

    private void applyParts(FrameLayout parent, List<View> parts, List<int[]> rects) {
        for (int i = 0; i < parts.size(); i++) {
            int[] r = rects.get(i);
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) parts.get(i).getLayoutParams();
            lp.width = s(r[2]);
            lp.height = s(r[3]);
            lp.leftMargin = s(r[0]);
            lp.topMargin = s(r[1]);
            parts.get(i).setLayoutParams(lp);
        }
    }

    /** 官方蓝色三层服务器图标（APK 内为图片资源，这里用矢量近似）。 */
    private View serverIcon(String mode) {
        if ("JELLYFIN".equalsIgnoreCase(mode)) {
            return new View(this) {
                private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

                protected void onDraw(Canvas canvas) {
                    float w = getWidth();
                    float h = getHeight();
                    float radius = Math.min(w, h) / 2f;
                    paint.setColor(0xFFAA5CC3);
                    canvas.drawCircle(w / 2f, h / 2f, radius, paint);
                    Path triangle = new Path();
                    triangle.moveTo(w * 0.40f, h * 0.32f);
                    triangle.lineTo(w * 0.72f, h * 0.50f);
                    triangle.lineTo(w * 0.40f, h * 0.68f);
                    triangle.close();
                    paint.setColor(0xFFFFFFFF);
                    canvas.drawPath(triangle, paint);
                }
            };
        }
        FrameLayout icon = new FrameLayout(this);
        for (int i = 0; i < 3; i++) {
            View bar = new View(this);
            GradientDrawable g = new GradientDrawable();
            g.setColor(COLOR_BLUE);
            g.setCornerRadius(sf(6));
            bar.setBackground(g);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(s(104), s(30));
            lp.leftMargin = s(13);
            lp.topMargin = s(12 + i * 38);
            icon.addView(bar, lp);

            View dot = new View(this);
            GradientDrawable dg = new GradientDrawable();
            dg.setShape(GradientDrawable.OVAL);
            dg.setColor(0xFF1B2B55);
            dot.setBackground(dg);
            FrameLayout.LayoutParams dlp = new FrameLayout.LayoutParams(s(10), s(10));
            dlp.leftMargin = s(96);
            dlp.topMargin = s(22 + i * 38);
            icon.addView(dot, dlp);
        }
        return icon;
    }

    private static String typeLabel(String mode) {
        return "JELLYFIN".equalsIgnoreCase(mode) ? "Jellyfin" : "飞牛影视";
    }

    private TextView text(String value, int size, int color, int gravity) {
        TextView v = new TextView(this);
        v.setText(value);
        v.setTextSize(TypedValue.COMPLEX_UNIT_PX, sf(size));
        v.setTextColor(color);
        v.setGravity(gravity);
        return v;
    }

    private GradientDrawable cardBg(boolean focused) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(COLOR_CARD);
        g.setCornerRadius(sf(12));
        g.setStroke(focused ? s(2) : s(1), focused ? 0xFFFFFFFF : COLOR_CARD_BORDER);
        return g;
    }

    private void startDiscovery() {
        if (discovery != null) discovery.cancel();
        scanSpinner.setVisibility(View.VISIBLE);
        scanStatus.setText("正在搜索局域网服务器…");
        discovery = App.from(this).discovery(this);
        discovery.discover(cfg, bundle.discovered, new DiscoveryService.Callback() {
            public void onProgress(final String text) {
                runOnUiThread(new Runnable() {
                    public void run() { scanStatus.setText(text); }
                });
            }

            public void onFound(final ConfigCodec.ServerInfo server) {
                runOnUiThread(new Runnable() {
                    public void run() {
                        int index = serverIndex(server.baseUrl);
                        if (index < 0) {
                            bundle.discovered.add(server);
                        } else {
                            bundle.discovered.get(index).mergeDiscovery(server);
                        }
                        refreshCards(server.baseUrl);
                    }
                });
            }

            public void onDone(final List<ConfigCodec.ServerInfo> result) {
                runOnUiThread(new Runnable() {
                    public void run() {
                        scanSpinner.setVisibility(View.GONE);
                        scanStatus.setText("扫描完成，找到 " + result.size() + " 台服务器");
                        store.save(bundle);
                        refreshCards(null);
                    }
                });
            }
        });
    }

    private int serverIndex(String baseUrl) {
        for (int i = 0; i < bundle.discovered.size(); i++) {
            if (norm(bundle.discovered.get(i).baseUrl).equals(norm(baseUrl))) return i;
        }
        return -1;
    }

    private void refreshCards(String focusBaseUrl) {
        View focused = getCurrentFocus();
        String keep = focused == null ? focusBaseUrl : null;
        if (focused != null && focused.getTag() instanceof Entry) {
            keep = ((Entry) focused.getTag()).baseUrl;
        }
        final String target = keep;
        int scrollY = contentScroll == null ? 0 : contentScroll.getScrollY();
        setContentView(build());
        rootView.post(new Runnable() {
            public void run() {
                View focus = target == null ? pendingFocus : findCard(target);
                if (focus != null) focus.requestFocus();
                if (contentScroll != null) contentScroll.scrollTo(0, scrollY);
            }
        });
    }

    private View findCard(String baseUrl) {
        if (rootView == null) return null;
        return findCard(rootView, baseUrl);
    }

    private View findCard(View view, String baseUrl) {
        if (view.getTag() instanceof Entry
                && norm(((Entry) view.getTag()).baseUrl).equals(norm(baseUrl))) {
            return view;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = findCard(group.getChildAt(i), baseUrl);
                if (found != null) return found;
            }
        }
        return null;
    }

    private void openLogin(String baseUrl, String name, String mode, String method) {
        Intent i = new Intent(this, OfficialLoginActivity.class);
        i.putExtra("pendingBaseUrl", baseUrl);
        i.putExtra("pendingName", name);
        i.putExtra("pendingMode", mode);
        i.putExtra("pendingMethod", method);
        startActivity(i);
    }

    // ---------------- 交互 ----------------

    private void openEntry(Entry entry) {
        if (entry.account != null) {
            bundle.server = entry.account;
            cfg = entry.account;
            store.save(bundle);
            Intent i = new Intent(this, MainActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
            startActivity(i);
            finish();
        } else {
            openLogin(entry.baseUrl, entry.name, entry.mode, entry.method);
        }
    }

    private void showRemoveDialog(final Entry entry) {
        if (dialogOverlay != null || rootView == null) return;

        final FrameLayout overlay = new FrameLayout(this);
        overlay.setClickable(true);
        overlay.setFocusable(true);
        overlay.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { /* 拦截点击 */ }
        });
        rootView.addView(overlay, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        dialogOverlay = overlay;

        FrameLayout box = new FrameLayout(this);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFF2A2C31);
        bg.setCornerRadius(sf(16));
        box.setBackground(bg);
        put(overlay, box, 693, 338, 534, 403);

        put(box, text("移除服务器", 26, 0xFF8E8F92, Gravity.CENTER_VERTICAL), 32, 32, 470, 42);
        TextView hint = text("确定移除 " + entry.name + " 服务器？", 34, COLOR_TEXT, Gravity.CENTER_VERTICAL);
        hint.setTypeface(Typeface.DEFAULT_BOLD);
        put(box, hint, 32, 128, 470, 49);

        TextView remove = new TextView(this);
        remove.setText("移除");
        remove.setTextSize(TypedValue.COMPLEX_UNIT_PX, sf(32));
        remove.setTextColor(0xFF000000);
        remove.setGravity(Gravity.CENTER);
        remove.setFocusable(true);
        remove.setClickable(true);
        GradientDrawable pillBg = new GradientDrawable();
        pillBg.setColor(0xFFFFFFFF);
        pillBg.setCornerRadius(sf(35));
        remove.setBackground(pillBg);
        remove.setId(nextViewId++);
        put(box, remove, 23, 228, 488, 70);

        TextView cancel = text("取消", 32, 0xFFD7D8DB, Gravity.CENTER);
        cancel.setFocusable(true);
        cancel.setClickable(true);
        cancel.setId(nextViewId++);
        put(box, cancel, 32, 303, 470, 68);

        remove.setNextFocusDownId(cancel.getId());
        cancel.setNextFocusUpId(remove.getId());
        remove.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                dismissDialog();
                removeEntry(entry);
            }
        });
        cancel.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { dismissDialog(); }
        });
        remove.requestFocus();
    }

    private void removeEntry(Entry entry) {
        for (int i = accounts.size() - 1; i >= 0; i--) {
            if (accountStore.same(accounts.get(i), entry.account)) accounts.remove(i);
        }
        accountStore.save(accounts);
        if (cfg != null && norm(cfg.baseUrl).equals(norm(entry.baseUrl))) {
            cfg.username = "";
            cfg.password = "";
            cfg.token = "";
            cfg.userId = "";
            store.save(bundle);
        }
        setContentView(build());
        if (pendingFocus != null) pendingFocus.requestFocus();
    }

    private void dismissDialog() {
        if (dialogOverlay == null) return;
        rootView.removeView(dialogOverlay);
        dialogOverlay = null;
    }

    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == 1) {
            bundle = store.load();
            cfg = bundle.server;
            accounts = accountStore.load();
            if (resultCode == RESULT_OK && data != null) {
                String baseUrl = data.getStringExtra("pendingBaseUrl");
                String name = data.getStringExtra("pendingName");
                String mode = data.getStringExtra("pendingMode");
                String method = data.getStringExtra("pendingMethod");
                if (baseUrl != null && baseUrl.length() > 0) {
                    openLogin(baseUrl, name, mode, method);
                    return;
                }
            }
            refreshCards(null);
        }
    }

    protected void onDestroy() {
        if (discovery != null) discovery.cancel();
        super.onDestroy();
    }

    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (dialogOverlay != null) {
                dismissDialog();
                return true;
            }
            finish();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }
}
