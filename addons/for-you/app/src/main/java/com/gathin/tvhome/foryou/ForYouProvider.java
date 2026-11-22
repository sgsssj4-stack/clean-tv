package com.gathin.tvhome.foryou;

import android.content.ContentProvider;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.media.tv.TvContract;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * {@code content://com.gathin.tvhome.foryou.provider/titles}: what the TV's apps publish to the system's Continue
 * watching (Watch Next) and their preview channels, newest engagement first, with each poster downloaded once and
 * served from {@code /poster/<name>} so the home screen needs no network access.
 *
 * Columns: {@code title}, {@code subtitle}, {@code package}, {@code intent_uri}, {@code poster} (a content:// URI
 * or ""), {@code progress} (0-1000, or -1), {@code kind} ("continue", "next", "new", "recent" or "pick"),
 * {@code imdb} (a rating such as "7.4", or ""), {@code rating_label} (who gave it: "IMDb" or "TMDB"; see {@link Ratings}).
 *
 * Besides the TV provider's rows, titles seen playing by {@link NowPlayingListener} ({@link History}) count as
 * Continue watching, so apps that publish nothing there still show up.
 *
 * The list is rebuilt when a query finds it older than 30 minutes (or with {@code ?refresh=1}); this TV kills an idle
 * provider process within seconds, so the work happens inside the query, which the caller makes off its main thread.
 */
public final class ForYouProvider extends ContentProvider {
    private static final String TAG = "ForYou";
    private static final String AUTHORITY = "com.gathin.tvhome.foryou.provider";
    private static final String[] COLUMNS = {"_id", "title", "subtitle", "package", "intent_uri", "poster", "progress", "kind", "imdb", "rating_label"};
    private static final long FRESH_MS = 30 * 60_000L;
    private static final long WATCH_NEXT_MAX_AGE_MS = 60L * 24 * 60 * 60_000L;
    private static final int MAX_TITLES = 16, PER_APP = 4;
    private static final long MAX_POSTER_BYTES = 4L * 1024 * 1024;
    private static final Pattern POSTER_NAME = Pattern.compile("[0-9a-f]{16,64}");

    private static final String[] WATCH_NEXT_COLUMNS = {
            TvContract.WatchNextPrograms.COLUMN_PACKAGE_NAME,
            TvContract.WatchNextPrograms.COLUMN_TITLE,
            TvContract.WatchNextPrograms.COLUMN_EPISODE_TITLE,
            TvContract.WatchNextPrograms.COLUMN_SEASON_DISPLAY_NUMBER,
            TvContract.WatchNextPrograms.COLUMN_EPISODE_DISPLAY_NUMBER,
            TvContract.WatchNextPrograms.COLUMN_POSTER_ART_URI,
            TvContract.WatchNextPrograms.COLUMN_THUMBNAIL_URI,
            TvContract.WatchNextPrograms.COLUMN_INTENT_URI,
            TvContract.WatchNextPrograms.COLUMN_WATCH_NEXT_TYPE,
            TvContract.WatchNextPrograms.COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS,
            TvContract.WatchNextPrograms.COLUMN_LAST_PLAYBACK_POSITION_MILLIS,
            TvContract.WatchNextPrograms.COLUMN_DURATION_MILLIS,
    };
    private static final String[] PREVIEW_COLUMNS = {
            TvContract.PreviewPrograms.COLUMN_PACKAGE_NAME,
            TvContract.PreviewPrograms.COLUMN_TITLE,
            TvContract.PreviewPrograms.COLUMN_EPISODE_TITLE,
            TvContract.PreviewPrograms.COLUMN_POSTER_ART_URI,
            TvContract.PreviewPrograms.COLUMN_THUMBNAIL_URI,
            TvContract.PreviewPrograms.COLUMN_INTENT_URI,
            TvContract.PreviewPrograms.COLUMN_LAST_PLAYBACK_POSITION_MILLIS,
            TvContract.PreviewPrograms.COLUMN_DURATION_MILLIS,
            TvContract.PreviewPrograms.COLUMN_BROWSABLE,
    };

    static final class Item {
        String title = "", subtitle = "", pkg = "", intentUri = "", art = "", poster = "", kind = "pick", imdb = "";
        /** The rating source's portrait poster (IMDb or TMDB) for the title, preferred over the app's (usually wide) art; not saved. */
        String tallArt = "";
        int progress = -1;
        long engaged;

        JSONObject json() throws Exception {
            return new JSONObject().put("title", title).put("subtitle", subtitle).put("package", pkg)
                    .put("intent_uri", intentUri).put("art", art).put("poster", poster)
                    .put("progress", progress).put("kind", kind).put("imdb", imdb);
        }

        static Item of(JSONObject o) {
            Item i = new Item();
            i.title = o.optString("title");
            i.subtitle = o.optString("subtitle");
            i.pkg = o.optString("package");
            i.intentUri = o.optString("intent_uri");
            i.art = o.optString("art");
            i.poster = o.optString("poster");
            i.progress = o.optInt("progress", -1);
            i.kind = o.optString("kind", "pick");
            i.imdb = o.optString("imdb");
            return i;
        }
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        Context context = getContext();
        if (context == null) return null;
        Ratings ratings = Ratings.load(context);
        List<Item> items = load(context, ratings, "1".equals(uri.getQueryParameter("refresh")));
        String label = ratings.label();
        MatrixCursor cursor = new MatrixCursor(COLUMNS, items.size());
        int id = 0;
        for (Item i : items) {
            String poster = i.poster.isEmpty() ? "" : "content://" + AUTHORITY + "/poster/" + i.poster;
            cursor.addRow(new Object[]{id++, i.title, i.subtitle, i.pkg, i.intentUri, poster, i.progress, i.kind, i.imdb, label});
        }
        return cursor;
    }

    private static synchronized List<Item> load(Context context, Ratings ratings, boolean force) {
        File list = new File(context.getFilesDir(), "titles.json");
        List<Item> cached = read(list);
        File history = new File(context.getFilesDir(), History.FILE);
        boolean watched = history.isFile() && history.lastModified() > list.lastModified();
        if (!force && !watched && list.isFile() && System.currentTimeMillis() - list.lastModified() < FRESH_MS) {
            ratings.apply(context, cached);
            return cached;
        }
        List<Item> fresh;
        try {
            fresh = collect(context);
        } catch (SecurityException e) {
            Log.w(TAG, "READ_TV_LISTINGS not granted");
            return cached;
        } catch (Exception e) {
            Log.w(TAG, "reading the TV provider failed", e);
            return cached;
        }
        ratings.rate(context, fresh);
        for (Item i : fresh) {
            if (!i.tallArt.isEmpty()) {   // a real poster beats the app's wide still
                i.art = i.tallArt;
                i.poster = "";
            }
        }
        downloadPosters(context, fresh);
        write(list, fresh);
        prunePosters(context, fresh);
        return fresh;
    }

    /** Continue watching first (newest engagement first), then the preview channels, a few per app, apps taking turns. */
    private static List<Item> collect(Context context) {
        ContentResolver resolver = context.getContentResolver();
        List<Item> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        long now = System.currentTimeMillis();

        List<Item> watchNext = new ArrayList<>();
        try (Cursor c = resolver.query(TvContract.WatchNextPrograms.CONTENT_URI, WATCH_NEXT_COLUMNS, null, null, null)) {
            while (c != null && c.moveToNext()) {
                Item i = new Item();
                i.pkg = str(c, 0);
                i.title = str(c, 1);
                String episode = str(c, 2), season = str(c, 3), number = str(c, 4);
                StringBuilder sub = new StringBuilder();
                if (!season.isEmpty()) sub.append('S').append(season);
                if (!number.isEmpty()) sub.append(sub.length() > 0 ? " " : "").append('E').append(number);
                if (!episode.isEmpty()) sub.append(sub.length() > 0 ? " · " : "").append(episode);
                i.subtitle = sub.toString();
                i.art = str(c, 5).isEmpty() ? str(c, 6) : str(c, 5);
                i.intentUri = str(c, 7);
                int type = c.isNull(8) ? 0 : c.getInt(8);
                i.kind = type == 0 ? "continue" : type == 1 ? "next" : "new";
                i.engaged = c.isNull(9) ? 0 : c.getLong(9);
                long pos = c.isNull(10) ? 0 : c.getLong(10), dur = c.isNull(11) ? 0 : c.getLong(11);
                if (dur > 0 && pos > 0) i.progress = (int) Math.min(1000, pos * 1000 / dur);
                if (i.title.isEmpty() || i.pkg.isEmpty()) continue;
                if (i.engaged > 0 && now - i.engaged > WATCH_NEXT_MAX_AGE_MS) continue;
                if (i.progress >= 970) continue;   // finished
                watchNext.add(i);
            }
        }
        // What was seen playing joins Continue watching, unless the app already published that title there.
        Set<String> published = new HashSet<>();
        for (Item i : watchNext) published.add(i.pkg + "|" + Imdb.norm(i.title));
        int played = 0;
        for (Item i : History.recent(context)) {
            if (published.add(i.pkg + "|" + Imdb.norm(i.title))) {
                watchNext.add(i);
                played++;
            }
        }
        watchNext.sort((a, b) -> Long.compare(b.engaged, a.engaged));
        for (Item i : watchNext) if (out.size() < MAX_TITLES && seen.add(key(i))) out.add(i);

        Map<String, List<Item>> byApp = new LinkedHashMap<>();
        try (Cursor c = resolver.query(TvContract.PreviewPrograms.CONTENT_URI, PREVIEW_COLUMNS, null, null, null)) {
            while (c != null && c.moveToNext()) {
                if (!c.isNull(8) && c.getInt(8) == 0) continue;   // not browsable
                Item i = new Item();
                i.pkg = str(c, 0);
                i.title = str(c, 1);
                i.subtitle = str(c, 2);
                i.art = str(c, 3).isEmpty() ? str(c, 4) : str(c, 3);
                i.intentUri = str(c, 5);
                long pos = c.isNull(6) ? 0 : c.getLong(6), dur = c.isNull(7) ? 0 : c.getLong(7);
                if (dur > 0 && pos > 0) i.progress = (int) Math.min(1000, pos * 1000 / dur);
                if (i.title.isEmpty() || i.pkg.isEmpty() || i.intentUri.isEmpty()) continue;
                if (i.intentUri.startsWith("content://android.media.tv")) continue;   // TV inputs and channels, not titles
                List<Item> l = byApp.get(i.pkg);
                if (l == null) byApp.put(i.pkg, l = new ArrayList<>());
                if (l.size() < PER_APP) l.add(i);
            }
        }
        for (int round = 0; round < PER_APP && out.size() < MAX_TITLES; round++) {
            for (List<Item> l : byApp.values()) {
                if (round < l.size() && out.size() < MAX_TITLES && seen.add(key(l.get(round)))) out.add(l.get(round));
            }
        }
        Log.i(TAG, "watch next " + (watchNext.size() - played) + ", played " + played + ", preview apps " + byApp.keySet() + ", kept " + out.size());
        return out;
    }

    private static void downloadPosters(Context context, List<Item> items) {
        File dir = new File(context.getFilesDir(), "posters");
        if (!dir.isDirectory() && !dir.mkdirs()) return;
        ExecutorService pool = Executors.newFixedThreadPool(4);
        for (Item i : items) {
            if (i.art.isEmpty() || (!i.poster.isEmpty() && new File(dir, i.poster).isFile())) continue;
            String name = sha(i.art);
            i.poster = name;
            File file = new File(dir, name);
            if (file.isFile() && file.length() > 0) continue;
            pool.execute(() -> {
                if (!fetch(context, i.art, file)) i.poster = "";
            });
        }
        pool.shutdown();
        try {
            if (!pool.awaitTermination(20, TimeUnit.SECONDS)) pool.shutdownNow();
        } catch (InterruptedException e) {
            pool.shutdownNow();
        }
        for (Item i : items) if (!i.poster.isEmpty() && !new File(dir, i.poster).isFile()) i.poster = "";
    }

    private static boolean fetch(Context context, String art, File file) {
        File temp = new File(file.getPath() + ".part");
        InputStream in = null;
        HttpURLConnection http = null;
        try {
            Uri u = Uri.parse(art);
            String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase(Locale.ROOT);
            if (scheme.equals("https") || scheme.equals("http")) {
                http = (HttpURLConnection) new URL(art).openConnection();
                http.setConnectTimeout(5000);
                http.setReadTimeout(8000);
                http.setInstanceFollowRedirects(true);
                if (http.getResponseCode() / 100 != 2) return false;
                in = http.getInputStream();
            } else if (scheme.equals("content") || scheme.equals("android.resource")) {
                in = context.getContentResolver().openInputStream(u);
            } else {
                return false;
            }
            if (in == null) return false;
            long total = 0;
            byte[] buf = new byte[16 * 1024];
            try (FileOutputStream out = new FileOutputStream(temp)) {
                int n;
                while ((n = in.read(buf)) > 0) {
                    total += n;
                    if (total > MAX_POSTER_BYTES) throw new java.io.IOException("too big");
                    out.write(buf, 0, n);
                }
            }
            return total > 0 && temp.renameTo(file);
        } catch (Exception e) {
            temp.delete();
            return false;
        } finally {
            try {
                if (in != null) in.close();
            } catch (Exception ignored) {
                // closing
            }
            if (http != null) http.disconnect();
        }
    }

    private static void prunePosters(Context context, List<Item> items) {
        File[] files = new File(context.getFilesDir(), "posters").listFiles();
        if (files == null) return;
        Set<String> keep = History.posters(context);
        for (Item i : items) keep.add(i.poster);
        for (File f : files) if (!keep.contains(f.getName())) f.delete();
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        List<String> seg = uri.getPathSegments();
        if (!"r".equals(mode) || seg.size() != 2 || !"poster".equals(seg.get(0)) || !POSTER_NAME.matcher(seg.get(1)).matches()) {
            throw new FileNotFoundException(uri.toString());
        }
        Context context = getContext();
        if (context == null) throw new FileNotFoundException(uri.toString());
        return ParcelFileDescriptor.open(new File(new File(context.getFilesDir(), "posters"), seg.get(1)),
                ParcelFileDescriptor.MODE_READ_ONLY);
    }

    private static List<Item> read(File file) {
        List<Item> out = new ArrayList<>();
        try {
            if (!file.isFile()) return out;
            JSONArray a = new JSONArray(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
            for (int k = 0; k < a.length(); k++) out.add(Item.of(a.getJSONObject(k)));
        } catch (Exception e) {
            out.clear();
        }
        return out;
    }

    private static void write(File file, List<Item> items) {
        File temp = new File(file.getPath() + ".tmp");
        try {
            JSONArray a = new JSONArray();
            for (Item i : items) a.put(i.json());
            try (FileOutputStream out = new FileOutputStream(temp)) {
                out.write(a.toString().getBytes(StandardCharsets.UTF_8));
            }
            if (!temp.renameTo(file)) temp.delete();
        } catch (Exception e) {
            temp.delete();
        }
    }

    private static String key(Item i) {
        return i.pkg + "|" + Imdb.norm(i.title);   // one card per title and app, whichever source had it first
    }

    private static String str(Cursor c, int column) {
        String s = c.isNull(column) ? null : c.getString(column);
        return s == null ? "" : s.trim();
    }

    static String sha(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder b = new StringBuilder();
            for (int k = 0; k < 12; k++) b.append(String.format(Locale.ROOT, "%02x", d[k]));
            return b.toString();
        } catch (Exception e) {
            return Integer.toHexString(s.hashCode()) + "00000000000000000";
        }
    }

    @Override
    public String getType(Uri uri) {
        return uri.getPathSegments().size() == 2 ? "image/*" : "vnd.android.cursor.dir/vnd.gathin.foryou";
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("read only");
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("read only");
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("read only");
    }
}
