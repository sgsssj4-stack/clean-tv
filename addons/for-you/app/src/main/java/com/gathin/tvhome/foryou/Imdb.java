package com.gathin.tvhome.foryou;

import android.content.Context;
import android.net.Uri;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.GZIPInputStream;

/**
 * IMDb ratings for the For you titles, for personal use on this TV. TMDB is unreachable on Jio; IMDb is not. A
 * title is matched through IMDb's search suggestions (only an exact name match counts, so a wrong rating is
 * unlikely); ratings come from IMDb's published non-commercial dataset. Cached in {@code files/imdb.json}: the
 * match for 30 days, the rating for 3, a miss for 3.
 */
final class Imdb {
    private static final String TAG = "ForYouImdb";
    private static final String FILE = "imdb.json";
    private static final long ID_MS = 30L * 24 * 60 * 60_000L, RATING_MS = 3L * 24 * 60 * 60_000L;
    private static final Set<String> KINDS = new HashSet<>(Arrays.asList(
            "movie", "tvSeries", "tvMiniSeries", "tvMovie", "tvSpecial", "video"));

    private Imdb() {
    }

    static String norm(String title) {
        return title.toLowerCase(Locale.ROOT).replace("&", "and").replaceAll("[^\\p{L}\\p{N}]+", "");
    }

    /** Fills {@code item.imdb} ("7.4", or "" when there is none), fetching what the cache lacks. */
    static void rate(Context context, List<ForYouProvider.Item> items) {
        File file = new File(context.getFilesDir(), FILE);
        JSONObject cache;
        synchronized (Imdb.class) {
            cache = read(file);
        }
        long now = System.currentTimeMillis();
        try {
            // 1. names to IMDb ids
            List<String> toFind = new ArrayList<>();
            for (ForYouProvider.Item i : items) {
                String key = norm(i.title);
                if (key.isEmpty() || toFind.contains(key)) continue;
                JSONObject e = cache.optJSONObject(key);
                long age = e == null ? Long.MAX_VALUE : now - e.optLong("found");
                if (e == null || age > (e.optString("id").isEmpty() ? RATING_MS : ID_MS)
                        || (!e.optString("id").isEmpty() && !e.has("img"))) toFind.add(key);   // older entries: no poster yet
            }
            if (!toFind.isEmpty()) {
                ExecutorService pool = Executors.newFixedThreadPool(4);
                for (ForYouProvider.Item i : items) {
                    String key = norm(i.title);
                    if (!toFind.remove(key)) continue;
                    String title = i.title;
                    pool.execute(() -> {
                        String[] hit = find(title, key);
                        if (hit == null) return;   // network trouble: try again next time
                        String id = hit[0];
                        synchronized (cache) {
                            try {
                                JSONObject e = cache.optJSONObject(key);
                                if (e == null) cache.put(key, e = new JSONObject());
                                if (!id.equals(e.optString("id"))) {
                                    e.remove("rating");
                                    e.remove("rated");
                                }
                                e.put("id", id).put("img", hit[1]).put("found", System.currentTimeMillis());
                            } catch (Exception ignored) {
                                // skipped
                            }
                        }
                    });
                }
                pool.shutdown();
                if (!pool.awaitTermination(12, TimeUnit.SECONDS)) pool.shutdownNow();
            }

            // 2. ratings: from IMDb's ratings dataset, in the background (this process stays up for the listener)
            Set<String> ids = new HashSet<>();
            for (ForYouProvider.Item i : items) {
                JSONObject e = cache.optJSONObject(norm(i.title));
                if (e != null && !e.optString("id").isEmpty() && now - e.optLong("rated") > RATING_MS) ids.add(e.optString("id"));
            }
            if (!ids.isEmpty()) fetchRatingsLater(context, ids);
        } catch (Exception e) {
            Log.w(TAG, "lookup failed", e);
        }
        int rated = 0;
        for (ForYouProvider.Item i : items) {
            JSONObject e = cache.optJSONObject(norm(i.title));
            i.imdb = e == null ? "" : e.optString("rating");
            i.tallArt = e == null ? "" : e.optString("img");
            if (!i.imdb.isEmpty()) rated++;
        }
        Log.i(TAG, rated + " of " + items.size() + " rated");
        synchronized (Imdb.class) {
            JSONObject latest = read(file);   // the dataset thread may have written ratings meanwhile
            for (Iterator<String> it = cache.keys(); it.hasNext(); ) {
                String k = it.next();
                JSONObject mine = cache.optJSONObject(k), theirs = latest.optJSONObject(k);
                try {
                    if (mine != null && theirs != null && theirs.optLong("rated") > mine.optLong("rated")
                            && theirs.optString("id").equals(mine.optString("id"))) {
                        mine.put("rating", theirs.optString("rating")).put("rated", theirs.optLong("rated"));
                    }
                } catch (Exception ignored) {
                    // keep mine
                }
            }
            try {
                cache.put("_dataset", Math.max(cache.optLong("_dataset"), latest.optLong("_dataset")));
            } catch (Exception ignored) {
                // fine
            }
            write(file, cache);
        }
    }

    /**
     * {id, portrait poster URL} for the title whose name matches exactly ({"", ""} for none), null when IMDb could
     * not be asked. The poster is IMDb's own, sized down to 720 px tall.
     */
    private static String[] find(String title, String key) {
        String q = title.toLowerCase(Locale.ROOT).trim();
        char first = q.isEmpty() ? 'x' : q.charAt(0);
        String folder = first >= 'a' && first <= 'z' ? String.valueOf(first) : "x";
        String body = get("https://v3.sg.media-imdb.com/suggestion/" + folder + "/" + Uri.encode(q) + ".json");
        if (body == null) return null;
        String[] none = {"", ""};
        try {
            JSONArray d = new JSONObject(body).optJSONArray("d");
            if (d == null) return none;
            for (int k = 0; k < d.length(); k++) {
                JSONObject o = d.getJSONObject(k);
                String id = o.optString("id");
                if (id.startsWith("tt") && KINDS.contains(o.optString("qid")) && key.equals(norm(o.optString("l")))) {
                    JSONObject i = o.optJSONObject("i");
                    String img = i == null ? "" : i.optString("imageUrl");
                    int w = i == null ? 0 : i.optInt("width"), h = i == null ? 0 : i.optInt("height");
                    if (!img.startsWith("https://") || h < w) img = "";   // portrait posters only
                    else if (img.endsWith("._V1_.jpg")) img = img.substring(0, img.length() - 9) + "._V1_UY720_.jpg";
                    return new String[]{id, img};
                }
            }
            return none;
        } catch (Exception e) {
            return none;
        }
    }

    private static final AtomicBoolean FETCHING = new AtomicBoolean();
    private static final String DATASET = "https://datasets.imdbws.com/title.ratings.tsv.gz";
    private static final long DATASET_RETRY_MS = 6L * 60 * 60_000L;

    /**
     * IMDb's ratings dataset (about 9 MB, for personal and non-commercial use) streamed once, keeping only the
     * wanted ids; at most every six hours. The next list rebuild shows the ratings.
     */
    private static void fetchRatingsLater(Context context, Set<String> ids) {
        File file = new File(context.getFilesDir(), FILE);
        synchronized (Imdb.class) {
            if (System.currentTimeMillis() - read(file).optLong("_dataset") < DATASET_RETRY_MS) return;
        }
        if (!FETCHING.compareAndSet(false, true)) return;
        new Thread(() -> {
            Map<String, String> found = new HashMap<>();
            boolean done = false;
            HttpURLConnection http = null;
            long t0 = System.currentTimeMillis();
            try {
                http = (HttpURLConnection) new URL(DATASET).openConnection();
                http.setConnectTimeout(8000);
                http.setReadTimeout(20000);
                if (http.getResponseCode() / 100 == 2) {
                    try (BufferedReader r = new BufferedReader(new InputStreamReader(
                            new GZIPInputStream(http.getInputStream(), 64 * 1024), StandardCharsets.UTF_8), 64 * 1024)) {
                        for (String line; (line = r.readLine()) != null; ) {
                            int tab = line.indexOf('\t');
                            if (tab < 0 || !ids.contains(line.substring(0, tab))) continue;
                            int tab2 = line.indexOf('\t', tab + 1);
                            found.put(line.substring(0, tab), line.substring(tab + 1, tab2 < 0 ? line.length() : tab2));
                            if (found.size() == ids.size()) break;
                        }
                    }
                    done = true;
                }
            } catch (Exception e) {
                Log.w(TAG, "ratings dataset failed", e);
            } finally {
                if (http != null) http.disconnect();
            }
            synchronized (Imdb.class) {
                JSONObject cache = read(file);
                long now = System.currentTimeMillis();
                try {
                    cache.put("_dataset", now);
                    if (done) {
                        for (Iterator<String> it = cache.keys(); it.hasNext(); ) {
                            JSONObject e = cache.optJSONObject(it.next());
                            if (e == null || !ids.contains(e.optString("id"))) continue;
                            String v = found.get(e.optString("id"));
                            e.put("rating", v == null ? "" : v).put("rated", now);
                        }
                    }
                } catch (Exception ignored) {
                    // next time
                }
                write(file, cache);
            }
            Log.i(TAG, "dataset: " + found.size() + " of " + ids.size() + " rated in " + (System.currentTimeMillis() - t0) + " ms");
            FETCHING.set(false);
        }, "imdb-ratings").start();
    }

    /** Ratings already cached, for a list served without a rebuild. */
    static synchronized void apply(Context context, List<ForYouProvider.Item> items) {
        JSONObject cache = read(new File(context.getFilesDir(), FILE));
        for (ForYouProvider.Item i : items) {
            JSONObject e = cache.optJSONObject(norm(i.title));
            i.imdb = e == null ? "" : e.optString("rating");
            i.tallArt = e == null ? "" : e.optString("img");
        }
    }

    private static String get(String url) {
        return request(url, null);
    }

    private static String request(String url, String json) {
        HttpURLConnection http = null;
        try {
            http = (HttpURLConnection) new URL(url).openConnection();
            http.setConnectTimeout(5000);
            http.setReadTimeout(8000);
            http.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 11) ForYou");
            if (json != null) {
                http.setRequestMethod("POST");
                http.setDoOutput(true);
                http.setRequestProperty("Content-Type", "application/json");
                try (OutputStream out = http.getOutputStream()) {
                    out.write(json.getBytes(StandardCharsets.UTF_8));
                }
            }
            if (http.getResponseCode() / 100 != 2) return null;
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
            return null;
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
