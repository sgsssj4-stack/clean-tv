package com.gathin.tvhome.foryou;

import android.content.ComponentName;
import android.content.Context;
import android.graphics.Bitmap;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.service.notification.NotificationListenerService;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Notification access, used only to see the TV's media sessions: what is playing, in which app, how far in. Each
 * title goes to {@link History} (with its artwork when the app gives one), so For you can show what was watched in
 * apps that publish nothing to Continue watching. Notifications themselves are never read.
 *
 * Grant once: {@code cmd notification allow_listener com.gathin.tvhome.foryou/.NowPlayingListener}.
 */
public final class NowPlayingListener extends NotificationListenerService {
    private static final String TAG = "NowPlaying";

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<MediaController, MediaController.Callback> watched = new HashMap<>();
    private MediaSessionManager sessions;
    private final MediaSessionManager.OnActiveSessionsChangedListener changed = this::track;

    @Override
    public void onListenerConnected() {
        sessions = getSystemService(MediaSessionManager.class);
        ComponentName me = new ComponentName(this, NowPlayingListener.class);
        try {
            sessions.addOnActiveSessionsChangedListener(changed, me, main);
            track(sessions.getActiveSessions(me));
        } catch (SecurityException e) {
            Log.w(TAG, "no notification access");
        }
    }

    @Override
    public void onListenerDisconnected() {
        if (sessions != null) sessions.removeOnActiveSessionsChangedListener(changed);
        for (Map.Entry<MediaController, MediaController.Callback> e : watched.entrySet()) {
            e.getKey().unregisterCallback(e.getValue());
        }
        watched.clear();
    }

    private void track(List<MediaController> controllers) {
        List<MediaController> gone = new ArrayList<>(watched.keySet());
        if (controllers != null) {
            for (MediaController c : controllers) {
                boolean known = false;
                for (MediaController w : watched.keySet()) {
                    if (w.getSessionToken().equals(c.getSessionToken())) {
                        known = true;
                        gone.remove(w);
                    }
                }
                if (known || getPackageName().equals(c.getPackageName())) continue;
                MediaController.Callback cb = new MediaController.Callback() {
                    @Override
                    public void onPlaybackStateChanged(PlaybackState state) {
                        record(c);
                    }

                    @Override
                    public void onMetadataChanged(MediaMetadata metadata) {
                        record(c);
                    }

                    @Override
                    public void onSessionDestroyed() {
                        record(c);
                    }
                };
                c.registerCallback(cb, main);
                watched.put(c, cb);
                record(c);
            }
        }
        for (MediaController c : gone) {
            record(c);
            MediaController.Callback cb = watched.remove(c);
            if (cb != null) c.unregisterCallback(cb);
        }
    }

    /** Playing or paused with a title: note it (and save its artwork once). */
    private void record(MediaController c) {
        PlaybackState state = c.getPlaybackState();
        MediaMetadata m = c.getMetadata();
        if (state == null || m == null) return;
        int s = state.getState();
        if (s != PlaybackState.STATE_PLAYING && s != PlaybackState.STATE_PAUSED) return;
        String title = text(m, MediaMetadata.METADATA_KEY_DISPLAY_TITLE, MediaMetadata.METADATA_KEY_TITLE);
        String subtitle = text(m, MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, MediaMetadata.METADATA_KEY_ARTIST);
        long duration = m.getLong(MediaMetadata.METADATA_KEY_DURATION);
        long position = state.getPosition();
        if (s == PlaybackState.STATE_PLAYING && state.getLastPositionUpdateTime() > 0) {
            position += (long) ((android.os.SystemClock.elapsedRealtime() - state.getLastPositionUpdateTime())
                    * state.getPlaybackSpeed());
        }
        String pkg = c.getPackageName();
        if (!History.worthKeeping(pkg, title, duration)) return;
        Context context = getApplicationContext();
        String poster = History.posterName(pkg, title);
        File file = new File(new File(context.getFilesDir(), "posters"), poster);
        if (!file.isFile()) {
            Bitmap art = m.getBitmap(MediaMetadata.METADATA_KEY_ART);
            if (art == null) art = m.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
            if (art == null) art = m.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
            if (art != null) save(art, file);
        }
        String artUri = text(m, MediaMetadata.METADATA_KEY_ART_URI, MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI);
        History.note(context, pkg, title, subtitle, position, duration, file.isFile() ? poster : "", artUri);
    }

    private static String text(MediaMetadata m, String first, String second) {
        CharSequence t = m.getText(first);
        if (t == null || t.toString().trim().isEmpty()) t = m.getText(second);
        return t == null ? "" : t.toString().trim();
    }

    private static void save(Bitmap art, File file) {
        File dir = file.getParentFile();
        if (dir == null || (!dir.isDirectory() && !dir.mkdirs())) return;
        File temp = new File(file.getPath() + ".part");
        try (FileOutputStream out = new FileOutputStream(temp)) {
            art.compress(Bitmap.CompressFormat.JPEG, 85, out);
        } catch (Exception e) {
            temp.delete();
            return;
        }
        if (!temp.renameTo(file)) temp.delete();
    }
}
