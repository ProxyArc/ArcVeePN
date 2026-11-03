package com.vpeen.android;

import java.util.ArrayList;
import java.util.List;

public class Server {
    public String region;
    public String regionName;
    public List<String> addresses = new ArrayList<>();
    public int port;
    public String username;
    public String password;
    // Resolved before the TUN starts, so upstream sockets never need
    // DNS through the tunnel itself (that would loop).
    public String resolvedIp;

    public String host() {
        if (addresses != null && !addresses.isEmpty()) return addresses.get(0);
        return "";
    }

    @Override public String toString() {
        return host() + ":" + port + " (" + region + ")";
    }
}
