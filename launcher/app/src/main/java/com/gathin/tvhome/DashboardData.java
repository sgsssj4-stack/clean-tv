package com.gathin.tvhome;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The dashboard's local data, all offline under the theme directory:
 * <ul>
 *   <li>{@code recommendations.json}: {@code [{"title","package","poster","rating"?,"deeplink"?}]}, the "For you"
 *       posters and the swipe card; {@code poster} is a file name in {@code posters/}.</li>
 *   <li>{@code ratings.json}: what the swipe rater recorded, {@code {"<package>|<title>": "like"|"skip"|"hide"}}.</li>
 * </ul>
 */
final class DashboardData {
    static final String RECOMMENDATIONS = "recommendations.json";
    static final String POSTERS = "posters";
    static final String RATINGS = "ratings.json";
    static final String LIKE = "like", SKIP = "skip", HIDE = "hide";

    private static final Pattern PACKAGE = Pattern.compile("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+");
    private static final Pattern POSTER = Pattern.compile("[A-Za-z0-9_.-]+\\.(jpg|jpeg|png|webp)", Pattern.CASE_INSENSITIVE);

    private DashboardData() {}

    static final class Title {
        final String title;
        final String pkg;
        /** File name in posters/, or "" for none. */
        final String poster;
        /** e.g. "U/A 13+" or "8.4"; "" for none. */
        final String rating;
        /** A URI the app opens directly, or "" to open the app's start screen. */
        final String deeplink;

        Title(String title, String pkg, String poster, String rating, String deeplink) {
            this.title = title;
            this.pkg = pkg;
            this.poster = poster;
            this.rating = rating;
            this.deeplink = deeplink;
        }

        String key() {
            return pkg + "|" + title;
        }
    }

    /** Parses recommendations.json; entries without a title or with a malformed package or poster are skipped. */
    static List<Title> parse(String json) {
        List<Title> list = new ArrayList<>();
        if (json == null) return list;
        try {
            JSONArray array = new JSONArray(json.trim());
            for (int i = 0; i < array.length() && list.size() < 50; i++) {
                JSONObject o = array.optJSONObject(i);
                if (o == null) continue;
                String title = Cards.cleanLabel(o.optString("title", ""), "");
                String pkg = o.optString("package", "").trim();
                String poster = o.optString("poster", "").trim();
                if (title.isEmpty() || !PACKAGE.matcher(pkg).matches()) continue;
                if (!poster.isEmpty() && !POSTER.matcher(poster).matches()) poster = "";
                String rating = Cards.cleanLabel(o.optString("rating", ""), "");
                String deeplink = o.optString("deeplink", "").trim();
                if (deeplink.length() > 500 || deeplink.startsWith("file:") || deeplink.startsWith("content:")) deeplink = "";
                list.add(new Title(title, pkg, poster, rating, deeplink));
            }
        } catch (Exception e) {
            return new ArrayList<>();
        }
        return list;
    }

    static Map<String, String> parseRatings(String json) {
        Map<String, String> map = new LinkedHashMap<>();
        if (json == null) return map;
        try {
            JSONObject o = new JSONObject(json.trim());
            java.util.Iterator<String> keys = o.keys();
            while (keys.hasNext() && map.size() < 1000) {
                String k = keys.next();
                String v = o.optString(k, "").toLowerCase(Locale.ROOT);
                if (LIKE.equals(v) || SKIP.equals(v) || HIDE.equals(v)) map.put(k, v);
            }
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
        return map;
    }

    /** Titles the rater has not seen yet, in list order. */
    static List<Title> unrated(List<Title> titles, Map<String, String> ratings) {
        List<Title> out = new ArrayList<>();
        for (Title t : titles) if (!ratings.containsKey(t.key())) out.add(t);
        return out;
    }

    /** "For you": everything not hidden, liked first, then unrated, then skipped; the order within each kept. */
    static List<Title> forYou(List<Title> titles, Map<String, String> ratings) {
        List<Title> liked = new ArrayList<>(), rest = new ArrayList<>(), skipped = new ArrayList<>();
        for (Title t : titles) {
            String r = ratings.get(t.key());
            if (HIDE.equals(r)) continue;
            if (LIKE.equals(r)) liked.add(t);
            else if (SKIP.equals(r)) skipped.add(t);
            else rest.add(t);
        }
        liked.addAll(rest);
        liked.addAll(skipped);
        return liked;
    }

    static List<Title> load(File dir) {
        return dir == null ? new ArrayList<>() : parse(read(new File(dir, RECOMMENDATIONS)));
    }

    static Map<String, String> loadRatings(File dir) {
        return dir == null ? new LinkedHashMap<>() : parseRatings(read(new File(dir, RATINGS)));
    }

    /** Records one rating, writing ratings.json through a temp file and a rename. */
    static synchronized boolean saveRating(File dir, Title title, String rating) {
        if (dir == null) return false;
        Map<String, String> ratings = loadRatings(dir);
        ratings.put(title.key(), rating);
        File temp = new File(dir, RATINGS + ".tmp");
        try {
            JSONObject o = new JSONObject();
            for (Map.Entry<String, String> e : ratings.entrySet()) o.put(e.getKey(), e.getValue());
            try (FileOutputStream out = new FileOutputStream(temp)) {
                out.write(o.toString(2).getBytes(StandardCharsets.UTF_8));
                out.getFD().sync();
            }
            return temp.renameTo(new File(dir, RATINGS));
        } catch (Exception e) {
            temp.delete();
            return false;
        }
    }

    private static String read(File file) {
        if (!file.isFile() || file.length() > 512 * 1024) return null;
        try {
            return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    /** The time-of-day greeting the dashboard uses: morning 5-12, afternoon 12-17, evening 17-21, else night. */
    static String partOfDay(int hour) {
        if (hour >= 5 && hour < 12) return "Good morning";
        if (hour >= 12 && hour < 17) return "Good afternoon";
        if (hour >= 17 && hour < 21) return "Good evening";
        return "Good night";
    }

    /** Weather older than three hours counts as offline. */
    static boolean weatherFresh(long updatedAtMs, long nowMs) {
        return updatedAtMs > 0 && nowMs - updatedAtMs <= 3 * 60 * 60 * 1000L && updatedAtMs - nowMs < 10 * 60 * 1000L;
    }

    /** India's CPCB AQI words: Good to 50, Satisfactory to 100, Moderate to 200, Poor to 300, Very poor to 400. */
    static String aqiLabel(int aqi) {
        if (aqi <= 50) return "Good";
        if (aqi <= 100) return "Satisfactory";
        if (aqi <= 200) return "Moderate";
        if (aqi <= 300) return "Poor";
        if (aqi <= 400) return "Very poor";
        return "Severe";
    }

    /** The AQI chip's colour: CPCB's own band colours. */
    static int aqiColour(int aqi) {
        if (aqi <= 50) return 0xFF00B050;
        if (aqi <= 100) return 0xFF92D050;
        if (aqi <= 200) return 0xFFFFFF00;
        if (aqi <= 300) return 0xFFFF9900;
        if (aqi <= 400) return 0xFFFF0000;
        return 0xFFC00000;
    }

    /** Dark text on the light bands (Good to Poor), white on Very poor and Severe. */
    static int aqiTextColour(int aqi) {
        return aqi <= 300 ? 0xFF0B1220 : 0xFFFFFFFF;
    }
}
