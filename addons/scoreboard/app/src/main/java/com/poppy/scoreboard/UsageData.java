package com.poppy.scoreboard;

import android.app.usage.UsageEvents;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.Drawable;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Reads six months of usage from the system and shapes it for the screen. Call {@link #load} off the main thread. */
final class UsageData {

    static final class App {
        String pkg;
        String label;
        Drawable icon;
        /** The icon's main colour, brightened, for its usage bar. */
        int colour = 0xFF8AB4FF;
        boolean streaming;
        long totalMs;
        List<Sessions.Session> sessions = new ArrayList<>();

        int plays() {
            return sessions.size();
        }

        long lastUsed() {
            return sessions.isEmpty() ? 0 : sessions.get(sessions.size() - 1).end;
        }
    }

    static final class Result {
        final List<App> apps = new ArrayList<>();
        long since;
        /** When the oldest session the system still keeps began; history before it has only totals. */
        long historySince;
        long now;
    }

    private UsageData() {}

    static Result load(Context context) {
        Result result = new Result();
        UsageStatsManager usm = (UsageStatsManager) context.getSystemService(Context.USAGE_STATS_SERVICE);
        PackageManager pm = context.getPackageManager();
        Calendar cal = Calendar.getInstance();
        long now = cal.getTimeInMillis();
        cal.add(Calendar.MONTH, -6);
        long since = cal.getTimeInMillis();
        result.since = since;
        result.now = now;
        if (usm == null) return result;

        Set<String> hidden = hiddenPackages(context);

        List<Sessions.Event> events = new ArrayList<>();
        UsageEvents ue = usm.queryEvents(since, now);
        if (ue != null) {
            UsageEvents.Event e = new UsageEvents.Event();
            while (ue.hasNextEvent()) {
                ue.getNextEvent(e);
                int type = e.getEventType();
                if (type == Sessions.RESUMED || type == Sessions.PAUSED || type == Sessions.STOPPED
                        || type == Sessions.SCREEN_OFF || type == Sessions.KEYGUARD || type == Sessions.SHUTDOWN) {
                    events.add(new Sessions.Event(e.getPackageName(), type, e.getTimeStamp()));
                }
            }
        }
        Map<String, List<Sessions.Session>> sessions = Sessions.build(events, now);

        Map<String, Long> totals = new HashMap<>();
        List<UsageStats> stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_BEST, since, now);
        if (stats != null) {
            for (UsageStats s : stats) {
                Long t = totals.get(s.getPackageName());
                totals.put(s.getPackageName(), (t == null ? 0 : t) + s.getTotalTimeInForeground());
            }
        }

        Set<String> pkgs = new HashSet<>(sessions.keySet());
        pkgs.addAll(totals.keySet());
        long historySince = now;
        for (String pkg : pkgs) {
            if (pkg == null || hidden.contains(pkg)) continue;
            List<Sessions.Session> list = sessions.get(pkg);
            long sessionMs = 0;
            if (list != null) for (Sessions.Session s : list) sessionMs += s.length();
            Long total = totals.get(pkg);
            long ms = Math.max(total == null ? 0 : total, sessionMs);
            if (ms < 60_000L && (list == null || list.isEmpty())) continue;

            ApplicationInfo info;
            try {
                info = pm.getApplicationInfo(pkg, 0);
            } catch (PackageManager.NameNotFoundException e) {
                continue; // uninstalled since
            }
            if (pm.getLeanbackLaunchIntentForPackage(pkg) == null && pm.getLaunchIntentForPackage(pkg) == null) {
                continue; // a service or overlay, not something the family opens
            }
            App app = new App();
            app.pkg = pkg;
            app.label = String.valueOf(pm.getApplicationLabel(info));
            app.icon = loadIcon(pm, pkg, info);
            app.colour = iconColour(app.icon);
            app.streaming = isStreaming(pkg);
            app.totalMs = ms;
            if (list != null) {
                app.sessions = list;
                if (!list.isEmpty()) historySince = Math.min(historySince, list.get(0).start);
            }
            result.apps.add(app);
        }
        result.historySince = historySince;
        result.apps.sort((a, b) -> a.totalMs != b.totalMs ? Long.compare(b.totalMs, a.totalMs)
                : Integer.compare(b.plays(), a.plays()));
        return result;
    }

    /** Home screens, this app and the system's own surfaces never count as "watching". */
    private static Set<String> hiddenPackages(Context context) {
        Set<String> out = new HashSet<>();
        out.add(context.getPackageName());
        out.add("android");
        out.add("com.android.systemui");
        out.add("com.gathin.tvhome");
        out.add("com.google.android.apps.tv.launcherx");
        out.add("com.google.android.tvlauncher");
        out.add("com.google.android.leanbacklauncher");
        // whichever home screen is current; Settings' fallback home is not one, so it stays listed
        Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        ResolveInfo r = context.getPackageManager().resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY);
        if (r != null && r.activityInfo != null && !"android".equals(r.activityInfo.packageName)) {
            out.add(r.activityInfo.packageName);
        }
        return out;
    }

    private static Drawable loadIcon(PackageManager pm, String pkg, ApplicationInfo info) {
        try {
            Drawable d = pm.getApplicationIcon(info);
            if (d != null) return d;
        } catch (Exception ignored) {
            // fall through to the banner
        }
        try {
            return pm.getApplicationBanner(pkg);
        } catch (Exception e) {
            return null;
        }
    }

    /** The most saturated common colour of the icon, lifted so it glows on dark glass. */
    static int iconColour(Drawable icon) {
        if (icon == null) return 0xFF8AB4FF;
        try {
            Bitmap b = Bitmap.createBitmap(12, 12, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(b);
            icon.setBounds(0, 0, 12, 12);
            icon.draw(c);
            float bestScore = -1;
            float[] best = null, hsv = new float[3];
            for (int y = 0; y < 12; y++) {
                for (int x = 0; x < 12; x++) {
                    int p = b.getPixel(x, y);
                    if (Color.alpha(p) < 128) continue;
                    Color.colorToHSV(p, hsv);
                    float score = hsv[1] * (0.3f + hsv[2]);
                    if (score > bestScore) {
                        bestScore = score;
                        best = hsv.clone();
                    }
                }
            }
            b.recycle();
            if (best == null || best[1] < 0.2f) return 0xFFB8C4D9;
            best[1] = Math.min(1f, best[1] * 0.85f + 0.1f);
            best[2] = Math.max(0.85f, best[2]);
            return Color.HSVToColor(best);
        } catch (Exception e) {
            return 0xFF8AB4FF;
        }
    }

    /** Video and music apps, Indian OTTs included. */
    static boolean isStreaming(String pkg) {
        String p = pkg.toLowerCase(Locale.ROOT);
        if (p.contains("setting") || p.contains("installer") || p.contains("vending") || p.contains("launcher")
                || p.contains("weather") || p.contains("wireguard") || p.contains("talkback")) {
            return false;
        }
        String[] media = {"netflix", "amazonvideo", "primevideo", "avod", "disney", "hotstar", "jio", "sonyliv",
                "zee", "graymatrix", "airtel", "mxtech", "videoplayer", "youtube", "plex", "peacock", "hulu", "hbo",
                "wbd", "paramount", "spotify", "twitch", "tubi", "pluto", "appletv", "apple.atve", "airscreen",
                "espn", "crunchyroll", "discovery", "kodi", "vlc", "gaana", "wynk", "jiosaavn", "erosnow",
                "voot", "altbalaji", "hoichoi", "sunnxt", "shemaroo", "mubi", "lionsgate", "fancode",
                "dangal", "tataplay", "dishtv", "yupptv", "zee5", "music", "tv.videos", "android.videos"};
        for (String m : media) if (p.contains(m)) return true;
        return false;
    }
}
