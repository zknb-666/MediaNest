package com.juying.medianest.ui;

import com.juying.medianest.app.R;

import com.juying.medianest.app.App;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.text.method.PasswordTransformationMethod;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.ScrollView;
import android.widget.TextView;

import com.juying.medianest.core.model.ConfigCodec;
import com.juying.medianest.core.model.FnException;
import com.juying.medianest.core.model.ServerConfig;
import com.juying.medianest.domain.model.AuthConfig;
import com.juying.medianest.domain.repository.AccountRepository;
import com.juying.medianest.domain.repository.ConfigRepository;
import com.juying.medianest.domain.usecase.MediaUseCases;

/**
 * 官方飞牛 TV 1.5.1「登录」页 1:1 复刻。
 *
 * 几何按 1920×1080 电视屏幕设计：
 * 顶栏 logo / 服务器名 / 账号 / 切换服务器 → 账号登录表单。
 * 官方原页还有「扫码登录」tab，其二维码由 FN Connect 云端生成，本地无法实现，
 * 已按要求整页移除，只保留影视账号登录。
 *
 * 登录走 MediaUseCases.verify(cfg)（即 FnNasApi.login + server/info），成功写回 ConfigRepository
 * 并跳回首页；失败按 FnException.userMessage() 提示（密码错误 / 签名错误等）。
 */
public final class OfficialLoginActivity extends Activity {

    private static final int COLOR_BG = 0xFF15171A;
    private static final int COLOR_TEXT = 0xFFFFFFFF;
    private static final int COLOR_MUTED = 0xFF9A9B9E;
    private static final int COLOR_INPUT = 0xFF1B1D21;
    private static final int COLOR_INPUT_BORDER = 0xFF3A3B40;
    private static final int COLOR_PILL = 0xFF2A2C31;
    private static final int COLOR_LOGIN_BTN = 0xFFFFFFFF;
    private static final int COLOR_LOGIN_BTN_TEXT = 0xFF000000;
    /** 聚焦态：输入框换成更亮的底 + 白色描边，让用户一眼看出焦点在哪。 */
    private static final int COLOR_INPUT_FOCUS = 0xFF33353B;
    private static final int COLOR_FOCUS_BORDER = 0xFFFFFFFF;

    private float scale;
    private Typeface icons;
    private ConfigRepository store;
    private ConfigCodec.ConfigBundle bundle;
    private ServerConfig cfg;

    private FrameLayout contentAccount;
    private EditText etUser;
    private EditText etPass;
    private TextView eye;
    private TextView btnLogin;
    private TextView btnAdvanced;
    private EditText etSecret;
    private EditText etApiKey;
    private FrameLayout advancedPanel;
    private boolean passVisible = false;
    private boolean authLoading = false;
    /** 动态创建的控件没有 XML id，用独立号段分配 id 以建立显式方向键焦点链。 */
    private int nextViewId = 0x40000;
    /** 从「选择影视服务器」带来的待登录服务器；为空表示登录当前服务器。 */
    private String pendingBaseUrl;
    private String pendingName;
    private String pendingMode;
    private String pendingMethod;

    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        scale = Math.max(0.1f, getResources().getDisplayMetrics().widthPixels / 1920f);
        icons = Typeface.createFromAsset(getAssets(), "official_icons.ttf");
        store = App.from(this).config();
        bundle = store.load();
        cfg = bundle.server;
        pendingBaseUrl = getIntent().getStringExtra("pendingBaseUrl");
        pendingName = getIntent().getStringExtra("pendingName");
        pendingMode = getIntent().getStringExtra("pendingMode");
        pendingMethod = getIntent().getStringExtra("pendingMethod");
        if (pendingBaseUrl != null && pendingBaseUrl.length() > 0) {
            // 只改内存中的目标地址，登录成功后才落盘，避免把用户困在未登录服务器上
            cfg.baseUrl = pendingBaseUrl;
            cfg.serverName = pendingName == null ? "" : pendingName;
            cfg.username = "";
            cfg.password = "";
            cfg.token = "";
            cfg.userId = "";
            if (pendingMode != null && pendingMode.length() > 0) {
                cfg.apiMode = pendingMode;
            }
            if (pendingMethod != null && pendingMethod.length() > 0) {
                cfg.sourceMethod = pendingMethod;
            }
        }
        setContentView(build());
        if ("JELLYFIN".equalsIgnoreCase(cfg.apiMode)) {
            btnAdvanced.setText("Jellyfin · 官方 API");
            btnAdvanced.setFocusable(false);
            btnAdvanced.setClickable(false);
            advancedPanel.setVisibility(View.GONE);
        }
        // 默认焦点放在用户名：删除扫码 tab 后首个可聚焦控件是右上角「切换服务器」，
        // 直接按方向键会先跑到顶栏，体验不对
        contentAccount.post(new Runnable() {
            public void run() { etUser.requestFocus(); }
        });
        loadWebAuthConfig();
    }

    private int s(int v) { return Math.round(v * scale); }
    private float sf(int v) { return v * scale; }

    private View build() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(COLOR_BG);

        // 顶栏 logo [0,60][405,145]
        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.ic_logo);
        logo.setScaleType(ImageView.ScaleType.FIT_START);
        put(root, logo, 80, 60, 405, 85);

        // 服务器名 [605,69][1251,132]
        String srv = (cfg != null && cfg.serverName.length() > 0) ? cfg.serverName
                : (cfg != null ? cfg.displayLabel() : "");
        TextView serverName = label(srv, 38, COLOR_TEXT);
        // 官方把服务器名右对齐到账号左侧
        serverName.setGravity(Gravity.CENTER_VERTICAL | Gravity.RIGHT);
        put(root, serverName, 605, 69, 646, 63);
        // 账号 [1283,69][1460,132]
        TextView userTag = label((cfg != null ? cfg.username : ""), 30, COLOR_MUTED);
        put(root, userTag, 1283, 69, 177, 63);
        // 切换服务器 [1510,60][1824,140]
        TextView switchBtn = pill("切换服务器", COLOR_PILL, COLOR_TEXT, 32, 16);
        switchBtn.setId(nextViewId++);
        switchBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                startActivity(new Intent(OfficialLoginActivity.this, OfficialScanDeviceActivity.class));
            }
        });
        put(root, switchBtn, 1510, 60, 314, 80);
        // 顶栏胶囊聚焦时同样反色 + 放大
        switchBtn.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            public void onFocusChange(View v, boolean hasFocus) {
                GradientDrawable g = new GradientDrawable();
                g.setColor(hasFocus ? 0xFFFFFFFF : COLOR_PILL);
                g.setCornerRadius(sf(16));
                v.setBackground(g);
                ((TextView) v).setTextColor(hasFocus ? 0xFF000000 : COLOR_TEXT);
                v.setScaleX(hasFocus ? 1.04f : 1f);
                v.setScaleY(hasFocus ? 1.04f : 1f);
            }
        });

        // ---- 账号登录表单 [0,290][1920,1080] ----
        contentAccount = buildAccountContent();
        root.addView(contentAccount);

        // 方向键顺序：切换服务器 ↓ 用户名 ↓ 密码 ⇄ 眼睛，密码 ↓ 登录
        switchBtn.setNextFocusDownId(etUser.getId());
        etUser.setNextFocusUpId(switchBtn.getId());
        etUser.setNextFocusDownId(etPass.getId());
        etPass.setNextFocusUpId(etUser.getId());
        etPass.setNextFocusRightId(eye.getId());
        eye.setNextFocusLeftId(etPass.getId());
        eye.setNextFocusUpId(etUser.getId());
        eye.setNextFocusDownId(btnLogin.getId());
        btnLogin.setNextFocusUpId(etPass.getId());
        btnLogin.setNextFocusLeftId(eye.getId());
        btnLogin.setNextFocusRightId(eye.getId());
        btnLogin.setNextFocusDownId(btnAdvanced.getId());
        btnAdvanced.setNextFocusUpId(btnLogin.getId());
        btnAdvanced.setNextFocusDownId(etSecret.getId());
        etSecret.setNextFocusUpId(btnAdvanced.getId());
        etSecret.setNextFocusDownId(etApiKey.getId());
        etApiKey.setNextFocusUpId(etSecret.getId());
        etApiKey.setNextFocusDownId(btnAdvanced.getId());
        // 顶栏只有一个可聚焦控件，避免左键按几何搜到密码旁的眼睛
        switchBtn.setNextFocusLeftId(etUser.getId());
        return root;
    }

    /** 账号页：ScrollView 内用户名/密码/眼睛/登录。 */
    private FrameLayout buildAccountContent() {
        FrameLayout wrap = new FrameLayout(this);
        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        FrameLayout.LayoutParams svLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        svLp.topMargin = s(290);
        wrap.addView(sv, svLp);

        FrameLayout inner = new FrameLayout(this);
        sv.addView(inner, new ViewGroup.LayoutParams(s(1920), s(1100)));

        // 用户名 label [690,370][1230,419]
        put(inner, label("用户名", 32, COLOR_MUTED), 690, 80, 540, 49);
        // et_user_name [690,427][1230,515]
        etUser = input(InputType.TYPE_CLASS_TEXT, "请输入用户名");
        etUser.setId(nextViewId++);
        put(inner, etUser, 690, 137, 540, 88);
        // 密码 label [690,555][1920,604]
        put(inner, label("密码", 32, COLOR_MUTED), 690, 265, 1230, 49);
        // et_password [690,612][1122,700]
        etPass = input(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD, "请输入密码");
        etPass.setTransformationMethod(PasswordTransformationMethod.getInstance());
        etPass.setId(nextViewId++);
        put(inner, etPass, 690, 322, 432, 88);
        // cl_eye [1142,612][1230,700]
        eye = new TextView(this);
        eye.setId(nextViewId++);
        eye.setTypeface(icons);
        eye.setText("\uEAF9"); // eye_closed
        eye.setTextSize(TypedValue.COMPLEX_UNIT_PX, sf(40));
        eye.setTextColor(COLOR_MUTED);
        eye.setGravity(Gravity.CENTER);
        eye.setFocusable(true);
        eye.setClickable(true);
        eye.setBackground(inputBg(false));
        bindInputFocus(eye);
        eye.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { togglePassVisible(); }
        });
        put(inner, eye, 1142, 322, 88, 88);
        // tv_login [690,740][1230,828]
        btnLogin = pill("登录", COLOR_LOGIN_BTN, COLOR_LOGIN_BTN_TEXT, 40, 12);
        btnLogin.setId(nextViewId++);
        btnLogin.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { doLogin(); }
        });
        // 登录按钮聚焦时反色 + 放大，和输入框的白描边区分开
        btnLogin.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            public void onFocusChange(View v, boolean hasFocus) {
                GradientDrawable g = new GradientDrawable();
                g.setColor(hasFocus ? COLOR_PILL : COLOR_LOGIN_BTN);
                g.setCornerRadius(sf(12));
                if (hasFocus) g.setStroke(s(3), COLOR_FOCUS_BORDER);
                v.setBackground(g);
                ((TextView) v).setTextColor(hasFocus ? COLOR_TEXT : COLOR_LOGIN_BTN_TEXT);
                v.setScaleX(hasFocus ? 1.04f : 1f);
                v.setScaleY(hasFocus ? 1.04f : 1f);
            }
        });
        put(inner, btnLogin, 690, 450, 540, 88);

        btnAdvanced = pill("签名参数：准备获取", COLOR_PILL, COLOR_TEXT, 30, 12);
        btnAdvanced.setId(nextViewId++);
        btnAdvanced.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (authLoading) return;
                if (etSecret.getText().length() == 0 || etApiKey.getText().length() == 0) {
                    loadWebAuthConfig();
                    return;
                }
                boolean show = advancedPanel.getVisibility() != View.VISIBLE;
                advancedPanel.setVisibility(show ? View.VISIBLE : View.GONE);
            }
        });
        put(inner, btnAdvanced, 690, 570, 540, 70);

        advancedPanel = new FrameLayout(this);
        advancedPanel.setVisibility(View.GONE);
        put(inner, advancedPanel, 690, 680, 540, 330);
        put(advancedPanel, label("authx sign secret", 26, COLOR_MUTED), 0, 0, 540, 42);
        etSecret = input(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD,
                "正在从网页端获取");
        etSecret.setId(nextViewId++);
        etSecret.setEnabled(false);
        etSecret.setFocusable(false);
        put(advancedPanel, etSecret, 0, 52, 540, 80);
        put(advancedPanel, label("authx api key", 26, COLOR_MUTED), 0, 165, 540, 42);
        etApiKey = input(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD,
                "正在从网页端获取");
        etApiKey.setId(nextViewId++);
        etApiKey.setEnabled(false);
        etApiKey.setFocusable(false);
        put(advancedPanel, etApiKey, 0, 217, 540, 80);
        etSecret.setText(cfg == null ? "" : cfg.signSecret);
        etApiKey.setText(cfg == null ? "" : cfg.signApiKey);

        if (cfg != null && cfg.username.length() > 0) etUser.setText(cfg.username);
        return wrap;
    }

    private void loadWebAuthConfig() {
        if (cfg == null || "JELLYFIN".equalsIgnoreCase(cfg.apiMode) || authLoading
                || cfg.baseUrl.length() == 0) return;
        authLoading = true;
        btnAdvanced.setText("签名参数：正在从网页端获取…");
        final String baseUrl = cfg.baseUrl;
        new Thread(new Runnable() {
            public void run() {
                try {
                    final AuthConfig result = App.from(OfficialLoginActivity.this).fetchAuthConfig(baseUrl);
                    runOnUiThread(new Runnable() {
                        public void run() {
                            authLoading = false;
                            if (isFinishing()) return;
                            cfg.signSecret = result.secret;
                            cfg.signApiKey = result.apiKey;
                            etSecret.setText(result.secret);
                            etApiKey.setText(result.apiKey);
                            btnAdvanced.setText("签名参数：已从网页端获取");
                        }
                    });
                } catch (final Exception e) {
                    runOnUiThread(new Runnable() {
                        public void run() {
                            authLoading = false;
                            if (isFinishing()) return;
                            btnAdvanced.setText("签名参数：获取失败，点击重试");
                            Ui.toast(OfficialLoginActivity.this,
                                    e.getMessage() == null ? "签名参数获取失败" : e.getMessage());
                        }
                    });
                }
            }
        }, "fntv-web-auth").start();
    }

    private void togglePassVisible() {
        passVisible = !passVisible;
        etPass.setInputType(passVisible
                ? InputType.TYPE_CLASS_TEXT
                : InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        etPass.setTransformationMethod(passVisible
                ? null : PasswordTransformationMethod.getInstance());
        eye.setTextColor(passVisible ? COLOR_TEXT : COLOR_MUTED);
        // 保持光标在末尾
        etPass.setSelection(etPass.getText().length());
    }

    private void doLogin() {
        final String u = etUser.getText().toString().trim();
        final String p = etPass.getText().toString();
        if (u.length() == 0) { Ui.toast(this, "请输入用户名"); return; }
        if (p.length() == 0) { Ui.toast(this, "请输入密码"); return; }
        String secret = etSecret.getText().toString().trim();
        String apiKey = etApiKey.getText().toString().trim();
        if (!"JELLYFIN".equalsIgnoreCase(cfg.apiMode)
                && (secret.length() == 0 || apiKey.length() == 0)) {
            loadWebAuthConfig();
            Ui.toast(this, "正在从网页端获取签名参数，请稍后重试");
            return;
        }
        cfg.signSecret = secret;
        cfg.signApiKey = apiKey;
        btnLogin.setEnabled(false);
        btnLogin.setText("登录中...");
        new Thread(new Runnable() {
            public void run() {
                try {
                    cfg.username = u;
                    cfg.password = p;
                    MediaUseCases useCases = App.from(OfficialLoginActivity.this).useCases(cfg);
                    useCases.verify(cfg);
                    bundle.server = cfg;
                    store.save(bundle);
                    AccountRepository accountStore = App.from(OfficialLoginActivity.this).accounts();
                    accountStore.addOrReplace(accountStore.load(), cfg);
                    runOnUiThread(new Runnable() {
                        public void run() {
                            Ui.toast(OfficialLoginActivity.this, "登录成功");
                            Intent i = new Intent(OfficialLoginActivity.this, MainActivity.class);
                            i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
                            startActivity(i);
                            finish();
                        }
                    });
                } catch (final FnException e) {
                    runOnUiThread(new Runnable() {
                        public void run() { showLoginError(e.userMessage()); }
                    });
                } catch (final Exception e) {
                    runOnUiThread(new Runnable() {
                        public void run() { showLoginError(e.getMessage() == null ? "登录失败" : e.getMessage()); }
                    });
                }
            }
        }).start();
    }

    private void showLoginError(String msg) {
        btnLogin.setEnabled(true);
        btnLogin.setText("登录");
        Ui.toast(this, msg);
    }

    // ---------------- 视图工厂 ----------------

    private void put(FrameLayout root, View v, int left, int top, int w, int h) {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(s(w), s(h));
        lp.leftMargin = s(left);
        lp.topMargin = s(top);
        root.addView(v, lp);
    }

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
        return v;
    }

    private GradientDrawable inputBg(boolean focused) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(focused ? COLOR_INPUT_FOCUS : COLOR_INPUT);
        g.setCornerRadius(sf(10));
        g.setStroke(s(focused ? 3 : 1), focused ? COLOR_FOCUS_BORDER : COLOR_INPUT_BORDER);
        return g;
    }

    /** 输入框 / 眼睛按钮共用：聚焦时亮底 + 白描边。 */
    private void bindInputFocus(final View v) {
        v.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            public void onFocusChange(View view, boolean hasFocus) {
                view.setBackground(inputBg(hasFocus));
            }
        });
    }

    private EditText input(int inputType, String hint) {
        EditText v = new EditText(this);
        v.setInputType(inputType);
        v.setHint(hint);
        v.setHintTextColor(0xFF5A5B60);
        v.setTextColor(COLOR_TEXT);
        v.setTextSize(TypedValue.COMPLEX_UNIT_PX, sf(36));
        v.setGravity(Gravity.CENTER_VERTICAL);
        v.setPadding(s(24), 0, s(24), 0);
        v.setBackground(inputBg(false));
        v.setSingleLine(true);
        bindInputFocus(v);
        return v;
    }

    /** 单行 EditText 会把 UP 当成光标移动吞掉，这里在 Activity 层把密码的 UP 交回用户名。 */
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN
                && event.getKeyCode() == KeyEvent.KEYCODE_DPAD_UP
                && etPass != null && etPass.hasFocus()) {
            etUser.requestFocus();
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            finish();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }
}
