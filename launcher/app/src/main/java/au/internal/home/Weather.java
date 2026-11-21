// SPDX-License-Identifier: GPL-3.0-only
package au.internal.home;

import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/** Current conditions from Open-Meteo (free, no API key). Delivers e.g. "18° Partly cloudy  ↑24° ↓13°", or null on failure. */
final class Weather {
    static final class Now {
        final String text; final int code; final boolean day;
        Now(String text, int code, boolean day) { this.text = text; this.code = code; this.day = day; }
    }

    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    /** A place for the weather and sun times (Postcodes). */
    static final class Place {
        final String name, label, tz; final double lat, lon;
        Place(String name, String label, double lat, double lon, String tz) {
            this.name = name; this.label = label; this.lat = lat; this.lon = lon; this.tz = tz;
        }
    }

    private static JSONObject get(URL url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(8000);
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            for (int n; (n = in.read(buf)) > 0; ) b.write(buf, 0, n);
            return new JSONObject(b.toString("UTF-8"));
        } finally {
            c.disconnect();
        }
    }

    static void fetch(String lat, String lon, String tz, Consumer<Now> cb) {
        IO.execute(() -> {
            Now out = null;
            try {
                URL url = new URL("https://api.open-meteo.com/v1/forecast?latitude=" + lat + "&longitude=" + lon
                        + "&current=temperature_2m,weather_code,is_day"
                        + "&daily=temperature_2m_max,temperature_2m_min&forecast_days=1"
                        + "&timezone=" + URLEncoder.encode(tz, "UTF-8"));
                JSONObject j = get(url);
                JSONObject cur = j.getJSONObject("current");
                JSONObject day = j.getJSONObject("daily");
                int code = cur.getInt("weather_code");
                boolean isDay = cur.optInt("is_day", 1) == 1;
                String text = String.format(Locale.ROOT, "%d° %s  ·  ↑%d° ↓%d°",
                        Math.round(cur.getDouble("temperature_2m")),
                        describe(code, isDay),
                        Math.round(day.getJSONArray("temperature_2m_max").getDouble(0)),
                        Math.round(day.getJSONArray("temperature_2m_min").getDouble(0)));
                out = new Now(text, code, isDay);
            } catch (Exception ignored) { }
            Now result = out;
            MAIN.post(() -> cb.accept(result));
        });
    }

    /** Line icon for a WMO code. */
    static int icon(int code, boolean day) {
        if (code <= 1) return day ? R.drawable.wx_sun : R.drawable.wx_moon;
        if (code == 2) return day ? R.drawable.wx_partly : R.drawable.wx_cloud;
        if (code == 3) return R.drawable.wx_cloud;
        if (code == 45 || code == 48) return R.drawable.wx_fog;
        if (code >= 95) return R.drawable.wx_storm;
        if ((code >= 71 && code <= 77) || code == 85 || code == 86) return R.drawable.wx_snow;
        return R.drawable.wx_rain;
    }

    /** WMO weather interpretation codes. */
    private static String describe(int code, boolean day) {
        switch (code) {
            case 0: return day ? "Sunny" : "Clear";
            case 1: return day ? "Mostly sunny" : "Mostly clear";
            case 2: return "Partly cloudy";
            case 3: return "Cloudy";
            case 45: case 48: return "Fog";
            case 51: case 53: case 55: return "Drizzle";
            case 56: case 57: return "Freezing drizzle";
            case 61: return "Light rain";
            case 63: return "Rain";
            case 65: return "Heavy rain";
            case 66: case 67: return "Freezing rain";
            case 71: case 73: case 75: case 77: return "Snow";
            case 80: return "Showers";
            case 81: return "Heavy showers";
            case 82: return "Storm showers";
            case 85: case 86: return "Snow showers";
            case 95: return "Thunderstorms";
            case 96: case 99: return "Storms with hail";
            default: return "";
        }
    }
}
