package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The seasonal wallpaper schedule from {@code files/seasons.json}: a list of date windows, each naming a
 * collection under {@code files/collections/}. A day outside every window shows {@link #EVERYDAY}.
 *
 * <pre>
 * [{"name":"christmas","from":"2026-12-23","to":"2026-12-30","collection":"christmas",
 *   "greeting":"Merry Christmas","accent":"#E0463C","rotation_minutes":10,"order":"sequential"}]
 * </pre>
 *
 * The choice of season is {@link #activeOn}, a pure function of the list and the day, so it is unit-tested.
 */
final class Seasons {
    static final String EVERYDAY = "everyday";
    static final String COLLECTIONS = "collections";
    static final String FILE_NAME = "seasons.json";
    /** Days are India's: the TV is in Delhi, and a season must not start at 05:30 because the clock is in UTC. */
    static final ZoneId ZONE = ZoneId.of("Asia/Kolkata");

    private static final String TEST_PREFS = "season_test";
    private static final long TEST_DATE_LIFETIME_MS = 24 * 60 * 60 * 1000L;
    private static final Pattern NAME = Pattern.compile("[a-z0-9][a-z0-9_-]{0,39}");

    private Seasons() {}

    /** One window of the schedule. Both ends are inclusive. */
    static final class Season {
        final String name;
        final LocalDate from;
        final LocalDate to;
        final String collection;
        final String greeting;
        /** 0xAARRGGBB, or 0 for none. */
        final int accent;
        /** 0 keeps the theme's wallpaper_rotation_minutes. */
        final int rotationMinutes;
        /** "" keeps the theme's wallpaper_order. */
        final String order;

        Season(String name, LocalDate from, LocalDate to, String collection, String greeting, int accent,
               int rotationMinutes, String order) {
            this.name = name;
            this.from = from;
            this.to = to;
            this.collection = collection;
            this.greeting = greeting == null ? "" : greeting;
            this.accent = accent;
            this.rotationMinutes = rotationMinutes;
            this.order = order == null ? "" : order;
        }

        boolean covers(LocalDate day) {
            return !day.isBefore(from) && !day.isAfter(to);
        }
    }

    /**
     * The season showing on {@code day}: the first window in the list that covers it, or null for everyday.
     * A window whose collection is "everyday" counts as no season.
     */
    static Season activeOn(List<Season> seasons, LocalDate day) {
        if (seasons == null || day == null) return null;
        for (Season s : seasons) {
            if (s.covers(day)) return EVERYDAY.equals(s.collection) ? null : s;
        }
        return null;
    }

    /** The collection name for {@code day}: the active season's, or "everyday". */
    static String collectionOn(List<Season> seasons, LocalDate day) {
        Season s = activeOn(seasons, day);
        return s == null ? EVERYDAY : s.collection;
    }

    /** The schedule written by the dashboard build, used when seasons.json is missing; dates are this year's festivals. */
    static List<Season> defaults() {
        List<Season> list = new ArrayList<>();
        list.add(new Season("christmas", LocalDate.of(2026, 12, 23), LocalDate.of(2026, 12, 30), "christmas",
                "Merry Christmas", 0xFFE0463C, 0, ""));
        list.add(new Season("new-year", LocalDate.of(2026, 12, 31), LocalDate.of(2027, 1, 7), "new-year",
                "Happy New Year", 0xFFFFD166, 0, ""));
        list.add(new Season("holi", LocalDate.of(2027, 3, 19), LocalDate.of(2027, 3, 24), "holi",
                "Happy Holi", 0xFFFF4FA3, 0, ""));
        list.add(new Season("dussehra", LocalDate.of(2027, 10, 6), LocalDate.of(2027, 10, 11), "dussehra",
                "Happy Dussehra", 0xFFFF9933, 0, ""));
        list.add(new Season("diwali", LocalDate.of(2027, 10, 25), LocalDate.of(2027, 11, 2), "diwali",
                "Happy Diwali", 0xFFFFB23C, 0, ""));
        return list;
    }

    /**
     * Parses seasons.json. Entries with a bad name, an unreadable or reversed date range or a bad collection name
     * are skipped, never guessed; an unreadable file gives an empty list (everyday all year).
     */
    static List<Season> parse(String json) {
        List<Season> list = new ArrayList<>();
        if (json == null) return list;
        try {
            String text = json.trim();
            JSONArray array = text.startsWith("{") ? new JSONObject(text).optJSONArray("seasons") : new JSONArray(text);
            if (array == null) return list;
            for (int i = 0; i < array.length() && i < 100; i++) {
                JSONObject o = array.optJSONObject(i);
                if (o == null) continue;
                String name = o.optString("name", "").trim().toLowerCase(Locale.ROOT);
                String collection = o.optString("collection", name).trim().toLowerCase(Locale.ROOT);
                if (!NAME.matcher(name).matches() || !NAME.matcher(collection).matches()) continue;
                LocalDate from, to;
                try {
                    from = LocalDate.parse(o.optString("from", ""));
                    to = LocalDate.parse(o.optString("to", ""));
                } catch (DateTimeParseException e) {
                    continue;
                }
                if (to.isBefore(from)) continue;
                int minutes = Math.max(0, Math.min(1440, o.optInt("rotation_minutes", 0)));
                String order = o.optString("order", "");
                if (!order.isEmpty() && !WallpaperRotation.validOrder(order)) order = "";
                String greeting = Cards.cleanLabel(o.optString("greeting", ""), "");
                list.add(new Season(name, from, to, collection, greeting,
                        Festival.parseColor(o.optString("accent", "")), minutes, order));
            }
        } catch (Exception e) {
            return new ArrayList<>();
        }
        return list;
    }

    /** seasons.json from the theme directory, or {@link #defaults()} when there is none. */
    static List<Season> load(File dir) {
        if (dir == null) return Collections.emptyList();
        File file = new File(dir, FILE_NAME);
        if (!file.isFile()) return defaults();
        try {
            if (file.length() > 256 * 1024) return Collections.emptyList();
            return parse(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    /**
     * Whether the theme uses collections at all. Without the folder (or a collections.json) Home keeps the plain
     * wallpapers/ rotation exactly as before.
     */
    static boolean enabled(File dir) {
        return dir != null && (new File(dir, COLLECTIONS).isDirectory() || new File(dir, TAGS_FILE).isFile());
    }

    // ---- collections.json: one picture in several collections without copies ------------------------------

    /**
     * {@code files/collections.json} tags pictures already on the TV with extra collections, so one file can show
     * in several seasons without a copy in each folder:
     * <pre>{"wallpapers/01_ayodhya_twilight.jpg": ["everyday", "diwali", "dussehra"]}</pre>
     * Paths are relative to the theme directory and must be wallpapers/&lt;image&gt; or
     * collections/&lt;name&gt;/&lt;image&gt;; anything else is ignored.
     */
    static final String TAGS_FILE = "collections.json";
    private static final Pattern TAG_PATH = Pattern.compile(
            "(wallpapers|collections/[a-z0-9][a-z0-9_-]{0,39})/[A-Za-z0-9_.-]+\\.(jpg|jpeg|png|webp)",
            Pattern.CASE_INSENSITIVE);

    /** collection name to the relative paths tagged with it, in file order. Pure, for tests. */
    static java.util.Map<String, List<String>> parseTags(String json) {
        java.util.Map<String, List<String>> tags = new java.util.LinkedHashMap<>();
        if (json == null) return tags;
        try {
            JSONObject o = new JSONObject(json.trim());
            java.util.Iterator<String> keys = o.keys();
            int n = 0;
            while (keys.hasNext() && n++ < 500) {
                String path = keys.next();
                if (!TAG_PATH.matcher(path).matches() || path.contains("..")) continue;
                JSONArray names = o.optJSONArray(path);
                if (names == null) continue;
                for (int i = 0; i < names.length() && i < 20; i++) {
                    String c = names.optString(i, "").trim().toLowerCase(Locale.ROOT);
                    if (!NAME.matcher(c).matches()) continue;
                    List<String> list = tags.get(c);
                    if (list == null) tags.put(c, list = new ArrayList<>());
                    if (!list.contains(path)) list.add(path);
                }
            }
        } catch (Exception e) {
            return new java.util.LinkedHashMap<>();
        }
        return tags;
    }

    /**
     * The pictures of one collection: the images in collections/&lt;name&gt;/ plus every picture collections.json
     * tags with it, without duplicates, sorted by file name, at most 100 like wallpapers/.
     */
    static List<File> images(File dir, String collection) {
        List<File> out = new ArrayList<>();
        if (dir == null || collection == null) return out;
        java.util.Set<String> seen = new java.util.HashSet<>();
        // A festival's picture copied into everyday would show all year: everyday leaves it to its season.
        java.util.Set<String> seasonal = EVERYDAY.equals(collection) ? seasonalNames(dir) : java.util.Collections.emptySet();
        for (File f : WallpaperRotation.images(collectionDir(dir, collection))) {
            if (!seasonal.contains(f.getName()) && seen.add(f.getName())) out.add(f);
        }
        File tagsFile = new File(dir, TAGS_FILE);
        if (tagsFile.isFile() && tagsFile.length() < 256 * 1024) {
            try {
                List<String> tagged = parseTags(new String(Files.readAllBytes(tagsFile.toPath()),
                        StandardCharsets.UTF_8)).get(collection);
                if (tagged != null) {
                    for (String path : tagged) {
                        File f = new File(dir, path);
                        if (f.isFile() && f.length() > 0 && f.length() <= 10L * 1024 * 1024 && seen.add(f.getName())) {
                            out.add(f);
                        }
                    }
                }
            } catch (Exception ignored) {
                // an unreadable tag file leaves only the folder's own pictures
            }
        }
        out.sort((a, b) -> a.getName().compareTo(b.getName()));
        return out.size() > 100 ? new ArrayList<>(out.subList(0, 100)) : out;
    }

    /** File names in every collection but everyday: the pictures that belong to a season's dates. */
    static java.util.Set<String> seasonalNames(File dir) {
        java.util.Set<String> out = new java.util.HashSet<>();
        File[] folders = dir == null ? null : new File(dir, COLLECTIONS).listFiles(File::isDirectory);
        if (folders == null) return out;
        for (File folder : folders) {
            if (EVERYDAY.equals(folder.getName())) continue;
            for (File f : WallpaperRotation.images(folder)) out.add(f.getName());
        }
        return out;
    }

    /** Whether a picture (by name) is a season's: the same name, or a collection copy named prefix_name. */
    static boolean isSeasonal(java.util.Set<String> seasonalNames, String name) {
        if (seasonalNames.contains(name)) return true;
        for (String s : seasonalNames) if (s.endsWith("_" + name)) return true;
        return false;
    }

    static File collectionDir(File dir, String collection) {
        return new File(new File(dir, COLLECTIONS), collection);
    }

    // ---- today, or the test date set over adb -----------------------------------------------------------

    /** Today in India, or the test date while one is set and less than a day old. */
    static LocalDate today(Context context) {
        LocalDate test = testDate(context);
        return test != null ? test : LocalDate.now(ZONE);
    }

    /** The test date, or null when none is set or it has expired (it is then cleared). */
    static LocalDate testDate(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(TEST_PREFS, Context.MODE_PRIVATE);
        String value = prefs.getString("date", null);
        if (value == null) return null;
        long set = prefs.getLong("set", 0);
        long now = System.currentTimeMillis();
        if (now - set > TEST_DATE_LIFETIME_MS || now < set) {
            prefs.edit().clear().apply();
            return null;
        }
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException e) {
            prefs.edit().clear().apply();
            return null;
        }
    }

    /** Sets ("YYYY-MM-DD") or clears ("clear") the test date; returns false for anything else. */
    static boolean setTestDate(Context context, String arg) {
        SharedPreferences prefs = context.getSharedPreferences(TEST_PREFS, Context.MODE_PRIVATE);
        if (arg == null) return false;
        String value = arg.trim();
        if ("clear".equalsIgnoreCase(value)) {
            prefs.edit().clear().commit();
            return true;
        }
        try {
            LocalDate.parse(value);
        } catch (DateTimeParseException e) {
            return false;
        }
        prefs.edit().putString("date", value).putLong("set", System.currentTimeMillis()).commit();
        return true;
    }
}
