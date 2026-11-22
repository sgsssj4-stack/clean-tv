package com.poppy.scoreboard;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns Android's usage events into sittings: one session runs from an app coming to the front until another app
 * does, the screen goes off or the TV shuts down. An app that pauses and comes back within {@link #MERGE_GAP_MS}
 * (a new screen inside the same app, a dialog) stays one session. Pure, so it is unit-tested.
 */
final class Sessions {
    static final int RESUMED = 1, PAUSED = 2, STOPPED = 23;
    static final int SCREEN_OFF = 16, KEYGUARD = 17, SHUTDOWN = 26;
    static final long MERGE_GAP_MS = 60_000L;
    /** Sittings shorter than this are a pass-through (the TV skipping past an app), not a play. */
    static final long MIN_MS = 5_000L;

    private Sessions() {}

    static final class Event {
        final String pkg;
        final int type;
        final long time;

        Event(String pkg, int type, long time) {
            this.pkg = pkg;
            this.type = type;
            this.time = time;
        }
    }

    static final class Session {
        final long start, end;

        Session(long start, long end) {
            this.start = start;
            this.end = end;
        }

        long length() {
            return end - start;
        }
    }

    /** Sessions per package, oldest first. A session still open at the last event ends at {@code now}. */
    static Map<String, List<Session>> build(List<Event> events, long now) {
        Map<String, List<Session>> out = new HashMap<>();
        String current = null;
        long start = 0, pausedAt = -1;
        for (Event e : events) {
            switch (e.type) {
                case RESUMED:
                    if (e.pkg == null) break;
                    if (e.pkg.equals(current)) {
                        if (pausedAt >= 0 && e.time - pausedAt > MERGE_GAP_MS) {
                            add(out, current, start, pausedAt);
                            start = e.time;
                        }
                    } else {
                        if (current != null) add(out, current, start, pausedAt >= 0 ? pausedAt : e.time);
                        current = e.pkg;
                        start = e.time;
                    }
                    pausedAt = -1;
                    break;
                case PAUSED:
                    // STOPPED is ignored: it always follows a PAUSED, and a splash screen's late STOPPED can arrive
                    // after the app's main screen has already resumed.
                    if (current != null && current.equals(e.pkg) && pausedAt < 0) pausedAt = e.time;
                    break;
                case SCREEN_OFF:
                case KEYGUARD:
                case SHUTDOWN:
                    if (current != null) add(out, current, start, pausedAt >= 0 ? pausedAt : e.time);
                    current = null;
                    pausedAt = -1;
                    break;
                default:
                    break;
            }
        }
        if (current != null) add(out, current, start, pausedAt >= 0 ? pausedAt : Math.max(start, now));
        return out;
    }

    private static void add(Map<String, List<Session>> out, String pkg, long start, long end) {
        if (end - start < MIN_MS) return;
        List<Session> list = out.get(pkg);
        if (list == null) out.put(pkg, list = new ArrayList<>());
        list.add(new Session(start, end));
    }
}
