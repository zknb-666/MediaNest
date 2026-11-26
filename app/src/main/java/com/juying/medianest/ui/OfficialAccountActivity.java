package com.juying.medianest.ui;

import com.juying.medianest.app.R;

import com.juying.medianest.app.App;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import com.juying.medianest.core.model.ConfigCodec;
import com.juying.medianest.domain.repository.AccountRepository;
import com.juying.medianest.domain.repository.ConfigRepository;
import com.juying.medianest.core.model.ServerConfig;

import java.util.List;

/**
 * 官方飞牛 TV 1.5.1「账号管理」页 1:1 复刻。
 *
 * 几何来自 181 官方实机 dump（1920×1080）：
 * 标题 / 服务器行 / 升级胶囊 / 切换服务器 / 用户卡片网格。
 *
 * 官方卡片焦点行为（181 实测）：
 * - 卡片默认 320×440，聚焦时以自身中心放大到 352×484（左 -16 / 上 -22）；
 * - 只有聚焦中的当前账号卡才显示「退出登录」白胶囊，失焦即隐藏并缩回；
 * - 当前账号卡按 OK 不直接注销，先弹「移除账号」确认框（移除 / 取消）。
 *
 * 用户列表为客户端本地存储（SharedPreferences fntv_accounts），
 * 每项为完整 ServerConfig JSON。
 */
public final class OfficialAccountActivity extends Activity {

    private static final int COLOR_TEXT = 0xFFFFFFFF;
    private static final int COLOR_SUB = 0xFF9A9B9E;
    private static final int COLOR_MUTED = 0xFFBDBEC1;
    /** 官方卡片为半透明白，叠在进入前的模糊首页快照上。 */
    private static final int COLOR_CARD = 0x2BFFFFFF;
    private static final int COLOR_CARD_BORDER = 0x1FFFFFFF;
    private static final int COLOR_PILL = 0xFF2A2C31;
    private static final int COLOR_CIRCLE = 0x33FFFFFF;

    /** 网格：卡片 320×440，间距 32，起点 (96,391)，每行 5 张。 */
    private static final int CARD_W = 320;
    private static final int CARD_H = 440;
    private static final int CARD_GAP = 32;
    private static final int GRID_LEFT = 96;
    private static final int GRID_TOP = 391;
    private static final int COL_COUNT = 5;
    /** 聚焦放大：以卡片中心扩展，左 -16 / 上 -22。 */
    private static final int FOCUS_DX = -16;
    private static final int FOCUS_DY = -22;

    private float scale;
    private Typeface icons;
    private ConfigRepository store;
    private AccountRepository accountStore;
    private ConfigCodec.ConfigBundle bundle;
    private ServerConfig cfg;
    private List<ServerConfig> accounts;
    private int nextViewId = 0x30000;
    private View pendingFocus;
    private FrameLayout rootView;
    private View dialogOverlay;
    private View currentCard;

    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        scale = Math.max(0.1f, getResources().getDisplayMetrics().widthPixels / 1920f);
        icons = Typeface.createFromAsset(getAssets(), "official_icons.ttf");
        store = App.from(this).config();
        accountStore = App.from(this).accounts();
        bundle = store.load();
        cfg = bundle.server;
        accounts = accountStore.load();
        ensureCurrentInAccounts();
        setContentView(build());
    }

    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && pendingFocus != null) {
            pendingFocus.requestFocus();
        }
    }

    private int s(int v) { return Math.round(v * scale); }
    private float sf(int v) { return v * scale; }

    private void ensureCurrentInAccounts() {
        if (cfg == null || cfg.username.length() == 0) return;
        if (!accountStore.contains(accounts, cfg)) {
            accounts.add(0, ServerConfig.fromJson(cfg.toJson()));
            accountStore.save(accounts);
        }
    }

    private boolean isCurrent(ServerConfig c) {
        return accountStore.same(c, cfg);
    }

    private void logout() {
        if (cfg != null) {
            // 先按退出前的用户名/地址移除账号，再清空字段，
            // 否则清空后再比对会把条目留在本地列表里
            for (int i = accounts.size() - 1; i >= 0; i--) {
                if (accountStore.same(accounts.get(i), cfg)) accounts.remove(i);
            }
            cfg.username = "";
            cfg.password = "";
            cfg.token = "";
            cfg.userId = "";
        }
        bundle.server = cfg;
        store.save(bundle);
        accountStore.save(accounts);
        Intent i = new Intent(this, MainActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        startActivity(i);
        finish();
    }

    private void switchTo(ServerConfig target) {
        bundle.server = target;
        cfg = target;
        store.save(bundle);
        Intent i = new Intent(this, MainActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        startActivity(i);
        finish();
    }

    // ---------------- UI 构建 ----------------

    private View build() {
        FrameLayout root = new FrameLayout(this);
        rootView = root;
        root.setBackgroundResource(R.drawable.official_account_bg);
        root.setClipChildren(false);

        // 标题「账号管理」 [96,96][344,179]（官方为常规字重、浅灰 #D0D1D1）
        TextView title = label("账号管理", 60, 0xFFD0D1D1);
        put(root, title, 96, 96, 248, 83);

        // 服务器名 [96,241][370,311]
        String srv = (cfg != null && cfg.serverName.length() > 0) ? cfg.serverName
                : (cfg != null ? cfg.displayLabel() : "");
        TextView serverName = label(srv, 40, COLOR_TEXT);
        put(root, serverName, 96, 241, 274, 70);
        // 当前账号名 [380,245][557,308]
        TextView serverIp = label(cfg != null ? cfg.username : "", 36, COLOR_MUTED);
        put(root, serverIp, 380, 245, 177, 63);

        // 切换服务器 [1534,98][1824,178]：聚焦为白底黑字（官方 181 实测）
        final TextView switchBtn = pill("切换服务器", COLOR_PILL, COLOR_TEXT, 34, 16);
        switchBtn.setId(nextViewId++);
        switchBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                startActivity(new Intent(OfficialAccountActivity.this, OfficialScanDeviceActivity.class));
            }
        });
        put(root, switchBtn, 1534, 98, 290, 80);
        switchBtn.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            public void onFocusChange(View v, boolean hasFocus) {
                FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) v.getLayoutParams();
                lp.leftMargin = s(hasFocus ? 1525 : 1534);
                lp.topMargin = s(hasFocus ? 96 : 98);
                lp.width = s(hasFocus ? 308 : 290);
                lp.height = s(hasFocus ? 84 : 80);
                v.setLayoutParams(lp);
                setPillStyle(switchBtn, hasFocus, 16);
                switchBtn.setTextColor(hasFocus ? 0xFF000000 : COLOR_TEXT);
            }
        });

        // 卡片网格：第 0 张固定「添加用户」，其后是本地账号列表
        View addCard = addAddUserCard(root, 0, 0);
        View firstAccountCard = null;
        View focusTarget = null;
        View prev = addCard;
        int col = 1, row = 0;
        for (int i = 0; i < accounts.size(); i++) {
            if (col >= COL_COUNT) { col = 0; row++; }
            View card = addAccountCard(root, accounts.get(i), col, row);
            if (firstAccountCard == null) firstAccountCard = card;
            linkCards(prev, card);
            prev = card;
            if (isCurrent(accounts.get(i))) {
                focusTarget = card;
                currentCard = card;
            }
            col++;
        }
        if (focusTarget == null) focusTarget = firstAccountCard != null ? firstAccountCard : addCard;
        pendingFocus = focusTarget;
        final View pf = focusTarget;
        root.post(new Runnable() {
            public void run() { pf.requestFocus(); }
        });

        // 上下焦点：卡片 ↑ 切换服务器胶囊；胶囊 ↓ 回卡片
        switchBtn.setNextFocusLeftId(focusTarget.getId());
        addCard.setNextFocusUpId(focusTarget.getId());
        switchBtn.setNextFocusDownId(focusTarget.getId());
        return root;
    }

    private void linkCards(View left, View right) {
        left.setNextFocusRightId(right.getId());
        right.setNextFocusLeftId(left.getId());
    }

    private int cardLeft(int col) { return GRID_LEFT + col * (CARD_W + CARD_GAP); }
    private int cardTop(int row) { return GRID_TOP + row * (CARD_H + CARD_GAP); }

    private void put(FrameLayout root, View v, int left, int top, int w, int h) {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(s(w), s(h));
        lp.leftMargin = s(left);
        lp.topMargin = s(top);
        root.addView(v, lp);
    }

    /** 一张卡片：容器 + 头像/加号 + 用户名 + 状态文案 + 白胶囊操作。 */
    private static final class Card {
        FrameLayout box;
        TextView avatar;
        TextView name;
        TextView type;
        TextView action;
        int[] ua, ub, uc, ud;
        int[] fa, fb, fc, fd;
        boolean add;
    }

    /** 「添加用户」卡：加号圆 + 文案。点击进入登录页。 */
    private View addAddUserCard(FrameLayout root, int col, int row) {
        Card c = new Card();
        c.add = true;
        c.box = new FrameLayout(this);
        c.box.setClipChildren(false);

        c.avatar = new TextView(this);
        c.avatar.setText("+");
        c.avatar.setTextSize(TypedValue.COMPLEX_UNIT_PX, sf(86));
        c.avatar.setTextColor(0xFFD7D8DB);
        c.avatar.setGravity(Gravity.CENTER);
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(COLOR_CIRCLE);
        c.avatar.setBackground(circle);

        c.name = label("添加用户", 40, 0xFFC9CACC);
        c.name.setGravity(Gravity.CENTER);

        c.ua = new int[]{89, 96, 143, 143};
        c.ub = new int[]{76, 287, 168, 57};
        c.fa = new int[]{98, 106, 157, 157};
        c.fb = new int[]{84, 316, 184, 62};

        addChild(c.box, c.avatar);
        addChild(c.box, c.name);
        install(root, c, null, col, row);
        return c.box;
    }

    /** 账号卡：头像 + 用户名 + 「当前账号」/「切换到该账号」+ 操作白胶囊。 */
    private View addAccountCard(FrameLayout root, final ServerConfig account, int col, int row) {
        final boolean current = isCurrent(account);
        Card c = new Card();
        c.box = new FrameLayout(this);
        c.box.setClipChildren(false);

        c.avatar = new TextView(this);
        c.avatar.setTypeface(icons);
        c.avatar.setText("\uEBA5"); // user_fill
        c.avatar.setTextColor(0xFFE9EAEC);
        c.avatar.setGravity(Gravity.CENTER);
        c.avatar.setIncludeFontPadding(false);
        // 官方头像是"半透明圆底 + 白色人形"，不是无底人形图标
        GradientDrawable avatarBg = new GradientDrawable();
        avatarBg.setShape(GradientDrawable.OVAL);
        avatarBg.setColor(COLOR_CIRCLE);
        c.avatar.setBackground(avatarBg);

        c.name = label(account.username, 40, 0xFFD8D7D8);
        c.name.setTypeface(Typeface.DEFAULT_BOLD);
        c.name.setGravity(Gravity.CENTER);

        c.type = label(current ? "当前账号" : "", 28, 0xFFBAB9BA);
        c.type.setGravity(Gravity.CENTER);

        c.action = pill(current ? "退出登录" : "切换到该账号",
                0xFFFFFFFF, 0xFF000000, 32, 12);
        // 官方操作胶囊只做触摸点击，不参与遥控焦点（焦点始终在整张卡片上）
        c.action.setFocusable(false);
        c.action.setVisibility(View.GONE);
        c.action.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (current) showRemoveDialog();
                else switchTo(account);
            }
        });

        c.ua = new int[]{79, 66, 162, 163};
        c.ub = new int[]{80, 255, 160, 70};
        c.uc = new int[]{92, 329, 136, 46};
        c.ud = new int[]{55, 390, 242, 73};
        c.fa = new int[]{87, 22, 178, 179};
        c.fb = new int[]{88, 230, 176, 77};
        c.fc = new int[]{101, 311, 150, 51};
        c.fd = new int[]{55, 390, 242, 73};

        addChild(c.box, c.avatar);
        addChild(c.box, c.name);
        addChild(c.box, c.type);
        addChild(c.box, c.action);
        install(root, c, account, col, row);
        return c.box;
    }

    private void addChild(FrameLayout parent, View v) {
        parent.addView(v, new FrameLayout.LayoutParams(0, 0));
    }

    private void install(FrameLayout root, final Card c, final ServerConfig account,
                         int col, int row) {
        final int left = cardLeft(col);
        final int top = cardTop(row);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(s(CARD_W), s(CARD_H));
        lp.leftMargin = s(left);
        lp.topMargin = s(top);
        root.addView(c.box, lp);
        c.box.setId(nextViewId++);
        c.box.setFocusable(true);
        c.box.setClickable(true);
        c.box.setBackground(cardBgDrawable(false));
        applyCardState(c, left, top, false);

        c.box.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            public void onFocusChange(View v, boolean hasFocus) {
                applyCardState(c, left, top, hasFocus);
            }
        });
        final boolean current = account != null && isCurrent(account);
        c.box.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (c.add) {
                    startActivity(new Intent(OfficialAccountActivity.this, OfficialLoginActivity.class));
                } else if (current) {
                    showRemoveDialog();
                } else if (account != null) {
                    switchTo(account);
                }
            }
        });
    }

    /** 按聚焦态更新卡片几何与内部元素（官方 181 实测两套坐标）。 */
    private void applyCardState(Card c, int left, int top, boolean focused) {
        ViewGroup.LayoutParams lp = c.box.getLayoutParams();
        if (lp instanceof FrameLayout.LayoutParams) {
            FrameLayout.LayoutParams flp = (FrameLayout.LayoutParams) lp;
            flp.width = s(focused ? CARD_W + 32 : CARD_W);
            flp.height = s(focused ? CARD_H + 44 : CARD_H);
            flp.leftMargin = s(focused ? left + FOCUS_DX : left);
            flp.topMargin = s(focused ? top + FOCUS_DY : top);
            c.box.setLayoutParams(flp);
        }
        c.box.setBackground(cardBgDrawable(focused));
        place(c.avatar, focused ? c.fa : c.ua);
        place(c.name, focused ? c.fb : c.ub);
        if (c.type != null) place(c.type, focused ? c.fc : c.uc);
        if (c.action != null) {
            place(c.action, focused ? c.fd : c.ud);
            c.action.setVisibility(focused ? View.VISIBLE : View.GONE);
        }
        if (!c.add) {
            // 官方头像人形只占圆底约一半（181 实测人形高约 94 / 圆 178）
            c.avatar.setTextSize(TypedValue.COMPLEX_UNIT_PX, sf(focused ? 128 : 116));
            c.name.setTextSize(TypedValue.COMPLEX_UNIT_PX, sf(focused ? 44 : 40));
            if (c.type != null) c.type.setTextSize(TypedValue.COMPLEX_UNIT_PX, sf(focused ? 30 : 28));
        }
    }

    private void place(View v, int[] r) {
        ViewGroup.LayoutParams lp = v.getLayoutParams();
        FrameLayout.LayoutParams flp = (lp instanceof FrameLayout.LayoutParams)
                ? (FrameLayout.LayoutParams) lp : new FrameLayout.LayoutParams(s(r[2]), s(r[3]));
        flp.width = s(r[2]);
        flp.height = s(r[3]);
        flp.leftMargin = s(r[0]);
        flp.topMargin = s(r[1]);
        v.setLayoutParams(flp);
    }

    private GradientDrawable cardBgDrawable(boolean focused) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(COLOR_CARD);
        g.setCornerRadius(sf(12));
        g.setStroke(focused ? s(2) : s(1), focused ? 0xFFFFFFFF : COLOR_CARD_BORDER);
        return g;
    }

    // ---------------- 移除账号确认框 ----------------

    private void showRemoveDialog() {
        if (dialogOverlay != null || rootView == null) return;
        String user = cfg != null ? cfg.username : "";

        final FrameLayout overlay = new FrameLayout(this);
        // 官方弹窗出现时会隐去账号页全部内容，只保留同一张模糊首页底图
        overlay.setBackgroundResource(R.drawable.official_account_bg);
        overlay.setClickable(true);
        overlay.setFocusable(true);
        overlay.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { /* 拦截点击，不穿透 */ }
        });
        rootView.addView(overlay, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        dialogOverlay = overlay;

        // 弹窗本体 [693,338][1227,741]
        FrameLayout box = new FrameLayout(this);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFF2A2C31);
        bg.setCornerRadius(sf(16));
        box.setBackground(bg);
        put(overlay, box, 693, 338, 534, 403);

        TextView caption = label("移除账号", 26, 0xFF8E8F92);
        put(box, caption, 32, 32, 470, 42);

        TextView hint = label("确定移除 " + user + " 账号？", 34, COLOR_TEXT);
        hint.setTypeface(Typeface.DEFAULT_BOLD);
        put(box, hint, 32, 128, 470, 49);

        TextView remove = pill("移除", 0xFFFFFFFF, 0xFF000000, 32, 35);
        remove.setId(nextViewId++);
        put(box, remove, 23, 228, 488, 70);

        TextView cancel = label("取消", 32, 0xFFD7D8DB);
        cancel.setGravity(Gravity.CENTER);
        cancel.setFocusable(true);
        cancel.setClickable(true);
        cancel.setId(nextViewId++);
        put(box, cancel, 32, 303, 470, 68);

        remove.setNextFocusDownId(cancel.getId());
        cancel.setNextFocusUpId(remove.getId());
        remove.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                dismissDialog();
                logout();
            }
        });
        cancel.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { dismissDialog(); }
        });
        remove.requestFocus();
    }

    private void dismissDialog() {
        if (dialogOverlay == null) return;
        rootView.removeView(dialogOverlay);
        dialogOverlay = null;
        if (currentCard != null) currentCard.requestFocus();
    }

    // ---------------- 视图工厂 ----------------

    private TextView label(String text, int size, int color) {
        TextView v = new TextView(this);
        v.setText(text);
        v.setTextSize(TypedValue.COMPLEX_UNIT_PX, sf(size));
        v.setTextColor(color);
        v.setGravity(Gravity.CENTER_VERTICAL);
        return v;
    }

    private TextView pill(String text, int bgColor, int textColor, int textSize, int radius) {
        TextView v = new TextView(this);
        v.setText(text);
        v.setTextSize(TypedValue.COMPLEX_UNIT_PX, sf(textSize));
        v.setTextColor(textColor);
        v.setGravity(Gravity.CENTER);
        v.setFocusable(true);
        v.setClickable(true);
        GradientDrawable g = new GradientDrawable();
        g.setColor(bgColor);
        g.setCornerRadius(sf(radius));
        v.setBackground(g);
        v.setTag(radius);
        return v;
    }

    private void setPillStyle(TextView v, boolean focused, int radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(focused ? 0xFFFFFFFF : COLOR_PILL);
        g.setCornerRadius(sf(radius));
        v.setBackground(g);
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
