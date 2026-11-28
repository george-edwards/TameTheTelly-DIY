// SPDX-License-Identifier: GPL-3.0-only
package au.internal.home;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Single shared WebView hosting active World Cam iframes plus up to two hidden standby prefetch
 * iframes behind Home's camera cards.
 *
 * Using one WebView (one Chromium WebContents) avoids Android WebView's per-WebView MediaSession
 * focus fight where two separate WebViews pause each other in a loop. Standby panes stay rendered
 * at opacity 0 behind the active card so Chromium keeps decoding H.264 frames and promotion on
 * stream switch is instantaneous.
 */
final class WebCamHost {
    private static final String ORIGIN = "https://au.internal.home";
    private static final int MAX_SLOTS = 2;
    private static final int MAX_PANES = 4; // up to 2 active slots + up to 2 standby prefetches
    private static final long WARMUP_MS = 1_400L;

    private final Context ctx;
    private final ViewGroup backdrop;
    private final View[] camViews;
    private final Runnable[] onLive;
    private final Handler h = new Handler(Looper.getMainLooper());

    private final String[] slotRawUrls = new String[MAX_SLOTS];
    private final boolean[] enabled = new boolean[MAX_SLOTS];
    private final boolean[] live = new boolean[MAX_SLOTS];
    private final boolean[] wantMuted = {true, true};
    private final int[] slotPane = {-1, -1};

    private final String[] paneRawUrl = new String[MAX_PANES];
    private final long[] paneLoadedAt = new long[MAX_PANES];
    private final boolean[] paneReady = new boolean[MAX_PANES];
    private final int[] paneGen = new int[MAX_PANES];

    private final List<String> desiredPrefetch = new ArrayList<>();
    private final Runnable syncLayoutRun = this::applyLayoutNow;
    private String lastLayoutJs;

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
            if (l != ol || t != ot || r != or_ || b != ob) scheduleSyncLayout();
        };
        backdrop.addOnLayoutChangeListener(relayout);
        for (View cv : camViews) cv.addOnLayoutChangeListener(relayout);
    }

    boolean isLive(int slot) { return slot >= 0 && slot < MAX_SLOTS && live[slot]; }

    void setFullscreen(boolean full) {
        fullscreen = full;
        if (!full) {
            setMuted(0, true);
            setMuted(1, true);
        }
        syncLayout();
    }

    /** Mute or unmute a YouTube/EarthCam slot live via postMessage JS API (no reload). */
    void setMuted(int slot, boolean muted) {
        if (slot < 0 || slot >= MAX_SLOTS) return;
        wantMuted[slot] = muted;
        int pane = slotPane[slot];
        if (webView == null || !pageReady || pane < 0) return;
        webView.evaluateJavascript("ytMute(" + pane + "," + muted + ");", null);
    }

    void setSlot(int slot, boolean active, String embedUrl) {
        if (slot < 0 || slot >= MAX_SLOTS) return;
        enabled[slot] = active && embedUrl != null && !embedUrl.isEmpty();
        slotRawUrls[slot] = enabled[slot] ? embedUrl : null;
        if (!enabled[slot]) live[slot] = false;
        if (running) sync();
    }

    /** Set up to 2 raw embed URLs to keep decoding silently in standby iframes for instant switching. */
    void setPrefetchUrls(List<String> rawUrls) {
        desiredPrefetch.clear();
        if (rawUrls != null) {
            for (String u : rawUrls) {
                if (u == null || u.isEmpty()) continue;
                if (desiredPrefetch.contains(u)) continue;
                desiredPrefetch.add(u);
                if (desiredPrefetch.size() >= 2) break;
            }
        }
        if (running && webView != null && pageReady) {
            reconcilePrefetch();
        }
    }

    void start() {
        running = true;
        sync();
    }

    void stop() {
        running = false;
        tearDownWebView();
    }

    private void resetPanes() {
        for (int i = 0; i < MAX_SLOTS; i++) {
            live[i] = false;
            slotPane[i] = -1;
        }
        for (int p = 0; p < MAX_PANES; p++) {
            paneRawUrl[p] = null;
            paneLoadedAt[p] = 0L;
            paneReady[p] = false;
            paneGen[p]++;
        }
        lastLayoutJs = null;
    }

    private void tearDownWebView() {
        pageReady = false;
        resetPanes();
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

    private String decorate(int pane, String embedUrl, boolean muted) {
        String u = embedUrl;
        // Alternate youtube.com / youtube-nocookie.com across panes so adjacent iframes have
        // separate origins (we also disable BroadcastChannel in the injected shim).
        if ((pane % 2) == 1 && u.startsWith("https://www.youtube.com/embed/")) {
            u = "https://www.youtube-nocookie.com/embed/" + u.substring("https://www.youtube.com/embed/".length());
        }
        if (!u.contains("youtube.com/") && !u.contains("youtube-nocookie.com/")) {
            return u;
        }
        if (!muted) {
            u = u.replace("mute=1", "mute=0");
        }
        String sep = u.contains("?") ? "&" : "?";
        return u + sep + "enablejsapi=1&origin=" + ORIGIN;
    }

    private void sync() {
        boolean any = running && (enabled[0] || enabled[1]);
        if (!any) {
            tearDownWebView();
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
        resetPanes();

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

        StringBuilder divs = new StringBuilder();
        for (int p = 0; p < MAX_PANES; p++) {
            divs.append("<div id=\"s").append(p).append("\" class=\"slot\">")
                    .append("<iframe id=\"f").append(p)
                    .append("\" referrerpolicy=\"strict-origin-when-cross-origin\" allow=\"autoplay; encrypted-media\" allowfullscreen></iframe>")
                    .append("</div>");
        }

        String html = "<!doctype html><html><head>"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no\">"
                + "<meta name=\"referrer\" content=\"strict-origin-when-cross-origin\">"
                + "<style>"
                + "html,body{margin:0;padding:0;width:100%;height:100%;background:transparent;overflow:hidden}"
                + ".slot{position:fixed;overflow:hidden;background:#000;display:none;pointer-events:none}"
                + "iframe{width:100%;height:100%;border:0;pointer-events:none;display:block}"
                + "</style></head><body>"
                + divs
                + "<script>"
                + "function loadPane(i,url,vis){"
                + "  var d=document.getElementById('s'+i),f=document.getElementById('f'+i);"
                + "  if(!url){d.style.display='none';d.style.opacity='0';d.style.zIndex='0';f.src='about:blank';return;}"
                + "  d.style.display='block';d.style.opacity=vis?'1':'0';d.style.zIndex=vis?'2':'0';"
                + "  f.src=url;"
                + "}"
                + "function showPane(i,vis){"
                + "  var d=document.getElementById('s'+i);"
                + "  if(!d)return;"
                + "  d.style.display='block';d.style.opacity=vis?'1':'0';d.style.zIndex=vis?'2':'0';"
                + "}"
                + "function setRect(i,l,t,w,h,r){"
                + "  var d=document.getElementById('s'+i);"
                + "  if(!d)return;"
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
                + "function ytSnapLive(i){"
                + "  var f=document.getElementById('f'+i);"
                + "  if(f&&f.contentWindow){f.contentWindow.postMessage({homeSnapLive:true},'*');}"
                + "}"
                + "</script></body></html>";
        wv.loadDataWithBaseURL(ORIGIN + "/", html, "text/html", "utf-8", null);
    }

    private boolean isPaneActive(int pane) {
        return pane >= 0 && (slotPane[0] == pane || slotPane[1] == pane);
    }

    private int findStandbyPane(String rawUrl) {
        if (rawUrl == null) return -1;
        for (int p = 0; p < MAX_PANES; p++) {
            if (!isPaneActive(p) && rawUrl.equals(paneRawUrl[p])) return p;
        }
        return -1;
    }

    private int allocateFreePane() {
        for (int p = 0; p < MAX_PANES; p++) {
            if (!isPaneActive(p) && paneRawUrl[p] == null) return p;
        }
        // Fallback: evict a standby pane if all panes are occupied.
        for (int p = 0; p < MAX_PANES; p++) {
            if (!isPaneActive(p)) return p;
        }
        return -1;
    }

    private void clearPane(int pane) {
        if (pane < 0 || pane >= MAX_PANES) return;
        paneRawUrl[pane] = null;
        paneLoadedAt[pane] = 0L;
        paneReady[pane] = false;
        paneGen[pane]++;
        if (webView != null && pageReady) {
            webView.evaluateJavascript("loadPane(" + pane + ",null,false);", null);
        }
    }

    private void applySlots() {
        if (webView == null || !pageReady) return;
        for (int i = 0; i < MAX_SLOTS; i++) {
            String want = (enabled[i] && camViews[i].getVisibility() == View.VISIBLE) ? slotRawUrls[i] : null;
            int curPane = slotPane[i];
            String curUrl = curPane >= 0 ? paneRawUrl[curPane] : null;

            if (want == null) {
                if (curPane >= 0) {
                    slotPane[i] = -1;
                    clearPane(curPane);
                }
                live[i] = false;
                TextView st = camViews[i].findViewById(R.id.state);
                st.setVisibility(View.GONE);
                continue;
            }
            if (want.equals(curUrl)) continue;

            TextView st = camViews[i].findViewById(R.id.state);
            int standby = findStandbyPane(want);
            if (standby >= 0) {
                // Instant promotion from standby prefetch pane!
                slotPane[i] = standby;
                if (curPane >= 0 && curPane != standby) {
                    clearPane(curPane);
                }
                webView.evaluateJavascript(
                        "showPane(" + standby + ",true);ytMute(" + standby + "," + wantMuted[i] + ");ytSnapLive(" + standby + ");", null);
                long elapsed = SystemClock.uptimeMillis() - paneLoadedAt[standby];
                if (paneReady[standby] || elapsed >= WARMUP_MS) {
                    paneReady[standby] = true;
                    live[i] = true;
                    st.setVisibility(View.GONE);
                    if (onLive[i] != null) onLive[i].run();
                } else {
                    live[i] = false;
                    st.setVisibility(View.VISIBLE);
                    st.setText("Connecting…");
                    scheduleSlotReady(i, standby, paneGen[standby], Math.max(100L, WARMUP_MS - elapsed));
                }
            } else {
                // Cold load into existing slot pane or a newly allocated pane.
                int target = curPane >= 0 ? curPane : allocateFreePane();
                if (target < 0) continue;
                slotPane[i] = target;
                paneRawUrl[target] = want;
                paneLoadedAt[target] = SystemClock.uptimeMillis();
                paneReady[target] = false;
                int gen = ++paneGen[target];
                String dec = decorate(target, want, wantMuted[i]);
                webView.evaluateJavascript("loadPane(" + target + "," + jsQuote(dec) + ",true);", null);
                live[i] = false;
                st.setVisibility(View.VISIBLE);
                st.setText("Connecting…");
                scheduleSlotReady(i, target, gen, WARMUP_MS);
            }
        }
        reconcilePrefetch();
        syncLayout();
    }

    private void scheduleSlotReady(int slot, int pane, int gen, long delayMs) {
        h.postDelayed(() -> {
            if (!running || slotPane[slot] != pane || paneGen[pane] != gen) return;
            paneReady[pane] = true;
            live[slot] = true;
            TextView st = camViews[slot].findViewById(R.id.state);
            st.setVisibility(View.GONE);
            if (webView != null && pageReady) {
                webView.evaluateJavascript("ytMute(" + pane + "," + wantMuted[slot] + ");", null);
            }
            if (onLive[slot] != null) onLive[slot].run();
        }, delayMs);
    }

    private void reconcilePrefetch() {
        if (webView == null || !pageReady) return;
        // Build list of wanted prefetch URLs that aren't already active in a visible slot.
        List<String> needed = new ArrayList<>();
        for (String u : desiredPrefetch) {
            boolean alreadyActive = false;
            for (int i = 0; i < MAX_SLOTS; i++) {
                if (slotPane[i] >= 0 && u.equals(paneRawUrl[slotPane[i]])) {
                    alreadyActive = true;
                    break;
                }
            }
            if (!alreadyActive && !needed.contains(u)) needed.add(u);
        }

        // Release any standby pane whose URL is no longer in `needed`.
        for (int p = 0; p < MAX_PANES; p++) {
            if (isPaneActive(p)) continue;
            if (paneRawUrl[p] != null && !needed.contains(paneRawUrl[p])) {
                clearPane(p);
            }
        }

        // Load any `needed` URL not already warming/ready in a standby pane.
        boolean startedAny = false;
        for (String u : needed) {
            if (findStandbyPane(u) >= 0) continue;
            int p = allocateFreePane();
            if (p < 0) break;
            paneRawUrl[p] = u;
            paneLoadedAt[p] = SystemClock.uptimeMillis();
            paneReady[p] = false;
            final int pane = p;
            final int gen = ++paneGen[p];
            String dec = decorate(p, u, true); // standby panes are ALWAYS muted
            webView.evaluateJavascript("loadPane(" + p + "," + jsQuote(dec) + ",false);", null);
            h.postDelayed(() -> {
                if (!running || paneGen[pane] != gen) return;
                paneReady[pane] = true;
                if (!isPaneActive(pane) && webView != null && pageReady) {
                    webView.evaluateJavascript("ytMute(" + pane + ",true);", null);
                }
            }, WARMUP_MS);
            startedAny = true;
        }
        if (startedAny) syncLayout();
    }

    private void scheduleSyncLayout() {
        h.removeCallbacks(syncLayoutRun);
        h.post(syncLayoutRun);
    }

    private void syncLayout() {
        h.removeCallbacks(syncLayoutRun);
        applyLayoutNow();
    }

    private void applyLayoutNow() {
        if (webView == null || !pageReady) return;
        float d = ctx.getResources().getDisplayMetrics().density;
        if (d <= 0f) d = 1f;
        int[] base = new int[2];
        backdrop.getLocationInWindow(base);
        int radius = fullscreen ? 0 : 10;
        StringBuilder js = new StringBuilder();
        int[] primaryRect = null;

        for (int i = 0; i < MAX_SLOTS; i++) {
            View cv = camViews[i];
            if (cv.getVisibility() != View.VISIBLE || cv.getWidth() <= 0 || cv.getHeight() <= 0) continue;
            int[] pos = new int[2];
            cv.getLocationInWindow(pos);
            int l = Math.round((pos[0] - base[0]) / d);
            int t = Math.round((pos[1] - base[1]) / d);
            int w = Math.round(cv.getWidth() / d);
            int hPx = Math.round(cv.getHeight() / d);
            if (primaryRect == null) primaryRect = new int[]{l, t, w, hPx};
            int pane = slotPane[i];
            if (pane >= 0) {
                js.append("setRect(").append(pane).append(',')
                        .append(l).append(',').append(t).append(',')
                        .append(w).append(',').append(hPx).append(',')
                        .append(radius).append(");");
            }
        }
        // Position standby prefetch panes at the primary card's rect (behind it at opacity 0) so
        // Chromium decodes at the real 16:9 viewport size rather than throttling a 0x0 frame.
        if (primaryRect != null) {
            for (int p = 0; p < MAX_PANES; p++) {
                if (isPaneActive(p) || paneRawUrl[p] == null) continue;
                js.append("setRect(").append(p).append(',')
                        .append(primaryRect[0]).append(',').append(primaryRect[1]).append(',')
                        .append(primaryRect[2]).append(',').append(primaryRect[3]).append(',')
                        .append(radius).append(");");
            }
        }
        if (js.length() > 0) {
            String cmd = js.toString();
            if (!cmd.equals(lastLayoutJs)) {
                lastLayoutJs = cmd;
                webView.evaluateJavascript(cmd, null);
            }
        }
    }

    private static String jsQuote(String s) {
        return "'" + s.replace("\\", "\\\\").replace("'", "\\'") + "'";
    }

    private static boolean isEmbedOffline(String html) {
        return html == null
                || html.contains("\"LIVE_STREAM_OFFLINE\"")
                || html.contains("\"status\":\"UNPLAYABLE\"")
                || html.contains("\"status\":\"ERROR\"");
    }

    /**
     * Fetch a YouTube /embed/ HTML document and inject a tiny script at the top of <head> that
     * hides AV1/VP9 from MediaSource.isTypeSupported(), forcing YouTube to serve H.264 (AVC).
     * On MediaTek/MStar TVs, OMX.MS.AV1.Decoder only allows 1 concurrent hardware instance
     * (throwing OMX_ErrorInsufficientResources -2147479533 on a 2nd stream), whereas OMX.MS.AVC.Decoder
     * decodes multiple simultaneous streams cleanly.
     */
    private static WebResourceResponse fetchWithoutAv1(String urlStr, Map<String, String> reqHeaders, String ua) {
        String channelId = null;
        boolean usedCachedVid = false;
        android.net.Uri origUri = null;
        if (urlStr.contains("/embed/live_stream")) {
            origUri = android.net.Uri.parse(urlStr);
            channelId = origUri.getQueryParameter("channel");
            if (channelId != null && !channelId.isEmpty()) {
                String vid = WorldCams.cachedChannelVideoId(channelId);
                if (vid != null) {
                    usedCachedVid = true;
                } else {
                    vid = WorldCams.resolveChannelLiveId(channelId, ua);
                    if (vid != null) WorldCams.cacheChannelVideoId(channelId, vid);
                }
                if (vid != null) {
                    String q = origUri.getEncodedQuery();
                    urlStr = "https://" + origUri.getHost() + "/embed/" + vid + (q != null ? "?" + q : "");
                }
            }
        }
        String html = downloadEmbedHtml(urlStr, reqHeaders, ua);
        if (usedCachedVid && isEmbedOffline(html) && channelId != null && origUri != null) {
            // Cached videoId may have rotated; invalidate and resolve once more.
            WorldCams.invalidateChannelVideoId(channelId);
            String freshVid = WorldCams.resolveChannelLiveId(channelId, ua);
            if (freshVid != null) {
                WorldCams.cacheChannelVideoId(channelId, freshVid);
                String q = origUri.getEncodedQuery();
                String retry = downloadEmbedHtml(
                        "https://" + origUri.getHost() + "/embed/" + freshVid + (q != null ? "?" + q : ""),
                        reqHeaders, ua);
                if (retry != null) html = retry;
            }
        }
        if (html == null) return null;

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
                + "try{window.BroadcastChannel=undefined;}catch(e){}"
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
                + "function clearResume(st){try{if(!st)return;for(var i=st.length-1;i>=0;i--){var k=st.key(i);if(k&&/resume|time|progress|position/i.test(k))st.removeItem(k);}}catch(e){}}"
                + "clearResume(window.localStorage);clearResume(window.sessionStorage);"
                + "var hm=null,liveTries=0;"
                + "function applyMute(){"
                + "if(hm===null)return;"
                + "var vs=document.querySelectorAll('video');"
                + "for(var i=0;i<vs.length;i++){"
                + "var v=vs[i];"
                + "if(v.muted!==hm)v.muted=hm;"
                + "if(!hm&&v.volume<0.99)v.volume=1;"
                + "}}"
                + "function snapToLive(force){"
                + "if(!force&&liveTries>=2)return;"
                + "if(!force)liveTries++;"
                + "try{var lb=document.querySelector('.ytp-live-badge');"
                + "if(lb&&!lb.disabled&&!lb.getAttribute('disabled')){lb.click();return;}"
                + "}catch(e){}"
                + "try{var p=document.getElementById('movie_player');"
                + "if(p&&p.getDuration&&p.getCurrentTime&&p.seekTo){"
                + "var dur=p.getDuration(),cur=p.getCurrentTime();"
                + "if(dur>60&&dur-cur>25){p.seekTo(dur,true);return;}"
                + "}}catch(e){}"
                + "try{var vs=document.querySelectorAll('video');"
                + "for(var i=0;i<vs.length;i++){"
                + "var v=vs[i];"
                + "if(v.paused&&force){try{v.play();}catch(e){}}"
                + "if(v.seekable&&v.seekable.length>0){"
                + "var end=v.seekable.end(v.seekable.length-1);"
                + "if(end-v.currentTime>25){v.currentTime=Math.max(0,end-2);}"
                + "}}}catch(e){}}"
                + "window.addEventListener('message',function(e){"
                + "if(!e||!e.data)return;"
                + "if(typeof e.data.homeMute==='boolean'){hm=e.data.homeMute;applyMute();}"
                + "if(e.data.homeSnapLive){snapToLive(true);}"
                + "});"
                + "document.addEventListener('playing',function(){applyMute();if(liveTries<2){setTimeout(function(){snapToLive(false);},600);setTimeout(function(){snapToLive(false);},2500);}},true);"
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
    }

    private static String downloadEmbedHtml(String urlStr, Map<String, String> reqHeaders, String ua) {
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
            if (conn.getResponseCode() != 200) return null;
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            try (InputStream in = conn.getInputStream()) {
                int n;
                while ((n = in.read(buf)) >= 0) bos.write(buf, 0, n);
            }
            return bos.toString("UTF-8");
        } catch (Exception e) {
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }
}
