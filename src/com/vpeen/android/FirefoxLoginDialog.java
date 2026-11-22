package com.vpeen.android;

import android.app.Activity;
import android.app.Dialog;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

/**
 * Firefox sign-in inside the app, exactly like Arman VPN's
 * browser sign-in (app/lib/ui/browser_signin.dart):
 * Mozilla's own sign-in page in a WebView with a Firefox
 * user-agent. The page reports the signed-in session over
 * its WebChannel (fxaccounts:login -> sessionToken); the
 * app never sees the password, and any verification code,
 * CAPTCHA, unblock code, or Google/Apple sign-in is handled
 * right there on Mozilla's page.
 *
 * After the session is captured, this class polls FxA until
 * the session is confirmed, exchanges it for OAuth tokens,
 * destroys the session, and fetches the server list + quota.
 * The dialog stays open while the user enters any code.
 *
 * Result (on the UI thread): "ok" | "cancelled" | "failed".
 * "failed" is generic on purpose — no error detail is ever
 * shown on screen; the detail stays in the silent log.
 */
public final class FirefoxLoginDialog {
    private FirefoxLoginDialog() {}

    public interface Listener {
        void onLoginResult(String status);
    }

    /** The WebChannel hook, adapted from Arman's FxaWebChannel.hookJs:
     *  forwards the page's WebChannelMessageToChrome events to the
     *  Java channel (window.FxaChannel.onWebChannel) and defines
     *  window.__ffxReply for the app's answers. */
    static final String HOOK_JS =
            "(function(){"
            + "if(window.__ffxHooked)return;window.__ffxHooked=true;"
            + "var send=function(o){try{window.FxaChannel.onWebChannel(JSON.stringify(o));}catch(e){}};"
            + "var onChannel=function(e){"
            + "var d=e.detail;"
            + "if(typeof d==='string'){try{d=JSON.parse(d);}catch(e){return;}}"
            + "if(d&&d.message)send({kind:'webchannel',detail:d});};"
            + "window.addEventListener('WebChannelMessageToChrome',onChannel);"
            + "document.addEventListener('WebChannelMessageToChrome',onChannel);"
            + "window.__ffxReply=function(id,messageId,command,data){"
            + "var detail={id:id,message:{messageId:messageId,command:command,data:data}};"
            + "window.dispatchEvent(new CustomEvent('WebChannelMessageToContent',{detail:detail}));"
            + "document.dispatchEvent(new CustomEvent('WebChannelMessageToContent',{detail:detail}));};"
            + "})();";

    private static final long SIGNIN_TIMEOUT_MS = 15L * 60 * 1000; // Arman signInTimeout
    // 3.1 fix6: the poll is only the BACKUP for a session that
    // is not verified yet — the WebChannel callback that
    // delivers the session is real-time. 500 ms, not 3 s:
    // the 3 s poll was a visible part of the "a second
    // later" the user felt after signing in.
    private static final long POLL_MS = 500;

    public static void show(final Activity activity, final String action,
            final Listener listener) {
        activity.runOnUiThread(() -> showOnUi(activity, action, listener));
    }

    private static void showOnUi(final Activity activity, final String action,
            final Listener listener) {
        final Dialog dialog = new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setCancelable(true);

        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout head = new LinearLayout(activity);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(32, 20, 16, 20);
        head.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView title = new TextView(activity);
        title.setText("Firefox");
        title.setTextSize(18);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        title.setLayoutParams(titleLp);
        head.addView(title);
        Button close = new Button(activity);
        close.setText("✕");
        close.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        head.addView(close);
        root.addView(head);

        final WebView webView = new WebView(activity);
        LinearLayout.LayoutParams webLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        webView.setLayoutParams(webLp);
        root.addView(webView);

        final Session session = new Session(activity, dialog, webView, listener);

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setUserAgentString(FirefoxAuth.FIREFOX_ANDROID_UA);
        try {
            android.webkit.CookieManager.getInstance().setAcceptCookie(true);
        } catch (Throwable ignored) {}
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                try {
                    view.evaluateJavascript(HOOK_JS, null);
                } catch (Throwable ignored) {}
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view,
                    WebResourceRequest request) {
                // The whole FxA flow stays inside this dialog.
                return false;
            }
        });
        webView.addJavascriptInterface(session.channel, "FxaChannel");

        close.setOnClickListener(v -> session.cancel("cancelled"));
        dialog.setOnCancelListener(d -> session.cancel("cancelled"));
        dialog.setOnDismissListener(d -> session.cancel("cancelled"));

        dialog.setContentView(root);
        try {
            Window w = dialog.getWindow();
            if (w != null) {
                DisplayMetrics m = new DisplayMetrics();
                activity.getWindowManager().getDefaultDisplay().getMetrics(m);
                WindowManager.LayoutParams lp = w.getAttributes();
                lp.width = WindowManager.LayoutParams.MATCH_PARENT;
                lp.height = (int) (m.heightPixels * 0.85);
                lp.gravity = Gravity.BOTTOM;
                w.setAttributes(lp);
            }
        } catch (Throwable ignored) {}
        dialog.show();

        String url = "signup".equals(action) ? FirefoxAuth.SIGNUP_URL : FirefoxAuth.LOGIN_URL;
        webView.loadUrl(url);
    }

    /** One sign-in attempt: the WebChannel handler + the
     *  background poll/exchange. Tokens are never logged. */
    private static final class Session {
        private final Activity activity;
        private final Dialog dialog;
        private final WebView webView;
        private final Listener listener;
        private volatile boolean done = false;
        private volatile String sessionToken = null;
        private volatile String email = "";

        Session(Activity activity, Dialog dialog, WebView webView, Listener listener) {
            this.activity = activity;
            this.dialog = dialog;
            this.webView = webView;
            this.listener = listener;
        }

        final Object channel = new Object() {
            @JavascriptInterface
            public void onWebChannel(String json) {
                try {
                    handleChannel(new JSONObject(json));
                } catch (Throwable ignored) {}
            }
        };

        private void handleChannel(JSONObject m) {
            try {
                if (!"webchannel".equals(m.optString("kind", ""))) return;
                JSONObject detail = m.optJSONObject("detail");
                if (detail == null) return;
                String id = detail.optString("id", "");
                JSONObject message = detail.optJSONObject("message");
                if (message == null || id.isEmpty()) return;
                String command = message.optString("command", "");
                Object messageId = message.opt("messageId");
                if ("fxaccounts:fxa_status".equals(command)) {
                    JSONObject data = new JSONObject();
                    data.put("capabilities", new JSONObject());
                    data.put("signedInUser", JSONObject.NULL);
                    reply(id, messageId, command, data);
                } else if ("fxaccounts:can_link_account".equals(command)) {
                    JSONObject data = new JSONObject();
                    data.put("ok", true);
                    reply(id, messageId, command, data);
                } else if ("fxaccounts:login".equals(command)
                        || "fxaccounts:oauth_login".equals(command)) {
                    JSONObject data = message.optJSONObject("data");
                    if (data == null) return;
                    String token = data.optString("sessionToken", "");
                    if (token.isEmpty()) token = data.optString("token", "");
                    if (!token.matches("^[0-9a-f]{64}$")) return;
                    String em = data.optString("email", "");
                    onSession(em, token);
                }
            } catch (Throwable ignored) {}
        }

        private void reply(final String id, final Object messageId,
                final String command, final JSONObject data) {
            final String js = "window.__ffxReply("
                    + JSONObject.quote(id) + ","
                    + (messageId == null ? "null" : JSONObject.quote(String.valueOf(messageId))) + ","
                    + JSONObject.quote(command) + ","
                    + data.toString() + ")";
            webView.post(() -> {
                try { webView.evaluateJavascript(js, null); } catch (Throwable ignored) {}
            });
        }

        /** A session, once — the page may repeat its message,
         *  and a different session replaces the first (Arman).
         *  A replaced poll thread exits at its next token
         *  check; the new token gets its own poll. */
        private synchronized void onSession(String em, String token) {
            if (done) return;
            if (token.equals(sessionToken)) return;
            sessionToken = token;
            email = em == null ? "" : em;
            startPoll(token, email);
        }

        private void startPoll(final String token, final String em) {
            // 3.1 fix6: the flow lives in SigninRunner (below)
            // so its timing is unit-testable (Fix6LoginTest).
            final Activity act = activity;
            SigninRunner.Steps steps = new SigninRunner.Steps() {
                @Override public boolean sessionVerified(String t) throws Exception {
                    return FirefoxAuth.sessionVerified(t);
                }
                @Override public FirefoxAuth.TokenResponse exchangeOAuth(String t) throws Exception {
                    return FirefoxAuth.exchangeOAuth(t);
                }
                @Override public void saveTokens(String e, FirefoxAuth.TokenResponse tok) {
                    FirefoxAuth.saveTokens(act, e, tok);
                }
                @Override public void afterLogin(String t) {
                    // Best effort, AFTER the dialog closed:
                    // destroy the FxA session and warm the
                    // server list + quota. (MainActivity fetches
                    // both itself on 'ok', so a failure here
                    // changes nothing.)
                    FirefoxAuth.destroySession(t);
                    try { FirefoxAuth.fetchServerList(act); } catch (Throwable ignored) {}
                    try { FirefoxAuth.refreshQuotaCached(act); } catch (Throwable ignored) {}
                }
            };
            new Thread(() -> SigninRunner.run(token, em, steps,
                    () -> !done && token.equals(sessionToken),
                    this::finish, SIGNIN_TIMEOUT_MS),
                    "arcveepn-ffx-signin").start();
        }

        void cancel(String status) {
            finish(status);
        }

        private void finish(final String status) {
            if (done) return;
            done = true;
            activity.runOnUiThread(() -> {
                try { dialog.dismiss(); } catch (Throwable ignored) {}
                try { listener.onLoginResult(status); } catch (Throwable ignored) {}
            });
        }
    }

    /** 3.1 fix6: the post-session sign-in flow, extracted from
     *  Session.startPoll so its TIMING is unit-testable (see
     *  Fix6LoginTest). The WebChannel callback that delivers
     *  the session is real-time; the poll below is only the
     *  backup for a session that is not verified yet — so it
     *  runs FAST (POLL_MS, 500 ms, not 3 s). And the dialog
     *  closes the moment the OAuth tokens are saved: the
     *  session destroy and the server-list/quota warm-up used
     *  to run BEFORE the close (three sequential HTTPS calls
     *  after the exchange — that was the "a second later"
     *  the user felt after signing in). They now run AFTER
     *  the close, in the background; the listener
     *  (MainActivity) fetches the list + quota itself on
     *  'ok', so nothing is lost. */
    static final class SigninRunner {
        interface Steps {
            boolean sessionVerified(String token) throws Exception;
            FirefoxAuth.TokenResponse exchangeOAuth(String token) throws Exception;
            void saveTokens(String email, FirefoxAuth.TokenResponse tok);
            /** Best-effort post-close work (destroy session,
             *  warm server list + quota). Never blocks close. */
            void afterLogin(String token);
        }

        static void run(String token, String email, Steps steps,
                java.util.function.BooleanSupplier isCurrent,
                java.util.function.Consumer<String> finish, long timeoutMs) {
            try {
                long deadline = System.currentTimeMillis() + timeoutMs;
                boolean verified = false;
                while (isCurrent.getAsBoolean()
                        && System.currentTimeMillis() < deadline) {
                    try {
                        if (steps.sessionVerified(token)) {
                            verified = true;
                            break;
                        }
                    } catch (FirefoxAuth.FfxException e) {
                        if (e.reauth) {
                            if (isCurrent.getAsBoolean()) finish.accept("failed");
                            return;
                        }
                        // Transient failure — ask again.
                    } catch (Throwable ignored) {
                        // Transient failure — ask again.
                    }
                    if (!isCurrent.getAsBoolean()) return;
                    try { Thread.sleep(POLL_MS); } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
                if (!isCurrent.getAsBoolean()) return;
                if (!verified) {
                    finish.accept("failed");
                    return;
                }
                FirefoxAuth.TokenResponse tok = steps.exchangeOAuth(token);
                if (!isCurrent.getAsBoolean()) return;
                steps.saveTokens(email, tok);
                // Close FIRST — the dialog must disappear the
                // moment the tokens exist (see above).
                finish.accept("ok");
                try { steps.afterLogin(token); } catch (Throwable ignored) {}
            } catch (Throwable t) {
                if (isCurrent.getAsBoolean()) finish.accept("failed");
            }
        }
    }
}
