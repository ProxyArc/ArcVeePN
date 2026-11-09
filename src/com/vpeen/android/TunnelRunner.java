package com.vpeen.android;

/**
 * Tunnel core runner, loaded ONLY on Connect.
 *
 * VpnTunnelService never references any libbox type directly — it
 * holds this interface and loads the implementation reflectively
 * (Class.forName("com.vpeen.android.SingBoxRunner")) only when a
 * tunnel is actually started. That keeps the 46 MB Go .so
 * (libbox.so, loaded by go/Seq's static initializer the first
 * time any libbox class is touched) completely out of the app
 * launch path: MainActivity's launch-time getStatusJson() can
 * never trigger libbox class loading or native loading.
 */
public interface TunnelRunner {
    /** Start the tunnel with the given sing-box config JSON. */
    void start(String configJson) throws Throwable;

    /** Stop the tunnel and release all native resources. */
    void stop();
}
