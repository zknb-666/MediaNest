package com.juying.medianest.core.model;

import com.juying.medianest.core.util.Json;


import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 配置持久化编解码：能力快照 + 服务器配置 + 发现记录 + 首启标记。
 * 解析永远容错——配置损坏时降级为默认值而不是崩溃，也不会把用户踢回死循环。
 */
public final class ConfigCodec {

    public static final int BUNDLE_SCHEMA = 2;

    public static final class ServerInfo {
        public String baseUrl = "";
        public String name = "";
        public int port = 5666;
        public String method = "SWEEP"; // MANUAL / SEED / SWEEP / UDP / SSDP
        public long latencyMs = 0L;
        public boolean verified = false;
        public String signature = "";

        public String apiMode() {
            return "jellyfin".equalsIgnoreCase(signature) ? "JELLYFIN" : "FNOS";
        }

        public String typeLabel() {
            return "JELLYFIN".equals(apiMode()) ? "Jellyfin" : "飞牛影视";
        }

        public void mergeDiscovery(ServerInfo source) {
            boolean manual = "MANUAL".equalsIgnoreCase(method);
            baseUrl = source.baseUrl;
            if (source.name.length() > 0) name = source.name;
            port = source.port;
            latencyMs = source.latencyMs;
            verified = source.verified;
            if (!manual) {
                method = source.method;
                signature = source.signature;
            }
        }

        public String label() {
            return (name.length() > 0 ? name : baseUrl) + "  [" + method + "]" +
                    (verified ? " ✓" : "");
        }

        public Map<String, Object> toJson() {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("baseUrl", baseUrl);
            m.put("name", name);
            m.put("port", Integer.valueOf(port));
            m.put("method", method);
            m.put("latencyMs", Long.valueOf(latencyMs));
            m.put("verified", Boolean.valueOf(verified));
            m.put("signature", signature);
            return m;
        }

        public static ServerInfo fromJson(Object o) {
            Map<String, Object> m = Json.map(o);
            ServerInfo s = new ServerInfo();
            s.baseUrl = Json.str(m, "baseUrl", "");
            s.name = Json.str(m, "name", "");
            s.port = Json.integer(m, "port", 5666);
            s.method = Json.str(m, "method", "SWEEP");
            s.latencyMs = Json.longer(m, "latencyMs", 0L);
            s.verified = Json.bool(m, "verified", false);
            s.signature = Json.str(m, "signature", "");
            return s;
        }
    }

    public static final class ConfigBundle {
        public int schemaVersion = BUNDLE_SCHEMA;
        public boolean firstRunDone = false;
        public Capability capability = null;
        public ServerConfig server = new ServerConfig();
        public List<ServerInfo> discovered = new ArrayList<ServerInfo>();
        public long savedAt = 0L;
        public String lastError = "";

        public boolean needsDetection() {
            return !firstRunDone || capability == null || capability.schemaVersion != Capability.SCHEMA;
        }
    }

    private ConfigCodec() {
    }

    public static String encode(ConfigBundle b) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("schemaVersion", Integer.valueOf(b.schemaVersion));
        m.put("firstRunDone", Boolean.valueOf(b.firstRunDone));
        m.put("capability", b.capability == null ? null : b.capability.toJson());
        m.put("server", b.server.toJson());
        List<Object> ds = new ArrayList<Object>();
        for (int i = 0; i < b.discovered.size(); i++) {
            ds.add(b.discovered.get(i).toJson());
        }
        m.put("discovered", ds);
        m.put("savedAt", Long.valueOf(b.savedAt));
        m.put("lastError", b.lastError);
        return Json.canonical(m);
    }

    public static ConfigBundle decode(String text) {
        ConfigBundle b = new ConfigBundle();
        if (text == null || text.trim().length() == 0) {
            return b;
        }
        Map<String, Object> m;
        try {
            m = Json.parseObject(text);
        } catch (RuntimeException ex) {
            // 配置损坏：保留默认服务器地址，标记需要重新检测，绝不抛异常
            b.capability = null;
            b.firstRunDone = false;
            b.lastError = "本地配置解析失败，已恢复默认值并重新检测设备能力";
            return b;
        }
        b.schemaVersion = Json.integer(m, "schemaVersion", 1);
        b.firstRunDone = Json.bool(m, "firstRunDone", false);
        Object cap = m.get("capability");
        b.capability = (cap == null) ? null : Capability.fromJson(cap);
        b.server = ServerConfig.fromJson(m.get("server"));
        List<Object> ds = Json.list(m.get("discovered"));
        for (int i = 0; i < ds.size(); i++) {
            b.discovered.add(ServerInfo.fromJson(ds.get(i)));
        }
        b.savedAt = Json.longer(m, "savedAt", 0L);
        b.lastError = Json.str(m, "lastError", "");

        // ---- 版本迁移 ----
        if (b.schemaVersion < 2) {
            // v1 没有签名常量与端点模板，用当前默认值补齐
            b.server.signSecret = "";
            b.server.signApiKey = "";
            b.schemaVersion = BUNDLE_SCHEMA;
        }
        if (b.capability != null && b.capability.schemaVersion != Capability.SCHEMA) {
            b.capability = null; // 能力模型变了，必须重测
            b.firstRunDone = false;
        }
        return b;
    }
}
