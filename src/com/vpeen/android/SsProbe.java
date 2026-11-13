package com.vpeen.android;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * v4: a REAL Shadowsocks AEAD handshake probe.
 *
 * A plain TCP connect to :990 only proves the port accepts
 * connections -- a dead Shadowsocks service behind it still
 * answers TCP, so the old ping showed a fake ~60 ms for
 * servers that cannot carry traffic. This probe performs a
 * full AEAD handshake and waits for the first relayed byte,
 * so it only reports a latency when the whole path works
 * (handshake accepted + request relayed + response returned).
 *
 * Protocol (Shadowsocks AEAD, SIP004, chacha20-ietf-poly1305):
 * - master key = EVP_BytesToKey(password, MD5, 1 iter, no
 *   salt) -> 32 bytes.
 * - random 32-byte salt; session subkey = HKDF-SHA1(masterKey,
 *   salt, info "ss-subkey") -> 32 bytes.
 * - first data chunk = SOCKS5 target (ATYP 0x01 + 1.1.1.1 +
 *   port 80) + a minimal HTTP request "GET / HTTP/1.0\r\n
 *   Host: 1.1.1.1\r\n\r\n".
 * - AEAD framing: the 2-byte big-endian length is encrypted
 *   as its own AEAD unit (nonce 0), then the data (nonce 1),
 *   nonces incrementing as little-endian; AAD is empty; each
 *   unit appends a 16-byte Poly1305 tag. The client sends:
 *   salt (32) + encrypted-length + encrypted-data.
 * - the ping is the time from sending the handshake to the
 *   FIRST response byte (the relayed HTTP response). Close
 *   after the first byte; do not download the whole response.
 * - no byte within the timeout (or connect failure, or a
 *   non-AEAD method) -> TIMEOUT (-1).
 *
 * The password here is the REAL  secret -- the
 * caller passes Server.password. It is never logged.
 */
public final class SsProbe {
    private SsProbe() {}

    public static final int DEFAULT_TIMEOUT_MS = 3000;

    /**
     * Probe one Shadowsocks server. Returns the handshake
     * latency in ms, or -1 for TIMEOUT / failure.
     * Connects directly (no proxy) -- the app runs on the
     * phone with direct internet.
     */
    public static long probe(String ip, int port, String method,
                             String password, int timeoutMs) {
        if (ip == null || ip.isEmpty() || port <= 0 || port > 65535) return -1;
        if (password == null || password.isEmpty()) return -1;
        // Only chacha20-ietf-poly1305 is implemented (the only
        // method the subscription uses). Anything else cannot
        // be probed honestly -- report TIMEOUT, never a fake ms.
        if (method != null && !method.isEmpty()
                && !"chacha20-ietf-poly1305".equals(method)) return -1;
        Socket sock = null;
        try {
            sock = new Socket();
            sock.connect(new InetSocketAddress(ip, port), timeoutMs);
            return probeOverSocket(sock, method, password, timeoutMs);
        } catch (Throwable ignored) {
            try { if (sock != null) sock.close(); } catch (Throwable ignored2) {}
            return -1;
        }
    }

    /** Test-only: probe with an explicit relay target (the
     *  harness points it at a local HTTP server). The app
     *  always uses probe() above (1.1.1.1:80). */
    static long probeWithTarget(String ip, int port, String method,
            String password, int timeoutMs,
            String targetIp, int targetPort) {
        if (ip == null || ip.isEmpty() || port <= 0 || port > 65535) return -1;
        if (password == null || password.isEmpty()) return -1;
        if (method != null && !method.isEmpty()
                && !"chacha20-ietf-poly1305".equals(method)) return -1;
        Socket sock = null;
        try {
            sock = new Socket();
            sock.connect(new InetSocketAddress(ip, port), timeoutMs);
            return probeOverSocketTarget(sock, method, password,
                    timeoutMs, targetIp, targetPort);
        } catch (Throwable ignored) {
            try { if (sock != null) sock.close(); } catch (Throwable ignored2) {}
            return -1;
        }
    }

    /** Run the handshake over an already-connected socket
     *  (used by the VM harness, which tunnels through the
     *  egress proxy via HTTP CONNECT; the app uses probe()
     *  above). Closes the socket. Returns ms or -1. */
    static long probeOverSocket(Socket sock, String method,
                                String password, int timeoutMs) {
        return probeOverSocketTarget(sock, method, password,
                timeoutMs, "1.1.1.1", 80);
    }

    /** Same, but the relay target is explicit (test-only:
     *  the VM harness points it at a local HTTP server,
     *  because the VM cannot reach 1.1.1.1:80; the app
     *  always uses 1.1.1.1:80). */
    static long probeOverSocketTarget(Socket sock, String method,
            String password, int timeoutMs,
            String targetIp, int targetPort) {
        try {
            byte[] masterKey = evpBytesToKey(password);
            byte[] salt = new byte[32];
            new SecureRandom().nextBytes(salt);
            byte[] subkey = hkdfSha1(masterKey, salt, "ss-subkey", 32);
            byte[] chunk = buildFirstChunk(targetIp, targetPort);
            byte[] lenBytes = new byte[]{
                    (byte) ((chunk.length >> 8) & 0xFF),
                    (byte) (chunk.length & 0xFF)};
            byte[] encLen = aeadEncrypt(subkey, nonce(0), lenBytes);
            byte[] encChunk = aeadEncrypt(subkey, nonce(1), chunk);

            sock.setSoTimeout(timeoutMs);
            OutputStream out = sock.getOutputStream();
            InputStream in = sock.getInputStream();
            long t0 = System.currentTimeMillis();
            out.write(salt);
            out.write(encLen);
            out.write(encChunk);
            out.flush();
            int b = in.read(); // first response byte, or -1 / timeout
            if (b < 0) return -1;
            return System.currentTimeMillis() - t0;
        } catch (Throwable ignored) {
            return -1;
        } finally {
            try { if (sock != null) sock.close(); } catch (Throwable ignored) {}
        }
    }

    /** Test-only: build the full handshake bytes (salt +
     *  encrypted-length + encrypted-data) for a given
     *  password + salt + target. Used by the harness to
     *  cross-check the crypto byte-for-byte against a
     *  reference implementation. */
    static byte[] buildHandshakeBytes(String password, byte[] salt,
            String targetIp, int targetPort) throws Exception {
        byte[] masterKey = evpBytesToKey(password);
        byte[] subkey = hkdfSha1(masterKey, salt, "ss-subkey", 32);
        byte[] chunk = buildFirstChunk(targetIp, targetPort);
        byte[] lenBytes = new byte[]{
                (byte) ((chunk.length >> 8) & 0xFF),
                (byte) (chunk.length & 0xFF)};
        byte[] encLen = aeadEncrypt(subkey, nonce(0), lenBytes);
        byte[] encChunk = aeadEncrypt(subkey, nonce(1), chunk);
        byte[] out = new byte[salt.length + encLen.length + encChunk.length];
        System.arraycopy(salt, 0, out, 0, salt.length);
        System.arraycopy(encLen, 0, out, salt.length, encLen.length);
        System.arraycopy(encChunk, 0, out, salt.length + encLen.length, encChunk.length);
        return out;
    }

    /** SOCKS5 target + minimal HTTP GET. The app always
     *  targets 1.1.1.1:80; the harness may point it at a
     *  local HTTP server. */
    private static byte[] buildFirstChunk(String targetIp, int targetPort) {
        byte[] ipBytes;
        try {
            ipBytes = java.net.InetAddress.getByName(targetIp).getAddress();
        } catch (Throwable e) {
            ipBytes = new byte[]{1, 1, 1, 1};
        }
        if (ipBytes.length != 4) ipBytes = new byte[]{1, 1, 1, 1};
        byte[] target = new byte[7];
        target[0] = 0x01;
        System.arraycopy(ipBytes, 0, target, 1, 4);
        target[5] = (byte) ((targetPort >> 8) & 0xFF);
        target[6] = (byte) (targetPort & 0xFF);
        byte[] http = ("GET / HTTP/1.0\r\nHost: " + targetIp + "\r\n\r\n")
                .getBytes(StandardCharsets.US_ASCII);
        byte[] chunk = new byte[target.length + http.length];
        System.arraycopy(target, 0, chunk, 0, target.length);
        System.arraycopy(http, 0, chunk, target.length, http.length);
        return chunk;
    }

    /** The app's chunk (1.1.1.1:80). */
    private static byte[] buildFirstChunk() {
        return buildFirstChunk("1.1.1.1", 80);
    }

    /** 12-byte AEAD nonce: the counter as little-endian. */
    private static byte[] nonce(long counter) {
        byte[] n = new byte[12];
        for (int i = 0; i < 8; i++) {
            n[i] = (byte) ((counter >> (8 * i)) & 0xFF);
        }
        return n;
    }

    /** ChaCha20-Poly1305 (RFC 8439) encrypt; output is
     *  password + 16-byte tag. */
    private static byte[] aeadEncrypt(byte[] key, byte[] nonce, byte[] plain)
            throws Exception {
        Cipher cipher = Cipher.getInstance("ChaCha20-Poly1305");
        cipher.init(Cipher.ENCRYPT_MODE,
                new SecretKeySpec(key, "ChaCha20"),
                new IvParameterSpec(nonce));
        return cipher.doFinal(plain);
    }

    /** EVP_BytesToKey (MD5, 1 iteration, no salt) -> 32 bytes.
     *  D1 = MD5(password); D2 = MD5(D1 || password);
     *  key = D1 || D2. */
    static byte[] evpBytesToKey(String password) throws Exception {
        byte[] pw = password.getBytes(StandardCharsets.UTF_8);
        MessageDigest md5 = MessageDigest.getInstance("MD5");
        byte[] d1 = md5.digest(pw);
        md5.reset();
        md5.update(d1);
        byte[] d2 = md5.digest(pw);
        byte[] key = new byte[32];
        System.arraycopy(d1, 0, key, 0, 16);
        System.arraycopy(d2, 0, key, 16, 16);
        return key;
    }

    /** HKDF (RFC 5869) with SHA-1. salt is the HKDF salt,
     *  ikm the input keying material, info the context string.
     *  Returns `length` bytes. */
    static byte[] hkdfSha1(byte[] ikm, byte[] salt, String info, int length)
            throws Exception {
        Mac mac = Mac.getInstance("HmacSHA1");
        // Extract: PRK = HMAC-SHA1(salt, IKM).
        mac.init(new SecretKeySpec(salt, "HmacSHA1"));
        byte[] prk = mac.doFinal(ikm);
        // Expand: T(1) = HMAC(PRK, info || 0x01),
        // T(2) = HMAC(PRK, T(1) || info || 0x02), ...
        byte[] infoBytes = info.getBytes(StandardCharsets.UTF_8);
        byte[] out = new byte[length];
        byte[] t = new byte[0];
        int off = 0;
        int counter = 1;
        while (off < length) {
            mac.init(new SecretKeySpec(prk, "HmacSHA1"));
            mac.update(t);
            mac.update(infoBytes);
            mac.update((byte) counter);
            t = mac.doFinal();
            int n = Math.min(t.length, length - off);
            System.arraycopy(t, 0, out, off, n);
            off += n;
            counter++;
        }
        return out;
    }

    /** For tests: the subkey for a given password + salt. */
    static byte[] subkeyFor(String password, byte[] salt) throws Exception {
        return hkdfSha1(evpBytesToKey(password), salt, "ss-subkey", 32);
    }
}
