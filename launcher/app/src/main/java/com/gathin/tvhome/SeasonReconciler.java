package com.gathin.tvhome;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.List;

/**
 * Brings the shown collection in line with today's season. Safe to call as often as anyone likes (Home's
 * onCreate and onResume, the clock, boot, a time or time-zone change, the midnight handler, the daily alarm):
 * when the season is the one in season-state.json it only re-arms the alarm.
 *
 * On a change it keeps the rotation's place per collection, so Christmas starts where it stopped last year and
 * everyday resumes on the picture it was showing, then writes season-state.json atomically (temp file, rename).
 */
final class SeasonReconciler {
    static final String STATE_FILE = "season-state.json";
    static final String ACTION_DAILY = "com.gathin.tvhome.action.SEASON_DAILY";
    private static final String POSITIONS = "season_positions";
    private static final String ROTATION = "wallpaper_rotation";

    private SeasonReconciler() {}

    /** Result of one reconcile: the active season (null for everyday) and whether it changed. */
    static final class Result {
        final Seasons.Season season;
        final String collection;
        final boolean changed;

        Result(Seasons.Season season, String collection, boolean changed) {
            this.season = season;
            this.collection = collection;
            this.changed = changed;
        }
    }

    static synchronized Result reconcile(Context context) {
        Context app = context.getApplicationContext() != null ? context.getApplicationContext() : context;
        File dir = Config.dir(app);
        scheduleDaily(app);
        if (!Seasons.enabled(dir)) return new Result(null, "", false);
        LocalDate today = Seasons.today(app);
        List<Seasons.Season> seasons = Seasons.load(dir);
        Seasons.Season season = Seasons.activeOn(seasons, today);
        String collection = season == null ? Seasons.EVERYDAY : season.collection;
        File stateFile = new File(dir, STATE_FILE);
        String previous = readCollection(stateFile);
        if (collection.equals(previous)) return new Result(season, collection, false);

        SharedPreferences rotation = app.getSharedPreferences(ROTATION, Context.MODE_PRIVATE);
        SharedPreferences positions = app.getSharedPreferences(POSITIONS, Context.MODE_PRIVATE);
        SharedPreferences.Editor saved = positions.edit();
        if (previous != null && !previous.isEmpty()) {
            String image = rotation.getString("image", null);
            if (image != null) {
                saved.putString(previous + ".image", image).putLong(previous + ".changed", rotation.getLong("changed", 0));
            }
        }
        saved.commit();
        // Point the player's state at the new collection and its remembered picture; WallpaperPlayer then
        // resumes it instead of resetting to the first picture.
        List<File> images = Seasons.images(dir, collection);
        SharedPreferences.Editor edit = rotation.edit().putString("collection", WallpaperPlayer.collectionKey(images));
        String image = positions.getString(collection + ".image", null);
        if (image != null && WallpaperRotation.find(images, image) != null) {
            edit.putString("image", image).putLong("changed", positions.getLong(collection + ".changed", 0));
        } else {
            edit.remove("image").remove("changed");
        }
        edit.commit();
        writeState(stateFile, collection, season == null ? Seasons.EVERYDAY : season.name, today);
        return new Result(season, collection, true);
    }

    /** The collection season-state.json records, or null when there is none or it is unreadable. */
    static String readCollection(File stateFile) {
        if (!stateFile.isFile() || stateFile.length() > 16 * 1024) return null;
        try {
            return new JSONObject(new String(Files.readAllBytes(stateFile.toPath()), StandardCharsets.UTF_8))
                    .optString("collection", null);
        } catch (Exception e) {
            return null;
        }
    }

    private static void writeState(File stateFile, String collection, String season, LocalDate day) {
        File temp = new File(stateFile.getParentFile(), STATE_FILE + ".tmp");
        try {
            JSONObject o = new JSONObject();
            o.put("collection", collection);
            o.put("season", season);
            o.put("since", day.toString());
            o.put("written", System.currentTimeMillis());
            try (FileOutputStream out = new FileOutputStream(temp)) {
                out.write(o.toString().getBytes(StandardCharsets.UTF_8));
                out.getFD().sync();
            }
            if (!temp.renameTo(stateFile)) temp.delete();
        } catch (Exception e) {
            temp.delete();
        }
    }

    /** Milliseconds from now to the next IST midnight plus {@code extraMs}. */
    static long untilNextMidnight(long extraMs) {
        ZonedDateTime now = ZonedDateTime.now(Seasons.ZONE);
        ZonedDateTime midnight = now.toLocalDate().plusDays(1).atStartOfDay(Seasons.ZONE);
        return Math.max(1000, midnight.toInstant().toEpochMilli() - now.toInstant().toEpochMilli() + extraMs);
    }

    /** One inexact alarm a day at about 00:01 IST; re-arming replaces the previous one. */
    static void scheduleDaily(Context context) {
        AlarmManager alarms = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarms == null) return;
        Intent intent = new Intent(context, SeasonReceiver.class).setAction(ACTION_DAILY);
        PendingIntent pending = PendingIntent.getBroadcast(context, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        try {
            alarms.set(AlarmManager.RTC, System.currentTimeMillis() + untilNextMidnight(60_000L), pending);
        } catch (RuntimeException ignored) {
            // a firmware without alarms only loses the overnight switch; Home still reconciles on start
        }
    }
}
