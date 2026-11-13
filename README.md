# ArcVeePN

Android VPN app — Shadowsocks (primary) with a VMess backup subscription,
a WebView UI, proxy mode and VPN mode with split tunneling, and a real
per-server ping test.

## Public source

This is the public version of the ArcVeePN 2.5 source. It is published
for learning and reference. **Put your own subscription URLs in
`SubscriptionApi.java`** (`SUBSCRIPTION_URL` and
`BACKUP_SUBSCRIPTION_URL`) — the placeholders here point at
`example.com` and return nothing, so a fork alone does not work
like the official build.

## Build

See `build.sh`. You need the Android SDK (build-tools 34) and JDK 17.
Signing needs your own keystore — the official keystore is not
published.
