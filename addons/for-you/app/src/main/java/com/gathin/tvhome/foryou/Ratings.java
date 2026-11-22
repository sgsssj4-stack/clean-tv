package com.gathin.tvhome.foryou;

import android.content.Context;

import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Locale;

/**
 * Where For you ratings and posters come from, set in {@code for-you.json} in this add-on's external files folder
 * ({@code /sdcard/Android/data/com.gathin.tvhome.foryou/files/}, written by tools/setup_wizard.py):
 * {@code {"ratings": "imdb" | "tmdb" | "off", "tmdb_key": "..."}}. Without the file: IMDb.
 */
final class Ratings {
    static final String FILE = "for-you.json";

    final String source, tmdbKey;

    private Ratings(String source, String tmdbKey) {
        this.source = source;
        this.tmdbKey = tmdbKey;
    }

    static Ratings load(Context context) {
        String source = "imdb", key = "";
        try {
            File dir = context.getExternalFilesDir(null);
            File f = dir == null ? null : new File(dir, FILE);
            if (f != null && f.isFile()) {
                JSONObject o = new JSONObject(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
                source = o.optString("ratings", "imdb").toLowerCase(Locale.ROOT);
                key = o.optString("tmdb_key").trim();
            }
        } catch (Exception ignored) {
            // unreadable: IMDb
        }
        if (!source.equals("tmdb") && !source.equals("off")) source = "imdb";
        return new Ratings(source, key);
    }

    /** "IMDb", "TMDB", or "" when ratings are off. */
    String label() {
        return source.equals("tmdb") ? "TMDB" : source.equals("off") ? "" : "IMDb";
    }

    void rate(Context context, List<ForYouProvider.Item> items) {
        switch (source) {
            case "tmdb": Tmdb.rate(context, items, tmdbKey); break;
            case "off": clear(items); break;
            default: Imdb.rate(context, items);
        }
    }

    void apply(Context context, List<ForYouProvider.Item> items) {
        switch (source) {
            case "tmdb": Tmdb.apply(context, items); break;
            case "off": clear(items); break;
            default: Imdb.apply(context, items);
        }
    }

    private static void clear(List<ForYouProvider.Item> items) {
        for (ForYouProvider.Item i : items) {
            i.imdb = "";
            i.tallArt = "";
        }
    }
}
