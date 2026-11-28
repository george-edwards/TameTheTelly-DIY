// SPDX-License-Identifier: GPL-3.0-only
package au.internal.home;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Dynamic catalog of public live streams ("World Cams") for households without local RTSP cameras.
 *
 * Loads a cached remote streams.json (refreshed asynchronously every 5 minutes from GitHub), falling back
 * to the copy bundled in assets/streams.json. Tracks per-slot selection and user-hearted bookmarks
 * in SharedPreferences("world_cams").
 */
final class WorldCams {
    private static final String TAG = "HomeWorldCams";
    private static final String PREFS = "world_cams";
    private static final String K_JSON = "catalog_json";
    private static final String K_FETCHED = "catalog_fetched_at";
    private static final String K_ETAG = "catalog_etag";
    private static final String K_HEARTS = "hearted";
    private static final String K_SLOT = "slot_";
    private static final String K_SLOT_WORLD = "slot_world_";
    private static final String K_SLOT_PINNED = "slot_pinned_";
    private static final String K_AUTO_ON = "auto_advance_on";
    private static final String K_AUTO_MIN = "auto_advance_min";
    private static final String K_RANDOM_RETURN = "random_on_return";
    private static final String K_PREFETCH = "prefetch_count";
    private static final long REFRESH_MS = 5 * 60_000L;
    private static final long LIVE_ID_TTL_MS = 15 * 60_000L;
    private static final long PROBE_GAP_MS = 250L;
    private static final long PROBE_CYCLE_MS = 10 * 60_000L;
    private static final int PROBE_WORKERS = 3;
    private static final Pattern YT_ID = Pattern.compile("(?:v=|youtu\\.be/|embed/|live/)([A-Za-z0-9_-]{11})");
    private static final Pattern CANONICAL_HREF = Pattern.compile("rel=\"canonical\"[^>]+href=\"([^\"]+)\"");
    private static final String PROBE_UA = "Mozilla/5.0 (Linux; Android 11; TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

    private static final class CachedVideoId {
        final String videoId;
        final long cachedAtMs;
        CachedVideoId(String videoId, long cachedAtMs) {
            this.videoId = videoId;
            this.cachedAtMs = cachedAtMs;
        }
    }

    private static final ConcurrentHashMap<String, Boolean> ONLINE = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, CachedVideoId> LIVE_VIDEO_IDS = new ConcurrentHashMap<>();
    private static final Object CHECKER_LOCK = new Object();
    private static final AtomicInteger CHECKER_CURSOR = new AtomicInteger(0);
    private static volatile int checkerGen;
    private static volatile boolean checkerRunning;
    private static volatile List<Stream> checkerStreams = Collections.emptyList();
    private static volatile Runnable statusListener;
    private static final List<Thread> CHECKER_THREADS = new ArrayList<>();
    private static volatile long checkerCycleDoneAt;

    static final class Stream {
        final String key;
        final String name;
        final String category;
        final String type;      // youtube_channel, youtube_multi, hls, iframe
        final String url;
        final String channelId;
        final String match;
        final double lat;
        final double lon;
        final boolean hasCoords;
        final float labelOffsetY;
        final float labelOffsetX;

        Stream(String name, String category, String type, String url, String channelId, String match) {
            this(name, category, type, url, channelId, match, Double.NaN, Double.NaN, 0f, 0f);
        }

        Stream(String name, String category, String type, String url, String channelId, String match,
               double lat, double lon, float labelOffsetY, float labelOffsetX) {
            this.name = name;
            this.category = category;
            this.type = type;
            this.url = url;
            this.channelId = channelId;
            this.match = match;
            this.lat = lat;
            this.lon = lon;
            this.hasCoords = !Double.isNaN(lat) && !Double.isNaN(lon);
            this.labelOffsetY = Math.max(0f, labelOffsetY);
            this.labelOffsetX = Math.max(0f, labelOffsetX);
            this.key = "youtube_channel".equals(type) && !channelId.isEmpty() ? "ch:" + channelId : url;
        }

        /** Name prefixed with its country/origin flag emoji, e.g. "🇦🇺 Australia: Port of Newcastle". */
        String displayName() {
            String f = flagFor(name);
            return f.isEmpty() ? name : f + " " + name;
        }

        /** Embeddable HTTPS URL for WebView playback (muted, autoplay, no controls). */
        String embedUrl() {
            if ("youtube_channel".equals(type) && !channelId.isEmpty()) {
                return "https://www.youtube.com/embed/live_stream?channel=" + channelId
                        + "&autoplay=1&mute=1&controls=0&playsinline=1&rel=0&modestbranding=1&iv_load_policy=3";
            }
            if ("youtube_multi".equals(type) || url.contains("youtube.com") || url.contains("youtu.be")) {
                String vid = extractVideoId(url);
                if (vid != null) {
                    return "https://www.youtube.com/embed/" + vid
                            + "?autoplay=1&mute=1&controls=0&playsinline=1&rel=0&modestbranding=1&iv_load_policy=3";
                }
            }
            return url;
        }
    }

    private final Context ctx;
    private final SharedPreferences p;
    private final Random rng = new Random();
    private volatile boolean refreshing;

    WorldCams(Context c) {
        ctx = c.getApplicationContext();
        p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static String flagFor(String name) {
        if (name == null || name.isEmpty()) return "";
        String prefix = name;
        int colon = name.indexOf(':');
        int dash = name.indexOf('—');
        int cut = -1;
        if (colon > 0 && dash > 0) cut = Math.min(colon, dash);
        else if (colon > 0) cut = colon;
        else if (dash > 0) cut = dash;
        if (cut > 0) prefix = name.substring(0, cut).trim();
        switch (prefix) {
            case "Australia": return "🇦🇺";
            case "New Zealand": return "🇳🇿";
            case "USA": return "🇺🇸";
            case "UK": return "🇬🇧";
            case "Ireland": return "🇮🇪";
            case "Canada": return "🇨🇦";
            case "Brazil": return "🇧🇷";
            case "Japan": return "🇯🇵";
            case "Thailand": return "🇹🇭";
            case "Philippines": return "🇵🇭";
            case "Taiwan": return "🇹🇼";
            case "India": return "🇮🇳";
            case "Israel": return "🇮🇱";
            case "Palestine": return "🇵🇸";
            case "Iraq": return "🇮🇶";
            case "Saudi Arabia": return "🇸🇦";
            case "Italy": return "🇮🇹";
            case "France": return "🇫🇷";
            case "Belgium": return "🇧🇪";
            case "Netherlands": return "🇳🇱";
            case "Germany": return "🇩🇪";
            case "Slovakia": return "🇸🇰";
            case "Norway": return "🇳🇴";
            case "Russia": return "🇷🇺";
            case "Namibia": return "🇳🇦";
            case "South Africa": return "🇿🇦";
            case "British Virgin Islands": return "🇻🇬";
            case "Space": return "🛰️";
            case "Ocean": return "🌊";
            default: return "";
        }
    }

    static String extractVideoId(String url) {
        if (url == null) return null;
        Matcher m = YT_ID.matcher(url);
        return m.find() ? m.group(1) : null;
    }

    /** Parse a streams.json string into Stream entries. Returns an empty list on invalid JSON. */
    static List<Stream> parse(String json) {
        if (json == null || json.trim().isEmpty()) return Collections.emptyList();
        try {
            JSONObject root = new JSONObject(json);
            JSONArray arr = root.optJSONArray("streams");
            if (arr == null) return Collections.emptyList();
            List<Stream> out = new ArrayList<>(arr.length());
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                String name = o.optString("name", "").trim();
                String url = o.optString("url", "").trim();
                if (name.isEmpty() || url.isEmpty()) continue;
                double lat = o.has("lat") && !o.isNull("lat") ? o.optDouble("lat", Double.NaN) : Double.NaN;
                double lon = o.has("lon") && !o.isNull("lon") ? o.optDouble("lon", Double.NaN) : Double.NaN;
                float labelOffsetY = (float) (o.has("labelOffsetY")
                        ? o.optDouble("labelOffsetY", 0.0)
                        : o.optDouble("labelOffsetLines", 0.0));
                float labelOffsetX = (float) o.optDouble("labelOffsetX", 0.0);
                out.add(new Stream(
                        name,
                        o.optString("category", "").trim(),
                        o.optString("type", "youtube_channel").trim(),
                        url,
                        o.optString("channelId", "").trim(),
                        o.optString("match", "").trim(),
                        lat,
                        lon,
                        labelOffsetY,
                        labelOffsetX
                ));
            }
            return out;
        } catch (JSONException e) {
            return Collections.emptyList();
        }
    }

    /** Load cached remote catalog if valid, else fall back to bundled assets/streams.json. */
    List<Stream> load() {
        String cached = p.getString(K_JSON, null);
        List<Stream> list = parse(cached);
        if (!list.isEmpty()) return list;
        return parse(readAsset("streams.json"));
    }

    /** Refresh remote streams.json at most once every 5 minutes in a background thread (ETag-aware). */
    void refreshIfDue(String remoteUrl, Runnable onUpdated) {
        if (remoteUrl == null || remoteUrl.trim().isEmpty() || refreshing) return;
        long now = System.currentTimeMillis();
        if (now - p.getLong(K_FETCHED, 0L) < REFRESH_MS) return;
        refreshing = true;
        Handler main = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) new URL(remoteUrl.trim()).openConnection();
                conn.setConnectTimeout(6_000);
                conn.setReadTimeout(8_000);
                conn.setRequestProperty("User-Agent", "HomeTV/2.20");
                String etag = p.getString(K_ETAG, null);
                if (etag != null && !etag.isEmpty()) conn.setRequestProperty("If-None-Match", etag);
                int code = conn.getResponseCode();
                if (code == 304) {
                    p.edit().putLong(K_FETCHED, System.currentTimeMillis()).apply();
                    return;
                }
                if (code != 200) return;
                String body = readStream(conn.getInputStream());
                List<Stream> parsed = parse(body);
                if (parsed.isEmpty()) return;
                SharedPreferences.Editor ed = p.edit()
                        .putString(K_JSON, body)
                        .putLong(K_FETCHED, System.currentTimeMillis());
                String newEtag = conn.getHeaderField("ETag");
                if (newEtag != null && !newEtag.isEmpty()) ed.putString(K_ETAG, newEtag);
                ed.apply();
                if (onUpdated != null) main.post(onUpdated);
            } catch (IOException | RuntimeException e) {
                Log.w(TAG, "catalog refresh failed: " + e.getMessage());
            } finally {
                refreshing = false;
                if (conn != null) conn.disconnect();
            }
        }, "world-cams-refresh").start();
    }

    // ---------- background online checker ----------

    /** Online state for a stream: TRUE = online, FALSE = offline, null = not yet checked. */
    static Boolean onlineStatus(Stream s) {
        return s == null ? null : ONLINE.get(s.key);
    }

    static boolean isOnline(Stream s) {
        return Boolean.TRUE.equals(onlineStatus(s));
    }

    static boolean isKnownOffline(Stream s) {
        return Boolean.FALSE.equals(onlineStatus(s));
    }

    static int onlineCount(List<Stream> list) {
        int n = 0;
        for (Stream s : list) if (isOnline(s)) n++;
        return n;
    }

    static void setOnlineForTest(Stream s, Boolean state) {
        if (s == null) return;
        if (state == null) ONLINE.remove(s.key);
        else ONLINE.put(s.key, state);
    }

    static void clearOnlineForTest() {
        ONLINE.clear();
        LIVE_VIDEO_IDS.clear();
    }

    /** Cached live YouTube videoId for a channelId (populated by background probes and player loads). */
    static String cachedChannelVideoId(String channelId) {
        return cachedChannelVideoId(channelId, System.currentTimeMillis());
    }

    static String cachedChannelVideoId(String channelId, long nowMs) {
        if (channelId == null || channelId.isEmpty()) return null;
        CachedVideoId entry = LIVE_VIDEO_IDS.get(channelId);
        if (entry == null) return null;
        if (nowMs - entry.cachedAtMs > LIVE_ID_TTL_MS) {
            LIVE_VIDEO_IDS.remove(channelId, entry);
            return null;
        }
        return entry.videoId;
    }

    static void cacheChannelVideoId(String channelId, String videoId) {
        cacheChannelVideoId(channelId, videoId, System.currentTimeMillis());
    }

    static void cacheChannelVideoId(String channelId, String videoId, long nowMs) {
        if (channelId == null || channelId.isEmpty() || videoId == null || videoId.isEmpty()) return;
        LIVE_VIDEO_IDS.put(channelId, new CachedVideoId(videoId, nowMs));
    }

    static void invalidateChannelVideoId(String channelId) {
        if (channelId == null || channelId.isEmpty()) return;
        LIVE_VIDEO_IDS.remove(channelId);
    }

    /** Sort earthbound streams West (-180°) -> East (+180°), with roving/orbital streams at the end. */
    static List<Stream> sortedByLongitude(List<Stream> list) {
        List<Stream> out = new ArrayList<>(list);
        Collections.sort(out, (a, b) -> {
            if (a.hasCoords != b.hasCoords) return a.hasCoords ? -1 : 1;
            if (a.hasCoords) {
                int c = Double.compare(a.lon, b.lon);
                if (c != 0) return c;
            }
            return a.name.compareToIgnoreCase(b.name);
        });
        return out;
    }

    static void setStatusListener(Runnable listener) {
        statusListener = listener;
    }

    static void clearStatusListener(Runnable listener) {
        if (statusListener == listener) statusListener = null;
    }

    /**
     * Start (or update) the low-priority background probe pool (3 MIN_PRIORITY daemon threads)
     * that gently cycles through all feeds while Home is on top.
     */
    static void startChecker(List<Stream> streams) {
        if (streams == null || streams.isEmpty()) return;
        List<Stream> snapshot = new ArrayList<>(streams);
        synchronized (CHECKER_LOCK) {
            checkerStreams = snapshot;
            boolean anyAlive = false;
            for (Thread t : CHECKER_THREADS) if (t.isAlive()) { anyAlive = true; break; }
            if (checkerRunning && anyAlive) return;
            CHECKER_THREADS.clear();
            checkerRunning = true;
            final int gen = ++checkerGen;
            for (int w = 0; w < PROBE_WORKERS; w++) {
                Thread t = new Thread(() -> runCheckerLoop(gen), "world-cams-probe-" + w);
                t.setDaemon(true);
                t.setPriority(Thread.MIN_PRIORITY);
                CHECKER_THREADS.add(t);
                t.start();
            }
        }
    }

    /** Stop the background probe pool (e.g. when leaving Home). Preserves cursor & cached results. */
    static void stopChecker() {
        synchronized (CHECKER_LOCK) {
            checkerRunning = false;
            checkerGen++;
            for (Thread t : CHECKER_THREADS) t.interrupt();
            CHECKER_THREADS.clear();
        }
    }

    private static void runCheckerLoop(int myGen) {
        Handler main = new Handler(Looper.getMainLooper());
        while (checkerRunning && checkerGen == myGen) {
            List<Stream> list = checkerStreams;
            if (list.isEmpty()) break;
            int idx = CHECKER_CURSOR.getAndIncrement();
            if (idx >= list.size()) {
                synchronized (CHECKER_LOCK) {
                    if (CHECKER_CURSOR.get() >= list.size()) {
                        if (checkerCycleDoneAt == 0L || System.currentTimeMillis() - checkerCycleDoneAt >= PROBE_CYCLE_MS) {
                            checkerCycleDoneAt = System.currentTimeMillis();
                        }
                    }
                }
                long rem = PROBE_CYCLE_MS - (System.currentTimeMillis() - checkerCycleDoneAt);
                if (rem > 0L) {
                    try { Thread.sleep(rem); }
                    catch (InterruptedException e) { break; }
                }
                synchronized (CHECKER_LOCK) {
                    if (CHECKER_CURSOR.get() >= list.size()) {
                        CHECKER_CURSOR.set(0);
                    }
                }
                continue;
            }
            Stream s = list.get(idx);
            Boolean live = probeStream(s);
            if (live != null) {
                Boolean prev = ONLINE.put(s.key, live);
                if (!live.equals(prev)) {
                    Runnable l = statusListener;
                    if (l != null) main.post(l);
                }
            }
            if (!checkerRunning || checkerGen != myGen) break;
            try { Thread.sleep(PROBE_GAP_MS); }
            catch (InterruptedException e) { break; }
        }
    }

    /**
     * Lightweight check whether a stream is currently live.
     * Returns TRUE if online, FALSE if definitely offline, or null on transient network error.
     */
    private static Boolean probeStream(Stream s) {
        if (s == null) return null;
        if ("earthcam".equals(s.type) || EarthCamResolver.isEarthCamUrl(s.url)) {
            return EarthCamResolver.probeOnline(s.url, PROBE_UA);
        }
        if ("youtube_channel".equals(s.type) && !s.channelId.isEmpty()) {
            return probeYouTubeHtml("https://www.youtube.com/channel/" + s.channelId + "/live", true, s.channelId);
        }
        String vid = extractVideoId(s.url);
        if (vid != null) {
            return probeYouTubeHtml("https://www.youtube.com/watch?v=" + vid, false, null);
        }
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(s.url).openConnection();
            conn.setRequestMethod("HEAD");
            conn.setInstanceFollowRedirects(true);
            conn.setConnectTimeout(5_000);
            conn.setReadTimeout(6_000);
            conn.setRequestProperty("User-Agent", PROBE_UA);
            int code = conn.getResponseCode();
            return code >= 200 && code < 400;
        } catch (IOException | RuntimeException e) {
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /**
     * Stream-scan YouTube HTML with a tiny sliding overlap window (16 KB RAM) up to 2 MB so
     * watch?v= pages whose ytInitialPlayerResponse appears around 500–900 KB are detected reliably.
     */
    private static Boolean probeYouTubeHtml(String urlStr, boolean isChannelLive, String channelId) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(urlStr).openConnection();
            conn.setInstanceFollowRedirects(true);
            conn.setConnectTimeout(6_000);
            conn.setReadTimeout(8_000);
            conn.setRequestProperty("User-Agent", PROBE_UA);
            conn.setRequestProperty("Accept-Language", "en-US,en;q=0.9");
            int code = conn.getResponseCode();
            if (code == 404 || code == 410) {
                if (channelId != null) invalidateChannelVideoId(channelId);
                return Boolean.FALSE;
            }
            if (code != 200) return null;
            byte[] buf = new byte[8192];
            String tail = "";
            int total = 0;
            boolean sawWatchCanonical = false;
            try (InputStream in = conn.getInputStream()) {
                int n;
                while (checkerRunning && (n = in.read(buf)) >= 0) {
                    total += n;
                    String chunk = tail + new String(buf, 0, n, StandardCharsets.UTF_8);
                    if (isChannelLive && !sawWatchCanonical && total <= 64 * 1024) {
                        Matcher cm = CANONICAL_HREF.matcher(chunk);
                        if (cm.find()) {
                            String href = cm.group(1);
                            if (href.contains("watch?v=")) {
                                sawWatchCanonical = true;
                                String vid = extractVideoId(href);
                                if (channelId != null && vid != null) cacheChannelVideoId(channelId, vid);
                            } else {
                                if (channelId != null) invalidateChannelVideoId(channelId);
                                return Boolean.FALSE;
                            }
                        }
                    }
                    if (chunk.contains("\"isLiveNow\":true")) return Boolean.TRUE;
                    if (chunk.contains("\"isLiveNow\":false")
                            || chunk.contains("\"LIVE_STREAM_OFFLINE\"")
                            || chunk.contains("\"isUpcoming\":true")
                            || chunk.contains("\"status\":\"ERROR\"")
                            || chunk.contains("\"status\":\"UNPLAYABLE\"")) {
                        if (channelId != null) invalidateChannelVideoId(channelId);
                        return Boolean.FALSE;
                    }
                    tail = chunk.length() > 128 ? chunk.substring(chunk.length() - 128) : chunk;
                    if (total >= 2 * 1024 * 1024) break;
                }
            }
            if (!checkerRunning) return null;
            if (isChannelLive) return sawWatchCanonical ? Boolean.TRUE : Boolean.FALSE;
            return Boolean.FALSE;
        } catch (IOException | RuntimeException e) {
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static final Pattern LIVE_VID = Pattern.compile("\"videoId\":\"([A-Za-z0-9_-]{11})\"");

    /**
     * Resolve a YouTube channel's /live page to its current live videoId using a tiny sliding
     * window (16 KB RAM) instead of buffering the full page. Checks `<link rel="canonical">` first
     * (usually in the first chunk) and falls back to `"videoId"`.
     */
    static String resolveChannelLiveId(String channelId, String ua) {
        if (channelId == null || channelId.isEmpty()) return null;
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL("https://www.youtube.com/channel/" + channelId + "/live").openConnection();
            conn.setInstanceFollowRedirects(true);
            conn.setConnectTimeout(6_000);
            conn.setReadTimeout(8_000);
            if (ua != null) conn.setRequestProperty("User-Agent", ua);
            if (conn.getResponseCode() != 200) return null;
            byte[] buf = new byte[8192];
            String tail = "";
            int total = 0;
            try (InputStream in = conn.getInputStream()) {
                int n;
                while ((n = in.read(buf)) >= 0) {
                    total += n;
                    String chunk = tail + new String(buf, 0, n, StandardCharsets.UTF_8);
                    if (total <= 64 * 1024) {
                        Matcher cm = CANONICAL_HREF.matcher(chunk);
                        if (cm.find()) {
                            String vid = extractVideoId(cm.group(1));
                            if (vid != null) return vid;
                        }
                    }
                    Matcher vm = LIVE_VID.matcher(chunk);
                    if (vm.find()) return vm.group(1);
                    tail = chunk.length() > 160 ? chunk.substring(chunk.length() - 160) : chunk;
                    if (total >= 1536 * 1024) break;
                }
            }
            return null;
        } catch (IOException | RuntimeException e) {
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    // ---------- hearting / bookmarks ----------

    private Set<String> heartsReadOnly() {
        Set<String> s = p.getStringSet(K_HEARTS, null);
        return s == null ? Collections.emptySet() : s;
    }

    boolean isHearted(Stream s) { return s != null && heartsReadOnly().contains(s.key); }

    boolean toggleHeart(Stream s) {
        if (s == null) return false;
        Set<String> h = new LinkedHashSet<>(heartsReadOnly());
        boolean added = !h.remove(s.key);
        if (added) h.add(s.key);
        p.edit().putStringSet(K_HEARTS, h).apply();
        return added;
    }

    int heartedCount(List<Stream> all) {
        Set<String> h = heartsReadOnly();
        if (h.isEmpty()) return 0;
        int n = 0;
        for (Stream s : all) if (h.contains(s.key)) n++;
        return n;
    }

    List<Stream> hearted(List<Stream> all) {
        Set<String> h = heartsReadOnly();
        if (h.isEmpty()) return Collections.emptyList();
        List<Stream> out = new ArrayList<>();
        for (Stream s : all) if (h.contains(s.key)) out.add(s);
        return out;
    }

    /** Active stepping/shuffle pool: hearted streams if at least 2 are hearted, else the full catalog. */
    List<Stream> activePool(List<Stream> all) {
        List<Stream> favs = hearted(all);
        return favs.size() > 1 ? favs : all;
    }

    /** Turn single-card World Cam mode on or off consistently across panel, menus, and remote key setup. */
    void setWorldMode(HomeConfig cfg, boolean on) {
        cfg.set(HomeConfig.K_WORLD_CAMS, on ? "on" : "off");
        setSlotUsesWorld(0, on);
        setSlotUsesWorld(1, false);
        if (on && !cfg.camerasOn()) cfg.set(HomeConfig.K_CAMERAS, "on");
    }

    // ---------- per-slot selection ----------

    boolean slotUsesWorld(int slot) { return p.getBoolean(K_SLOT_WORLD + slot, false); }
    void setSlotUsesWorld(int slot, boolean on) { p.edit().putBoolean(K_SLOT_WORLD + slot, on).apply(); }

    boolean isPinned(int slot) { return p.getBoolean(K_SLOT_PINNED + slot, false); }
    void setPinned(int slot, boolean pinned) { p.edit().putBoolean(K_SLOT_PINNED + slot, pinned).apply(); }

    boolean autoAdvanceOn() { return p.getBoolean(K_AUTO_ON, true); }
    void setAutoAdvanceOn(boolean on) { p.edit().putBoolean(K_AUTO_ON, on).apply(); }

    int autoAdvanceMinutes() { return Math.max(1, p.getInt(K_AUTO_MIN, 5)); }
    void setAutoAdvanceMinutes(int min) { p.edit().putInt(K_AUTO_MIN, Math.max(1, min)).apply(); }

    boolean randomOnReturn() { return p.getBoolean(K_RANDOM_RETURN, true); }
    void setRandomOnReturn(boolean on) { p.edit().putBoolean(K_RANDOM_RETURN, on).apply(); }

    int prefetchCount() { return Math.max(0, Math.min(2, p.getInt(K_PREFETCH, 0))); }
    void setPrefetchCount(int n) { p.edit().putInt(K_PREFETCH, Math.max(0, Math.min(2, n))).apply(); }

    Stream slotStream(int slot, List<Stream> all) {
        if (all.isEmpty()) return null;
        String key = p.getString(K_SLOT + slot, null);
        if (key != null) {
            for (Stream s : all) if (s.key.equals(key)) return s;
        }
        // Sensible defaults: slot 0 -> first stream (Port of Newcastle), slot 1 -> NASA ISS (or second stream).
        if (slot == 1) {
            for (Stream s : all) if (s.name.contains("ISS")) return s;
            return all.size() > 1 ? all.get(1) : all.get(0);
        }
        return all.get(0);
    }

    void setSlot(int slot, Stream s) {
        if (s == null) return;
        p.edit().putString(K_SLOT + slot, s.key).apply();
    }

    /** Pure stepping helper (dir = +1 or -1) that skips known-offline feeds and `avoidKey` when possible. */
    static Stream pickStep(int idx, int dir, List<Stream> pool, String avoidKey) {
        if (pool.isEmpty()) return null;
        int n = pool.size();
        for (int pass = 0; pass < 2; pass++) {
            boolean skipOffline = (pass == 0);
            for (int step = 1; step <= n; step++) {
                int pos = dir < 0 ? Math.floorMod(idx - step, n) : Math.floorMod(idx + step, n);
                Stream cand = pool.get(pos);
                if (skipOffline && isKnownOffline(cand)) continue;
                if (n == 1 || avoidKey == null || !cand.key.equals(avoidKey)) return cand;
            }
        }
        return idx >= 0 && idx < n ? pool.get(idx) : pool.get(0);
    }

    /** Advance `slot` to the next stream in `pool` (skipping known-offline feeds and `avoidKey` when possible). */
    Stream next(int slot, List<Stream> pool, String avoidKey) {
        if (pool.isEmpty()) return null;
        Stream cur = slotStream(slot, pool);
        Stream cand = pickStep(indexOf(pool, cur), 1, pool, avoidKey);
        if (cand != null) setSlot(slot, cand);
        return cand != null ? cand : cur;
    }

    /** Advance both slots together as a non-overlapping pair, skipping known-offline feeds when possible. */
    Stream[] nextPair(List<Stream> pool) { return stepPair(pool, 1); }

    /** Step both slots backward as a non-overlapping pair, skipping known-offline feeds when possible. */
    Stream[] prevPair(List<Stream> pool) { return stepPair(pool, -1); }

    private Stream[] stepPair(List<Stream> pool, int dir) {
        if (pool.isEmpty()) return new Stream[]{null, null};
        if (pool.size() == 1) {
            Stream only = pool.get(0);
            setSlot(0, only);
            setSlot(1, only);
            return new Stream[]{only, only};
        }
        Stream s0 = slotStream(0, pool);
        Stream s1 = slotStream(1, pool);
        int i0 = indexOf(pool, s0), i1 = indexOf(pool, s1);
        int base = dir > 0 ? (i1 >= 0 ? i1 : i0) : (i0 >= 0 ? i0 : (i1 >= 0 ? i1 : 0));
        int n = pool.size();
        Stream first = null, second = null;
        for (int step = 1; step <= n && second == null; step++) {
            int pos = dir > 0 ? (base + step) % n : Math.floorMod(base - step, n);
            Stream cand = pool.get(pos);
            if (isKnownOffline(cand)) continue;
            if (first == null) first = cand;
            else if (!cand.key.equals(first.key)) second = cand;
        }
        Stream p0 = dir > 0 ? first : second;
        Stream p1 = dir > 0 ? second : first;
        if (p0 == null || p1 == null) {
            p0 = pool.get(Math.floorMod(base + (dir > 0 ? 1 : -2), n));
            p1 = pool.get(Math.floorMod(base + (dir > 0 ? 2 : -1), n));
        }
        setSlot(0, p0);
        setSlot(1, p1);
        return new Stream[]{p0, p1};
    }

    /** Step `slot` backward by 1 in `pool` (skipping known-offline feeds and `avoidKey` when possible). */
    Stream prev(int slot, List<Stream> pool, String avoidKey) {
        if (pool.isEmpty()) return null;
        Stream cur = slotStream(slot, pool);
        int idx = indexOf(pool, cur);
        if (idx < 0) idx = 0;
        Stream cand = pickStep(idx, -1, pool, avoidKey);
        if (cand != null) setSlot(slot, cand);
        return cand != null ? cand : cur;
    }

    private static int indexOf(List<Stream> pool, Stream s) {
        if (s == null) return -1;
        for (int i = 0; i < pool.size(); i++) if (pool.get(i).key.equals(s.key)) return i;
        return -1;
    }

    /**
     * Peek up to `count` upcoming streams stepping forward from `cur` in `pool`,
     * skipping known-offline feeds, `cur`, `avoidKey`, and duplicates. Does not mutate slot prefs.
     */
    static List<Stream> peekNext(Stream cur, List<Stream> pool, String avoidKey, int count) {
        if (pool == null || pool.isEmpty() || count <= 0) return Collections.emptyList();
        List<Stream> out = new ArrayList<>(count);
        Set<String> seen = new LinkedHashSet<>();
        if (cur != null) seen.add(cur.key);
        if (avoidKey != null) seen.add(avoidKey);
        int idx = indexOf(pool, cur);
        int n = pool.size();
        for (int pass = 0; pass < 2 && out.size() < count; pass++) {
            boolean skipOffline = (pass == 0);
            for (int step = 1; step <= n && out.size() < count; step++) {
                Stream cand = pool.get(Math.floorMod(idx + step, n));
                if (seen.contains(cand.key)) continue;
                if (skipOffline && isKnownOffline(cand)) continue;
                seen.add(cand.key);
                out.add(cand);
            }
        }
        return out;
    }

    /** Pick a random stream in `pool` without committing it to prefs, preferring confirmed-online streams. */
    Stream peekShuffle(Stream cur, List<Stream> pool, String avoidKey) {
        if (pool == null || pool.isEmpty()) return null;
        List<Stream> confirmedOnline = new ArrayList<>();
        List<Stream> notOffline = new ArrayList<>();
        List<Stream> anyCands = new ArrayList<>();
        for (Stream s : pool) {
            if (cur != null && s.key.equals(cur.key)) continue;
            if (avoidKey != null && s.key.equals(avoidKey)) continue;
            anyCands.add(s);
            if (isOnline(s)) confirmedOnline.add(s);
            if (!isKnownOffline(s)) notOffline.add(s);
        }
        List<Stream> cands = !confirmedOnline.isEmpty() ? confirmedOnline
                : (!notOffline.isEmpty() ? notOffline : (!anyCands.isEmpty() ? anyCands : pool));
        return cands.get(rng.nextInt(cands.size()));
    }

    /** Pick a random stream in `pool`, strictly preferring confirmed-online streams (never known-offline). */
    Stream shuffle(int slot, List<Stream> pool, String avoidKey) {
        if (pool.isEmpty()) return null;
        Stream pick = peekShuffle(slotStream(slot, pool), pool, avoidKey);
        if (pick != null) setSlot(slot, pick);
        return pick;
    }

    // ---------- io ----------

    private String readAsset(String name) {
        try (InputStream in = ctx.getAssets().open(name)) {
            return readStream(in);
        } catch (IOException e) {
            return "";
        }
    }

    private static String readStream(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) sb.append(line).append('\n');
        }
        return sb.toString();
    }
}
