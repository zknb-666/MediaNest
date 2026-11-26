package com.juying.medianest.data.discovery;

import com.juying.medianest.core.util.L;

import android.content.Context;
import android.net.wifi.WifiManager;

import com.juying.medianest.domain.usecase.DiscoveryService;
import com.juying.medianest.data.api.HttpStack;
import com.juying.medianest.data.api.HttpStack.Response;
import com.juying.medianest.data.MediaApiFactory;
import com.juying.medianest.core.model.ConfigCodec;
import com.juying.medianest.core.util.IpRange;
import com.juying.medianest.core.util.NetUtil;
import com.juying.medianest.core.model.ServerConfig;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 局域网发现：三段式，逐级加码，保证「默认 NAS / 已知 NAS」秒出结果。
 *
 *  A. 种子探测：配置里的地址（默认 nas.example.com）+ 历史发现记录，优先并发验证。
 *  B. 广播发现：UDP 广播 + SSDP M-SEARCH，收集主动应答的设备 IP。
 *  C. 网段扫描：本机 /24 逐 IP 做短连接探测，命中端口再用 HTTP 握手确认。
 *
 * 握手确认不依赖账号密码：GET /v/api/v1/server/info 即使未登录也会返回
 * {"code":5000,"msg":"invalid sign"}，这个响应本身就是飞牛影视的可靠指纹。
 */
public final class DefaultDiscoveryService implements DiscoveryService {

    private static final int THREADS = 12;
    private static final Charset UTF_8 = Charset.forName("UTF-8");
    private static final int PORT_CONNECT_TIMEOUT = 350;
    private static final int HTTP_TIMEOUT = 1200;

    private final Context context;
    private volatile boolean cancelled = false;
    private final Set<String> seen = Collections.synchronizedSet(new HashSet<String>());
    private final List<ConfigCodec.ServerInfo> results =
            Collections.synchronizedList(new ArrayList<ConfigCodec.ServerInfo>());

    public DefaultDiscoveryService(Context context) {
        this.context = context.getApplicationContext();
    }

    public void cancel() {
        cancelled = true;
    }

    public void discover(final ServerConfig cfg, final List<ConfigCodec.ServerInfo> known,
                         final Callback cb) {
        new Thread(new Runnable() {
            public void run() {
                runDiscovery(cfg, known, cb);
            }
        }, "fntv-discovery").start();
    }

    private void runDiscovery(ServerConfig cfg, List<ConfigCodec.ServerInfo> known, Callback cb) {
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        WifiManager.MulticastLock lock = null;
        try {
            try {
                WifiManager wm = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
                if (wm != null) {
                    lock = wm.createMulticastLock("fntv-discovery");
                    lock.setReferenceCounted(true);
                    lock.acquire();
                }
            } catch (Throwable t) {
                L.w("Discovery", "组播锁不可用，跳过广播发现");
            }

            List<String> locals = localIpv4Addresses();
            if (locals.isEmpty()) {
                cb.onProgress("未获取到局域网 IP，请检查 Wi-Fi / 网线连接");
            } else {
                cb.onProgress("本机 IP：" + locals.get(0) + "，开始探测…");
            }

            // ---- A. 种子 ----
            List<String> seeds = new ArrayList<String>();
            String configuredHost = NetUtil.hostOf(cfg.baseUrl);
            if (configuredHost.length() > 0) {
                seeds.add(configuredHost);
            }
            if (known != null) {
                for (int i = 0; i < known.size(); i++) {
                    String h = NetUtil.hostOf(known.get(i).baseUrl);
                    if (h.length() > 0) seeds.add(h);
                }
            }
            for (int i = 0; i < locals.size(); i++) {
                seeds.add(IpRange.fromLong((IpRange.toLong(locals.get(i)) & 0xffffff00L) + 1L));
            }
            await(submitHosts(pool, dedupe(seeds), "SEED", cb), 12);

            if (cancelled) {
                finish(cb);
                return;
            }

            // ---- B. UDP 广播 + SSDP ----
            cb.onProgress("广播发现中（UDP / SSDP）…");
            List<String> broadcastHits = new ArrayList<String>();
            udpProbe(locals, broadcastHits);
            ssdpProbe(broadcastHits);
            if (!broadcastHits.isEmpty()) {
                await(submitHosts(pool, dedupe(broadcastHits), "UDP", cb), 8);
            }

            if (cancelled) {
                finish(cb);
                return;
            }

            // ---- C. /24 网段扫描 ----
            List<String> sweep = new ArrayList<String>();
            for (int i = 0; i < locals.size() && i < 2; i++) {
                sweep.addAll(IpRange.hosts24(locals.get(i)));
            }
            List<String> targets = dedupe(sweep);
            cb.onProgress("扫描网段 " + targets.size() + " 个地址…");
            submitHosts(pool, targets, "SWEEP", cb);

            pool.shutdown();
            try {
                pool.awaitTermination(30, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
            }
            finish(cb);
        } catch (Throwable t) {
            L.e("Discovery", "发现流程异常", t);
            finish(cb);
        } finally {
            if (lock != null) {
                try {
                    lock.release();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private CountDownLatch submitHosts(ExecutorService pool, List<String> hosts, final String method,
                                       final Callback cb) {
        final int[] ports = "SWEEP".equals(method)
                ? new int[]{5666, 8005, 8096}
                : NetUtil.DEFAULT_PORTS;
        final CountDownLatch latch = new CountDownLatch(hosts.size());
        for (int i = 0; i < hosts.size(); i++) {
            final String host = hosts.get(i);
            pool.execute(new Runnable() {
                public void run() {
                    try {
                        if (cancelled) {
                            return;
                        }
                        for (int p = 0; p < ports.length; p++) {
                            int port = ports[p];
                            if (!portOpen(host, port)) {
                                continue;
                            }
                            String base = NetUtil.withPort(host, port);
                            if (!seen.add(host + ":" + port)) {
                                continue;
                            }
                            long started = System.currentTimeMillis();
                            ConfigCodec.ServerInfo jellyfin = MediaApiFactory.probeJellyfin(base);
                            String fingerprint = jellyfin != null ? "jellyfin" : fingerprint(base);
                            if (fingerprint == null) {
                                continue;
                            }
                            ConfigCodec.ServerInfo s = new ConfigCodec.ServerInfo();
                            s.baseUrl = base;
                            s.name = jellyfin == null ? "" : jellyfin.name;
                            s.port = port;
                            s.method = method;
                            s.signature = fingerprint;
                            s.verified = true;
                            s.latencyMs = System.currentTimeMillis() - started;
                            results.add(s);
                            cb.onFound(s);
                            cb.onProgress("发现 " + base + " (" + method + ")");
                            break;
                        }
                    } catch (Throwable t) {
                        L.w("Discovery", "探测 " + host + " 异常：" + t.getClass().getName());
                    } finally {
                        latch.countDown();
                    }
                }
            });
        }
        return latch;
    }

    private void await(CountDownLatch latch, int seconds) {
        try {
            latch.await(seconds, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
        }
    }

    /** 未鉴权也能拿到飞牛影视指纹，因此扫描阶段不需要账号密码。 */
    private String fingerprint(String base) {
        String apiUrl = NetUtil.join(base, "/v/api/v1/server/info");
        Response api = HttpStack.probe(apiUrl, HTTP_TIMEOUT);
        String body = api.body == null ? "" : api.body;
        if (body.indexOf("invalid sign") >= 0 || body.indexOf("\"code\":0") >= 0 ||
                body.indexOf("\"code\":-2") >= 0 || body.indexOf("Auth Failed") >= 0) {
            return "fnos-api";
        }
        if (body.indexOf("trim.media") >= 0 || body.indexOf("飞牛") >= 0 ||
                body.indexOf("fnOS") >= 0) {
            return "fnos-web";
        }
        Response root = HttpStack.probe(base + "/", HTTP_TIMEOUT);
        String rbody = root.body == null ? "" : root.body;
        if (rbody.indexOf("/v/assets/") >= 0 || rbody.indexOf("trim.media") >= 0 ||
                rbody.indexOf("飞牛") >= 0 || rbody.indexOf("fnOS") >= 0) {
            return "fnos-web";
        }
        String location = root.headers.get("location");
        if (location != null && location.indexOf("5666") >= 0) {
            return "redirect-5666";
        }
        return null;
    }

    private boolean portOpen(String host, int port) {
        Socket s = null;
        try {
            s = new Socket();
            s.connect(new InetSocketAddress(host, port), PORT_CONNECT_TIMEOUT);
            return true;
        } catch (Throwable t) {
            return false;
        } finally {
            if (s != null) {
                try {
                    s.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private void udpProbe(List<String> locals, List<String> hits) {
        DatagramSocket socket = null;
        try {
            socket = new DatagramSocket();
            socket.setSoTimeout(600);
            byte[] payload = "FNTV-DISCOVER-1".getBytes(UTF_8);
            List<String> targets = new ArrayList<String>();
            targets.add("255.255.255.255");
            for (int i = 0; i < locals.size(); i++) {
                targets.add(IpRange.broadcast24(locals.get(i)));
            }
            for (int i = 0; i < targets.size(); i++) {
                InetAddress addr = InetAddress.getByName(targets.get(i));
                for (int port = 5666; port <= 5668; port++) {
                    socket.send(new DatagramPacket(payload, payload.length, addr, port));
                }
            }
            long deadline = System.currentTimeMillis() + 900;
            while (System.currentTimeMillis() < deadline) {
                try {
                    byte[] buf = new byte[512];
                    DatagramPacket p = new DatagramPacket(buf, buf.length);
                    socket.receive(p);
                    String ip = p.getAddress().getHostAddress();
                    if (ip != null && !hits.contains(ip)) {
                        hits.add(ip);
                    }
                } catch (java.net.SocketTimeoutException ex) {
                    break;
                } catch (Throwable t) {
                    break;
                }
            }
        } catch (Throwable t) {
            L.w("Discovery", "UDP 广播不可用：" + t.getClass().getName());
        } finally {
            if (socket != null) {
                socket.close();
            }
        }
    }

    private void ssdpProbe(List<String> hits) {
        DatagramSocket socket = null;
        try {
            socket = new DatagramSocket();
            socket.setSoTimeout(800);
            String msg = "M-SEARCH * HTTP/1.1\r\n" +
                    "HOST: 239.255.255.250:1900\r\n" +
                    "MAN: \"ssdp:discover\"\r\n" +
                    "MX: 1\r\n" +
                    "ST: ssdp:all\r\n\r\n";
            byte[] payload = msg.getBytes(UTF_8);
            InetAddress group = InetAddress.getByName("239.255.255.250");
            socket.send(new DatagramPacket(payload, payload.length, group, 1900));
            long deadline = System.currentTimeMillis() + 1000;
            while (System.currentTimeMillis() < deadline) {
                byte[] buf = new byte[1024];
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                socket.receive(p);
                String ip = p.getAddress().getHostAddress();
                if (ip != null && !hits.contains(ip)) {
                    hits.add(ip);
                }
            }
        } catch (Throwable t) {
            // SSDP 无应答是常态（很多 NAS 不实现），不算错误
            L.d("Discovery", "SSDP 无应答");
        } finally {
            if (socket != null) {
                socket.close();
            }
        }
    }

    private List<String> localIpv4Addresses() {
        List<String> out = new ArrayList<String>();
        try {
            Enumeration<NetworkInterface> nis = NetworkInterface.getNetworkInterfaces();
            while (nis.hasMoreElements()) {
                NetworkInterface ni = nis.nextElement();
                if (!ni.isUp() || ni.isLoopback()) {
                    continue;
                }
                List<InterfaceAddress> addrs = ni.getInterfaceAddresses();
                for (int i = 0; i < addrs.size(); i++) {
                    InetAddress a = addrs.get(i).getAddress();
                    if (a instanceof Inet4Address && !a.isLoopbackAddress()) {
                        String ip = a.getHostAddress();
                        if (NetUtil.isLan(ip) && !out.contains(ip)) {
                            out.add(ip);
                        }
                    }
                }
            }
        } catch (Throwable t) {
            L.w("Discovery", "枚举网卡失败：" + t.getClass().getName());
        }
        // Wi-Fi 的 int IP 是小端序，必须反转
        if (out.isEmpty()) {
            try {
                WifiManager wm = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
                if (wm != null && wm.getConnectionInfo() != null) {
                    int ip = wm.getConnectionInfo().getIpAddress();
                    if (ip != 0) {
                        String s = (ip & 0xff) + "." + ((ip >> 8) & 0xff) + "." +
                                ((ip >> 16) & 0xff) + "." + ((ip >> 24) & 0xff);
                        out.add(s);
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        return out;
    }

    private void finish(Callback cb) {
        List<ConfigCodec.ServerInfo> snapshot = new ArrayList<ConfigCodec.ServerInfo>(results);
        cb.onProgress("发现完成，共 " + snapshot.size() + " 台可用服务");
        cb.onDone(snapshot);
    }

    private static List<String> dedupe(List<String> in) {
        List<String> out = new ArrayList<String>();
        for (int i = 0; i < in.size(); i++) {
            String s = in.get(i);
            if (s != null && s.length() > 0 && !out.contains(s)) {
                out.add(s);
            }
        }
        return out;
    }
}
