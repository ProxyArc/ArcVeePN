package com.vpeen.android;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Port of VPeeN's vpeen/localproxy.py, SOCKS5 + HTTP CONNECT parts.
 * Listens only on 127.0.0.1. Every connection is forwarded through
 * the VeePN HTTPS upstream (Upstream.connectViaServer).
 * UDP ASSOCIATE relays DNS (port 53) as DNS-over-TCP through the
 * tunnel; other UDP is dropped, exactly like desktop VPeeN.
 * Note: with hev's mapdns, most DNS never reaches here at all:
 * hev maps names to fake IPs and sends domain CONNECTs instead.
 */
public class LocalSocksServer {
    public static final int SOCKS_PORT = 1080;
    public static final int HTTP_PORT = 8080;

    private volatile Server server;
    private final Upstream.Protector protector;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private ServerSocket socksSocket;
    private ServerSocket httpSocket;
    private DatagramSocket udpSocket;
    private Thread socksThread, httpThread, udpThread;

    public LocalSocksServer(Server server, Upstream.Protector protector) {
        this.server = server;
        this.protector = protector;
    }

    public void setServer(Server s) { this.server = s; }

    public void start() throws IOException {
        if (running.getAndSet(true)) return;
        socksSocket = new ServerSocket();
        socksSocket.bind(new InetSocketAddress("127.0.0.1", SOCKS_PORT));
        httpSocket = new ServerSocket();
        httpSocket.bind(new InetSocketAddress("127.0.0.1", HTTP_PORT));
        socksThread = new Thread(this::acceptSocks, "vpeen-socks");
        socksThread.setDaemon(true); socksThread.start();
        httpThread = new Thread(this::acceptHttp, "vpeen-http");
        httpThread.setDaemon(true); httpThread.start();
    }

    public void stop() {
        running.set(false);
        try { if (socksSocket != null) socksSocket.close(); } catch (Exception ignored) {}
        try { if (httpSocket != null) httpSocket.close(); } catch (Exception ignored) {}
        try { if (udpSocket != null) udpSocket.close(); } catch (Exception ignored) {}
    }

    private void acceptSocks() {
        while (running.get()) {
            try {
                Socket c = socksSocket.accept();
                Thread t = new Thread(() -> handleSocks(c), "vpeen-socks-conn");
                t.setDaemon(true); t.start();
            } catch (Exception e) { if (running.get()) sleepQuiet(100); }
        }
    }

    private void acceptHttp() {
        while (running.get()) {
            try {
                Socket c = httpSocket.accept();
                Thread t = new Thread(() -> handleHttp(c), "vpeen-http-conn");
                t.setDaemon(true); t.start();
            } catch (Exception e) { if (running.get()) sleepQuiet(100); }
        }
    }

    private static void sleepQuiet(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) {}
    }

    // ---------------- SOCKS5 ----------------

    private void handleSocks(Socket client) {
        try {
            client.setSoTimeout(30000);
            InputStream in = client.getInputStream();
            OutputStream out = client.getOutputStream();
            int ver = in.read();
            if (ver != 5) { client.close(); return; }
            int nmethods = in.read();
            if (nmethods < 0) { client.close(); return; }
            byte[] methods = new byte[nmethods];
            readFully(in, methods);
            out.write(new byte[]{5, 0}); out.flush(); // no auth (loopback only)
            int v2 = in.read(), cmd = in.read(), rsv = in.read(), atyp = in.read();
            if (v2 != 5) { client.close(); return; }
            String host;
            if (atyp == 1) {
                byte[] ip = new byte[4]; readFully(in, ip);
                host = InetAddress.getByAddress(ip).getHostAddress();
            } else if (atyp == 3) {
                int len = in.read();
                byte[] d = new byte[len]; readFully(in, d);
                host = new String(d, StandardCharsets.UTF_8);
            } else if (atyp == 4) {
                byte[] ip = new byte[16]; readFully(in, ip);
                host = InetAddress.getByAddress(ip).getHostAddress();
            } else { client.close(); return; }
            int port = (in.read() << 8) | in.read();
            if (cmd == 1) { // CONNECT
                Socket upstream = Upstream.connectViaServer(server, host, port, protector);
                out.write(new byte[]{5, 0, 0, 1, 0, 0, 0, 0, 0, 0}); out.flush();
                client.setSoTimeout(0);
                relay(client, upstream);
            } else if (cmd == 3) { // UDP ASSOCIATE
                ensureUdp();
                int udpPort = udpSocket.getLocalPort();
                out.write(new byte[]{5, 0, 0, 1, 127, 0, 0, 1,
                        (byte) ((udpPort >> 8) & 0xFF), (byte) (udpPort & 0xFF)});
                out.flush();
                // Hold the TCP control connection open until the client closes it.
                try { while (in.read() != -1) { /* discard */ } } catch (Exception ignored) {}
            } else {
                out.write(new byte[]{5, 7, 0, 1, 0, 0, 0, 0, 0, 0}); out.flush();
            }
        } catch (Exception ignored) {
        } finally {
            try { client.close(); } catch (Exception ignored) {}
        }
    }

    private synchronized void ensureUdp() throws IOException {
        if (udpSocket != null && !udpSocket.isClosed()) return;
        udpSocket = new DatagramSocket(new InetSocketAddress("127.0.0.1", 0));
        udpThread = new Thread(this::udpLoop, "vpeen-udp");
        udpThread.setDaemon(true); udpThread.start();
    }

    private void udpLoop() {
        byte[] buf = new byte[9000];
        while (running.get() && udpSocket != null && !udpSocket.isClosed()) {
            try {
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                udpSocket.receive(p);
                byte[] data = new byte[p.getLength()];
                System.arraycopy(p.getData(), p.getOffset(), data, 0, data.length);
                if (data.length < 10) continue;
                int frag = data[2] & 0xFF;
                if (frag != 0) continue;
                int atyp = data[3] & 0xFF;
                int off = 4; String host;
                if (atyp == 1) {
                    if (data.length < 10) continue;
                    host = InetAddress.getByAddress(new byte[]{data[4], data[5], data[6], data[7]}).getHostAddress();
                    off = 8;
                } else if (atyp == 3) {
                    int len = data[4] & 0xFF; off = 5;
                    if (data.length < off + len + 2) continue;
                    host = new String(data, off, len, StandardCharsets.UTF_8);
                    off += len;
                } else if (atyp == 4) {
                    if (data.length < 22) continue;
                    byte[] ip = new byte[16]; System.arraycopy(data, 4, ip, 0, 16);
                    host = InetAddress.getByAddress(ip).getHostAddress();
                    off = 20;
                } else continue;
                int port = ((data[off] & 0xFF) << 8) | (data[off + 1] & 0xFF);
                off += 2;
                if (port != 53) continue; // VPeeN: only DNS over UDP, rest dropped
                byte[] query = new byte[data.length - off];
                System.arraycopy(data, off, query, 0, query.length);
                byte[] resp = Upstream.dnsOverTcp(server, host, query, protector);
                // Build SOCKS UDP reply: header + response
                byte[] hdr;
                if (host.contains(":")) {
                    hdr = new byte[22];
                    hdr[3] = 4;
                    byte[] ip = InetAddress.getByName(host).getAddress();
                    System.arraycopy(ip, 0, hdr, 4, 16);
                    hdr[20] = (byte) ((port >> 8) & 0xFF); hdr[21] = (byte) (port & 0xFF);
                } else {
                    // Reply with the queried host as a domain (hev accepts it).
                    byte[] hb = host.getBytes(StandardCharsets.UTF_8);
                    hdr = new byte[5 + hb.length + 2];
                    hdr[3] = 3; hdr[4] = (byte) hb.length;
                    System.arraycopy(hb, 0, hdr, 5, hb.length);
                    hdr[5 + hb.length] = (byte) ((port >> 8) & 0xFF);
                    hdr[6 + hb.length] = (byte) (port & 0xFF);
                }
                byte[] out = new byte[hdr.length + resp.length];
                System.arraycopy(hdr, 0, out, 0, hdr.length);
                System.arraycopy(resp, 0, out, hdr.length, resp.length);
                udpSocket.send(new DatagramPacket(out, out.length, p.getAddress(), p.getPort()));
            } catch (Exception e) {
                if (running.get()) sleepQuiet(50);
            }
        }
    }

    // ---------------- HTTP (CONNECT only) ----------------

    private void handleHttp(Socket client) {
        try {
            client.setSoTimeout(30000);
            InputStream in = client.getInputStream();
            OutputStream out = client.getOutputStream();
            String reqLine = readLine(in);
            if (reqLine == null) { client.close(); return; }
            // Consume headers.
            String h; while ((h = readLine(in)) != null && !h.isEmpty()) { /* skip */ }
            String[] parts = reqLine.split(" ");
            if (parts.length < 2 || !"CONNECT".equalsIgnoreCase(parts[0])) {
                out.write("HTTP/1.1 405 Only CONNECT supported\r\nContent-Length: 0\r\n\r\n"
                        .getBytes(StandardCharsets.ISO_8859_1));
                out.flush(); client.close(); return;
            }
            String hp = parts[1];
            int colon = hp.lastIndexOf(':');
            if (colon < 0) { client.close(); return; }
            String host = hp.substring(0, colon);
            int port = Integer.parseInt(hp.substring(colon + 1));
            Socket upstream = Upstream.connectViaServer(server, host, port, protector);
            out.write("HTTP/1.1 200 Connection Established\r\n\r\n"
                    .getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
            client.setSoTimeout(0);
            relay(client, upstream);
        } catch (Exception ignored) {
        } finally {
            try { client.close(); } catch (Exception ignored) {}
        }
    }

    // ---------------- helpers ----------------

    private static void readFully(InputStream in, byte[] b) throws IOException {
        int off = 0;
        while (off < b.length) {
            int n = in.read(b, off, b.length - off);
            if (n < 0) throw new IOException("eof");
            off += n;
        }
    }

    private static String readLine(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') break;
            if (c != '\r') sb.append((char) c);
            if (sb.length() > 8192) break;
        }
        if (c == -1 && sb.length() == 0) return null;
        return sb.toString();
    }

    private static void relay(Socket a, Socket b) {
        Thread t1 = new Thread(() -> copy(a, b), "vpeen-relay-1");
        Thread t2 = new Thread(() -> copy(b, a), "vpeen-relay-2");
        t1.setDaemon(true); t2.setDaemon(true);
        t1.start(); t2.start();
        try { t1.join(); } catch (InterruptedException ignored) {}
        try { b.shutdownOutput(); } catch (Exception ignored) {}
        try { t2.join(2000); } catch (InterruptedException ignored) {}
        try { a.close(); } catch (Exception ignored) {}
        try { b.close(); } catch (Exception ignored) {}
    }

    private static void copy(Socket from, Socket to) {
        try {
            InputStream in = from.getInputStream();
            OutputStream out = to.getOutputStream();
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
                out.flush();
            }
        } catch (Exception ignored) {
        } finally {
            try { from.shutdownInput(); } catch (Exception ignored) {}
            try { to.shutdownOutput(); } catch (Exception ignored) {}
        }
    }
}
