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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Dynamic catalog of public live streams ("World Cams") for households without local RTSP cameras.
 *
 * Loads a cached remote streams.json (refreshed at most once every 24h from GitHub), falling back
 * to the copy bundled in assets/streams.json. Tracks per-slot selection and user-hearted bookmarks
 * in SharedPreferences("world_cams").
 */
final class WorldCams {
    private static final String TAG = "HomeWorldCams";
    private static final String PREFS = "world_cams";
    private static final String K_JSON = "catalog_json";
    private static final String K_FETCHED = "catalog_fetched_at";
    private static final String K_HEARTS = "hearted";
    private static final String K_SLOT = "slot_";
    private static final String K_SLOT_WORLD = "slot_world_";
    private static final long REFRESH_MS = 24 * 3_600_000L;
    private static final Pattern YT_ID = Pattern.compile("(?:v=|youtu\\.be/|embed/|live/)([A-Za-z0-9_-]{11})");

    static final class Stream {
        final String key;
        final String name;
        final String category;
        final String type;      // youtube_channel, youtube_multi, hls, iframe
        final String url;
        final String channelId;
        final String match;

        Stream(String name, String category, String type, String url, String channelId, String match) {
            this.name = name;
            this.category = category;
            this.type = type;
            this.url = url;
            this.channelId = channelId;
            this.match = match;
            this.key = "youtube_channel".equals(type) && !channelId.isEmpty() ? "ch:" + channelId : url;
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

    WorldCams(Context c) {
        ctx = c.getApplicationContext();
        p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
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
                out.add(new Stream(
                        name,
                        o.optString("category", "").trim(),
                        o.optString("type", "youtube_channel").trim(),
                        url,
                        o.optString("channelId", "").trim(),
                        o.optString("match", "").trim()
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

    /** Refresh remote streams.json at most once every 24h in a background thread. */
    void refreshIfDue(String remoteUrl, Runnable onUpdated) {
        if (remoteUrl == null || remoteUrl.trim().isEmpty()) return;
        long now = System.currentTimeMillis();
        if (now - p.getLong(K_FETCHED, 0L) < REFRESH_MS) return;
        Handler main = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) new URL(remoteUrl.trim()).openConnection();
                conn.setConnectTimeout(6_000);
                conn.setReadTimeout(8_000);
                conn.setRequestProperty("User-Agent", "HomeTV/2.20");
                if (conn.getResponseCode() != 200) return;
                String body = readStream(conn.getInputStream());
                List<Stream> parsed = parse(body);
                if (parsed.isEmpty()) return;
                p.edit().putString(K_JSON, body).putLong(K_FETCHED, System.currentTimeMillis()).apply();
                if (onUpdated != null) main.post(onUpdated);
            } catch (IOException | RuntimeException e) {
                Log.w(TAG, "catalog refresh failed: " + e.getMessage());
            } finally {
                if (conn != null) conn.disconnect();
            }
        }, "world-cams-refresh").start();
    }

    // ---------- hearting / bookmarks ----------

    private Set<String> hearts() {
        Set<String> s = p.getStringSet(K_HEARTS, null);
        return s == null ? new LinkedHashSet<>() : new LinkedHashSet<>(s);
    }

    boolean isHearted(Stream s) { return s != null && hearts().contains(s.key); }

    boolean toggleHeart(Stream s) {
        if (s == null) return false;
        Set<String> h = hearts();
        boolean added = !h.remove(s.key);
        if (added) h.add(s.key);
        p.edit().putStringSet(K_HEARTS, h).apply();
        return added;
    }

    int heartedCount(List<Stream> all) {
        Set<String> h = hearts();
        int n = 0;
        for (Stream s : all) if (h.contains(s.key)) n++;
        return n;
    }

    List<Stream> hearted(List<Stream> all) {
        Set<String> h = hearts();
        List<Stream> out = new ArrayList<>();
        for (Stream s : all) if (h.contains(s.key)) out.add(s);
        return out;
    }

    // ---------- per-slot selection ----------

    boolean slotUsesWorld(int slot) { return p.getBoolean(K_SLOT_WORLD + slot, false); }
    void setSlotUsesWorld(int slot, boolean on) { p.edit().putBoolean(K_SLOT_WORLD + slot, on).apply(); }

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

    /** Advance `slot` to the next stream in `pool` (skipping `avoidKey` when possible). */
    Stream next(int slot, List<Stream> pool, String avoidKey) {
        if (pool.isEmpty()) return null;
        Stream cur = slotStream(slot, pool);
        int idx = -1;
        if (cur != null) {
            for (int i = 0; i < pool.size(); i++) if (pool.get(i).key.equals(cur.key)) { idx = i; break; }
        }
        for (int step = 1; step <= pool.size(); step++) {
            Stream cand = pool.get((idx + step) % pool.size());
            if (pool.size() == 1 || avoidKey == null || !cand.key.equals(avoidKey)) {
                setSlot(slot, cand);
                return cand;
            }
        }
        return cur;
    }

    /** Advance both slots together as a non-overlapping pair: (0,1) -> (2,3) -> (4,5) -> ... */
    Stream[] nextPair(List<Stream> pool) {
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
        int base = i1 >= 0 ? i1 : i0;
        int n = pool.size();
        Stream next0 = pool.get((base + 1) % n);
        Stream next1 = pool.get((base + 2) % n);
        setSlot(0, next0);
        setSlot(1, next1);
        return new Stream[]{next0, next1};
    }

    /** Step both slots backward as a non-overlapping pair: (4,5) -> (2,3) -> (0,1) -> (n-2,n-1). */
    Stream[] prevPair(List<Stream> pool) {
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
        int base = i0 >= 0 ? i0 : (i1 >= 0 ? i1 : 0);
        int n = pool.size();
        Stream prev0 = pool.get(Math.floorMod(base - 2, n));
        Stream prev1 = pool.get(Math.floorMod(base - 1, n));
        setSlot(0, prev0);
        setSlot(1, prev1);
        return new Stream[]{prev0, prev1};
    }

    /** Step `slot` backward by 1 in `pool`. */
    Stream prev(int slot, List<Stream> pool, String avoidKey) {
        if (pool.isEmpty()) return null;
        Stream cur = slotStream(slot, pool);
        int idx = indexOf(pool, cur);
        if (idx < 0) idx = 0;
        int n = pool.size();
        for (int step = 1; step <= n; step++) {
            Stream cand = pool.get(Math.floorMod(idx - step, n));
            if (n == 1 || avoidKey == null || !cand.key.equals(avoidKey)) {
                setSlot(slot, cand);
                return cand;
            }
        }
        return cur;
    }

    private static int indexOf(List<Stream> pool, Stream s) {
        if (s == null) return -1;
        for (int i = 0; i < pool.size(); i++) if (pool.get(i).key.equals(s.key)) return i;
        return -1;
    }

    /** Pick a random stream in `pool` (avoiding current and `avoidKey` when possible). */
    Stream shuffle(int slot, List<Stream> pool, String avoidKey) {
        if (pool.isEmpty()) return null;
        Stream cur = slotStream(slot, pool);
        List<Stream> cands = new ArrayList<>();
        for (Stream s : pool) {
            if (cur != null && s.key.equals(cur.key)) continue;
            if (avoidKey != null && s.key.equals(avoidKey)) continue;
            cands.add(s);
        }
        if (cands.isEmpty()) cands = pool;
        Stream pick = cands.get(rng.nextInt(cands.size()));
        setSlot(slot, pick);
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
