package com.juying.medianest.data.api;

import com.juying.medianest.core.util.L;
import com.juying.medianest.core.model.FailureReason;
import com.juying.medianest.core.model.FnException;
import com.juying.medianest.core.util.NetUtil;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.UnknownHostException;
import java.nio.charset.Charset;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * 只用 API 1 就存在的 HttpURLConnection，不引入 OkHttp / Volley / androidx。
 * 统一把底层网络异常翻译成用户可读的 FnException。
 *
 * 真机（Android 4.2.1 / TCL TV）实测：局域网频繁请求时旧连接被 NAS 提前关闭，
 * 会偶发 EOFException。因此这里禁用 keep-alive 复用，并对瞬态连接错误重试一次。
 */
public final class HttpStack {

    public static final class Response {
        public int status = 0;
        public String body = "";
        public Map<String, String> headers = new LinkedHashMap<String, String>();

        public boolean ok() {
            return status >= 200 && status < 300;
        }
    }

    private static final String UA = "FnTV/1.0 (Android TV Client)";
    private static final Charset UTF_8 = Charset.forName("UTF-8");
    private static final Charset ISO_8859_1 = Charset.forName("ISO-8859-1");

    private HttpStack() {
    }

    /**
     * 只取二进制响应的前 maxBytes 字节就断开，用于读取 TS 分片头部的 PMT。
     * 播放列表的 CODECS 会谎报音频编码（真机实测），真实编码只能看 TS 的 PMT；
     * 整片有 1~6MB，这里只需要前几十 KB。
     */
    public static byte[] requestBytes(String url, Map<String, String> headers,
                                      int maxBytes, int connectMs, int readMs) throws FnException {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(connectMs);
            conn.setReadTimeout(readMs);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", UA);
            conn.setRequestProperty("Accept-Encoding", "identity");
            conn.setRequestProperty("Connection", "close");
            conn.setRequestProperty("Range", "bytes=0-" + (maxBytes - 1));
            if (headers != null) {
                for (Map.Entry<String, String> e : headers.entrySet()) {
                    conn.setRequestProperty(e.getKey(), e.getValue());
                }
            }
            int status = conn.getResponseCode();
            if (status < 200 || status >= 300) {
                throw new FnException(FailureReason.SERVER_ERROR,
                        "读取媒体分片失败 HTTP " + status + " @ " + shortUrl(url));
            }
            InputStream in = conn.getInputStream();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int total = 0;
            try {
                while (total < maxBytes) {
                    int n = in.read(buf, 0, Math.min(buf.length, maxBytes - total));
                    if (n <= 0) {
                        break;
                    }
                    out.write(buf, 0, n);
                    total += n;
                }
            } finally {
                try {
                    in.close();
                } catch (Throwable ignored) {
                }
            }
            return out.toByteArray();
        } catch (FnException ex) {
            throw ex;
        } catch (SocketTimeoutException ex) {
            throw new FnException(FailureReason.TIMEOUT, "媒体分片读取超时 " + shortUrl(url));
        } catch (Throwable t) {
            throw new FnException(FailureReason.CONNECTION_FAILED,
                    t.getClass().getSimpleName() + " @ " + shortUrl(url));
        } finally {
            if (conn != null) {
                try {
                    conn.disconnect();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    public static Response request(String method, String url, Map<String, String> headers,
                                   String body, int connectMs, int readMs) throws FnException {
        FnException last = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                return doRequest(method, url, headers, body, connectMs, readMs);
            } catch (FnException ex) {
                if (attempt == 0 && isTransient(ex)) {
                    L.w("HttpStack", "瞬态网络异常，自动重试一次：" + ex.detail);
                    last = ex;
                    continue;
                }
                throw ex;
            }
        }
        throw last == null
                ? new FnException(FailureReason.CONNECTION_FAILED, "请求失败 " + shortUrl(url))
                : last;
    }

    /** 只把「连接被对端提前关闭」这类可重试错误算瞬态，绝不重放超时/鉴权错误。 */
    private static boolean isTransient(FnException ex) {
        if (ex.reason != FailureReason.CONNECTION_FAILED || ex.detail == null) {
            return false;
        }
        String d = ex.detail.toLowerCase();
        return d.indexOf("eof") >= 0
                || d.indexOf("reset") >= 0
                || d.indexOf("broken pipe") >= 0
                || d.indexOf("connection closed") >= 0
                || d.indexOf("recvfrom failed") >= 0
                || d.indexOf("unexpected end of stream") >= 0;
    }

    private static Response doRequest(String method, String url, Map<String, String> headers,
                                      String body, int connectMs, int readMs) throws FnException {
        // 老系统 HttpURLConnection 的 DELETE 不能带 body（API 17 实测抛
        // "DELETE does not support writing"），而取消收藏只接受 DELETE + {item_guid}
        if ("DELETE".equals(method) && body != null) {
            return doDeleteWithBody(url, headers, body, connectMs, readMs);
        }
        HttpURLConnection conn = null;
        try {
            URL u = new URL(url);
            conn = (HttpURLConnection) u.openConnection();
            conn.setRequestMethod(method);
            conn.setConnectTimeout(connectMs);
            conn.setReadTimeout(readMs);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", UA);
            conn.setRequestProperty("Accept", "application/json, */*");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Accept-Encoding", "identity"); // 避免老设备 gzip 解码差异
            // 老 Android 的连接池会复用已被 NAS 关闭的 socket，导致 EOFException
            conn.setRequestProperty("Connection", "close");
            if (headers != null) {
                for (Map.Entry<String, String> e : headers.entrySet()) {
                    conn.setRequestProperty(e.getKey(), e.getValue());
                }
            }
            if (body != null && !"GET".equals(method)) {
                conn.setDoOutput(true);
                byte[] bytes = body.getBytes(UTF_8);
                conn.setFixedLengthStreamingMode(bytes.length);
                OutputStream os = conn.getOutputStream();
                os.write(bytes);
                os.flush();
                os.close();
            }

            Response r = new Response();
            r.status = conn.getResponseCode();
            for (Map.Entry<String, java.util.List<String>> e : conn.getHeaderFields().entrySet()) {
                if (e.getKey() != null && e.getValue() != null && !e.getValue().isEmpty()) {
                    r.headers.put(e.getKey().toLowerCase(), e.getValue().get(0));
                }
            }
            InputStream in = (r.status >= 400) ? conn.getErrorStream() : conn.getInputStream();
            r.body = (in == null) ? "" : readAll(in, 512 * 1024);
            return r;
        } catch (SocketTimeoutException ex) {
            throw new FnException(FailureReason.TIMEOUT, "请求超时 " + shortUrl(url));
        } catch (UnknownHostException ex) {
            throw new FnException(FailureReason.DNS_FAILED, "无法解析 " + NetUtil.hostOf(url));
        } catch (ConnectException ex) {
            throw new FnException(FailureReason.CONNECTION_FAILED, "端口未开放或主机不可达 " + shortUrl(url), 0, 0, ex);
        } catch (IOException ex) {
            String msg = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
            if (msg.indexOf("SSL") >= 0 || msg.indexOf("handshake") >= 0 || msg.indexOf("certificate") >= 0) {
                throw new FnException(FailureReason.CONNECTION_FAILED, "HTTPS 握手失败（老设备可能不支持 TLS 1.2）：" + msg);
            }
            throw new FnException(FailureReason.CONNECTION_FAILED, msg + " @ " + shortUrl(url), 0, 0, ex);
        } catch (Throwable t) {
            throw new FnException(FailureReason.UNKNOWN, t.getClass().getName() + " @ " + shortUrl(url));
        } finally {
            if (conn != null) {
                try {
                    conn.disconnect();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /**
     * 用原生 Socket 发一个最小的 HTTP/1.1 请求，仅用于「DELETE + JSON body」。
     * 系统 HttpURLConnection 在 Android 4.x 上禁止 DELETE 写 body，而收藏接口只认这种写法。
     * 请求头沿用业务层的 Authorization / authx，签名与 POST/PUT 完全一致。
     */
    private static Response doDeleteWithBody(String url, Map<String, String> headers,
                                             String body, int connectMs, int readMs) throws FnException {
        Socket socket = null;
        try {
            URL u = new URL(url);
            boolean https = "https".equalsIgnoreCase(u.getProtocol());
            int port = u.getPort() > 0 ? u.getPort() : (https ? 443 : 80);
            socket = https ? SSLSocketFactory.getDefault().createSocket() : new Socket();
            socket.connect(new InetSocketAddress(u.getHost(), port), connectMs);
            socket.setSoTimeout(readMs);
            if (socket instanceof SSLSocket) {
                ((SSLSocket) socket).startHandshake();
            }

            byte[] bodyBytes = body.getBytes(UTF_8);
            String path = u.getFile() == null || u.getFile().length() == 0 ? "/" : u.getFile();
            StringBuilder head = new StringBuilder();
            head.append("DELETE ").append(path).append(" HTTP/1.1\r\n");
            head.append("Host: ").append(u.getHost());
            if (u.getPort() > 0) {
                head.append(':').append(u.getPort());
            }
            head.append("\r\n");
            head.append("User-Agent: ").append(UA).append("\r\n");
            head.append("Accept: application/json, */*\r\n");
            head.append("Content-Type: application/json\r\n");
            head.append("Content-Length: ").append(bodyBytes.length).append("\r\n");
            head.append("Accept-Encoding: identity\r\n");
            head.append("Connection: close\r\n");
            if (headers != null) {
                for (Map.Entry<String, String> e : headers.entrySet()) {
                    head.append(e.getKey()).append(": ").append(e.getValue()).append("\r\n");
                }
            }
            head.append("\r\n");

            OutputStream os = socket.getOutputStream();
            os.write(head.toString().getBytes(ISO_8859_1));
            os.write(bodyBytes);
            os.flush();

            byte[] raw = readAllBytes(socket.getInputStream(), 512 * 1024);
            int split = indexOfHeaderEnd(raw);
            if (split < 0) {
                throw new FnException(FailureReason.SERVER_ERROR,
                        "DELETE 响应不完整 @ " + shortUrl(url));
            }
            String[] lines = new String(raw, 0, split, ISO_8859_1).split("\r\n");
            Response r = new Response();
            int sp = lines[0].indexOf(' ');
            if (sp > 0) {
                String rest = lines[0].substring(sp + 1).trim();
                int sp2 = rest.indexOf(' ');
                r.status = Integer.parseInt(sp2 > 0 ? rest.substring(0, sp2) : rest);
            }
            boolean chunked = false;
            for (int i = 1; i < lines.length; i++) {
                int colon = lines[i].indexOf(':');
                if (colon <= 0) {
                    continue;
                }
                String k = lines[i].substring(0, colon).trim().toLowerCase();
                String v = lines[i].substring(colon + 1).trim();
                r.headers.put(k, v);
                if ("transfer-encoding".equals(k) && v.toLowerCase().indexOf("chunked") >= 0) {
                    chunked = true;
                }
            }
            byte[] payload = new byte[raw.length - split - 4];
            System.arraycopy(raw, split + 4, payload, 0, payload.length);
            if (chunked) {
                payload = dechunk(payload);
            }
            r.body = new String(payload, UTF_8);
            return r;
        } catch (SocketTimeoutException ex) {
            throw new FnException(FailureReason.TIMEOUT, "请求超时 " + shortUrl(url));
        } catch (UnknownHostException ex) {
            throw new FnException(FailureReason.DNS_FAILED, "无法解析 " + NetUtil.hostOf(url));
        } catch (ConnectException ex) {
            throw new FnException(FailureReason.CONNECTION_FAILED,
                    "端口未开放或主机不可达 " + shortUrl(url), 0, 0, ex);
        } catch (IOException ex) {
            String msg = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
            throw new FnException(FailureReason.CONNECTION_FAILED, msg + " @ " + shortUrl(url), 0, 0, ex);
        } catch (FnException ex) {
            throw ex;
        } catch (Throwable t) {
            throw new FnException(FailureReason.UNKNOWN, t.getClass().getName() + " @ " + shortUrl(url));
        } finally {
            if (socket != null) {
                try {
                    socket.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static byte[] readAllBytes(InputStream in, int limit) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int total = 0;
        int n;
        while (total < limit && (n = in.read(buf)) > 0) {
            int take = Math.min(n, limit - total);
            out.write(buf, 0, take);
            total += take;
        }
        return out.toByteArray();
    }

    /** 找到响应头结束标记 CRLFCRLF 的下标。 */
    private static int indexOfHeaderEnd(byte[] data) {
        for (int i = 0; i + 3 < data.length; i++) {
            if (data[i] == '\r' && data[i + 1] == '\n' && data[i + 2] == '\r' && data[i + 3] == '\n') {
                return i;
            }
        }
        return -1;
    }

    /** 兼容 chunked 响应：逐块拼回原始 JSON。 */
    private static byte[] dechunk(byte[] data) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int i = 0;
        while (i < data.length) {
            int lineEnd = i;
            while (lineEnd + 1 < data.length
                    && !(data[lineEnd] == '\r' && data[lineEnd + 1] == '\n')) {
                lineEnd++;
            }
            // HTTP chunk-size 行只含 ASCII，显式 ISO-8859-1，避免跟随平台默认编码
            String sizeText = new String(data, i, lineEnd - i, ISO_8859_1);
            int semi = sizeText.indexOf(';');
            if (semi >= 0) {
                sizeText = sizeText.substring(0, semi);
            }
            int size;
            try {
                size = Integer.parseInt(sizeText.trim(), 16);
            } catch (NumberFormatException ex) {
                break;
            }
            if (size == 0) {
                break;
            }
            int start = lineEnd + 2;
            int copy = Math.min(size, data.length - start);
            if (copy <= 0) {
                break;
            }
            out.write(data, start, copy);
            i = start + size + 2;
        }
        return out.toByteArray();
    }

    /** 发现阶段用：只探测端口 + 取一小段响应判断服务特征，不做完整业务请求。 */
    public static Response probe(String url, int timeoutMs) {
        try {
            return request("GET", url, null, null, timeoutMs, timeoutMs);
        } catch (FnException ex) {
            Response r = new Response();
            r.status = -1;
            r.body = ex.detail;
            return r;
        }
    }

    private static String readAll(InputStream in, int limit) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int total = 0;
        int n;
        while ((n = in.read(buf)) > 0) {
            int take = Math.min(n, limit - total);
            if (take <= 0) {
                break;
            }
            out.write(buf, 0, take);
            total += take;
        }
        in.close();
        // 字幕等文本可能带 BOM 或是 UTF-16，交给统一的解码器处理
        return NetUtil.decodeText(out.toByteArray());
    }

    private static String shortUrl(String url) {
        int q = url.indexOf('?');
        return q > 0 ? url.substring(0, q) : url;
    }
}
