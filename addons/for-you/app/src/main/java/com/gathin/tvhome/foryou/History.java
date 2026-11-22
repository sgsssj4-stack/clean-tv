package com.gathin.tvhome.foryou;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * What {@link NowPlayingListener} saw playing: {@code files/history.json}, newest first, at most {@link #MAX}
 * titles. Kept on the TV only.
 */
final class History {
    static final String FILE = "history.json";
    private static final int MAX = 40;
    private static final long KEEP_MS = 21L * 24 * 60 * 60_000L;
    /** Shorter than this (and not live) is a clip or a song, not something to come back to. */
    private static final long MIN_DURATION_MS = 8 * 60_000L;
    /** Music apps: their sessions are songs. */
    private static final Set<String> MUSIC = new HashSet<>(Arrays.asList(
            "com.google.android.youtube.tvmusic", "com.google.android.apps.youtube.music", "com.spotify.tv.android",
            "com.spotify.music", "com.jio.media.jiobeats", "com.saavn.android", "com.gaana", "com.bsbportal.music",
            "com.amazon.mp3", "com.apple.android.music"));

    private History() {
    }

    static boolean worthKeeping(String pkg, String title, long durationMs) {
        if (pkg == null || title == null || title.trim().isEmpty() || MUSIC.contains(pkg)) return false;
        return durationMs <= 0 || durationMs >= MIN_DURATION_MS;
    }

    static String posterName(String pkg, String title) {
        return ForYouProvider.sha("now:" + pkg + "|" + title.toLowerCase(Locale.ROOT));
    }

    static synchronized void note(Context context, String pkg, String title, String subtitle, long positionMs,
                                  long durationMs, String poster, String artUri) {
        File file = new File(context.getFilesDir(), FILE);
        JSONArray old = read(file);
        JSONArray out = new JSONArray();
        long now = System.currentTimeMillis();
        try {
            JSONObject entry = new JSONObject().put("package", pkg).put("title", title).put("subtitle", subtitle)
                    .put("position", Math.max(0, positionMs)).put("duration", Math.max(0, durationMs))
                    .put("poster", poster).put("art", artUri == null ? "" : artUri).put("at", now);
            out.put(entry);
            for (int k = 0; k < old.length() && out.length() < MAX; k++) {
                JSONObject o = old.optJSONObject(k);
                if (o == null || now - o.optLong("at") > KEEP_MS) continue;
                if (pkg.equals(o.optString("package")) && title.equalsIgnoreCase(o.optString("title"))) {
                    if (poster.isEmpty()) entry.put("poster", o.optString("poster"));
                    continue;
                }
                out.put(o);
            }
            File temp = new File(file.getPath() + ".tmp");
            try (FileOutputStream w = new FileOutputStream(temp)) {
                w.write(out.toString().getBytes(StandardCharsets.UTF_8));
            }
            if (!temp.renameTo(file)) temp.delete();
        } catch (Exception ignored) {
            // the next state change tries again
        }
    }

    /** As For you items: half-watched ones as "continue", the rest as "recent". */
    static synchronized List<ForYouProvider.Item> recent(Context context) {
        List<ForYouProvider.Item> out = new ArrayList<>();
        JSONArray a = read(new File(context.getFilesDir(), FILE));
        long now = System.currentTimeMillis();
        for (int k = 0; k < a.length(); k++) {
            JSONObject o = a.optJSONObject(k);
            if (o == null || now - o.optLong("at") > KEEP_MS) continue;
            ForYouProvider.Item i = new ForYouProvider.Item();
            i.pkg = o.optString("package");
            i.title = o.optString("title");
            i.subtitle = o.optString("subtitle");
            i.poster = o.optString("poster");
            String art = o.optString("art");
            if (art.startsWith("https://") || art.startsWith("http://")) i.art = art;
            long pos = o.optLong("position"), dur = o.optLong("duration");
            if (dur > 0 && pos > 0) i.progress = (int) Math.min(1000, pos * 1000 / dur);
            if (i.progress >= 950) continue;   // finished
            i.kind = i.progress >= 20 ? "continue" : "recent";
            i.engaged = o.optLong("at");
            out.add(i);
        }
        return out;
    }

    static synchronized Set<String> posters(Context context) {
        Set<String> out = new HashSet<>();
        JSONArray a = read(new File(context.getFilesDir(), FILE));
        for (int k = 0; k < a.length(); k++) {
            JSONObject o = a.optJSONObject(k);
            if (o != null && !o.optString("poster").isEmpty()) out.add(o.optString("poster"));
        }
        return out;
    }

    private static JSONArray read(File file) {
        try {
            if (file.isFile()) return new JSONArray(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
        } catch (Exception ignored) {
            // damaged: start again
        }
        return new JSONArray();
    }
}
