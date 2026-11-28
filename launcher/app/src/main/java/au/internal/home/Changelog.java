// SPDX-License-Identifier: GPL-3.0-only
package au.internal.home;

import android.content.Context;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Parses the bundled CHANGELOG.md asset for the Settings -> About screen.
 *
 * Expected format:
 *   ## v26.11.28.1 — 2026-11-28
 *   - Change description
 */
final class Changelog {
    static final class Entry {
        final String version;
        final String date;
        final List<String> notes;

        Entry(String version, String date, List<String> notes) {
            this.version = version;
            this.date = date;
            this.notes = Collections.unmodifiableList(new ArrayList<>(notes));
        }
    }

    private Changelog() { }

    static List<Entry> load(Context c) {
        try (InputStream in = c.getAssets().open("CHANGELOG.md");
             BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line).append('\n');
            return parse(sb.toString());
        } catch (IOException e) {
            return Collections.emptyList();
        }
    }

    static List<Entry> parse(String markdown) {
        if (markdown == null || markdown.isEmpty()) return Collections.emptyList();
        List<Entry> out = new ArrayList<>();
        String curVer = null, curDate = "";
        List<String> curNotes = new ArrayList<>();
        for (String raw : markdown.split("\r?\n")) {
            String line = raw.trim();
            if (line.startsWith("## ")) {
                if (curVer != null) out.add(new Entry(curVer, curDate, curNotes));
                String header = line.substring(3).trim();
                String[] parts = splitHeader(header);
                curVer = stripLeadingV(parts[0]);
                curDate = parts[1];
                curNotes = new ArrayList<>();
            } else if (curVer != null && (line.startsWith("- ") || line.startsWith("* "))) {
                String item = line.substring(2).trim().replace("`", "");
                if (!item.isEmpty()) curNotes.add(item);
            }
        }
        if (curVer != null) out.add(new Entry(curVer, curDate, curNotes));
        return out;
    }

    static Entry forVersion(List<Entry> entries, String versionName) {
        if (entries == null || entries.isEmpty()) return null;
        String target = stripLeadingV(versionName);
        if (!target.isEmpty()) {
            for (Entry e : entries) {
                if (target.equals(e.version)) return e;
            }
        }
        return entries.get(0);
    }

    static String formatDate(String isoDate, long fallbackMs) {
        if (isoDate != null && isoDate.matches("\\d{4}-\\d{2}-\\d{2}")) {
            try {
                Date d = new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).parse(isoDate);
                if (d != null) return new SimpleDateFormat("d MMM yyyy", Locale.ENGLISH).format(d);
            } catch (Exception ignored) { }
        }
        if (fallbackMs > 0) {
            return new SimpleDateFormat("d MMM yyyy", Locale.ENGLISH).format(new Date(fallbackMs));
        }
        return isoDate != null ? isoDate : "";
    }

    static String formatAbout(String versionName, long lastUpdateMs, List<Entry> entries) {
        String ver = stripLeadingV(versionName);
        Entry e = forVersion(entries, ver);
        String date = formatDate(e != null ? e.date : "", lastUpdateMs);
        StringBuilder sb = new StringBuilder();
        if (!ver.isEmpty()) sb.append("Version: v").append(ver);
        if (!date.isEmpty()) {
            if (sb.length() > 0) sb.append('\n');
            sb.append("Updated: ").append(date);
        }
        if (e != null && !e.notes.isEmpty()) {
            sb.append("\n\nWhat's new");
            if (!e.version.isEmpty() && !e.version.equals(ver)) sb.append(" (v").append(e.version).append(')');
            sb.append(':');
            for (String n : e.notes) sb.append("\n• ").append(n);
        }
        return sb.toString();
    }

    static String formatAll(List<Entry> entries) {
        if (entries == null || entries.isEmpty()) return "No changelog entries.";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < entries.size(); i++) {
            Entry e = entries.get(i);
            if (i > 0) sb.append("\n\n");
            sb.append('v').append(e.version);
            String date = formatDate(e.date, 0);
            if (!date.isEmpty()) sb.append("  ·  ").append(date);
            for (String n : e.notes) sb.append("\n• ").append(n);
        }
        return sb.toString();
    }

    private static String[] splitHeader(String header) {
        for (String sep : new String[]{" — ", " – ", " - "}) {
            int idx = header.indexOf(sep);
            if (idx >= 0) {
                return new String[]{header.substring(0, idx).trim(), header.substring(idx + sep.length()).trim()};
            }
        }
        return new String[]{header, ""};
    }

    private static String stripLeadingV(String s) {
        if (s == null) return "";
        String t = s.trim();
        return (t.startsWith("v") || t.startsWith("V")) ? t.substring(1).trim() : t;
    }
}
