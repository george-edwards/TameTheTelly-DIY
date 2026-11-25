// SPDX-License-Identifier: GPL-3.0-only
package au.internal.home;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.TextView;

/**
 * Muted, non-focusable WebView embed player for public YouTube Live / web streams inside a Home camera card.
 *
 * Dynamically creates its WebView on start() and destroys it on stop(), so RTSP-only setups pay zero
 * WebView memory cost and nothing decodes in the background when the user leaves Home.
 */
final class WebCamFeed {
    private final Context ctx;
    private final ViewGroup container;
    private final View rtspSurface;
    private final TextView state;
    private final Runnable onLive;
    private final Handler h = new Handler(Looper.getMainLooper());

    private String embedUrl;
    private WebView webView;
    private boolean running;
    private boolean live;

    WebCamFeed(Context ctx, ViewGroup container, View rtspSurface, TextView state, Runnable onLive) {
        this.ctx = ctx;
        this.container = container;
        this.rtspSurface = rtspSurface;
        this.state = state;
        this.onLive = onLive;
    }

    boolean isLive() { return live; }

    void setEmbedUrl(String url) {
        embedUrl = url;
        if (running) {
            stop();
            start();
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    void start() {
        if (running || embedUrl == null || embedUrl.isEmpty()) return;
        running = true;
        live = false;
        rtspSurface.setVisibility(View.GONE);
        show("Connecting…");

        WebView wv = new WebView(ctx);
        wv.setBackgroundColor(Color.BLACK);
        wv.setFocusable(false);
        wv.setFocusableInTouchMode(false);
        wv.setClickable(false);
        wv.setAlpha(0f);

        WebSettings s = wv.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);

        wv.setWebChromeClient(new WebChromeClient());
        wv.setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView view, String url) {
                if (!running || view != webView) return;
                h.postDelayed(() -> {
                    if (!running || view != webView) return;
                    live = true;
                    show(null);
                    view.animate().alpha(1f).setDuration(450).start();
                    onLive.run();
                }, 1_100);
            }

            @Override public void onReceivedError(WebView view, WebResourceRequest req, WebResourceError err) {
                if (!running || view != webView || !req.isForMainFrame()) return;
                show("Stream unavailable");
            }
        });

        container.addView(wv, 0, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        webView = wv;

        String safeUrl = embedUrl.replace("\"", "&quot;");
        String html = "<!doctype html><html><head><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                + "<style>html,body{margin:0;padding:0;width:100%;height:100%;background:#000;overflow:hidden}"
                + "iframe{position:fixed;inset:0;width:100%;height:100%;border:0;pointer-events:none}</style></head>"
                + "<body><iframe src=\"" + safeUrl + "\" allow=\"autoplay; encrypted-media\" allowfullscreen></iframe></body></html>";
        wv.loadDataWithBaseURL("https://www.youtube.com", html, "text/html", "utf-8", null);
    }

    void stop() {
        running = false;
        live = false;
        h.removeCallbacksAndMessages(null);
        WebView wv = webView;
        webView = null;
        if (wv != null) {
            container.removeView(wv);
            try {
                wv.stopLoading();
                wv.loadUrl("about:blank");
                wv.onPause();
                wv.destroy();
            } catch (RuntimeException ignored) { }
        }
    }

    private void show(String msg) {
        state.setVisibility(msg == null ? View.GONE : View.VISIBLE);
        if (msg != null) state.setText(msg);
    }
}
