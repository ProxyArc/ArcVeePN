package com.vpeen.android;

/**
 * One server from a subscription (primary Shadowsocks or
 * backup VMess). The password / uuid is supplied by the
 * subscription and never logged anywhere (see SubscriptionApi).
 *
 * proto: "ss" (primary, Shadowsocks) or "vmess" (backup).
 * name: display name — for VMess the link's ps (may carry a
 * flag emoji); for SS it is empty and the UI derives the
 * country name from the flag.
 */
public class Server {
    public String ip;
    public int port = 990;
    public String method;
    public String password;
    public String flag;
    public String countryCode;
    public String proto = "ss";
    public String name;

    // VMess only (backup sub).
    public String uuid;
    public int alterId;
    public String network;   // "tcp" | "ws" | ...
    public String type;      // "none" | "http" | ...
    public String host;      // ws host / http host
    public String path;      // ws path
    public String tls;       // "tls" | "none"
    public String serverName; // TLS SNI (host if set, else ip)

    /** IP + proto + flag only — NEVER the password/uuid. Safe for logs. */
    @Override public String toString() {
        String f = (flag != null) ? flag : "";
        String h = (ip != null) ? ip : "";
        String p = (proto != null) ? proto : "ss";
        return p + " " + f + " " + h + ":" + port;
    }
}
