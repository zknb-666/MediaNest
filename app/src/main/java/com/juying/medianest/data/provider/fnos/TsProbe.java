package com.juying.medianest.data.provider.fnos;

/**
 * 极简 MPEG-TS 解析：只从 PAT/PMT 里读出音视频 ES 的 stream_type。
 *
 * 为什么需要：飞牛影视的 master playlist 把 CODECS 写成
 * "avc1.640029,mp4a.40.34"（硬编码模板），但 TS 分片里真实音频可能是
 * 0x87 E-AC-3（真机实测）。只信播放列表会误判「音频已是 AAC」，造成无声。
 * 这里只读分片开头几十 KB，不下载整个分片。
 */
public final class TsProbe {

    private static final int PACKET = 188;

    private TsProbe() {
    }

    /** 返回 PMT 中音频 ES 的 stream_type；解析不到返回 -1。 */
    public static int audioStreamType(byte[] data) {
        int[] types = streamTypes(data);
        for (int i = 0; i < types.length; i++) {
            int t = types[i];
            if (t == 0x03 || t == 0x04 || t == 0x06 || t == 0x0F || t == 0x11
                    || t == 0x81 || t == 0x82 || t == 0x83 || t == 0x84 || t == 0x87) {
                return t;
            }
        }
        return -1;
    }

    /** 返回 PMT 中视频 ES 的 stream_type；解析不到返回 -1。 */
    public static int videoStreamType(byte[] data) {
        int[] types = streamTypes(data);
        for (int i = 0; i < types.length; i++) {
            if (types[i] == 0x1B || types[i] == 0x24 || types[i] == 0x02 || types[i] == 0x01) {
                return types[i];
            }
        }
        return -1;
    }

    private static int[] streamTypes(byte[] data) {
        if (data == null || data.length < PACKET) {
            return new int[0];
        }
        int pmtPid = patPmtPid(data);
        if (pmtPid < 0) {
            return new int[0];
        }
        int packets = data.length / PACKET;
        for (int i = 0; i < packets; i++) {
            int off = i * PACKET;
            if (data[off] != 0x47 || pid(data, off) != pmtPid) {
                continue;
            }
            int start = payloadStart(data, off);
            if (start + 12 > off + PACKET || (data[start] & 0xFF) != 0x02) {
                continue;
            }
            int sectionLen = (((data[start + 1] & 0x0F) << 8) | (data[start + 2] & 0xFF));
            int progInfoLen = (((data[start + 10] & 0x0F) << 8) | (data[start + 11] & 0xFF));
            int p = start + 12 + progInfoLen;
            int end = Math.min(start + 3 + sectionLen - 4, off + PACKET);
            int[] out = new int[16];
            int n = 0;
            while (p + 5 <= end && n < out.length) {
                out[n++] = data[p] & 0xFF;
                int esLen = (((data[p + 3] & 0x0F) << 8) | (data[p + 4] & 0xFF));
                p += 5 + esLen;
            }
            int[] trimmed = new int[n];
            System.arraycopy(out, 0, trimmed, 0, n);
            if (n > 0) {
                return trimmed;
            }
        }
        return new int[0];
    }

    private static int patPmtPid(byte[] data) {
        int packets = Math.min(data.length / PACKET, 40);
        for (int i = 0; i < packets; i++) {
            int off = i * PACKET;
            if (data[off] != 0x47 || pid(data, off) != 0) {
                continue;
            }
            int start = payloadStart(data, off);
            if (start + 8 > off + PACKET || (data[start] & 0xFF) != 0x00) {
                continue;
            }
            int sectionLen = (((data[start + 1] & 0x0F) << 8) | (data[start + 2] & 0xFF));
            int p = start + 8;
            int end = Math.min(start + 3 + sectionLen - 4, off + PACKET);
            while (p + 4 <= end) {
                int program = ((data[p] & 0xFF) << 8) | (data[p + 1] & 0xFF);
                int ppid = (((data[p + 2] & 0x1F) << 8) | (data[p + 3] & 0xFF));
                if (program != 0) {
                    return ppid;
                }
                p += 4;
            }
        }
        return -1;
    }

    private static int pid(byte[] d, int off) {
        return (((d[off + 1] & 0x1F) << 8) | (d[off + 2] & 0xFF));
    }

    private static int payloadStart(byte[] d, int off) {
        int p = off + 4;
        if ((d[off + 1] & 0x40) != 0) {
            // PUSI：跳过 pointer_field
            p++;
        }
        int afc = (d[off + 3] >> 4) & 0x03;
        if ((afc & 0x02) != 0 && p <= off + PACKET) {
            p += 1 + (d[p] & 0xFF); // adaptation_field_length + 内容
        }
        return p;
    }
}
