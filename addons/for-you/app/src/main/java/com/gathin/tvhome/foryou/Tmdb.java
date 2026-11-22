package com.gathin.tvhome.foryou;

import android.content.Context;
import android.net.Uri;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * TMDB ratings and posters for the For you titles, with the owner's own TMDB API key (a v3 key, or a v4 read access
 * token). Some Indian internet providers (Jio among them) block TMDB; then nothing is rated and the app's own art
 * stays. A title counts only when TMDB's name matches exactly. Cached in {@code files/tmdb.json} for 3 days.
 * This product uses the TMDB API but is not endorsed or certified by TMDB.
 */
final class Tmdb {
    private static final String TAG = "ForYouTmdb";
    private static final String FILE = "tmdb.json";
    private static final long REFRESH_MS = 3L * 24 * 60 * 60_000L;
    private static final int MIN_VOTES = 5;

    private Tmdb() {
    }

    /** Fills {@code item.imdb} (the rating) and {@code item.tallArt} (TMDB's poster), fetching what the cache lacks. */
    static void rate(Context context, List<ForYouProvider.Item> items, String apiKey) {
        File file = new File(context.getFilesDir(), FILE);
        JSONObject cache;
        synchronized (Tmdb.class) {
            cache = read(file);
        }
        long now = System.currentTimeMillis();
        List<String> wanted = new ArrayList<>();
        if (!apiKey.isEmpty()) {
            ExecutorService pool = Executors.newFixedThreadPool(4);
            for (ForYouProvider.Item i : items) {
                String key = Imdb.norm(i.title);
                if (key.isEmpty() || wanted.contains(key)) continue;
                JSONObject e = cache.optJSONObject(key);
                if (e != null && now - e.optLong("found") < REFRESH_MS) continue;
                wanted.add(key);
                String title = i.title;
                pool.execute(() -> {
                    JSONObject hit = find(title, key, apiKey);
                    if (hit == null) return;   // network trouble or blocked: try again next time
                    synchronized (cache) {
                        try {
                            cache.put(key, hit.put("found", System.currentTimeMillis()));
                        } catch (Exception ignored) {
                            // skipped
                        }
                    }
                });
            }
            pool.shutdown();
            try {
                if (!pool.awaitTermination(12, TimeUnit.SECONDS)) pool.shutdownNow();
            } catch (InterruptedException e) {
                pool.shutdownNow();
            }
        }
        synchronized (Tmdb.class) {
            write(file, cache);
        }
        fill(items, cache);
    }

    /** Ratings already cached, for a list served without a rebuild. */
    static synchronized void apply(Context context, List<ForYouProvider.Item> items) {
        fill(items, read(new File(context.getFilesDir(), FILE)));
    }

    private static void fill(List<ForYouProvider.Item> items, JSONObject cache) {
        int rated = 0;
        for (ForYouProvider.Item i : items) {
            JSONObject e = cache.optJSONObject(Imdb.norm(i.title));
            i.imdb = e == null ? "" : e.optString("rating");
            i.tallArt = e == null ? "" : e.optString("img");
            if (!i.imdb.isEmpty()) rated++;
        }
        Log.i(TAG, rated + " of " + items.size() + " rated");
    }

    /** {rating, img} for the exact-name match ({} for none), null when TMDB could not be asked. */
    private static JSONObject find(String title, String key, String apiKey) {
        boolean token = apiKey.length() > 40;   // a v4 read access token, else a v3 key
        String url = "https://api.themoviedb.org/3/search/multi?include_adult=false&query=" + Uri.encode(title.trim())
                + (token ? "" : "&api_key=" + Uri.encode(apiKey));
        String body = get(url, token ? "Bearer " + apiKey : null);
        if (body == null) return null;
        JSONObject none = new JSONObject();
        try {
            JSONArray results = new JSONObject(body).optJSONArray("results");
            if (results == null) return none;
            for (int k = 0; k < results.length(); k++) {
                JSONObject o = results.getJSONObject(k);
                String type = o.optString("media_type");
                if (!"movie".equals(type) && !"tv".equals(type)) continue;
                String name = o.optString("movie".equals(type) ? "title" : "name");
                String original = o.optString("movie".equals(type) ? "original_title" : "original_name");
                if (!key.equals(Imdb.norm(name)) && !key.equals(Imdb.norm(original))) continue;
                JSONObject hit = new JSONObject();
                double avg = o.optDouble("vote_average", 0);
                if (avg > 0 && o.optInt("vote_count") >= MIN_VOTES) hit.put("rating", String.format(Locale.ROOT, "%.1f", avg));
                String poster = o.optString("poster_path");
                if (poster.startsWith("/")) hit.put("img", "https://image.tmdb.org/t/p/w500" + poster);
                return hit;
            }
            return none;
        } catch (Exception e) {
            return none;
        }
    }

    private static String get(String url, String auth) {
        HttpURLConnection http = null;
        try {
            http = (HttpURLConnection) new URL(url).openConnection();
            http.setConnectTimeout(5000);
            http.setReadTimeout(8000);
            http.setRequestProperty("Accept", "application/json");
            if (auth != null) http.setRequestProperty("Authorization", auth);
            int code = http.getResponseCode();
            if (code == 401) Log.w(TAG, "TMDB refused the API key");
            if (code / 100 != 2) return null;
            try (InputStream in = http.getInputStream()) {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                for (int n; (n = in.read(buf)) > 0; ) {
                    bytes.write(buf, 0, n);
                    if (bytes.size() > 1024 * 1024) return null;
                }
                return bytes.toString("UTF-8");
            }
        } catch (Exception e) {
            return null;   // includes a provider's block
        } finally {
            if (http != null) http.disconnect();
        }
    }

    private static JSONObject read(File file) {
        try {
            if (file.isFile()) return new JSONObject(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
        } catch (Exception ignored) {
            // damaged: start again
        }
        return new JSONObject();
    }

    private static void write(File file, JSONObject cache) {
        File temp = new File(file.getPath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(temp)) {
            out.write(cache.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            temp.delete();
            return;
        }
        if (!temp.renameTo(file)) temp.delete();
    }
}
