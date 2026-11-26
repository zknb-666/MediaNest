package com.juying.medianest.ui;

import com.juying.medianest.app.R;

import com.juying.medianest.app.App;
import com.juying.medianest.core.util.L;
import com.juying.medianest.player.DeviceProbe;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.util.TypedValue;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import com.juying.medianest.core.model.ConfigCodec;
import com.juying.medianest.domain.repository.ConfigRepository;
import com.juying.medianest.core.model.ServerConfig;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 启动引导页。
 *
 * 首次启动：检测播放方式 / 解码器 / 格式 → 保存结果 → 进入服务器选择页。
 * 后续启动：已登录时直接读能力快照并进入主界面；未登录时进入服务器选择页。
 * 设置页的「重新检测」会带 redetect=true 进来，强制重测一次。
 */
public final class BootstrapActivity extends Activity {

    private TextView status;
    private ConfigRepository store;
    private static final long PROBE_TIMEOUT_MS = 5000L;
    private final android.os.Handler splashHandler = new android.os.Handler();
    private final AtomicBoolean probeFinished = new AtomicBoolean(false);
    private final long createTime = android.os.SystemClock.uptimeMillis();

    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_bootstrap);
        status = (TextView) findViewById(R.id.boot_status);
        applySplashScale();
        store = App.from(this).config();

        final boolean force = getIntent().getBooleanExtra("redetect", false);
        if (force) {
            store.invalidateCapability();
        }
        final ConfigCodec.ConfigBundle bundle = store.load();

        if (!bundle.needsDetection()) {
            status.setText("已加载设备配置");
            L.i("Bootstrap", "使用已保存的能力快照，跳过检测");
            goNext(bundle);
            return;
        }

        status.setText("首次启动：正在检测设备播放能力…");
        splashHandler.postDelayed(new Runnable() {
            public void run() {
                finishProbe(bundle, DeviceProbe.fallback(), true);
            }
        }, PROBE_TIMEOUT_MS);
        new Thread(new Runnable() {
            public void run() {
                final com.juying.medianest.core.model.Capability cap;
                try {
                    cap = DeviceProbe.probe(BootstrapActivity.this);
                } catch (Throwable t) {
                    L.w("Bootstrap", "设备检测异常，使用保守基线: " + t.getClass().getName());
                    runOnUiThread(new Runnable() {
                        public void run() {
                            finishProbe(bundle, DeviceProbe.fallback(), false);
                        }
                    });
                    return;
                }
                runOnUiThread(new Runnable() {
                    public void run() {
                        finishProbe(bundle, cap, false);
                    }
                });
            }
        }, "fntv-probe").start();
    }

    /** 官方启动页按 1920×1080 像素设计，这里按实际屏宽等比缩放。 */
    private void applySplashScale() {
        float scale = Math.max(0.1f, getResources().getDisplayMetrics().widthPixels / 1920f);
        ImageView logo = (ImageView) findViewById(R.id.boot_logo);
        FrameLayout.LayoutParams logoLp = (FrameLayout.LayoutParams) logo.getLayoutParams();
        logoLp.width = Math.round(580 * scale);
        logoLp.height = Math.round(160 * scale);
        logo.setLayoutParams(logoLp);

        TextView copyright = (TextView) findViewById(R.id.boot_copyright);
        FrameLayout.LayoutParams copyLp =
                (FrameLayout.LayoutParams) copyright.getLayoutParams();
        copyLp.bottomMargin = Math.round(50 * scale);
        copyright.setLayoutParams(copyLp);
        copyright.setTextSize(TypedValue.COMPLEX_UNIT_PX, 24 * scale);
    }

    private void finishProbe(ConfigCodec.ConfigBundle bundle,
                             com.juying.medianest.core.model.Capability cap, boolean timedOut) {
        if (!probeFinished.compareAndSet(false, true)) {
            return;
        }
        splashHandler.removeCallbacksAndMessages(null);
        if (timedOut) {
            L.w("Bootstrap", "设备检测超过 " + PROBE_TIMEOUT_MS + "ms，使用保守能力继续启动");
            status.setText("设备检测超时，已使用保守配置");
        } else {
            status.setText("检测完成：" + cap.summary());
        }
        bundle.capability = cap;
        bundle.firstRunDone = true;
        bundle.lastError = "";
        store.save(bundle);
        L.i("Bootstrap", "能力快照已保存，之后启动不再检测");
        goNext(bundle);
    }

    @Override
    protected void onDestroy() {
        probeFinished.set(true);
        splashHandler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    private void goNext(ConfigCodec.ConfigBundle bundle) {
        long shown = android.os.SystemClock.uptimeMillis() - createTime;
        long wait = Math.max(0, 600 - shown);
        splashHandler.postDelayed(new Runnable() {
            public void run() {
                if (isFinishing()) {
                    return;
                }
                ServerConfig server = bundle.server;
                boolean ready = server != null && server.baseUrl.length() > 0
                        && server.hasCredentials();
                Intent i = new Intent(BootstrapActivity.this,
                        ready ? MainActivity.class : OfficialScanDeviceActivity.class);
                i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
                startActivity(i);
                finish();
            }
        }, wait);
    }
}
