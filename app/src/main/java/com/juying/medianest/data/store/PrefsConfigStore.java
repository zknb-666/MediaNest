package com.juying.medianest.data.store;

import android.content.Context;
import android.content.SharedPreferences;

import com.juying.medianest.domain.repository.ConfigRepository;
import com.juying.medianest.core.util.L;
import com.juying.medianest.core.model.ConfigCodec;

/**
 * 配置持久化：能力快照、服务器地址/凭据、发现记录、首启标记全部存 SharedPreferences。
 * 只使用 API 1 就有的 SharedPreferences.commit()，不用 API 9 的 apply()。
 */
public final class PrefsConfigStore implements ConfigRepository {

    private static final String PREF_NAME = "fntv_config";
    private static final String KEY_BUNDLE = "bundle";
    private static final String KEY_LAST_ITEM = "last_media_item";

    private final Context context;
    private ConfigCodec.ConfigBundle cached;

    public PrefsConfigStore(Context context) {
        this.context = context.getApplicationContext();
    }

    public synchronized ConfigCodec.ConfigBundle load() {
        if (cached != null) {
            return cached;
        }
        SharedPreferences sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        String text = sp.getString(KEY_BUNDLE, "");
        cached = ConfigCodec.decode(text);
        return cached;
    }

    public synchronized void save(ConfigCodec.ConfigBundle bundle) {
        bundle.savedAt = System.currentTimeMillis();
        SharedPreferences sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        SharedPreferences.Editor e = sp.edit();
        e.putString(KEY_BUNDLE, ConfigCodec.encode(bundle));
        e.commit();
        cached = bundle;
        L.i("ConfigStore", "配置已保存 firstRun=" + bundle.firstRunDone +
                " capability=" + (bundle.capability != null));
    }

    public synchronized void saveLastItem(String itemJson) {
        SharedPreferences sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        SharedPreferences.Editor e = sp.edit();
        e.putString(KEY_LAST_ITEM, itemJson);
        e.commit();
    }

    public String loadLastItem() {
        SharedPreferences sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        return sp.getString(KEY_LAST_ITEM, "");
    }

    /** 只清能力快照，强制下次启动重新检测（设置页「重新检测」用）。 */
    public synchronized void invalidateCapability() {
        ConfigCodec.ConfigBundle b = load();
        b.capability = null;
        b.firstRunDone = false;
        save(b);
    }

    public synchronized void clearAll() {
        SharedPreferences sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        sp.edit().clear().commit();
        cached = null;
    }
}
