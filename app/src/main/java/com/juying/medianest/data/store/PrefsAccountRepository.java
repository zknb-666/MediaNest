package com.juying.medianest.data.store;

import android.content.Context;
import android.content.SharedPreferences;

import com.juying.medianest.domain.repository.AccountRepository;
import com.juying.medianest.core.util.Json;
import com.juying.medianest.core.model.ServerConfig;

import java.util.ArrayList;
import java.util.List;

/**
 * 本地账号列表（SharedPreferences {@code fntv_accounts}）。
 *
 * 官方客户端把已登录过的账号保存在设备本地，账号页/选择服务器页直接读取本地列表，
 * 打开账号页本身不产生服务端请求。每个条目是完整 {@link ServerConfig} JSON。
 */
public final class PrefsAccountRepository implements AccountRepository {

    private static final String PREF = "fntv_accounts";
    private static final String KEY = "accounts";

    private final Context context;

    public PrefsAccountRepository(Context context) {
        this.context = context.getApplicationContext();
    }

    public List<ServerConfig> load() {
        SharedPreferences sp = context.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        String text = sp.getString(KEY, "");
        List<ServerConfig> result = new ArrayList<ServerConfig>();
        if (text == null || text.length() == 0) return result;
        try {
            List<Object> list = Json.list(Json.parse(text));
            for (Object o : list) {
                ServerConfig s = ServerConfig.fromJson(o);
                if (s.username != null && s.username.length() > 0) result.add(s);
            }
        } catch (Exception e) {
            // 损坏数据忽略，与官方一致地降级为空列表
        }
        return result;
    }

    public void save(List<ServerConfig> list) {
        List<Object> arr = new ArrayList<Object>();
        for (ServerConfig s : list) {
            // 已退出的账号（用户名为空）不落盘，避免残留空条目
            if (s.username == null || s.username.length() == 0) continue;
            arr.add(s.toJson());
        }
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
                .putString(KEY, Json.stringify(arr)).commit();
    }

    public boolean same(ServerConfig a, ServerConfig b) {
        if (a == null || b == null) return false;
        return eq(a.username, b.username) && eq(a.baseUrl, b.baseUrl);
    }

    public boolean contains(List<ServerConfig> list, ServerConfig target) {
        for (ServerConfig s : list) {
            if (same(s, target)) return true;
        }
        return false;
    }

    public void addOrReplace(List<ServerConfig> list, ServerConfig cfg) {
        for (int i = list.size() - 1; i >= 0; i--) {
            if (same(list.get(i), cfg)) list.remove(i);
        }
        // 存副本：cfg 与当前配置共享同一对象，退出登录时会清空字段，不能把别名写进账号列表
        list.add(0, ServerConfig.fromJson(cfg.toJson()));
        save(list);
    }

    private static boolean eq(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }
}
