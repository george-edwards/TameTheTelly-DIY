// SPDX-License-Identifier: GPL-3.0-only
package au.internal.home;

import android.net.Uri;
import android.webkit.WebResourceResponse;

import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Self-contained resolver and minimal ad-free HLS player for EarthCam web pages.
 *
 * EarthCam pages mint short-lived signed `.m3u8` URLs inside `json_base = {...};` on each load.
 * Intercepting the EarthCam iframe URL in WebCamHost lets us fetch a fresh token on demand and
 * return a clean fullscreen-capable HTML5 player (using hls.js) that speaks the same `{homeMute}`
 * postMessage protocol as YouTube slots.
 */
final class EarthCamResolver {
    private static final Pattern JSON_BASE = Pattern.compile("json_base\\s*=\\s*(\\{.*?\\});\\s*\\n");

    private EarthCamResolver() { }

    static boolean isEarthCamUrl(String url) {
        if (url == null) return false;
        Uri u = Uri.parse(url);
        String host = u.getHost();
        return host != null && (host.equals("www.earthcam.com") || host.equals("earthcam.com"))
                && !url.endsWith(".m3u8") && !url.endsWith(".ts");
    }

    /** Probe whether an EarthCam page currently yields a live HLS stream. */
    static Boolean probeOnline(String pageUrl, String ua) {
        try {
            String hls = resolveHlsUrl(pageUrl, ua);
            return hls != null ? Boolean.TRUE : Boolean.FALSE;
        } catch (Exception e) {
            return null;
        }
    }

    /** Fetch an EarthCam page and extract the fresh signed `.m3u8` URL for its `cam` query param. */
    static String resolveHlsUrl(String pageUrl, String ua) throws Exception {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(pageUrl).openConnection();
            conn.setInstanceFollowRedirects(true);
            conn.setConnectTimeout(7_000);
            conn.setReadTimeout(9_000);
            if (ua != null) conn.setRequestProperty("User-Agent", ua);
            conn.setRequestProperty("Accept-Language", "en-US,en;q=0.9");
            if (conn.getResponseCode() != 200) return null;

            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            try (InputStream in = conn.getInputStream()) {
                int n;
                while ((n = in.read(buf)) >= 0) {
                    bos.write(buf, 0, n);
                    if (bos.size() > 1536 * 1024) break;
                }
            }
            String html = bos.toString("UTF-8");
            Matcher m = JSON_BASE.matcher(html);
            if (!m.find()) return null;

            JSONObject root = new JSONObject(m.group(1));
            JSONObject cams = root.optJSONObject("cam");
            if (cams == null || cams.length() == 0) return null;

            String wanted = Uri.parse(pageUrl).getQueryParameter("cam");
            JSONObject cam = (wanted != null && !wanted.isEmpty()) ? cams.optJSONObject(wanted) : null;
            if (cam == null) {
                Iterator<String> it = cams.keys();
                if (it.hasNext()) cam = cams.optJSONObject(it.next());
            }
            if (cam == null) return null;
            if ("yes".equalsIgnoreCase(cam.optString("offline", ""))) return null;

            String domain = cam.optString("html5_streamingdomain", "").trim();
            String path = cam.optString("html5_streampath", "").trim();
            if (domain.isEmpty() || path.isEmpty() || !path.contains(".m3u8")) return null;
            return domain + path;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** Intercept an EarthCam iframe request and return a minimal ad-free HLS player HTML page. */
    static WebResourceResponse buildPlayerResponse(String pageUrl, String ua) {
        try {
            String hls = resolveHlsUrl(pageUrl, ua);
            if (hls == null) return null;
            String jsHls = hls.replace("\\", "\\\\").replace("'", "\\'");
            String html = "<!doctype html><html><head>"
                    + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                    + "<style>html,body,video{margin:0;padding:0;width:100%;height:100%;background:#000;overflow:hidden;object-fit:cover}</style>"
                    + "<script src=\"https://cdn.jsdelivr.net/npm/hls.js@1/dist/hls.min.js\"></script>"
                    + "</head><body><video id=\"v\" autoplay playsinline muted></video><script>"
                    + "(function(){"
                    + "var v=document.getElementById('v'),u='" + jsHls + "',hm=true;"
                    + "function applyMute(){v.muted=hm;if(!hm)v.volume=1;}"
                    + "window.addEventListener('message',function(e){"
                    + "if(e&&e.data&&typeof e.data.homeMute==='boolean'){hm=e.data.homeMute;applyMute();}"
                    + "});"
                    + "v.addEventListener('playing',applyMute);"
                    + "if(window.Hls&&Hls.isSupported()){"
                    + "var h=new Hls({lowLatencyMode:true,liveSyncDurationCount:2});"
                    + "h.loadSource(u);h.attachMedia(v);"
                    + "h.on(Hls.Events.MANIFEST_PARSED,function(){applyMute();v.play().catch(function(){});});"
                    + "h.on(Hls.Events.ERROR,function(ev,d){if(d&&d.fatal){setTimeout(function(){location.reload();},4000);}});"
                    + "}else if(v.canPlayType('application/vnd.apple.mpegurl')){"
                    + "v.src=u;v.play().catch(function(){});"
                    + "}"
                    + "})();</script></body></html>";
            Map<String, String> headers = new HashMap<>();
            headers.put("Cache-Control", "no-store");
            return new WebResourceResponse("text/html", "utf-8", 200, "OK", headers,
                    new ByteArrayInputStream(html.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            return null;
        }
    }
}
