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
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Single shared WebView hosting up to two muted YouTube Live iframes behind Home's camera cards.
 *
 * Using one WebView (one Chromium WebContents) instead of two avoids Android WebView's per-WebView
 * MediaSession focus fight where two separate WebViews pause each other in a loop.
 * Created on demand when at least one slot is a World Cam, and destroyed on stop().
 */
final class WebCamHost {
    private static final String ORIGIN = "https://au.internal.home";

    private final Context ctx;
    private final ViewGroup backdrop;
    private final View[] camViews;
    private final Runnable[] onLive;
    private final Handler h = new Handler(Looper.getMainLooper());

    private final String[] urls = new String[2];
    private final boolean[] enabled = new boolean[2];
    private final boolean[] live = new boolean[2];
    private final boolean[] wantMuted = {true, true};
    private final String[] loadedUrls = new String[2];

    private WebView webView;
    private boolean pageReady;
    private boolean running;
    private boolean fullscreen;

    WebCamHost(Context ctx, ViewGroup backdrop, View[] camViews, Runnable[] onLive) {
        this.ctx = ctx;
        this.backdrop = backdrop;
        this.camViews = camViews;
        this.onLive = onLive;
        View.OnLayoutChangeListener relayout = (v, l, t, r, b, ol, ot, or_, ob) -> {
            if (l != ol || t != ot || r != or_ || b != ob) syncLayout();
        };
        backdrop.addOnLayoutChangeListener(relayout);
        for (View cv : camViews) cv.addOnLayoutChangeListener(relayout);
    }

    boolean isLive(int slot) { return slot >= 0 && slot < 2 && live[slot]; }

    void setFullscreen(boolean full) {
        fullscreen = full;
        if (!full) {
            setMuted(0, true);
            setMuted(1, true);
        }
        syncLayout();
    }

    /** Mute or unmute a YouTube embed slot live via its postMessage JS API + injected video shim (no reload). */
    void setMuted(int slot, boolean muted) {
        if (slot < 0 || slot > 1) return;
        wantMuted[slot] = muted;
        if (webView == null || !pageReady) return;
        webView.evaluateJavascript("ytMute(" + slot + "," + muted + ");", null);
    }

    void setSlot(int slot, boolean active, String embedUrl) {
        enabled[slot] = active && embedUrl != null && !embedUrl.isEmpty();
        urls[slot] = enabled[slot] ? decorate(slot, embedUrl) : null;
        if (!enabled[slot]) live[slot] = false;
        if (running) sync();
    }

    void start() {
        running = true;
        sync();
    }

    void stop() {
        running = false;
        pageReady = false;
        live[0] = live[1] = false;
        loadedUrls[0] = loadedUrls[1] = null;
        h.removeCallbacksAndMessages(null);
        WebView wv = webView;
        webView = null;
        backdrop.setVisibility(View.GONE);
        if (wv != null) {
            backdrop.removeView(wv);
            try {
                wv.stopLoading();
                wv.loadUrl("about:blank");
                wv.onPause();
                wv.destroy();
            } catch (RuntimeException ignored) { }
        }
    }

    private String decorate(int slot, String embedUrl) {
        String u = embedUrl;
        // Put slot 1 on youtube-nocookie.com so the two iframes have separate origins and
        // YouTube's embed JS can't see or pause its sibling via localStorage / BroadcastChannel.
        if (slot == 1 && u.startsWith("https://www.youtube.com/embed/")) {
            u = "https://www.youtube-nocookie.com/embed/" + u.substring("https://www.youtube.com/embed/".length());
        }
        if (!u.contains("youtube.com/") && !u.contains("youtube-nocookie.com/")) {
            return u;
        }
        if (!wantMuted[slot]) {
            u = u.replace("mute=1", "mute=0");
        }
        String sep = u.contains("?") ? "&" : "?";
        return u + sep + "enablejsapi=1&origin=" + ORIGIN;
    }

    private void sync() {
        boolean any = running && (enabled[0] || enabled[1]);
        if (!any) {
            boolean wasRunning = running;
            stop();
            running = wasRunning;
            return;
        }
        backdrop.setVisibility(View.VISIBLE);
        if (webView == null) {
            createWebView();
            return;
        }
        if (pageReady) applySlots();
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void createWebView() {
        pageReady = false;
        loadedUrls[0] = loadedUrls[1] = null;

        WebView wv = new WebView(ctx);
        wv.setBackgroundColor(Color.TRANSPARENT);
        wv.setFocusable(false);
        wv.setFocusableInTouchMode(false);
        wv.setClickable(false);

        WebSettings s = wv.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        // Strip "; wv)" so YouTube's embed player doesn't reject Android WebView with Error 152-4.
        String ua = s.getUserAgentString();
        if (ua != null) s.setUserAgentString(ua.replace("; wv)", ")").replace("Version/4.0 ", ""));
        final String cleanUa = s.getUserAgentString();

        wv.setWebChromeClient(new WebChromeClient() {
            @Override public android.graphics.Bitmap getDefaultVideoPoster() {
                return android.graphics.Bitmap.createBitmap(1, 1, android.graphics.Bitmap.Config.ARGB_8888);
            }
        });
        wv.setWebViewClient(new WebViewClient() {
            @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest req) {
                if ("GET".equals(req.getMethod()) && req.getUrl() != null) {
                    String host = req.getUrl().getHost();
                    String path = req.getUrl().getPath();
                    if (host != null && (host.equals("www.youtube.com") || host.equals("www.youtube-nocookie.com"))
                            && path != null && path.startsWith("/embed/")) {
                        WebResourceResponse patched = fetchWithoutAv1(req.getUrl().toString(), req.getRequestHeaders(), cleanUa);
                        if (patched != null) return patched;
                    }
                    if (EarthCamResolver.isEarthCamUrl(req.getUrl().toString())) {
                        WebResourceResponse ec = EarthCamResolver.buildPlayerResponse(req.getUrl().toString(), cleanUa);
                        if (ec != null) return ec;
                    }
                }
                return super.shouldInterceptRequest(view, req);
            }

            @Override public void onPageFinished(WebView view, String url) {
                if (!running || view != webView) return;
                pageReady = true;
                applySlots();
            }
        });

        backdrop.addView(wv, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        webView = wv;

        String html = "<!doctype html><html><head>"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no\">"
                + "<meta name=\"referrer\" content=\"strict-origin-when-cross-origin\">"
                + "<style>"
                + "html,body{margin:0;padding:0;width:100%;height:100%;background:transparent;overflow:hidden}"
                + ".slot{position:fixed;overflow:hidden;background:#000;display:none}"
                + "iframe{width:100%;height:100%;border:0;pointer-events:none;display:block}"
                + "</style></head><body>"
                + "<div id=\"s0\" class=\"slot\"><iframe id=\"f0\" referrerpolicy=\"strict-origin-when-cross-origin\" allow=\"autoplay; encrypted-media\" allowfullscreen></iframe></div>"
                + "<div id=\"s1\" class=\"slot\"><iframe id=\"f1\" referrerpolicy=\"strict-origin-when-cross-origin\" allow=\"autoplay; encrypted-media\" allowfullscreen></iframe></div>"
                + "<script>"
                + "function setSlot(i,url){"
                + "  var d=document.getElementById('s'+i),f=document.getElementById('f'+i);"
                + "  if(!url){d.style.display='none';f.src='about:blank';return;}"
                + "  d.style.display='block';f.src=url;"
                + "}"
                + "function setRect(i,l,t,w,h,r){"
                + "  var d=document.getElementById('s'+i);"
                + "  d.style.left=l+'px';d.style.top=t+'px';d.style.width=w+'px';d.style.height=h+'px';d.style.borderRadius=r+'px';"
                + "}"
                + "function ytCmd(i,fn,args){"
                + "  var f=document.getElementById('f'+i);"
                + "  if(f&&f.contentWindow){f.contentWindow.postMessage(JSON.stringify({event:'command',func:fn,args:args||[]}),'*');}"
                + "}"
                + "function ytMute(i,m){"
                + "  var f=document.getElementById('f'+i);"
                + "  if(!f||!f.contentWindow)return;"
                + "  ytCmd(i,m?'mute':'unMute',[]);"
                + "  if(!m)ytCmd(i,'setVolume',[100]);"
                + "  f.contentWindow.postMessage({homeMute:!!m},'*');"
                + "}"
                + "</script></body></html>";
        wv.loadDataWithBaseURL(ORIGIN + "/", html, "text/html", "utf-8", null);
    }

    private void applySlots() {
        if (webView == null || !pageReady) return;
        syncLayout();
        for (int i = 0; i < 2; i++) {
            String want = (enabled[i] && camViews[i].getVisibility() == View.VISIBLE) ? urls[i] : null;
            if (want == null && loadedUrls[i] == null) continue;
            if (want != null && want.equals(loadedUrls[i])) continue;
            loadedUrls[i] = want;
            String jsUrl = want == null ? "null" : ("'" + want.replace("\\", "\\\\").replace("'", "\\'") + "'");
            webView.evaluateJavascript("setSlot(" + i + "," + jsUrl + ");", null);
            TextView st = camViews[i].findViewById(R.id.state);
            if (want != null) {
                live[i] = false;
                st.setVisibility(View.VISIBLE);
                st.setText("Connecting…");
                final int slot = i;
                final String expected = want;
                h.postDelayed(() -> {
                    if (!running || !expected.equals(loadedUrls[slot])) return;
                    live[slot] = true;
                    st.setVisibility(View.GONE);
                    if (webView != null && pageReady) {
                        webView.evaluateJavascript("ytMute(" + slot + "," + wantMuted[slot] + ");", null);
                    }
                    if (onLive[slot] != null) onLive[slot].run();
                }, 1_400);
            } else {
                live[i] = false;
                st.setVisibility(View.GONE);
            }
        }
    }

    private void syncLayout() {
        if (webView == null || !pageReady) return;
        float d = ctx.getResources().getDisplayMetrics().density;
        if (d <= 0f) d = 1f;
        int[] base = new int[2];
        backdrop.getLocationInWindow(base);
        int radius = fullscreen ? 0 : 10;
        StringBuilder js = new StringBuilder();
        for (int i = 0; i < 2; i++) {
            View cv = camViews[i];
            if (cv.getVisibility() != View.VISIBLE || cv.getWidth() <= 0 || cv.getHeight() <= 0) continue;
            int[] pos = new int[2];
            cv.getLocationInWindow(pos);
            float l = (pos[0] - base[0]) / d;
            float t = (pos[1] - base[1]) / d;
            float w = cv.getWidth() / d;
            float hPx = cv.getHeight() / d;
            js.append("setRect(").append(i).append(',')
                    .append(Math.round(l)).append(',').append(Math.round(t)).append(',')
                    .append(Math.round(w)).append(',').append(Math.round(hPx)).append(',')
                    .append(radius).append(");");
        }
        if (js.length() > 0) webView.evaluateJavascript(js.toString(), null);
    }

    private static final java.util.regex.Pattern CANONICAL_VID =
            java.util.regex.Pattern.compile("rel=\"canonical\"[^>]+watch\\?v=([A-Za-z0-9_-]{11})");
    private static final java.util.regex.Pattern LIVE_VID =
            java.util.regex.Pattern.compile("\"videoId\":\"([A-Za-z0-9_-]{11})\"");

    /**
     * Resolve a channel's /live page to its current live videoId (YouTube's /embed/live_stream?channel=
     * endpoint often returns "Stream unavailable" even when /channel/<id>/live is live and embeddable).
     * Checks <link rel="canonical"> first (around byte ~2 KB, so it finishes in one chunk) and falls
     * back to scanning for "videoId".
     */
    private static String resolveChannelLiveId(String channelId, String ua) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL("https://www.youtube.com/channel/" + channelId + "/live").openConnection();
            conn.setInstanceFollowRedirects(true);
            conn.setConnectTimeout(6_000);
            conn.setReadTimeout(8_000);
            if (ua != null) conn.setRequestProperty("User-Agent", ua);
            if (conn.getResponseCode() != 200) return null;
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            try (InputStream in = conn.getInputStream()) {
                int n;
                while ((n = in.read(buf)) >= 0) {
                    bos.write(buf, 0, n);
                    if (bos.size() <= 32 * 1024) {
                        java.util.regex.Matcher cm = CANONICAL_VID.matcher(bos.toString("UTF-8"));
                        if (cm.find()) return cm.group(1);
                    }
                    if (bos.size() > 1536 * 1024) break;
                }
            }
            String body = bos.toString("UTF-8");
            java.util.regex.Matcher cm = CANONICAL_VID.matcher(body);
            if (cm.find()) return cm.group(1);
            java.util.regex.Matcher m = LIVE_VID.matcher(body);
            return m.find() ? m.group(1) : null;
        } catch (Exception e) {
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /**
     * Fetch a YouTube /embed/ HTML document and inject a tiny script at the top of <head> that
     * hides AV1/VP9 from MediaSource.isTypeSupported(), forcing YouTube to serve H.264 (AVC).
     * On MediaTek/MStar TVs, OMX.MS.AV1.Decoder only allows 1 concurrent hardware instance
     * (throwing OMX_ErrorInsufficientResources -2147479533 on a 2nd stream), whereas OMX.MS.AVC.Decoder
     * decodes multiple simultaneous streams cleanly.
     */
    private static WebResourceResponse fetchWithoutAv1(String urlStr, Map<String, String> reqHeaders, String ua) {
        if (urlStr.contains("/embed/live_stream")) {
            android.net.Uri u = android.net.Uri.parse(urlStr);
            String ch = u.getQueryParameter("channel");
            if (ch != null && !ch.isEmpty()) {
                String vid = resolveChannelLiveId(ch, ua);
                if (vid != null) {
                    String q = u.getEncodedQuery();
                    urlStr = "https://" + u.getHost() + "/embed/" + vid + (q != null ? "?" + q : "");
                }
            }
        }
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(urlStr).openConnection();
            conn.setInstanceFollowRedirects(true);
            conn.setConnectTimeout(8_000);
            conn.setReadTimeout(10_000);
            if (reqHeaders != null) {
                for (Map.Entry<String, String> e : reqHeaders.entrySet()) {
                    if (e.getKey() != null && e.getValue() != null
                            && !e.getKey().equalsIgnoreCase("Accept-Encoding")) {
                        conn.setRequestProperty(e.getKey(), e.getValue());
                    }
                }
            }
            if (ua != null) conn.setRequestProperty("User-Agent", ua);
            int code = conn.getResponseCode();
            if (code != 200) return null;
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            try (InputStream in = conn.getInputStream()) {
                int n;
                while ((n = in.read(buf)) >= 0) bos.write(buf, 0, n);
            }
            String html = bos.toString("UTF-8");
            String shim = "<style>"
                    + ".html5-video-player{background:#000!important}"
                    + ".ytp-chrome-top,.ytp-chrome-bottom,.ytp-gradient-top,.ytp-gradient-bottom,"
                    + ".ytp-pause-overlay,.ytp-cued-thumbnail-overlay,.ytp-cued-thumbnail-overlay-image,"
                    + ".ytp-large-play-button,.ytp-watermark,.ytp-bezel,.ytp-bezel-text-wrapper,"
                    + ".ytp-ce-element,.ytp-cards-teaser,.ytp-paid-content-overlay,.ytp-impression-link,"
                    + ".ytp-title,.ytp-title-channel,.ytp-show-cards-title,.ytp-player-content,"
                    + ".ytp-suggested-action,.ytp-spinner,.ytp-muting-overlay,.ytp-unmute{"
                    + "opacity:0!important;visibility:hidden!important;pointer-events:none!important}"
                    + "</style>"
                    + "<script>(function(){"
                    + "function b(t){return /av01|vp0?9|webm/i.test(String(t||''));}"
                    + "if(window.MediaSource&&MediaSource.isTypeSupported){"
                    + "var o=MediaSource.isTypeSupported.bind(MediaSource);"
                    + "MediaSource.isTypeSupported=function(t){return b(t)?false:o(t);};}"
                    + "var c=HTMLMediaElement.prototype.canPlayType;"
                    + "HTMLMediaElement.prototype.canPlayType=function(t){return b(t)?'':c.call(this,t);};"
                    + "if(navigator.mediaCapabilities&&navigator.mediaCapabilities.decodingInfo){"
                    + "var d=navigator.mediaCapabilities.decodingInfo.bind(navigator.mediaCapabilities);"
                    + "navigator.mediaCapabilities.decodingInfo=function(cfg){"
                    + "if(cfg&&cfg.video&&b(cfg.video.contentType))return Promise.resolve({supported:false,smooth:false,powerEfficient:false});"
                    + "return d(cfg);};}"
                    + "try{for(var k in localStorage){if(/resume|time|progress|position/i.test(k))localStorage.removeItem(k);}}catch(e){}"
                    + "var hm=null,liveChecked=false;"
                    + "function applyMute(){"
                    + "if(hm===null)return;"
                    + "var vs=document.querySelectorAll('video');"
                    + "for(var i=0;i<vs.length;i++){"
                    + "var v=vs[i];"
                    + "if(v.muted!==hm)v.muted=hm;"
                    + "if(!hm&&v.volume<0.99)v.volume=1;"
                    + "}}"
                    + "function clickLiveIfBehind(){"
                    + "if(liveChecked)return;liveChecked=true;"
                    + "try{var lb=document.querySelector('.ytp-live-badge');"
                    + "if(lb&&!lb.disabled&&!lb.getAttribute('disabled')){lb.click();}"
                    + "}catch(e){}}"
                    + "window.addEventListener('message',function(e){"
                    + "if(e&&e.data&&typeof e.data.homeMute==='boolean'){hm=e.data.homeMute;applyMute();}"
                    + "});"
                    + "document.addEventListener('playing',function(){applyMute();setTimeout(clickLiveIfBehind,600);},true);"
                    + "document.addEventListener('loadedmetadata',applyMute,true);"
                    + "document.addEventListener('volumechange',applyMute,true);"
                    + "})();</script>";
            int headIdx = html.indexOf("<head>");
            if (headIdx >= 0) {
                html = html.substring(0, headIdx + 6) + shim + html.substring(headIdx + 6);
            } else {
                html = shim + html;
            }
            byte[] out = html.getBytes(StandardCharsets.UTF_8);
            Map<String, String> respHeaders = new HashMap<>();
            respHeaders.put("Cache-Control", "no-store");
            return new WebResourceResponse("text/html", "utf-8", 200, "OK", respHeaders, new ByteArrayInputStream(out));
        } catch (Exception e) {
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }
}
