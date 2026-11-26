package com.juying.medianest.ui;

import com.juying.medianest.app.R;

import com.juying.medianest.app.App;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import com.juying.medianest.core.model.ConfigCodec;
import com.juying.medianest.domain.repository.ConfigRepository;

/**
 * 官方飞牛 TV 1.5.1「添加影视服务器」页复刻。
 *
 * 181 实机几何：logo [0,60][501,145]，标题 [743,145][1177,228]，
 * 「IP / FN ID / 域名」输入 [674,322][1246,416]，端口 [690,510][935,598]，
 * 协议 https 开关 [1142,538][1206,570]，连接按钮 [690,638][1230,726]。
 *
 * 连接后把服务器返回给「选择影视服务器」页，并立即进入账号登录。
 */
public final class OfficialAddServerActivity extends Activity {

    private static final int COLOR_BG = 0xFF15171A;
    private static final int COLOR_TEXT = 0xFFFFFFFF;
    private static final int COLOR_MUTED = 0xFF9A9B9E;
    private static final int COLOR_INPUT = 0xFF1B1D21;
    private static final int COLOR_INPUT_BORDER = 0xFF3A3B40;

    private float scale;
    private ConfigRepository store;
    private ConfigCodec.ConfigBundle bundle;
    private EditText etHost;
    private EditText etPort;
    private HttpsSwitch swHttps;
    private TextView btnConnect;
    private TextView fnOption;
    private TextView jellyfinOption;
    private String selectedMode = "FNOS";

    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        scale = Math.max(0.1f, getResources().getDisplayMetrics().widthPixels / 1920f);
        store = App.from(this).config();
        bundle = store.load();
        setContentView(build());
    }

    private int s(int v) { return Math.round(v * scale); }
    private float sf(int v) { return v * scale; }

    private View build() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(COLOR_BG);

        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.ic_logo);
        logo.setScaleType(ImageView.ScaleType.FIT_START);
        put(root, logo, 80, 60, 501, 85);

        TextView title = new TextView(this);
        title.setText("添加影视服务器");
        title.setTextSize(TypedValue.COMPLEX_UNIT_PX, sf(56));
        title.setTextColor(0xFFD0D1D1);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setGravity(Gravity.CENTER);
        put(root, title, 743, 145, 434, 83);

        put(root, label("服务器类型", 32, COLOR_MUTED), 690, 250, 540, 44);
        fnOption = typeOption("飞牛影视", R.id.add_server_type_fnos);
        jellyfinOption = typeOption("Jellyfin", R.id.add_server_type_jellyfin);
        put(root, fnOption, 690, 300, 260, 72);
        put(root, jellyfinOption, 970, 300, 276, 72);
        fnOption.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { selectMode("FNOS"); }
        });
        jellyfinOption.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { selectMode("JELLYFIN"); }
        });

        put(root, label("IP / FN ID / 域名", 32, COLOR_MUTED), 690, 396, 540, 49);

        etHost = input(InputType.TYPE_CLASS_TEXT, "", R.id.add_server_host);
        etHost.setText(defaultSubnet());
        put(root, etHost, 674, 444, 572, 94);

        put(root, label("端口", 32, COLOR_MUTED), 690, 552, 245, 49);
        etPort = input(InputType.TYPE_CLASS_NUMBER, "", R.id.add_server_port);
        etPort.setText("5667");
        put(root, etPort, 690, 600, 245, 88);

        put(root, label("协议", 32, COLOR_MUTED), 985, 552, 245, 49);
        // 官方协议项是一个和端口输入框同款的容器 [985,510][1230,598]
        View protoBox = new View(this);
        GradientDrawable pb = new GradientDrawable();
        pb.setColor(COLOR_INPUT);
        pb.setCornerRadius(sf(10));
        pb.setStroke(s(1), COLOR_INPUT_BORDER);
        protoBox.setBackground(pb);
        put(root, protoBox, 985, 600, 245, 88);

        TextView proto = label("https", 40, COLOR_TEXT);
        proto.setGravity(Gravity.CENTER_VERTICAL);
        put(root, proto, 1009, 600, 130, 88);

        swHttps = new HttpsSwitch(this);
        swHttps.setChecked(true);
        swHttps.setId(R.id.add_server_https);
        put(root, swHttps, 1142, 628, 64, 32);

        TextView connect = new TextView(this);
        btnConnect = connect;
        connect.setText("连接");
        connect.setTextSize(TypedValue.COMPLEX_UNIT_PX, sf(40));
        connect.setTextColor(COLOR_TEXT);
        connect.setGravity(Gravity.CENTER);
        connect.setFocusable(true);
        connect.setClickable(true);
        GradientDrawable g = new GradientDrawable();
        g.setColor(0xFF23252B);
        g.setCornerRadius(sf(12));
        g.setStroke(s(1), COLOR_INPUT_BORDER);
        connect.setBackground(g);
        connect.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { doConnect(); }
        });
        put(root, connect, 690, 730, 540, 88);

        fnOption.setNextFocusRightId(jellyfinOption.getId());
        jellyfinOption.setNextFocusLeftId(fnOption.getId());
        jellyfinOption.setNextFocusDownId(etHost.getId());
        etHost.setNextFocusUpId(jellyfinOption.getId());
        etHost.setNextFocusDownId(etPort.getId());
        etPort.setNextFocusDownId(swHttps.getId());
        swHttps.setNextFocusDownId(connect.getId());
        updateTypeOptions();
        return root;
    }

    private TextView typeOption(String value, int id) {
        TextView option = new TextView(this);
        option.setId(id);
        option.setText(value);
        option.setTextSize(TypedValue.COMPLEX_UNIT_PX, sf(34));
        option.setTextColor(COLOR_TEXT);
        option.setGravity(Gravity.CENTER);
        option.setFocusable(true);
        option.setClickable(true);
        option.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            public void onFocusChange(View v, boolean hasFocus) {
                updateTypeOptions();
            }
        });
        return option;
    }

    private void selectMode(String mode) {
        selectedMode = mode;
        if (etPort != null) {
            etPort.setText("JELLYFIN".equals(mode) ? "8096" : "5667");
        }
        if (swHttps != null) {
            swHttps.setChecked(!"JELLYFIN".equals(mode));
        }
        updateTypeOptions();
    }

    private void updateTypeOptions() {
        if (fnOption == null || jellyfinOption == null) return;
        styleTypeOption(fnOption, "FNOS".equals(selectedMode), 0xFF4B7EF7, fnOption.isFocused());
        styleTypeOption(jellyfinOption, "JELLYFIN".equals(selectedMode), 0xFFAA5CC3,
                jellyfinOption.isFocused());
    }

    private void styleTypeOption(TextView option, boolean selected, int color, boolean focused) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(selected ? 0xFF253453 : COLOR_INPUT);
        bg.setCornerRadius(sf(12));
        bg.setStroke(s(focused ? 3 : selected ? 2 : 1),
                focused ? 0xFFFFFFFF : selected ? color : COLOR_INPUT_BORDER);
        option.setBackground(bg);
        option.setTextColor(focused || selected ? COLOR_TEXT : COLOR_MUTED);
    }

    /** 官方默认把当前网段前两段填入（如 181 实机显示 192.168.）。 */
    private String defaultSubnet() {
        try {
            String host = bundle.server.baseUrl.replaceFirst("^[a-zA-Z]+://", "");
            int colon = host.indexOf(':');
            if (colon > 0) host = host.substring(0, colon);
            String[] parts = host.split("\\.");
            if (parts.length >= 3) return parts[0] + "." + parts[1] + ".";
        } catch (Exception ignored) {
        }
        return "";
    }

    private void doConnect() {
        String host = etHost.getText().toString().trim();
        if (host.length() == 0) { Ui.toast(this, "请输入服务器地址"); return; }
        final String mode = selectedMode;
        int port;
        try {
            port = Integer.parseInt(etPort.getText().toString().trim());
        } catch (Exception e) {
            port = "JELLYFIN".equals(mode) ? 8096 : 5667;
        }
        boolean https = swHttps.isChecked();
        StringBuilder url = new StringBuilder(https ? "https://" : "http://").append(host);
        boolean defaultPort = (https && port == 443) || (!https && port == 80);
        if (!defaultPort) url.append(':').append(port);
        final String baseUrl = url.toString();
        final String fallbackName = host;
        final int finalPort = port;
        btnConnect.setEnabled(false);
        btnConnect.setText("正在连接服务器…");
        new Thread(new Runnable() {
            public void run() {
                final ConfigCodec.ServerInfo detected = "JELLYFIN".equals(mode)
                        ? App.from(OfficialAddServerActivity.this).probeJellyfin(baseUrl) : null;
                runOnUiThread(new Runnable() {
                    public void run() {
                        if (isFinishing()) {
                            return;
                        }
                        btnConnect.setEnabled(true);
                        btnConnect.setText("连接");
                        ConfigCodec.ServerInfo info = new ConfigCodec.ServerInfo();
                        info.baseUrl = baseUrl;
                        info.name = fallbackName;
                        info.port = finalPort;
                        info.verified = false;
                        if (detected != null) {
                            info.name = detected.name;
                            info.verified = detected.verified;
                            info.latencyMs = detected.latencyMs;
                        } else {
                            for (ConfigCodec.ServerInfo s : bundle.discovered) {
                                if (baseUrl.equals(s.baseUrl)) {
                                    info.name = s.name;
                                    info.verified = s.verified;
                                    break;
                                }
                            }
                        }
                        info.method = "MANUAL";
                        info.signature = "JELLYFIN".equals(mode) ? "jellyfin" : "fnos-api";
                        upsertServer(info);
                        store.save(bundle);
                        Intent data = new Intent();
                        data.putExtra("pendingBaseUrl", info.baseUrl);
                        data.putExtra("pendingName", info.name);
                        data.putExtra("pendingMode", mode);
                        data.putExtra("pendingMethod", "MANUAL");
                        setResult(RESULT_OK, data);
                        Ui.toast(OfficialAddServerActivity.this, "正在进入账号登录");
                        finish();
                    }
                });
            }
        }, "medianest-connect-server").start();
    }

    private void upsertServer(ConfigCodec.ServerInfo info) {
        for (int i = 0; i < bundle.discovered.size(); i++) {
            ConfigCodec.ServerInfo existing = bundle.discovered.get(i);
            if (info.baseUrl.equals(existing.baseUrl)) {
                bundle.discovered.set(i, info);
                return;
            }
        }
        bundle.discovered.add(info);
    }

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

    private EditText input(int inputType, String hint, int id) {
        EditText v = new EditText(this);
        v.setInputType(inputType);
        v.setHint(hint);
        v.setHintTextColor(0xFF5A5B60);
        v.setTextColor(COLOR_TEXT);
        v.setTextSize(TypedValue.COMPLEX_UNIT_PX, sf(40));
        v.setGravity(Gravity.CENTER_VERTICAL);
        v.setPadding(s(24), 0, s(24), 0);
        GradientDrawable g = new GradientDrawable();
        g.setColor(COLOR_INPUT);
        g.setCornerRadius(sf(10));
        g.setStroke(s(1), COLOR_INPUT_BORDER);
        v.setBackground(g);
        v.setSingleLine(true);
        v.setId(id);
        return v;
    }

    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            int code = event.getKeyCode();
            if (code == KeyEvent.KEYCODE_DPAD_DOWN) {
                if ((fnOption != null && fnOption.hasFocus())
                        || (jellyfinOption != null && jellyfinOption.hasFocus())) {
                    etHost.requestFocus();
                    return true;
                }
                if (etHost != null && etHost.hasFocus()) {
                    etPort.requestFocus();
                    return true;
                }
                if (etPort != null && etPort.hasFocus()) {
                    swHttps.requestFocus();
                    return true;
                }
                if (swHttps != null && swHttps.hasFocus()) {
                    btnConnect.requestFocus();
                    return true;
                }
            } else if (code == KeyEvent.KEYCODE_DPAD_UP) {
                if (etHost != null && etHost.hasFocus()) {
                    ("JELLYFIN".equals(selectedMode) ? jellyfinOption : fnOption).requestFocus();
                    return true;
                }
                if (btnConnect != null && btnConnect.hasFocus()) {
                    swHttps.requestFocus();
                    return true;
                }
                if (swHttps != null && swHttps.hasFocus()) {
                    etPort.requestFocus();
                    return true;
                }
                if (etPort != null && etPort.hasFocus()) {
                    etHost.requestFocus();
                    return true;
                }
            }
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

    /**
     * 协议开关：官方为 64×28 轨道 + 28 直径白色滑块（蓝 #3374DB）。
     * 系统 Switch 在这个尺寸下会被裁切，这里按 181 实测像素自绘。
     */
    private static final class HttpsSwitch extends View {
        private static final int TRACK_ON = 0xFF3374DB;
        private static final int TRACK_OFF = 0xFF4A4B50;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private boolean checked = true;

        HttpsSwitch(Context context) {
            super(context);
            setFocusable(true);
            setClickable(true);
            setOnClickListener(new OnClickListener() {
                public void onClick(View v) {
                    checked = !checked;
                    invalidate();
                }
            });
        }

        void setChecked(boolean value) {
            checked = value;
            invalidate();
        }

        boolean isChecked() {
            return checked;
        }

        protected void onDraw(Canvas canvas) {
            float w = getWidth();
            float h = getHeight();
            float r = h * 14f / 32f;
            float top = h * 2f / 32f;
            paint.setColor(checked ? TRACK_ON : TRACK_OFF);
            RectF track = new RectF(0, top, w, h - top);
            canvas.drawRoundRect(track, r, r, paint);
            paint.setColor(0xFFFFFFFF);
            float cx = checked ? w - r : r;
            canvas.drawCircle(cx, h / 2f, r, paint);
        }
    }
}
