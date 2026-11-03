package com.vpeen.android;

import android.util.Base64;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * Port of VPeeN's vpeen/upstream.py connect_via_server:
 * TLS to the VeePN proxy host, then HTTP CONNECT with Basic auth.
 * The returned socket is the raw tunnel: bytes written to it go
 * to the target, bytes read from it come from the target.
 */
public class Upstream {
    public interface Protector {
        void protect(Socket s);
    }

    private static String fmtTarget(String host) {
        host = host.trim();
        if (host.startsWith("[") && host.endsWith("]")) return host;
        if (host.contains(":")) return "[" + host + "]";
        return host;
    }

    /** Read one HTTP header block byte-by-byte (no buffering past it). */
    private static String readStatusLine(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int prev = -1, cur;
        boolean gotLine = false;
        String firstLine = null;
        while ((cur = in.read()) != -1) {
            if (cur == '\n') {
                String line = sb.toString().trim();
                sb.setLength(0);
                if (firstLine == null) firstLine = line;
                if (line.isEmpty()) break;
                gotLine = true;
            } else if (cur != '\r') {
                sb.append((char) cur);
            }
            prev = cur;
            if (sb.length() > 8192) throw new IOException("header too long");
        }
        if (!gotLine && firstLine == null) throw new IOException("empty CONNECT reply");
        return firstLine == null ? "" : firstLine;
    }

    public static Socket connectViaServer(Server server, String targetHost, int targetPort,
                                          Protector protector) throws IOException {
        IOException last = null;
        // Prefer the pre-resolved IP (set before TUN starts) to avoid
        // DNS through the tunnel; fall back to the hostname itself.
        java.util.List<String> addrs = server.addresses;
        if (server.resolvedIp != null && !server.resolvedIp.isEmpty()) {
            addrs = java.util.Collections.singletonList(server.resolvedIp);
        }
        for (String addr : addrs) {
            Socket raw = null;
            SSLSocket ssl = null;
            try {
                raw = new Socket();
                if (protector != null) protector.protect(raw);
                raw.setSoTimeout(10000);
                raw.connect(new InetSocketAddress(addr, server.port), 10000);
                String sniHost = server.host();
                ssl = (SSLSocket) ((SSLSocketFactory) SSLSocketFactory.getDefault())
                        .createSocket(raw, sniHost, server.port, true);
                ssl.setSoTimeout(10000);
                ssl.startHandshake();
                String target = fmtTarget(targetHost);
                String auth = Base64.encodeToString(
                        (server.username + ":" + server.password).getBytes(StandardCharsets.UTF_8),
                        Base64.NO_WRAP);
                String req = "CONNECT " + target + ":" + targetPort + " HTTP/1.1\r\n"
                        + "Host: " + target + ":" + targetPort + "\r\n"
                        + "Proxy-Authorization: Basic " + auth + "\r\n"
                        + "User-Agent: " + VeePnApi.USER_AGENT + "\r\n"
                        + "Proxy-Connection: keep-alive\r\n\r\n";
                OutputStream out = ssl.getOutputStream();
                out.write(req.getBytes(StandardCharsets.ISO_8859_1));
                out.flush();
                String status = readStatusLine(ssl.getInputStream());
                if (status.contains(" 200")) {
                    ssl.setSoTimeout(0);
                    return ssl;
                }
                throw new IOException("Upstream CONNECT failed: " + status);
            } catch (IOException e) {
                last = e;
                try { if (ssl != null) ssl.close(); } catch (Exception ignored) {}
                try { if (raw != null) raw.close(); } catch (Exception ignored) {}
            }
        }
        throw last != null ? last : new IOException("No upstream server address");
    }

    /** DNS-over-TCP through the tunnel, as VPeeN does for UDP DNS. */
    public static byte[] dnsOverTcp(Server server, String dnsHost, byte[] query,
                                    Protector protector) throws IOException {
        Socket tunnel = connectViaServer(server, dnsHost, 53, protector);
        try {
            tunnel.setSoTimeout(8000);
            OutputStream out = tunnel.getOutputStream();
            InputStream in = tunnel.getInputStream();
            out.write((query.length >> 8) & 0xFF);
            out.write(query.length & 0xFF);
            out.write(query);
            out.flush();
            int hi = in.read(), lo = in.read();
            if (hi < 0 || lo < 0) throw new IOException("short DNS reply");
            int len = (hi << 8) | lo;
            if (len <= 0 || len > 65535) throw new IOException("bad DNS length");
            byte[] resp = new byte[len];
            int off = 0;
            while (off < len) {
                int n = in.read(resp, off, len - off);
                if (n < 0) throw new IOException("truncated DNS reply");
                off += n;
            }
            return resp;
        } finally {
            try { tunnel.close(); } catch (Exception ignored) {}
        }
    }
}
