package com.fongmi.android.tv.offline;

import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.util.Collections;

import okhttp3.HttpUrl;

/** Literal IPv4 endpoints on an attached subnet only; no Internet hosts or redirects. */
public final class OfflineLan {
    private OfflineLan() {}

    public static String endpoint(String address) {
        HttpUrl url = HttpUrl.parse(address);
        if (url == null || !url.scheme().equals("http") || !url.username().isEmpty() || !url.password().isEmpty()
                || !url.encodedPath().equals("/") || url.query() != null || url.fragment() != null || !contains(url.host()))
            throw new IllegalArgumentException("Only devices on the same LAN are supported");
        return url.scheme() + "://" + url.host() + ":" + url.port();
    }

    public static boolean contains(String host) {
        try {
            if (host == null || !host.matches("[0-9]{1,3}(\\.[0-9]{1,3}){3}")) return false;
            String[] octets = host.split("\\.");
            for (String octet : octets) if (Integer.parseInt(octet) > 255) return false;
            InetAddress target = InetAddress.getByName(host);
            if (target.isAnyLocalAddress() || target.isMulticastAddress()) return false;
            if (target.isLoopbackAddress()) return true;
            byte[] bytes = target.getAddress();
            for (NetworkInterface network : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!network.isUp() || network.isLoopback()) continue;
                for (InterfaceAddress local : network.getInterfaceAddresses()) {
                    byte[] own = local.getAddress().getAddress();
                    int bits = local.getNetworkPrefixLength();
                    if (own.length != 4 || bits < 1 || bits > 32) continue;
                    boolean match = true;
                    for (int i = 0; i < 4 && bits > 0; i++) {
                        int n = Math.min(bits, 8), mask = (255 << (8 - n)) & 255;
                        if ((own[i] & mask) != (bytes[i] & mask)) { match = false; break; }
                        bits -= n;
                    }
                    if (match && !host.equals(local.getBroadcast() == null ? "" : local.getBroadcast().getHostAddress())) return true;
                }
            }
        } catch (Exception ignored) {}
        return false;
    }
}
