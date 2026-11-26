package com.juying.medianest.core.util;

import java.util.ArrayList;
import java.util.List;

/** 局域网网段枚举。发现服务只扫本机所在 /24，避免全子网风暴。 */
public final class IpRange {

    private IpRange() {
    }

    public static long toLong(String ipv4) {
        String[] p = ipv4.split("\\.");
        if (p.length != 4) {
            return -1L;
        }
        long v = 0L;
        for (int i = 0; i < 4; i++) {
            try {
                int part = Integer.parseInt(p[i]);
                if (part < 0 || part > 255) return -1L;
                v = (v << 8) | part;
            } catch (NumberFormatException ex) {
                return -1L;
            }
        }
        return v & 0xffffffffL;
    }

    public static String fromLong(long v) {
        return ((v >>> 24) & 0xff) + "." + ((v >>> 16) & 0xff) + "." +
                ((v >>> 8) & 0xff) + "." + (v & 0xff);
    }

    /** 返回 /24 内可探测的主机地址（排除网络号与广播地址），最多 254 个。 */
    public static List<String> hosts24(String localIp) {
        List<String> out = new ArrayList<String>();
        long ip = toLong(localIp);
        if (ip < 0) {
            return out;
        }
        long base = ip & 0xffffff00L;
        for (int i = 1; i <= 254; i++) {
            out.add(fromLong(base + i));
        }
        return out;
    }

    public static String broadcast24(String localIp) {
        long ip = toLong(localIp);
        return ip < 0 ? "255.255.255.255" : fromLong((ip & 0xffffff00L) + 255L);
    }

    public static String subnet24(String localIp) {
        long ip = toLong(localIp);
        return ip < 0 ? "" : fromLong(ip & 0xffffff00L);
    }

    public static boolean sameSubnet24(String a, String b) {
        long x = toLong(a), y = toLong(b);
        return x >= 0 && y >= 0 && (x & 0xffffff00L) == (y & 0xffffff00L);
    }
}
