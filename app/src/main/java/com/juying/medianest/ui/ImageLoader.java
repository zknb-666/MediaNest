package com.juying.medianest.ui;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.widget.ImageView;

import com.juying.medianest.core.util.L;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 极简异步图片加载器：只用标准库，不依赖 Glide/Picasso。
 *
 * 飞牛影视海报接口 /v/api/v1/sys/img 返回 webp，带 Authorization 头；
 * BitmapFactory 从 Android 4.0 起支持 webp（真机 4.2.1 已实测）。
 *
 * - 内存 LRU 缓存，命中直接设图，不重复请求
 * - 单线程串行下载，避免老设备并发连接池被 NAS 提前关闭
 * - 用 ImageView.setTag(url) 防止列表复用时旧请求覆盖新图
 */
public final class ImageLoader {

    private static final int MAX_CACHE = 48;
    private final Activity activity;
    private final Handler main;
    private final Map<String, Bitmap> cache;
    private final Bitmap placeholder;

    public ImageLoader(Activity activity) {
        this.activity = activity;
        this.main = new Handler(activity.getMainLooper());
        // 访问序 LinkedHashMap 的 get() 会改结构，必须整体加锁；
        // 旧实现只在 put 上加锁，UI 线程 get 与下载线程 put 并发时可能破坏链表。
        this.cache = Collections.synchronizedMap(
                new LinkedHashMap<String, Bitmap>(MAX_CACHE, 0.75f, true) {
                    protected boolean removeEldestEntry(Map.Entry<String, Bitmap> eldest) {
                        return size() > MAX_CACHE;
                    }
                });
        Bitmap ph = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888);
        ph.eraseColor(0xFF2B3546);
        this.placeholder = ph;
    }

    /** 异步把 url 的图片加载到 iv；失败保留占位色块。 */
    public void load(final String url, final ImageView iv, final String token) {
        load(url, iv, token, 0);
    }

    /** 官方主题可指定原包占位素材；默认主题仍走原有色块，不改变现有表现。 */
    public void load(final String url, final ImageView iv, final String token,
                     final int placeholderRes) {
        if (url == null || url.length() == 0) {
            setPlaceholder(iv, placeholderRes);
            return;
        }
        iv.setTag(url);
        Bitmap hit = cache.get(url);
        if (hit != null) {
            iv.setImageBitmap(hit);
            return;
        }
        setPlaceholder(iv, placeholderRes);
        new Thread(new Runnable() {
            public void run() {
                final Bitmap bm = fetch(url, token);
                if (bm == null) {
                    return;
                }
                synchronized (cache) {
                    cache.put(url, bm);
                }
                main.post(new Runnable() {
                    public void run() {
                        // 视图已被复用指向另一张图时，丢弃这次结果
                        Object tag = iv.getTag();
                        if (tag instanceof String && url.equals(tag)) {
                            iv.setImageBitmap(bm);
                        }
                    }
                });
            }
        }, "fntv-img").start();
    }

    private void setPlaceholder(ImageView iv, int placeholderRes) {
        if (placeholderRes != 0) {
            iv.setImageResource(placeholderRes);
        } else {
            iv.setImageBitmap(placeholder);
        }
    }

    private static Bitmap fetch(String url, String token) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(6000);
            conn.setReadTimeout(10000);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", "FnTV/1.0 (Android TV Client)");
            conn.setRequestProperty("Accept-Encoding", "identity");
            conn.setRequestProperty("Connection", "close");
            if (token != null && token.length() > 0) {
                conn.setRequestProperty("Authorization", token);
            }
            int status = conn.getResponseCode();
            if (status < 200 || status >= 300) {
                L.w("ImageLoader", "HTTP " + status + " @ " + shortUrl(url));
                return null;
            }
            InputStream in = conn.getInputStream();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            int total = 0;
            try {
                while ((n = in.read(buf)) > 0 && total < 2 * 1024 * 1024) {
                    out.write(buf, 0, n);
                    total += n;
                }
            } finally {
                in.close();
            }
            Bitmap bm = BitmapFactory.decodeByteArray(out.toByteArray(), 0, total);
            return bm;
        } catch (Throwable t) {
            L.w("ImageLoader", "加载图片失败：" + t.getClass().getSimpleName());
            return null;
        } finally {
            if (conn != null) {
                try {
                    conn.disconnect();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static String shortUrl(String url) {
        int q = url.indexOf('?');
        return q > 0 ? url.substring(0, q) : url;
    }
}
