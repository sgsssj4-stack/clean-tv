package com.gathin.tvhome;

import android.animation.ArgbEvaluator;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.View;
import android.widget.ImageView;

import java.io.File;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * The clock screensaver's scene (layout dream_clock): the thin clock and date on black over a slow ambient
 * glow. Every minute the clock drifts to a new spot and the glow shifts colour, so nothing burns in; a
 * ClockRefresh keeps its TextClocks current where Android would not.
 * Shared by ClockDream (the system screensaver) and ClockActivity (Home left idle).
 */
final class ClockScene {

    private static final long MOVE_MS = 60_000L;
    /** The glow's colour change: 16 steps over 8 s. Animated per frame it redrew the full-screen gradient ~480 times. */
    private static final int GLOW_STEPS = 16;
    private static final long GLOW_STEP_MS = 500L;
    /** Deep, low-brightness glow colours; the glow is drawn at partial alpha on black. */
    private static final int[] PALETTE = {0xFF2B5CFF, 0xFF00A39A, 0xFF8A3FFC, 0xFFE0661B, 0xFFD6336C};

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Random random = new Random();
    private final View content;
    private final ClockRefresh clockRefresh;
    private final ImageView ambient;
    private final android.widget.TextView dreamHour;
    private final android.widget.TextView dreamMinute;
    private final android.widget.TextView dreamAmPm;
    private final GradientDrawable glow;
    private int colorIndex;
    private int glowColor;
    /** clock_wallpaper or clock_glow false: something else (a picture, or plain black) replaced the coloured glow. */
    private boolean wallpaperShown;
    /** clock_wallpaper: the picture start() decodes, its darkening, and the resources to draw it with. */
    private File wallpaperFile;
    private int wallpaperShade;
    private Resources wallpaperRes;
    /** One number per decode request: a result whose number is no longer the current one is dropped. Main thread only. */
    private int wallpaperGeneration;
    /** clock_wallpaper with a collection: the pictures, how long each lasts, and how the next one is picked. */
    private List<File> rotation;
    private long rotationMs;
    private long shownMs;
    private String order;
    private Map<String, Set<String>> moods;
    private String latitude, longitude;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            moveClock();
            shiftGlow();
            advanceWallpaper();
            handler.postDelayed(this, MOVE_MS);
        }
    };

    private final Runnable minuteTick = new Runnable() {
        @Override
        public void run() {
            updateTime(true);
            long delay = 60_000L - (System.currentTimeMillis() % 60_000L) + 50L;
            handler.postDelayed(this, delay);
        }
    };

    /** root is the inflated dream_clock layout. */
    ClockScene(View root) {
        content = root.findViewById(R.id.dreamContent);
        clockRefresh = new ClockRefresh(content);
        ambient = root.findViewById(R.id.dreamAmbient);
        dreamHour = root.findViewById(R.id.dreamHour);
        dreamMinute = root.findViewById(R.id.dreamMinute);
        dreamAmPm = root.findViewById(R.id.dreamAmPm);
        glow = new GradientDrawable();
        glow.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        ambient.setBackground(glow);
        ambient.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) ->
                glow.setGradientRadius((r - l) * 0.55f));
        colorIndex = random.nextInt(PALETTE.length);
        setGlow(PALETTE[colorIndex]);
        setupDiwaliAndVikram(root);
    }

    private void setupDiwaliAndVikram(View root) {
        android.widget.TextView dreamGreeting = root.findViewById(R.id.dreamGreeting);
        android.widget.TextView dreamVikramDate = root.findViewById(R.id.dreamVikramDate);
        try {
            Config cfg = Config.load(root.getContext());
            java.time.LocalDate today = java.time.LocalDate.now();
            boolean festive = cfg.festival != null && cfg.festival.active(today);
            if (dreamGreeting != null) {
                String g = festive ? cfg.festival.greetingText(root.getResources()) : "";
                if (g.isEmpty() && festive) g = "HAPPY DIWALI";
                if (!g.isEmpty()) {
                    dreamGreeting.setText(g.toUpperCase(java.util.Locale.ROOT));
                    dreamGreeting.setVisibility(View.VISIBLE);
                    int accent = cfg.festival.accentColor();
                    if (accent != 0) dreamGreeting.setTextColor(accent);
                } else if (!cfg.greetingName.isEmpty()) {
                    int hour = java.time.LocalTime.now().getHour();
                    String timeGreeting;
                    if (hour < 5) timeGreeting = "GOOD NIGHT";
                    else if (hour < 12) timeGreeting = "GOOD MORNING";
                    else if (hour < 17) timeGreeting = "GOOD AFTERNOON";
                    else if (hour < 22) timeGreeting = "GOOD EVENING";
                    else timeGreeting = "GOOD NIGHT";
                    dreamGreeting.setText(timeGreeting + ", " + cfg.greetingName.toUpperCase(java.util.Locale.ROOT));
                    dreamGreeting.setVisibility(View.VISIBLE);
                }
            }
            if (dreamVikramDate != null) {
                dreamVikramDate.setText(VikramSamvat.today());
                dreamVikramDate.setVisibility(View.VISIBLE);
            }
        } catch (Exception ignored) {
        }
    }

    void start() {
        content.setAlpha(0f);
        updateTime(false);
        content.post(() -> {
            placeClock();
            content.animate().alpha(1f).setDuration(1200).start();
        });
        handler.removeCallbacks(tick);
        handler.postDelayed(tick, MOVE_MS);
        handler.removeCallbacks(minuteTick);
        long delay = 60_000L - (System.currentTimeMillis() % 60_000L) + 50L;
        handler.postDelayed(minuteTick, delay);
        clockRefresh.start();
        if (wallpaperFile != null && !wallpaperShown) loadWallpaper();
    }

    void stop() {
        handler.removeCallbacksAndMessages(null);   // the tick, minuteTick and any glow steps still queued
        clockRefresh.stop();
        content.animate().cancel();
        wallpaperGeneration++;   // a decode still running must not attach its picture to a stopped scene
    }

    private void updateTime(boolean animate) {
        if (dreamHour == null || dreamMinute == null) return;
        Context ctx = content.getContext();
        boolean is24 = android.text.format.DateFormat.is24HourFormat(ctx);
        java.time.LocalTime now = java.time.LocalTime.now();
        int hour = now.getHour();
        int minute = now.getMinute();
        String hStr;
        String ampmStr = "";
        if (is24) {
            hStr = String.format(java.util.Locale.ROOT, "%02d", hour);
        } else {
            int h12 = hour % 12;
            if (h12 == 0) h12 = 12;
            hStr = String.valueOf(h12);
            ampmStr = hour < 12 ? "AM" : "PM";
        }
        String mStr = String.format(java.util.Locale.ROOT, "%02d", minute);

        if (!animate) {
            dreamHour.setText(hStr);
            dreamMinute.setText(mStr);
            if (dreamAmPm != null) {
                dreamAmPm.setText(ampmStr);
                dreamAmPm.setVisibility(ampmStr.isEmpty() ? View.GONE : View.VISIBLE);
            }
        } else {
            if (!hStr.equals(dreamHour.getText().toString())) {
                flipView(dreamHour, hStr);
            }
            if (!mStr.equals(dreamMinute.getText().toString())) {
                flipView(dreamMinute, mStr);
            }
            if (dreamAmPm != null && !ampmStr.equals(dreamAmPm.getText().toString())) {
                flipView(dreamAmPm, ampmStr);
            }
        }
    }

    private void flipView(final android.widget.TextView tv, final String nextText) {
        if (tv == null) return;
        float density = tv.getResources().getDisplayMetrics().density;
        tv.setCameraDistance(12000 * density);
        tv.animate()
                .rotationX(-90f)
                .setDuration(220)
                .setInterpolator(new android.view.animation.AccelerateInterpolator())
                .withEndAction(() -> {
                    tv.setText(nextText);
                    tv.setRotationX(90f);
                    tv.animate()
                            .rotationX(0f)
                            .setDuration(220)
                            .setInterpolator(new android.view.animation.DecelerateInterpolator())
                            .start();
                })
                .start();
    }

    /**
     * clock_wallpaper: put the picture Home is showing behind the clock, darkened by clock_wallpaper_dim, in
     * place of the glow, and carry a collection's rotation on while the screensaver is up. Decoded off the main
     * thread at screen size in RGB_565 (4 MB at 1080p), like Home's own; until then, or if it cannot be read,
     * the glow stays.
     * Call before start(), which starts the decode (again after a stop() that dropped an unfinished one).
     */
    void useHomeWallpaper(Context ctx) {
        Config cfg;
        try {
            cfg = Config.load(ctx);
        } catch (RuntimeException e) {
            return;
        }
        if (!cfg.clockWallpaper) {
            // clock_glow false: plain black behind the clock (OLED); the clock itself still moves every minute.
            if (!cfg.clockGlow) {
                wallpaperShown = true;   // also stops the glow's colour cycle
                ambient.setBackground(null);
            }
            return;
        }
        File ext = ctx.getExternalFilesDir(null);
        File screensaversDir = ext != null ? new File(ext, "screensavers") : null;
        File screensaverSingle = ext != null ? new File(ext, "screensaver.jpg") : null;
        // A festival season shows its own collection behind the clock too (cfg.wallpapers is that collection);
        // screensavers/ and screensaver.jpg are the everyday clock pictures.
        boolean seasonal = cfg.season != null && !cfg.wallpapers.isEmpty();
        if (seasonal) {
            screensaversDir = null;
            screensaverSingle = null;
        }
        // Everyday: every wallpaper, screensavers/ and today's Home pictures together, except a season's own
        // pictures, which wait for their dates (a season shows only its collection, above).
        List<File> list = new ArrayList<>();
        if (screensaversDir != null && screensaversDir.isDirectory()) {
            java.util.Set<String> seasonalNames = Seasons.seasonalNames(ext);
            java.util.Set<String> names = new java.util.HashSet<>();
            File[] files = screensaversDir.listFiles();
            if (files != null) {
                java.util.Arrays.sort(files, (a, b) -> a.getName().compareTo(b.getName()));
                for (File f : files) {
                    String n = f.getName().toLowerCase(java.util.Locale.ROOT);
                    if ((n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png") || n.endsWith(".webp"))
                            && !Seasons.isSeasonal(seasonalNames, f.getName())) {
                        list.add(f);
                        names.add(f.getName());
                    }
                }
            }
            for (File f : cfg.wallpapers) {
                boolean dup = false;
                for (String n : names) if (f.getName().equals(n) || f.getName().endsWith("_" + n)) dup = true;
                if (!dup && !list.contains(f)) list.add(f);
            }
        }
        if (!list.isEmpty()) {
            rotation = list;
            android.content.SharedPreferences prefs = ctx.getSharedPreferences("screensaver_rotation", Context.MODE_PRIVATE);
            String prev = prefs.getString("image", "");
            File next = (rotation.size() > 1 && !prev.isEmpty())
                    ? WallpaperRotation.pick(rotation, prev, "sequential", random)
                    : rotation.get(0);
            wallpaperFile = next;
            wallpaperShown = false;
            prefs.edit().putString("image", next.getName()).apply();
            rotationMs = cfg.wallpaperRotationMinutes > 0 ? cfg.wallpaperRotationMinutes * 60_000L : 10 * 60_000L;
            order = cfg.wallpaperOrder;
        } else if (screensaverSingle != null && screensaverSingle.exists()) {
            wallpaperFile = screensaverSingle;
            rotation = null;
        } else if (cfg.wallpapers.isEmpty()) {
            wallpaperFile = null;
        } else {
            // Start on the picture Home is showing. WallpaperPlayer records it under the same
            // preferences and the screensaver runs in the same process, so this is just a read.
            rotation = new ArrayList<>(cfg.wallpapers);
            String shown = ctx.getSharedPreferences("wallpaper_rotation", Context.MODE_PRIVATE)
                    .getString("image", "");
            File current = WallpaperRotation.find(rotation, shown);
            wallpaperFile = current != null ? current : rotation.get(0);
            // Home only rotates while it is visible, so the screensaver carries the rotation on
            // itself; without this it holds one picture for as long as it is up.
            rotationMs = cfg.wallpaperRotationMinutes * 60_000L;
            order = cfg.wallpaperOrder;
            moods = cfg.wallpaperMoods;
            latitude = moods.isEmpty() ? "" : ctx.getString(R.string.latitude);
            longitude = moods.isEmpty() ? "" : ctx.getString(R.string.longitude);
        }
        wallpaperShade = (cfg.clockWallpaperDim * 255 / 100) << 24;
        wallpaperRes = ctx.getResources();
    }

    private void loadWallpaper() {
        File file = wallpaperFile;
        int shade = wallpaperShade;
        Resources res = wallpaperRes;
        DisplayMetrics dm = res.getDisplayMetrics();
        int w = dm.widthPixels, h = dm.heightPixels;
        int generation = ++wallpaperGeneration;
        new Thread(() -> {
            Bitmap bmp = decode(file, w, h);
            if (bmp == null) return;
            handler.post(() -> {
                if (generation != wallpaperGeneration || wallpaperShown) {
                    bmp.recycle();   // stopped meanwhile (or already shown): this picture was never attached
                    return;
                }
                wallpaperShown = true;
                // As the image, not the background: a background is stretched to the view, so a picture of
                // another shape than the screen would be distorted. centerCrop scales and crops it like Home.
                ambient.setBackground(null);
                ambient.setImageDrawable(new LayerDrawable(new Drawable[]{
                        new BitmapDrawable(res, bmp), new ColorDrawable(shade)}));
            });
        }, "clock-wallpaper").start();
    }

    /** WallpaperPlayer's decode: sampled and scaled down to cover the screen, never bigger than 4K. */
    private static Bitmap decode(File file, int screenW, int screenH) {
        try {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getPath(), options);
            int width = options.outWidth, height = options.outHeight;
            if (width <= 0 || height <= 0) return null;
            int sample = WallpaperSize.sampleSize(width, height, screenW, screenH);
            if (!WallpaperSize.accepted(width / sample, height / sample)) return null;
            options.inJustDecodeBounds = false;
            options.inSampleSize = sample;
            int[] scale = WallpaperSize.coverScale(width / sample, height / sample, screenW, screenH);
            if (scale != null) {
                options.inScaled = true;
                options.inDensity = scale[0];
                options.inTargetDensity = scale[1];
            }
            options.inPreferredConfig = Bitmap.Config.RGB_565;
            return BitmapFactory.decodeFile(file.getPath(), options);
        } catch (Exception | OutOfMemoryError failure) {
            return null;
        }
    }

    /** Step to the next picture once its turn is up; the tick already runs every minute. */
    private void advanceWallpaper() {
        if (rotation == null || rotationMs <= 0 || rotation.size() < 2) return;
        shownMs += MOVE_MS;
        if (shownMs < rotationMs) return;
        shownMs = 0;
        // The same pick as Home's, so a shuffled collection stays shuffled and wallpaper_moods still
        // decides what may show at this hour.
        File next = WallpaperRotation.pick(playable(), wallpaperFile.getName(), order, random);
        if (next.equals(wallpaperFile)) return;   // a mood group of one
        wallpaperFile = next;
        wallpaperShown = false;
        loadWallpaper();
    }

    /** WallpaperPlayer's filter: the pictures wallpaper_moods allows at this hour, or all of them. */
    private List<File> playable() {
        if (moods.isEmpty()) return rotation;
        LocalDateTime now = LocalDateTime.now();
        int[] sun = WallpaperMood.sun(now.toLocalDate(), latitude, longitude, ZoneId.systemDefault());
        return WallpaperMood.select(rotation, moods,
                WallpaperMood.timeOfDay(now.getHour() * 60 + now.getMinute(), sun[0], sun[1]));
    }

    private void moveClock() {
        content.animate().alpha(0f).setDuration(900).withLayer().withEndAction(() -> {
            placeClock();
            content.animate().alpha(1f).setDuration(1200).withLayer().start();
        }).start();
    }

    /** Apple TV style: centered presentation with subtle drift to prevent burn-in on OLED/LED displays. */
    private void placeClock() {
        View parent = (View) content.getParent();
        if (parent == null) return;
        int maxX = Math.max(0, parent.getWidth() - content.getWidth());
        int maxY = Math.max(0, parent.getHeight() - content.getHeight());
        float cx = maxX * 0.5f + (random.nextFloat() - 0.5f) * 40f;
        // Shift clock up so it sits cleanly in the sky above the silhouette
        float cy = 110f + (random.nextFloat() - 0.5f) * 20f;
        float x = Math.max(0, Math.min(maxX, cx));
        float y = Math.max(0, Math.min(maxY, cy));
        content.setX(x);
        content.setY(y);
        if (parent.getWidth() > 0 && parent.getHeight() > 0) {
            glow.setGradientCenter((x + content.getWidth() / 2f) / parent.getWidth(),
                    (y + content.getHeight() / 2f) / parent.getHeight());
        }
    }

    private void shiftGlow() {
        if (wallpaperShown) return;
        int from = glowColor;
        colorIndex = (colorIndex + 1) % PALETTE.length;
        int to = PALETTE[colorIndex];
        ArgbEvaluator mix = new ArgbEvaluator();
        for (int i = 1; i <= GLOW_STEPS; i++) {
            float f = (float) i / GLOW_STEPS;
            handler.postDelayed(() -> setGlow((int) mix.evaluate(f, from, to)), i * GLOW_STEP_MS);
        }
    }

    private void setGlow(int color) {
        glowColor = color;
        int c = color & 0x00FFFFFF;
        glow.setColors(new int[]{0x40000000 | c, 0x14000000 | c, 0x00000000 | c});
    }
}
