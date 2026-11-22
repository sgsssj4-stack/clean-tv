package com.gathin.tvhome;

import android.app.Activity;
import android.app.Dialog;
import android.app.SearchManager;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.ContentProviderClient;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognizerIntent;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Home's dashboard layout (config.json {@code home_layout: "dashboard"}): frosted panels over the wallpaper, laid
 * out as fractions of the screen.
 *
 * <pre>
 *  [ Search ] [ Ask Jarvis ]
 *  clock + weather | For you (scrolling row)
 *                  | Arcade (games)
 *  dock: every app, a loop (Right on the last app goes to the first)
 * </pre>
 *
 * The launcher itself stays offline. Online data comes from companion add-ons over content providers: weather
 * ({@link #WEATHER_URI}) and the For you feed ({@link #FEED_URI}, the rows apps publish to Android TV's Continue
 * watching and channels). Without the feed, For you falls back to recommendations.json. The glass is a once-per-wallpaper blur ({@link GlassBlur}).
 */
final class DashboardHome extends FrameLayout implements WallpaperPlayer.Listener {
    static final String WEATHER_URI = "content://com.gathin.tvhome.weather.provider/current";
    static final String FEED_URI = "content://com.gathin.tvhome.foryou.provider/titles";
    private static final String YOUTUBE = "com.google.android.youtube.tv";
    private static final String PLAY_STORE = "com.android.vending";
    private static final String TV_SETTINGS = "com.android.tv.settings";
    /** The glass tint at full opacity; Launcher settings' glass choice (cfg.dockOpacity, 30-100 %) scales it and the blurred glass behind it. */
    private static final int TINT_ALPHA = 64;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("h:mm", Locale.ENGLISH);
    private static final DateTimeFormatter AMPM = DateTimeFormatter.ofPattern("a", Locale.ENGLISH);
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("EEEE, d MMMM", Locale.ENGLISH);

    private final Activity activity;
    private final Config cfg;
    private final File dir;
    private final float dp;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ExecutorService feedWorker = Executors.newSingleThreadExecutor();
    private final ExecutorService posterWorker = Executors.newFixedThreadPool(2);
    private final Typeface light, regular, medium;
    private final int tint;
    /** How solid the blurred wallpaper inside each panel is, 0-255: the glass opacity choice, so 30 % shows the real wallpaper through. */
    private final int glassAlpha;
    private final boolean reducedMotion;

    private final List<GlassPanel> panels = new ArrayList<>();
    private final List<View> rowBar = new ArrayList<>(), rowTop = new ArrayList<>(), rowMiddle = new ArrayList<>(),
            rowDock = new ArrayList<>();
    private final Map<String, Integer> colours = new HashMap<>();
    /** Where focus was in each row when it left, so Up then Down comes back to the same tile. */
    private final Map<List<View>, View> lastInRow = new java.util.IdentityHashMap<>();
    private Bitmap blur;
    private File blurFile;
    private boolean destroyed;

    private TextView sunriseText, sunsetText;
    private SunLine sunLine;
    private View inputsPill;
    private LinearLayout aqiBox;
    private double sunLat = Double.NaN, sunLon = Double.NaN;
    private String sunKey = "";
    private TextView greeting, clock, ampm, date, weatherTemp, weatherSky, weatherPlace, weatherMore, aqiChip,
            jarvisLabel;
    private LinearLayout forYouRow, arcadeRow, dockRow;
    private TextView forYouCount, forYouEmpty, arcadeTitle;
    /** Poster size in dp: larger when For You has the whole right column (no games, so no Arcade). */
    /** For you card size in pixels, set from the well's height once it is laid out (0 before then). */
    private int posterW, posterH;
    private List<Item> items = new ArrayList<>();
    private long feedLoadedAt;

    /** A For you title: from the feed add-on (poster is a content:// URI) or from recommendations.json (a file). */
    static final class Item {
        final String title, subtitle, pkg, intentUri, poster, kind;
        final File posterFile;
        /** A rating such as "7.4" from the feed add-on, or "". */
        String imdb = "";
        /** Who gave the rating: "IMDb" (the default) or "TMDB". */
        String ratingLabel = "IMDb";
        /** 0..1000, or -1 when the app did not say. */
        final int progress;

        Item(String title, String subtitle, String pkg, String intentUri, String poster, File posterFile, int progress,
             String kind) {
            this.title = title;
            this.subtitle = subtitle;
            this.pkg = pkg;
            this.intentUri = intentUri;
            this.poster = poster;
            this.posterFile = posterFile;
            this.progress = progress;
            this.kind = kind;
        }
    }

    private final Runnable minuteTick = new Runnable() {
        @Override
        public void run() {
            updateClock();
            long now = System.currentTimeMillis();
            handler.postDelayed(this, 60_000L - now % 60_000L + 50);
        }
    };

    DashboardHome(Activity activity, Config cfg) {
        super(activity);
        this.activity = activity;
        this.cfg = cfg;
        this.dir = Config.dir(activity);
        this.dp = activity.getResources().getDisplayMetrics().density;
        this.reducedMotion = cfg.reducedMotion;
        light = font(R.string.font_light);
        regular = font(R.string.font_regular);
        medium = font(R.string.font_medium);
        int accent = cfg.festival.active(Seasons.today(activity)) ? cfg.festival.accentColor() : 0;
        int opacity = Math.max(30, Math.min(100, cfg.dockOpacity));
        int alpha = Math.round(TINT_ALPHA * opacity / 100f);
        glassAlpha = Math.round(255 * opacity / 100f);
        int base = Color.argb(alpha, 52, 84, 130);
        tint = accent == 0 ? base : blend(base, Color.argb(alpha, Color.red(accent), Color.green(accent), Color.blue(accent)), 0.35f);
        setClipChildren(false);
        setClipToPadding(false);
        setFocusable(false);
        setDescendantFocusability(FOCUS_AFTER_DESCENDANTS);
        build();
    }

    // ---- lifecycle --------------------------------------------------------------------------------------

    // ---- idle (Settings > Fade when idle, on by default): after 20 s without a key everything fades to 10 % so the wallpaper shows; any key brings it back

    static final long IDLE_FADE_MS = 20_000L;
    static final float IDLE_ALPHA = 0.10f;
    private boolean faded;
    private int swallowUp = -1;
    private final Runnable idleFade = new Runnable() {
        @Override
        public void run() {
            if (destroyed) return;
            if (!activity.hasWindowFocus()) {   // a dialog or the search is up: wait
                handler.postDelayed(this, fadeMs());
                return;
            }
            faded = true;
            animate().cancel();
            animate().alpha(IDLE_ALPHA).setDuration(reducedMotion ? 0 : 900).start();
        }
    };

    private long fadeMs() {
        return cfg.fadeSeconds * 1000L;
    }

    private void restartIdle() {
        handler.removeCallbacks(idleFade);
        if (!destroyed && cfg.fadeWhenIdle) handler.postDelayed(idleFade, fadeMs());
    }

    private void wake() {
        restartIdle();
        if (!faded && getAlpha() == 1f) return;
        faded = false;
        animate().cancel();
        animate().alpha(1f).setDuration(reducedMotion ? 0 : 250).start();
    }

    /**
     * Called first for every key Home sees. Faded, the key only brings Home back (its UP too), except volume and
     * mute, which still work; otherwise it restarts the 20 s.
     */
    boolean wakeKey(KeyEvent event) {
        int code = event.getKeyCode();
        boolean volume = code == KeyEvent.KEYCODE_VOLUME_UP || code == KeyEvent.KEYCODE_VOLUME_DOWN
                || code == KeyEvent.KEYCODE_VOLUME_MUTE;
        if (event.getAction() == KeyEvent.ACTION_UP && code == swallowUp) {
            swallowUp = -1;
            return true;
        }
        if (faded && !volume && event.getAction() == KeyEvent.ACTION_DOWN) {
            if (event.getRepeatCount() == 0) swallowUp = code;
            wake();
            return true;
        }
        if (volume) {
            if (faded) restartIdle();   // stays faded: the picture is still what is being looked at
            return false;
        }
        wake();
        return false;
    }

    void onResume() {
        wake();
        handler.removeCallbacks(minuteTick);
        minuteTick.run();
        loadFeed(false);
        loadWeather();
    }

    void onPause() {
        handler.removeCallbacks(minuteTick);
        handler.removeCallbacks(idleFade);
        faded = false;
        swallowUp = -1;
        animate().cancel();
        setAlpha(1f);
    }

    void destroy() {
        destroyed = true;
        handler.removeCallbacksAndMessages(null);
        worker.shutdownNow();
        feedWorker.shutdownNow();
        posterWorker.shutdownNow();
    }

    /** The first dock app, or whatever can take focus: Home opens with focus on the dock. */
    void focusFirst() {
        wake();
        for (List<View> row : rows()) {
            if (!row.isEmpty() && row == rowDock) {
                row.get(0).requestFocus();
                return;
            }
        }
        for (List<View> row : rows()) {
            if (!row.isEmpty()) {
                row.get(0).requestFocus();
                return;
            }
        }
    }

    @Override
    public void onWallpaperShown(File file) {
        if (file == null || file.equals(blurFile) || destroyed) return;
        blurFile = file;
        int w = getResources().getDisplayMetrics().widthPixels, h = getResources().getDisplayMetrics().heightPixels;
        worker.execute(() -> {
            Bitmap made = GlassBlur.make(file, w, h, 6);
            post(() -> {
                if (destroyed || !file.equals(blurFile)) {
                    if (made != null) made.recycle();
                    return;
                }
                Bitmap old = blur;
                blur = made;
                for (GlassPanel p : panels) p.invalidate();
                if (old != null) postDelayed(old::recycle, 500);
            });
        });
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getKeyCode() == KeyEvent.KEYCODE_MENU) {
            if (event.getAction() == KeyEvent.ACTION_UP) openHomeSettings();
            return true;
        }
        if (event.getKeyCode() == KeyEvent.KEYCODE_SEARCH) {
            if (event.getAction() == KeyEvent.ACTION_UP) openSearch();
            return true;
        }
        // Move focus here, before the scroll views see the key: at a row's end they would scroll their padding
        // instead of letting the dock loop round.
        int direction = direction(event.getKeyCode());
        View focused = activity.getCurrentFocus();
        if (direction != 0 && focused != null && event.getAction() == KeyEvent.ACTION_DOWN) {
            View target = navigate(focused, direction);
            if (target != null) {
                if (target != focused) target.requestFocus(direction);
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    // ---- layout ------------------------------------------------------------------------------------------

    private void build() {
        // Top bar: search and the assistant, as pills
        GlassPanel search = panel(0.045f, 0.040f, 0.272f, 0.105f);
        LinearLayout s = pillRow(search);
        s.addView(new GlyphView(getContext(), GlyphView.SEARCH), new LinearLayout.LayoutParams(dp(18), dp(18)));
        TextView sl = text(s, 15, regular, 0xD9FFFFFF);
        sl.setText("Search apps and shows");
        ((LinearLayout.LayoutParams) sl.getLayoutParams()).leftMargin = dp(10);
        makeFocusable(search, 1.03f);
        search.setOnClickListener(v -> openSearch());
        rowBar.add(search);

        GlassPanel jarvis = panel(0.282f, 0.040f, 0.432f, 0.105f);
        LinearLayout j = pillRow(jarvis);
        j.addView(new OrbView(getContext()), new LinearLayout.LayoutParams(dp(22), dp(22)));
        jarvisLabel = text(j, 15, regular, 0xD9FFFFFF);
        jarvisLabel.setText("Ask Jarvis");
        ((LinearLayout.LayoutParams) jarvisLabel.getLayoutParams()).leftMargin = dp(10);
        makeFocusable(jarvis, 1.03f);
        jarvis.setOnClickListener(v -> askJarvis());
        rowBar.add(jarvis);

        // The TV's own: input sources (HDMI, AV, Live TV) and system Settings, at the right of the bar
        GlassPanel inputs = panel(0.690f, 0.040f, 0.812f, 0.105f);
        LinearLayout in = pillRow(inputs);
        in.addView(new GlyphView(getContext(), GlyphView.INPUT), new LinearLayout.LayoutParams(dp(18), dp(18)));
        TextView inl = text(in, 15, regular, 0xD9FFFFFF);
        inl.setText("Inputs");
        ((LinearLayout.LayoutParams) inl.getLayoutParams()).leftMargin = dp(10);
        makeFocusable(inputs, 1.03f);
        inputs.setOnClickListener(v -> {
            if (activity instanceof MainActivity) ((MainActivity) activity).showInputsFrom(v);
        });
        rowBar.add(inputs);
        inputsPill = inputs;

        // One Settings: this launcher's look and layout, with TV settings (picture, sound, network) inside it
        GlassPanel settings = panel(0.822f, 0.040f, 0.955f, 0.105f);
        LinearLayout st = pillRow(settings);
        st.addView(new GlyphView(getContext(), GlyphView.GEAR), new LinearLayout.LayoutParams(dp(18), dp(18)));
        TextView stl = text(st, 15, regular, 0xD9FFFFFF);
        stl.setText("Settings");
        ((LinearLayout.LayoutParams) stl.getLayoutParams()).leftMargin = dp(10);
        makeFocusable(settings, 1.03f);
        settings.setOnClickListener(v -> openHomeSettings());
        rowBar.add(settings);

        // Clock and weather
        GlassPanel clockPanel = panel(0.045f, 0.135f, 0.345f, 0.700f);
        LinearLayout c = column(clockPanel, 26, 22);
        greeting = text(c, 21, light, 0xF2FFFFFF);
        LinearLayout clockLine = new LinearLayout(getContext());
        clockLine.setOrientation(LinearLayout.HORIZONTAL);
        clockLine.setGravity(Gravity.BOTTOM);
        c.addView(clockLine, wrap(2, 0));
        clock = text(clockLine, 74, light, Color.WHITE);
        clock.setIncludeFontPadding(false);
        ampm = text(clockLine, 22, regular, 0xD9FFFFFF);
        ((LinearLayout.LayoutParams) ampm.getLayoutParams()).leftMargin = dp(8);
        ((LinearLayout.LayoutParams) ampm.getLayoutParams()).bottomMargin = dp(10);
        date = text(c, 17, regular, 0xCCFFFFFF);
        c.addView(new View(getContext()), new LinearLayout.LayoutParams(1, 0, 1f));
        // The line under the date: sunrise at one end, sunset at the other, the sun travelling between them
        LinearLayout sunRow = new LinearLayout(getContext());
        sunRow.setOrientation(LinearLayout.HORIZONTAL);
        sunRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams srl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        srl.bottomMargin = dp(12);
        c.addView(sunRow, srl);
        sunriseText = text(sunRow, 13, regular, 0xB3FFFFFF);
        sunriseText.setCompoundDrawablesRelative(new SunIcon(true, dp(22)), null, null, null);
        sunriseText.setCompoundDrawablePadding(dp(6));
        sunLine = new SunLine(getContext());
        LinearLayout.LayoutParams sll = new LinearLayout.LayoutParams(0, dp(16), 1f);
        sll.leftMargin = dp(10);
        sll.rightMargin = dp(10);
        sunRow.addView(sunLine, sll);
        sunsetText = text(sunRow, 13, regular, 0xB3FFFFFF);
        sunsetText.setCompoundDrawablesRelative(new SunIcon(false, dp(22)), null, null, null);
        sunsetText.setCompoundDrawablePadding(dp(6));
        updateSun();
        LinearLayout wx = new LinearLayout(getContext());
        wx.setOrientation(LinearLayout.HORIZONTAL);
        wx.setGravity(Gravity.CENTER_VERTICAL);
        c.addView(wx, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        weatherTemp = text(wx, 40, light, Color.WHITE);
        weatherTemp.setIncludeFontPadding(false);
        LinearLayout wc = new LinearLayout(getContext());
        wc.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams wcl = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        wcl.leftMargin = dp(10);
        wx.addView(wc, wcl);
        weatherSky = text(wc, 16, medium, Color.WHITE);
        weatherSky.setSingleLine(false);   // a long sky ("Thunderstorm with hail") wraps rather than hides
        weatherSky.setMaxLines(2);
        weatherPlace = text(wc, 13, regular, 0xB3FFFFFF);
        // AQI beside the temperature as a coloured pill; its band ("Very unhealthy air") leads the line below
        aqiBox = new LinearLayout(getContext());
        aqiBox.setOrientation(LinearLayout.VERTICAL);
        aqiBox.setGravity(Gravity.END);
        LinearLayout.LayoutParams abl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        abl.leftMargin = dp(4);
        wx.addView(aqiBox, abl);
        aqiChip = text(aqiBox, 14, medium, Color.WHITE);
        aqiChip.setPadding(dp(7), dp(2), dp(7), dp(3));
        aqiChip.setShadowLayer(0, 0, 0, 0);
        aqiBox.setVisibility(GONE);
        LinearLayout more = new LinearLayout(getContext());
        more.setOrientation(LinearLayout.HORIZONTAL);
        more.setGravity(Gravity.CENTER_VERTICAL);
        c.addView(more, wrap(10, 0));
        weatherMore = text(more, 13, regular, 0xB3FFFFFF);
        showWeatherOffline();

        // For you: the right column, tall cards; Arcade (when there are games) takes its lower part
        List<String> games = cfg.arcadeTile ? games() : new ArrayList<>();   // Arcade off: big posters, whole column
        GlassPanel forYou = panel(0.360f, 0.135f, 0.955f, games.isEmpty() ? 0.700f : 0.490f);
        LinearLayout f = column(forYou, 20, 14);
        LinearLayout fHead = header(f, "For you");
        forYouCount = text(fHead, 13, regular, 0x99FFFFFF);
        FrameLayout fBody = new DockWell(getContext());
        fBody.setClipChildren(false);
        LinearLayout.LayoutParams fbl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        fbl.topMargin = dp(2);
        f.addView(fBody, fbl);
        forYouRow = scrollRow(fBody, 4, 0);
        forYouEmpty = emptyText(fBody, "Loading what you were watching…");
        forYouEmpty.setVisibility(VISIBLE);
        fBody.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            // as tall as the well allows (room left for the focus zoom and glow), 3:5 like a tarot card
            int fit = Math.max(dp(120), (b - t) - dp(26));
            if (Math.abs(fit - posterH) > dp(2)) {
                posterH = fit;
                posterW = Math.round(fit * 2 / 3f);   // a film poster's 2:3
                if (!items.isEmpty()) post(this::fillForYou);
            }
        });

        // Dock: the chosen apps, centred, with room above and below for the focus lift and glow
        GlassPanel dock = panel(0.045f, 0.725f, 0.955f, 0.955f);
        FrameLayout dBody = new DockWell(getContext());
        FrameLayout.LayoutParams dbl = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        dbl.setMargins(dp(8), 0, dp(8), 0);
        dock.addView(dBody, dbl);
        dBody.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override
            public void getOutline(View view, android.graphics.Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), 24 * dp);
            }
        });
        dBody.setClipToOutline(true);
        dockRow = scrollRow(dBody, 4, 0);
        ((View) dockRow.getParent()).setFadingEdgeLength(dp(60));
        ((HorizontalScrollView) dockRow.getParent()).setFillViewport(true);
        dockRow.setGravity(Gravity.CENTER);

        if (!games.isEmpty()) buildArcade(games);
        fillDock();
        float below = 0.985f;
        if (cfg.renewalsTile) below = buildRenewals(below) + 0.025f;
        buildAllApps(below);
        getViewTreeObserver().addOnGlobalFocusChangeListener((oldFocus, newFocus) -> followFocus(newFocus));
    }

    private LinearLayout pillRow(GlassPanel pill) {
        LinearLayout l = new LinearLayout(getContext());
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        l.setPadding(dp(18), 0, dp(18), 0);
        pill.addView(l, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return l;
    }

    /** A panel's title on the left; the returned row takes a quiet note on the right. */
    private LinearLayout header(LinearLayout parent, String title) {
        LinearLayout h = new LinearLayout(getContext());
        h.setOrientation(LinearLayout.HORIZONTAL);
        h.setGravity(Gravity.CENTER_VERTICAL);
        parent.addView(h, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView t = text(h, 16, medium, Color.WHITE);
        t.setText(title);
        t.setLetterSpacing(0.02f);
        h.addView(new View(getContext()), new LinearLayout.LayoutParams(0, 1, 1f));
        return h;
    }

    // ---- For you -----------------------------------------------------------------------------------------

    /** The feed add-on first (it reads the TV's Continue watching rows, ~3 s); recommendations.json if it has none. */
    private void loadFeed(boolean force) {
        long now = System.currentTimeMillis();
        if (!force && now - feedLoadedAt < 5 * 60_000L && !items.isEmpty()) return;
        feedLoadedAt = now;
        feedWorker.execute(() -> {
            List<Item> found = queryFeed();
            if (found.isEmpty()) found = localTitles();
            List<Item> result = found;
            post(() -> {
                if (destroyed) return;
                items = result;
                fillForYou();
            });
        });
    }

    private List<Item> queryFeed() {
        List<Item> out = new ArrayList<>();
        ContentProviderClient client = null;
        try {
            Uri uri = Uri.parse(FEED_URI);
            client = getContext().getContentResolver().acquireUnstableContentProviderClient(uri);
            if (client == null) return out;
            try (Cursor cursor = client.query(uri, null, null, null, null)) {
                if (cursor == null) return out;
                int title = cursor.getColumnIndex("title"), sub = cursor.getColumnIndex("subtitle"),
                        pkg = cursor.getColumnIndex("package"), link = cursor.getColumnIndex("intent_uri"),
                        poster = cursor.getColumnIndex("poster"), progress = cursor.getColumnIndex("progress"),
                        kind = cursor.getColumnIndex("kind"), imdb = cursor.getColumnIndex("imdb"),
                        label = cursor.getColumnIndex("rating_label");
                if (title < 0 || pkg < 0) return out;
                while (cursor.moveToNext() && out.size() < 24) {
                    String t = Cards.cleanLabel(str(cursor, title), "");
                    String p = str(cursor, pkg);
                    if (t.isEmpty() || p.isEmpty()) continue;
                    Item item = new Item(t, Cards.cleanLabel(str(cursor, sub), ""), p, str(cursor, link), str(cursor, poster),
                            null, progress < 0 ? -1 : cursor.getInt(progress), str(cursor, kind));
                    String r = str(cursor, imdb);
                    if (r.matches("\\d{1,2}\\.\\d")) item.imdb = r;
                    if (label >= 0 && "TMDB".equals(str(cursor, label))) item.ratingLabel = "TMDB";
                    out.add(item);
                }
            }
        } catch (Exception e) {
            // no feed add-on, or it failed: the local list instead
        } finally {
            if (client != null) client.close();
        }
        return out;
    }

    private static String str(Cursor c, int column) {
        if (column < 0 || c.isNull(column)) return "";
        String s = c.getString(column);
        return s == null ? "" : s.trim();
    }

    private List<Item> localTitles() {
        List<Item> out = new ArrayList<>();
        for (DashboardData.Title t : DashboardData.forYou(DashboardData.load(dir), DashboardData.loadRatings(dir))) {
            File poster = t.poster.isEmpty() || dir == null ? null : new File(new File(dir, DashboardData.POSTERS), t.poster);
            out.add(new Item(t.title, t.rating, t.pkg, t.deeplink, "", poster, -1, "pick"));
        }
        return out;
    }

    private void fillForYou() {
        View focused = activity.getCurrentFocus();
        boolean hadFocus = focused != null && rowTop.contains(focused);
        forYouRow.removeAllViews();
        rowTop.clear();
        for (int i = 0; i < items.size(); i++) {
            Item it = items.get(i);
            View card = posterTile(it, posterW > 0 ? posterW : dp(132), posterH > 0 ? posterH : dp(220));
            card.setOnClickListener(v -> openItem(it));
            forYouRow.addView(card);
            rowTop.add(card);
        }
        forYouEmpty.setText("Nothing to continue yet");
        forYouEmpty.setVisibility(items.isEmpty() ? VISIBLE : GONE);
        forYouCount.setText(items.isEmpty() ? "" : items.size() + (items.size() == 1 ? " title" : " titles"));
        if (hadFocus) {
            if (!rowTop.isEmpty()) rowTop.get(0).requestFocus();
            else focusFirst();
        }
    }

    /**
     * A poster card, as on Apple TV: the picture fills the whole card edge to edge (a real portrait poster when
     * IMDb has one, else the app's art cropped to fit), badges at the top. A poster already carries its title, so
     * the title and app show on a dark fade only while the card is focused; art without a printed title (or no
     * art) keeps them showing. Focus lifts the card with a soft shadow and a glare, like the app tiles.
     */
    private View posterTile(Item it, int w, int h) {
        FrameLayout tile = new FrameLayout(getContext());
        tile.setClipChildren(false);
        tile.setClipToPadding(false);
        int colour = colourFor(it.pkg);
        int reach = dp(14);
        GlowView glow = new GlowView(getContext(), colour, reach, dp(14), true);
        glow.setAlpha(0f);
        FrameLayout.LayoutParams gl = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        gl.setMargins(-2 * reach, -2 * reach, -2 * reach, -2 * reach);   // room for the blur: no flat, cut-off edge
        tile.addView(glow, gl);
        FrameLayout art = new FrameLayout(getContext());
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{Color.argb(255, Color.red(colour) / 2, Color.green(colour) / 2, Color.blue(colour) / 2), 0xFF0B1220});
        bg.setCornerRadius(dp(14));
        art.setBackground(bg);
        art.setClipToOutline(true);
        TextView fallback = new TextView(getContext());
        fallback.setText(it.title);
        fallback.setTextColor(Color.WHITE);
        fallback.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        fallback.setTypeface(light);
        fallback.setGravity(Gravity.CENTER);
        fallback.setMaxLines(4);
        fallback.setEllipsize(TextUtils.TruncateAt.END);
        fallback.setPadding(dp(12), dp(12), dp(12), dp(56));
        art.addView(fallback, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        ImageView poster = new ImageView(getContext());
        poster.setScaleType(ImageView.ScaleType.CENTER_CROP);
        art.addView(poster, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        // a light veil at the top for the badges
        View top = new View(getContext());
        top.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0x66000000, 0x00000000}));
        art.addView(top, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44), Gravity.TOP));
        // the fade behind the words: the lower half, dark at the edge
        FrameLayout caption = new FrameLayout(getContext());
        caption.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x00000000, 0x99000000, 0xE6000000}));
        art.addView(caption, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.round(h * 0.5f), Gravity.BOTTOM));
        boolean small = w < dp(110);   // Arcade shares the column: small cards, fewer words
        // on a small card the IMDb badge wins the top edge; the progress line still says "continue"
        if (("continue".equals(it.kind) || "next".equals(it.kind)) && !(small && !it.imdb.isEmpty())) {
            TextView badge = new TextView(getContext());
            badge.setText("next".equals(it.kind) ? "UP NEXT" : "CONTINUE");
            badge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9);
            badge.setLetterSpacing(0.10f);
            badge.setTypeface(medium);
            badge.setTextColor(0xFF0B1220);
            badge.setPadding(dp(7), dp(2), dp(7), dp(3));
            GradientDrawable pill = new GradientDrawable();
            pill.setCornerRadius(dp(9));
            pill.setColor(0xF2FFFFFF);
            badge.setBackground(pill);
            FrameLayout.LayoutParams bl = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.START);
            bl.setMargins(dp(small ? 6 : 8), dp(small ? 6 : 8), 0, 0);
            art.addView(badge, bl);
        }
        if (!it.imdb.isEmpty()) {
            TextView rating = new TextView(getContext());
            boolean tmdb = "TMDB".equals(it.ratingLabel);
            rating.setText(it.ratingLabel + " " + it.imdb);
            rating.setTextSize(TypedValue.COMPLEX_UNIT_SP, small ? 9 : 10);
            rating.setTypeface(medium);
            rating.setTextColor(0xFF000000);
            rating.setPadding(dp(6), dp(1), dp(6), dp(2));
            GradientDrawable pill = new GradientDrawable();
            pill.setCornerRadius(dp(5));
            pill.setColor(tmdb ? 0xFF90CEA1 : 0xFFF5C518);   // TMDB green, IMDb yellow
            rating.setBackground(pill);
            FrameLayout.LayoutParams rl = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.END);
            rl.setMargins(0, dp(small ? 6 : 8), dp(small ? 6 : 8), 0);
            art.addView(rating, rl);
        }
        LinearLayout words = new LinearLayout(getContext());
        words.setOrientation(LinearLayout.VERTICAL);
        words.setPadding(dp(small ? 8 : 11), 0, dp(small ? 8 : 11), dp(it.progress > 0 ? (small ? 10 : 13) : (small ? 8 : 11)));
        caption.addView(words, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));
        TextView title = text(words, small ? 12 : h > w ? 15 : 13, medium, Color.WHITE);
        title.setText(it.title);
        title.setSingleLine(false);
        title.setMaxLines(2);
        title.setLineSpacing(0, 0.95f);
        TextView sub = text(words, small ? 10 : 11, regular, 0xB3FFFFFF);
        String app = appLabel(it.pkg);
        sub.setText(it.subtitle.isEmpty() || it.subtitle.equals(app) ? app : it.subtitle + " · " + app);
        ((LinearLayout.LayoutParams) sub.getLayoutParams()).topMargin = dp(2);
        if (it.progress > 0) {
            FrameLayout track = new FrameLayout(getContext());
            track.setBackgroundColor(0x40FFFFFF);
            View bar = new View(getContext());
            bar.setBackgroundColor(0xFFFF5A5F);
            track.addView(bar, new FrameLayout.LayoutParams(Math.max(dp(4), w * Math.min(1000, it.progress) / 1000),
                    ViewGroup.LayoutParams.MATCH_PARENT));
            art.addView(track, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3), Gravity.BOTTOM));
        }
        // the tvOS glare: light across the raised card from the top left, and a faint bright rim
        GradientDrawable glare = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0x38FFFFFF, 0x12FFFFFF, 0x00FFFFFF, 0x00FFFFFF});
        glare.setCornerRadius(dp(14));
        glare.setStroke(Math.max(1, Math.round(dp)), 0x33FFFFFF);
        glare.setAlpha(0);
        art.setForeground(glare);
        tile.addView(art, new FrameLayout.LayoutParams(w, h));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(w, h);
        lp.rightMargin = dp(18);
        lp.gravity = Gravity.CENTER_VERTICAL;
        tile.setLayoutParams(lp);
        tile.setFocusable(true);
        tile.setClickable(true);
        tile.setContentDescription(it.title + ", " + app + (it.imdb.isEmpty() ? "" : ", IMDb " + it.imdb));
        long ms = reducedMotion ? Motion.REDUCED_MS : 260;
        boolean[] cleanPoster = {false};   // set once a real portrait poster is showing
        tile.setOnFocusChangeListener((v, focused) -> {
            tile.animate().scaleX(focused ? 1.08f : 1f).scaleY(focused ? 1.08f : 1f).translationY(focused ? -dp(3) : 0f)
                    .setInterpolator(focused ? new android.view.animation.OvershootInterpolator(0.9f)
                            : new android.view.animation.DecelerateInterpolator())
                    .setDuration(ms).start();
            glow.animate().alpha(focused ? 1f : 0f).setDuration(ms).start();
            android.animation.ValueAnimator g = android.animation.ValueAnimator.ofInt(glare.getAlpha(), focused ? 255 : 0);
            g.setDuration(ms);
            g.addUpdateListener(a -> glare.setAlpha((int) a.getAnimatedValue()));
            g.start();
            if (cleanPoster[0]) caption.animate().alpha(focused ? 1f : 0f).setDuration(ms).start();
        });
        loadPoster(it, poster, fallback, w, h, tall -> {
            cleanPoster[0] = tall || small;   // a small card is too small for words over every picture
            if (cleanPoster[0] && !tile.isFocused()) caption.setAlpha(0f);
        });
        return tile;
    }

    /** Decodes a card's picture off the main thread; {@code shown} hears whether it was a portrait poster. */
    private void loadPoster(Item it, ImageView into, View fallback, int width, int height,
                            java.util.function.Consumer<Boolean> shown) {
        if (it.poster.isEmpty() && it.posterFile == null) return;
        boolean tallCard = height > width;
        posterWorker.execute(() -> {
            // a tall card crops a wide picture: decode wide enough to fill the card's height
            int want = tallCard ? Math.max(width, height * 16 / 9) : width;
            Bitmap b = it.posterFile != null ? decodePoster(it.posterFile, want) : decodeUri(it.poster, want);
            if (b == null) return;
            boolean tall = b.getHeight() >= b.getWidth() * 0.95f;
            post(() -> {
                if (destroyed) return;
                into.setImageBitmap(b);
                fallback.setVisibility(INVISIBLE);
                shown.accept(tallCard && tall);
            });
        });
    }

    private Bitmap decodeUri(String uri, int targetWidth) {
        if (!uri.startsWith("content://com.gathin.tvhome.foryou.provider/")) return null;
        try (InputStream in = getContext().getContentResolver().openInputStream(Uri.parse(uri))) {
            if (in == null) return null;
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buf = new byte[16 * 1024];
            for (int n; (n = in.read(buf)) > 0; ) {
                bytes.write(buf, 0, n);
                if (bytes.size() > 6 * 1024 * 1024) return null;
            }
            byte[] data = bytes.toByteArray();
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, o);
            if (o.outWidth <= 0) return null;
            int sample = 1;
            while (o.outWidth / (sample * 2) >= targetWidth) sample *= 2;
            o.inJustDecodeBounds = false;
            o.inSampleSize = sample;
            o.inPreferredConfig = Bitmap.Config.RGB_565;
            return BitmapFactory.decodeByteArray(data, 0, data.length, o);
        } catch (Exception | OutOfMemoryError e) {
            return null;
        }
    }

    /** Opens a title in its app: the app's own link (intent: or a plain URI), else the app's start screen. */
    private void openItem(Item it) {
        if (!it.intentUri.isEmpty()) {
            try {
                Intent i;
                if (it.intentUri.startsWith("intent:") || it.intentUri.contains("#Intent;")) {
                    i = Intent.parseUri(it.intentUri, Intent.URI_INTENT_SCHEME);
                } else {
                    i = new Intent(Intent.ACTION_VIEW, Uri.parse(it.intentUri));
                }
                i.setSelector(null);
                ComponentName cn = i.getComponent();
                if (cn != null && !cn.getPackageName().equals(it.pkg)) i.setComponent(null);
                if (i.getComponent() == null && (i.getPackage() == null || !i.getPackage().equals(it.pkg))) i.setPackage(it.pkg);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                activity.startActivity(i);
                return;
            } catch (Exception e) {
                // the app no longer takes that link: its start screen instead
            }
        }
        launchPackage(it.pkg);
    }

    // ---- Arcade -----------------------------------------------------------------------------------------

    /** The installed games. */
    private List<String> games() {
        List<String> games = new ArrayList<>();
        PackageManager pm = getContext().getPackageManager();
        for (String pkg : launchable()) {
            try {
                ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
                if (ai.category == ApplicationInfo.CATEGORY_GAME || (ai.flags & ApplicationInfo.FLAG_IS_GAME) != 0) games.add(pkg);
            } catch (PackageManager.NameNotFoundException ignored) {
                // gone
            }
        }
        return games;
    }

    /** The games in a panel under For You, above the dock (no games: no panel, For You takes the column). */
    private void buildArcade(List<String> games) {
        GlassPanel panel = panel(0.360f, 0.510f, 0.955f, 0.700f);
        LinearLayout a = column(panel, 20, 10);
        LinearLayout aHead = header(a, "Arcade");
        arcadeTitle = text(aHead, 13, regular, 0x99FFFFFF);
        arcadeTitle.setText(games.size() + (games.size() == 1 ? " game" : " games"));
        FrameLayout aBody = new DockWell(getContext());
        aBody.setClipChildren(false);
        a.addView(aBody, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        arcadeRow = scrollRow(aBody, 4, 0);
        // the panel is short: let the row draw past the well, up and down, so the raised tile and its shadow are
        // never cut flat (the row still clips at its left and right ends)
        int bleed = dp(16);
        View aScroll = (View) arcadeRow.getParent();
        FrameLayout.LayoutParams asl = (FrameLayout.LayoutParams) aScroll.getLayoutParams();
        asl.topMargin = -bleed;
        asl.bottomMargin = -bleed;
        aScroll.setPadding(aScroll.getPaddingLeft(), bleed, aScroll.getPaddingRight(), bleed);
        aBody.setClipChildren(false);
        ((DockWell) aBody).bleed = bleed;
        for (String pkg : games) {
            View tile = appTile(pkg, dp(90), dp(51), false);
            arcadeRow.addView(tile);
            rowMiddle.add(tile);
        }
        if (launchIntent(PLAY_STORE) != null) {
            View get = actionTile("Get games", GlyphView.PLUS, dp(90), dp(51), () -> openUri(
                    "market://search?q=games&c=apps", PLAY_STORE));
            arcadeRow.addView(get);
            rowMiddle.add(get);
        }
    }


    // ---- Jarvis -----------------------------------------------------------------------------------------

    private void askJarvis() {
        PackageManager pm = getContext().getPackageManager();
        Intent[] tries = {
                new Intent(Intent.ACTION_ASSIST),
                new Intent(RecognizerIntent.ACTION_VOICE_SEARCH_HANDS_FREE),
                new Intent(RecognizerIntent.ACTION_WEB_SEARCH),
        };
        for (Intent i : tries) {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (i.resolveActivity(pm) == null) continue;
            try {
                activity.startActivity(i);
                return;
            } catch (ActivityNotFoundException | SecurityException e) {
                // try the next one
            }
        }
        jarvisLabel.setText("No assistant");
        handler.postDelayed(() -> jarvisLabel.setText("Ask Jarvis"), 3000);
    }

    /** A static orb with a soft wave across it; no animation, so it costs nothing while Home sits there. */
    private static final class OrbView extends View {
        private final Paint orb = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint wave = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();

        OrbView(Context c) {
            super(c);
            wave.setStyle(Paint.Style.STROKE);
            wave.setStrokeCap(Paint.Cap.ROUND);
        }

        @Override
        protected void onSizeChanged(int w, int h, int ow, int oh) {
            float r = Math.min(w, h) / 2f;
            orb.setShader(new RadialGradient(w * 0.42f, h * 0.38f, r, new int[]{0xFFE8F4FF, 0xFF6FB6FF, 0xFF3A4FD8, 0x003A4FD8},
                    new float[]{0f, 0.35f, 0.8f, 1f}, Shader.TileMode.CLAMP));
            wave.setStrokeWidth(Math.max(2f, r * 0.1f));
            wave.setShader(new LinearGradient(0, 0, w, 0, 0x00FFFFFF, 0xE6FFFFFF, Shader.TileMode.MIRROR));
            path.reset();
            float mid = h * 0.5f;
            path.moveTo(w * 0.12f, mid);
            for (int i = 1; i <= 16; i++) {
                float x = w * 0.12f + (w * 0.76f) * i / 16f;
                double env = Math.sin(Math.PI * i / 16.0);
                path.lineTo(x, (float) (mid + Math.sin(i * 1.4) * r * 0.34 * env));
            }
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float r = Math.min(getWidth(), getHeight()) / 2f;
            canvas.drawCircle(getWidth() / 2f, getHeight() / 2f, r, orb);
            canvas.drawPath(path, wave);
        }
    }

    /** Line icons drawn in code: a magnifier, a plus, a microphone, a play triangle, a store bag, a globe. */
    static final class GlyphView extends View {
        static final int SEARCH = 0, PLUS = 1, MIC = 2, PLAY = 3, BAG = 4, GLOBE = 5, GEAR = 6, GRID = 7, INPUT = 8, SLIDERS = 9;
        private final int kind;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        private final Path path = new Path();

        GlyphView(Context c, int kind) {
            super(c);
            this.kind = kind;
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeJoin(Paint.Join.ROUND);
            p.setColor(0xE6FFFFFF);
        }

        void setColour(int c) {
            p.setColor(c);
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), s = Math.min(w, h);
            p.setStrokeWidth(Math.max(2f, s * 0.11f));
            float cx = w / 2f, cy = h / 2f;
            switch (kind) {
                case SEARCH:
                    c.drawCircle(cx - s * 0.08f, cy - s * 0.08f, s * 0.30f, p);
                    c.drawLine(cx + s * 0.14f, cy + s * 0.14f, cx + s * 0.40f, cy + s * 0.40f, p);
                    break;
                case PLUS:
                    c.drawLine(cx - s * 0.35f, cy, cx + s * 0.35f, cy, p);
                    c.drawLine(cx, cy - s * 0.35f, cx, cy + s * 0.35f, p);
                    break;
                case MIC:
                    r.set(cx - s * 0.16f, cy - s * 0.44f, cx + s * 0.16f, cy + s * 0.12f);
                    c.drawRoundRect(r, s * 0.16f, s * 0.16f, p);
                    r.set(cx - s * 0.32f, cy - s * 0.18f, cx + s * 0.32f, cy + s * 0.26f);
                    c.drawArc(r, 0, 180, false, p);
                    c.drawLine(cx, cy + s * 0.26f, cx, cy + s * 0.44f, p);
                    break;
                case PLAY:
                    path.reset();
                    path.moveTo(cx - s * 0.22f, cy - s * 0.32f);
                    path.lineTo(cx + s * 0.32f, cy);
                    path.lineTo(cx - s * 0.22f, cy + s * 0.32f);
                    path.close();
                    c.drawPath(path, p);
                    break;
                case BAG:
                    r.set(cx - s * 0.34f, cy - s * 0.16f, cx + s * 0.34f, cy + s * 0.42f);
                    c.drawRoundRect(r, s * 0.06f, s * 0.06f, p);
                    r.set(cx - s * 0.16f, cy - s * 0.42f, cx + s * 0.16f, cy - s * 0.04f);
                    c.drawArc(r, 180, 180, false, p);
                    break;
                case GEAR:
                    c.drawCircle(cx, cy, s * 0.15f, p);
                    c.drawCircle(cx, cy, s * 0.30f, p);
                    for (int k = 0; k < 8; k++) {
                        double a = Math.PI * k / 4;
                        c.drawLine(cx + (float) Math.cos(a) * s * 0.30f, cy + (float) Math.sin(a) * s * 0.30f,
                                cx + (float) Math.cos(a) * s * 0.44f, cy + (float) Math.sin(a) * s * 0.44f, p);
                    }
                    break;
                case GRID:
                    for (int i = 0; i < 4; i++) {
                        float x = cx + (i % 2 == 0 ? -s * 0.38f : s * 0.06f), y = cy + (i < 2 ? -s * 0.38f : s * 0.06f);
                        r.set(x, y, x + s * 0.32f, y + s * 0.32f);
                        c.drawRoundRect(r, s * 0.08f, s * 0.08f, p);
                    }
                    break;
                case INPUT:
                    // a screen with an arrow coming into it
                    r.set(cx - s * 0.42f, cy - s * 0.34f, cx + s * 0.42f, cy + s * 0.26f);
                    c.drawRoundRect(r, s * 0.07f, s * 0.07f, p);
                    c.drawLine(cx - s * 0.18f, cy + s * 0.44f, cx + s * 0.18f, cy + s * 0.44f, p);
                    c.drawLine(cx - s * 0.20f, cy - s * 0.04f, cx + s * 0.12f, cy - s * 0.04f, p);
                    path.reset();
                    path.moveTo(cx - s * 0.01f, cy - s * 0.15f);
                    path.lineTo(cx + s * 0.12f, cy - s * 0.04f);
                    path.lineTo(cx - s * 0.01f, cy + s * 0.07f);
                    c.drawPath(path, p);
                    break;
                case SLIDERS:
                    for (int i = 0; i < 3; i++) {
                        float y = cy + (i - 1) * s * 0.30f, k = i == 1 ? 0.14f : -0.16f;
                        c.drawLine(cx - s * 0.42f, y, cx + s * 0.42f, y, p);
                        c.drawCircle(cx + k * s, y, s * 0.09f, p);
                    }
                    break;
                default:
                    c.drawCircle(cx, cy, s * 0.40f, p);
                    r.set(cx - s * 0.17f, cy - s * 0.40f, cx + s * 0.17f, cy + s * 0.40f);
                    c.drawOval(r, p);
                    c.drawLine(cx - s * 0.40f, cy, cx + s * 0.40f, cy, p);
                    break;
            }
        }
    }

    // ---- Dock -------------------------------------------------------------------------------------------

    /** dock_apps first, then every other app A to Z, Settings last. */
    private List<String> dockOrder() {
        List<String> order = new ArrayList<>();
        for (String pkg : cfg.dockApps) {
            String p = pkg == null ? "" : pkg.trim();
            if (!p.isEmpty() && !order.contains(p) && launchIntent(p) != null) order.add(p);
        }
        List<String> rest = new ArrayList<>();
        for (String pkg : launchable()) if (!order.contains(pkg) && !TV_SETTINGS.equals(pkg)) rest.add(pkg);
        Collections.sort(rest, (x, y) -> appLabel(x).compareToIgnoreCase(appLabel(y)));
        order.addAll(rest);
        if (!order.contains(TV_SETTINGS) && launchIntent(TV_SETTINGS) != null) order.add(TV_SETTINGS);
        return order;
    }

    /** The streaming apps the dock offers when config.json names none; the first six installed count. */
    private static final String[] DEFAULT_DOCK = {YOUTUBE, "com.netflix.ninja", "com.amazon.amazonvideo.livingroom",
            "in.startv.hotstar", "com.sonyliv", "com.graymatrix.did"};
    static final int DOCK_MAX = 6;

    /** dock_apps (installed ones, at most six), else the default streaming apps; never every app. */
    private List<String> dockApps() {
        List<String> out = new ArrayList<>();
        List<String> want = cfg.dockApps.isEmpty() ? Arrays.asList(DEFAULT_DOCK) : cfg.dockApps;
        for (String pkg : want) {
            String p = pkg == null ? "" : pkg.trim();
            if (!p.isEmpty() && !out.contains(p) && launchIntent(p) != null) out.add(p);
            if (out.size() == DOCK_MAX) break;
        }
        if (out.isEmpty()) {
            for (String p : dockOrder()) {
                out.add(p);
                if (out.size() == DOCK_MAX) break;
            }
        }
        return out;
    }

    private void fillDock() {
        List<String> apps = dockApps();
        // Every tile fits inside the dock with clear glass at both ends: nothing scrolls, nothing is cut by the
        // dock's rounded edge. More apps make the tiles smaller rather than spill past it.
        // No names under the icons: the banners say which app they are (All apps below has the names).
        float inner = 0.91f * getResources().getDisplayMetrics().widthPixels - dp(16) - dp(20) - dp(48);
        int w = Math.min(dp(144), Math.round(inner / Math.max(1, apps.size())) - dp(24));
        for (String pkg : apps) {
            View tile = appTile(pkg, w, w * 9 / 16, false);
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) tile.getLayoutParams();
            lp.leftMargin = dp(12);
            lp.rightMargin = dp(12);
            dockRow.addView(tile);
            rowDock.add(tile);
        }
    }

    // ---- below the dock: renewals (optional) and every app ---------------------------------------------

    private final List<List<View>> belowRows = new ArrayList<>();
    private final List<View> renewalRow = new ArrayList<>();
    private float pageScroll;
    private android.animation.ValueAnimator pageAnim;

    /** Every app, A to Z, in a grid under the dock; Down from the dock scrolls the page to it. */
    private void buildAllApps(float top) {
        List<String> apps = dockOrder();
        final int GRID_COLUMNS = clamp(cfg.columns, 4, 7);   // UX: All apps columns
        int tileW = Math.min(dp(120), Math.round((0.91f * getResources().getDisplayMetrics().widthPixels - dp(44)) / GRID_COLUMNS) - dp(24));
        int rowsN = (apps.size() + GRID_COLUMNS - 1) / GRID_COLUMNS;
        float h = getResources().getDisplayMetrics().heightPixels;
        float height = (dp(16) * 2 + dp(34) + rowsN * dp(112)) / h;
        GlassPanel panel = panel(0.045f, top, 0.955f, top + height);
        LinearLayout col = column(panel, 22, 16);
        LinearLayout head = header(col, "All apps");
        TextView count = text(head, 13, regular, 0x99FFFFFF);
        count.setText(apps.size() + " apps");
        for (int r = 0; r < rowsN; r++) {
            LinearLayout row = new LinearLayout(getContext());
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setClipChildren(false);
            row.setClipToPadding(false);
            col.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(112)));
            List<View> navRow = new ArrayList<>();
            for (int c = 0; c < GRID_COLUMNS; c++) {
                FrameLayout cell = new FrameLayout(getContext());
                cell.setClipChildren(false);
                cell.setClipToPadding(false);
                row.addView(cell, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
                int k = r * GRID_COLUMNS + c;
                if (k >= apps.size()) continue;
                View tile = appTile(apps.get(k), tileW, tileW * 68 / 120, true, true);
                LinearLayout.LayoutParams old = (LinearLayout.LayoutParams) tile.getLayoutParams();
                tile.setLayoutParams(new FrameLayout.LayoutParams(old.width, old.height, Gravity.CENTER));
                cell.addView(tile);
                navRow.add(tile);
            }
            belowRows.add(navRow);
        }
    }

    /** Where the page sits: at the top unless focus is below the dock, then far enough down to show that row. */
    private void followFocus(View focus) {
        float target = 0;
        if (focus != null && focus.isAttachedToWindow()) {
            for (List<View> row : belowRows) {
                if (!row.contains(focus)) continue;
                View panel = focus;
                while (panel != null && !(panel instanceof GlassPanel)) {
                    panel = panel.getParent() instanceof View ? (View) panel.getParent() : null;
                }
                if (panel == null) break;
                // the focused row's bottom, in this view's unscrolled coordinates
                int[] at = new int[2], me = new int[2];
                View rowView = (View) (focus.getParent() instanceof FrameLayout ? focus.getParent().getParent() : focus.getParent());
                rowView.getLocationOnScreen(at);
                getLocationOnScreen(me);
                float bottom = at[1] - me[1] + pageScroll + rowView.getHeight();
                float max = panel.getBottom() + dp(28) - getHeight();
                target = Math.max(0, Math.min(max, bottom + dp(34) - getHeight()));
                // a panel that fits on screen shows whole, its bottom edge and the lifted tile's shadow clear of the
                // screen's edge; a taller one (All apps) follows the focused row
                if (panel.getHeight() + dp(56) <= getHeight()) target = Math.max(0, max);
                break;
            }
        }
        scrollPage(target);
    }

    private void scrollPage(float target) {
        if (Math.abs(target - pageScroll) < 1) return;
        if (pageAnim != null) pageAnim.cancel();
        pageAnim = android.animation.ValueAnimator.ofFloat(pageScroll, target);
        pageAnim.setDuration(reducedMotion ? 0 : 320);
        pageAnim.setInterpolator(new android.view.animation.DecelerateInterpolator(1.6f));
        pageAnim.addUpdateListener(a -> {
            pageScroll = (float) a.getAnimatedValue();
            for (GlassPanel p : panels) {
                p.setTranslationY(-pageScroll);
                p.invalidate();   // its slice of the blurred wallpaper moves with it
            }
        });
        pageAnim.start();
    }

    // ---- renewals ------------------------------------------------------------------------------------

    private static final DateTimeFormatter RENEW_DATE = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH);

    /** Renewal dates per dock app: config.json renewals, with dates set on the TV (prefs "renewals") on top. */
    private java.time.LocalDate renewalDate(String pkg) {
        android.content.SharedPreferences sp = getContext().getSharedPreferences("renewals", Context.MODE_PRIVATE);
        String d = sp.contains(pkg) ? sp.getString(pkg, "") : cfg.renewals.get(pkg);
        if (d == null || d.trim().isEmpty()) return null;
        try {
            return java.time.LocalDate.parse(d.trim());
        } catch (Exception e) {
            return null;
        }
    }

    /** A strip of the dock apps with their next renewal; OK on one sets its date. Returns the panel's bottom. */
    private float buildRenewals(float top) {
        float h = getResources().getDisplayMetrics().heightPixels;
        float bottom = top + (dp(14) * 2 + dp(30) + dp(54)) / h;
        GlassPanel panel = panel(0.045f, top, 0.955f, bottom);
        LinearLayout col = column(panel, 22, 14);
        LinearLayout head = header(col, "Renewals");
        TextView hint = text(head, 13, regular, 0x99FFFFFF);
        hint.setText("OK sets a date");
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setClipChildren(false);
        LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54));
        rl.topMargin = dp(6);
        col.addView(row, rl);
        for (String pkg : dockApps()) {
            LinearLayout chip = new LinearLayout(getContext());
            chip.setOrientation(LinearLayout.VERTICAL);
            chip.setGravity(Gravity.CENTER_VERTICAL);
            chip.setPadding(dp(16), 0, dp(16), 0);
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(dp(14));
            bg.setColor(0x1FFFFFFF);
            bg.setStroke(Math.max(1, Math.round(dp)), 0x33FFFFFF);
            chip.setBackground(bg);
            TextView name = text(chip, 14, medium, Color.WHITE);
            name.setText(appLabel(pkg));
            TextView when = text(chip, 12, regular, 0xB3FFFFFF);
            LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
            cl.rightMargin = dp(12);
            row.addView(chip, cl);
            Runnable show = () -> {
                java.time.LocalDate d = renewalDate(pkg);
                if (d == null) {
                    when.setText("No date set");
                    when.setTextColor(0x8CFFFFFF);
                    return;
                }
                long days = java.time.temporal.ChronoUnit.DAYS.between(java.time.LocalDate.now(), d);
                String in = days < 0 ? "lapsed " + (-days) + (days == -1 ? " day ago" : " days ago")
                        : days == 0 ? "renews today" : days == 1 ? "renews tomorrow" : "in " + days + " days";
                when.setText(RENEW_DATE.format(d) + " · " + in);
                when.setTextColor(days <= 3 ? 0xFFFFB86B : 0xB3FFFFFF);
            };
            show.run();
            chip.setFocusable(true);
            chip.setClickable(true);
            long ms = reducedMotion ? Motion.REDUCED_MS : 150;
            int colour = colourFor(pkg);
            chip.setOnFocusChangeListener((v, f) -> {
                v.animate().scaleX(f ? 1.04f : 1f).scaleY(f ? 1.04f : 1f).setDuration(ms).start();
                bg.setColor(f ? Color.argb(70, Color.red(colour), Color.green(colour), Color.blue(colour)) : 0x1FFFFFFF);
                bg.setStroke(Math.max(1, Math.round(dp)), f ? Color.argb(150, Color.red(colour), Color.green(colour), Color.blue(colour)) : 0x33FFFFFF);
            });
            chip.setOnClickListener(v -> {
                java.time.LocalDate d = renewalDate(pkg);
                if (d == null) d = java.time.LocalDate.now().plusMonths(1);
                android.app.DatePickerDialog picker = new android.app.DatePickerDialog(activity, (dp0, y, m, dd) -> {
                    getContext().getSharedPreferences("renewals", Context.MODE_PRIVATE).edit()
                            .putString(pkg, java.time.LocalDate.of(y, m + 1, dd).toString()).apply();
                    show.run();
                }, d.getYear(), d.getMonthValue() - 1, d.getDayOfMonth());
                picker.setButton(android.content.DialogInterface.BUTTON_NEUTRAL, "Clear", (dlg, which) -> {
                    getContext().getSharedPreferences("renewals", Context.MODE_PRIVATE).edit().putString(pkg, "").apply();
                    show.run();
                });
                picker.setTitle(appLabel(pkg) + " renews on");
                picker.show();
            });
            renewalRow.add(chip);
        }
        belowRows.add(renewalRow);
        return bottom;
    }

    private AppLibraryDialog allApps;

    /** Shaped like a dock app tile (glass art, name underneath, same quiet focus) so the row lines up. */
    private View allAppsTile(int w, int h) {
        LinearLayout tile = new LinearLayout(getContext());
        tile.setOrientation(LinearLayout.VERTICAL);
        tile.setGravity(Gravity.CENTER_HORIZONTAL);
        tile.setClipChildren(false);
        tile.setClipToPadding(false);
        FrameLayout art = new FrameLayout(getContext());
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(12));
        bg.setColor(0x26FFFFFF);
        bg.setStroke(Math.max(1, Math.round(dp)), 0x33FFFFFF);
        art.setBackground(bg);
        GlyphView icon = new GlyphView(getContext(), GlyphView.GRID);
        art.addView(icon, new FrameLayout.LayoutParams(dp(26), dp(26), Gravity.CENTER));
        GradientDrawable ring = new GradientDrawable();
        ring.setCornerRadius(dp(12));
        ring.setStroke(Math.max(1, Math.round(1.5f * dp)), Color.WHITE);
        ring.setAlpha(0);
        art.setForeground(ring);
        tile.addView(art, new LinearLayout.LayoutParams(w, h));
        TextView name = text(tile, 12, regular, Color.WHITE);
        name.setText("All apps");
        name.setGravity(Gravity.CENTER);
        name.setAlpha(0.55f);
        LinearLayout.LayoutParams nl = (LinearLayout.LayoutParams) name.getLayoutParams();
        nl.width = w + dp(8);
        nl.topMargin = dp(11);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tl.rightMargin = dp(18);
        tl.gravity = Gravity.CENTER_VERTICAL;
        tile.setLayoutParams(tl);
        tile.setFocusable(true);
        tile.setClickable(true);
        tile.setContentDescription("All apps");
        long ms = reducedMotion ? Motion.REDUCED_MS : 150;
        tile.setOnFocusChangeListener((v, focused) -> {
            art.animate().scaleX(focused ? 1.04f : 1f).scaleY(focused ? 1.04f : 1f).translationY(focused ? -dp(2) : 0f)
                    .setDuration(ms).start();
            bg.setColor(focused ? 0x40FFFFFF : 0x26FFFFFF);
            ring.setAlpha(focused ? 190 : 0);
            name.animate().alpha(focused ? 1f : 0.55f).setDuration(ms).start();
        });
        tile.setOnClickListener(v -> openAllApps());
        return tile;
    }

    /** The installed-app grid; OK opens an app. Focus comes back to the All apps tile. */
    private void openAllApps() {
        if (allApps != null) allApps.close();
        View from = activity.getCurrentFocus();
        allApps = new AppLibraryDialog(activity, true, reducedMotion, false, true, pkg -> false, () -> { }, () -> {
            allApps = null;
            if (from != null && from.isAttachedToWindow()) from.requestFocus();
        }, new HashSet<>(cfg.libraryExcludedApps));
        allApps.show();
    }

    /** Apps with a TV (or phone) launcher entry, except this launcher. */
    private List<String> launchable() {
        PackageManager pm = getContext().getPackageManager();
        List<String> out = new ArrayList<>();
        for (String category : new String[]{Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
            List<ResolveInfo> found;
            try {
                found = pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(category), 0);
            } catch (RuntimeException e) {
                continue;
            }
            for (ResolveInfo ri : found) {
                String pkg = ri.activityInfo.packageName;
                if (!out.contains(pkg) && !getContext().getPackageName().equals(pkg)) out.add(pkg);
            }
            if (!out.isEmpty() && Intent.CATEGORY_LEANBACK_LAUNCHER.equals(category)) break;   // a TV: TV apps only
        }
        return out;
    }

    /**
     * An app's banner (or icon) that grows 1.08x over 150 ms on focus with a glow in the app's own colour, and
     * shows its name underneath (dock tiles).
     */
    private View appTile(String pkg, int w, int h, boolean named) {
        return appTile(pkg, w, h, named, false);
    }

    /**
     * {@code centred}: the icon sits in the middle of the tile's height (an empty band above it as tall as the
     * name below), so a row of them is centred on the icons rather than on icon and name together.
     */
    private View appTile(String pkg, int w, int h, boolean named, boolean centred) {
        PackageManager pm = getContext().getPackageManager();
        LinearLayout tile = new LinearLayout(getContext());
        tile.setOrientation(LinearLayout.VERTICAL);
        tile.setGravity(Gravity.CENTER_HORIZONTAL);
        tile.setClipChildren(false);
        tile.setClipToPadding(false);
        FrameLayout art = new FrameLayout(getContext());
        art.setClipChildren(false);
        Drawable banner = banner(pm, pkg);
        int colour = colourFor(pkg);
        int reach = dp(14);
        GlowView glow = new GlowView(getContext(), colour, reach, dp(12), true);
        glow.setAlpha(0f);
        FrameLayout.LayoutParams gl = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        gl.setMargins(-2 * reach, -2 * reach, -2 * reach, -2 * reach);   // room for the blur: no flat, cut-off edge
        art.addView(glow, gl);
        ImageView image = new ImageView(getContext());
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        image.setImageDrawable(banner);
        GradientDrawable ib = new GradientDrawable();
        ib.setCornerRadius(dp(12));
        ib.setColor(0x40000000);
        image.setBackground(ib);
        image.setClipToOutline(true);
        art.addView(image, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        // the tvOS glare: light falling across the raised tile from the top left, plus a faint bright rim
        GradientDrawable glare = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0x40FFFFFF, 0x14FFFFFF, 0x00FFFFFF, 0x00FFFFFF});
        glare.setCornerRadius(dp(12));
        glare.setStroke(Math.max(1, Math.round(dp)), 0x33FFFFFF);
        glare.setAlpha(0);
        art.setForeground(glare);
        GradientDrawable ring = glare;
        if (centred && named) tile.addView(new View(getContext()), new LinearLayout.LayoutParams(1, dp(22)));
        tile.addView(art, new LinearLayout.LayoutParams(w, h));
        TextView name = null;
        if (named) {
            name = text(tile, 12, regular, Color.WHITE);
            name.setText(appLabel(pkg));
            name.setGravity(Gravity.CENTER);
            name.setAlpha(0.55f);
            LinearLayout.LayoutParams nl = (LinearLayout.LayoutParams) name.getLayoutParams();
            nl.width = w + dp(8);
            nl.topMargin = dp(centred ? 6 : 11);
            if (centred) nl.height = dp(16);
        }
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tl.rightMargin = dp(named ? 18 : 14);
        tl.gravity = Gravity.CENTER_VERTICAL;
        tile.setLayoutParams(tl);
        tile.setFocusable(true);
        tile.setClickable(true);
        tile.setContentDescription(appLabel(pkg));
        long ms = reducedMotion ? Motion.REDUCED_MS : 150;
        TextView label = name;
        tile.setOnFocusChangeListener((v, focused) -> {
            // tvOS: the focused tile rises (bigger, a touch higher) with a soft spring, its shadow growing under it
            art.animate().scaleX(focused ? 1.08f : 1f).scaleY(focused ? 1.08f : 1f).translationY(focused ? -dp(3) : 0f)
                    .setInterpolator(focused ? new android.view.animation.OvershootInterpolator(0.9f)
                            : new android.view.animation.DecelerateInterpolator())
                    .setDuration(reducedMotion ? ms : 260).start();
            glow.animate().alpha(focused ? 1f : 0f).setDuration(reducedMotion ? ms : 260).start();
            android.animation.ValueAnimator g = android.animation.ValueAnimator.ofInt(ring.getAlpha(), focused ? 255 : 0);
            g.setDuration(reducedMotion ? ms : 260);
            g.addUpdateListener(a -> ring.setAlpha((int) a.getAnimatedValue()));
            g.start();
            if (label != null) label.animate().alpha(focused ? 1f : 0.55f).setDuration(ms).start();
        });
        tile.setOnClickListener(v -> launchPackage(pkg));
        return tile;
    }

    /** A soft halo in an app's own colour around its tile: a blurred rounded rectangle, no outline. */
    private static final class GlowView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        private final int reach;
        private final float corner;

        private final boolean shadow;

        GlowView(Context c, int colour, int reach, float corner) {
            this(c, colour, reach, corner, false);
        }

        /**
         * shadow: the tvOS lift instead of a coloured halo. A soft, dark shadow under the tile, a little narrower
         * than it and dropped below it, as if the tile has risen off the glass towards the viewer.
         */
        GlowView(Context c, int colour, int reach, float corner, boolean shadow) {
            super(c);
            this.reach = reach;
            this.corner = corner;
            this.shadow = shadow;
            paint.setColor(shadow ? 0x8C000000 : Color.argb(150, Color.red(colour), Color.green(colour), Color.blue(colour)));
            paint.setMaskFilter(new android.graphics.BlurMaskFilter(Math.max(1f, reach * (shadow ? 0.8f : 0.75f)),
                    android.graphics.BlurMaskFilter.Blur.NORMAL));
            setLayerType(LAYER_TYPE_SOFTWARE, null);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            if (shadow) {
                // the view reaches 2x past the tile: the shadow is the tile's own rectangle, 0.3x narrower at
                // each side, 0.45x lower; its blur (0.8x) stays inside the view
                float side = reach * 2.3f, top = reach * 2.45f, bottom = reach * 1.55f;
                r.set(side, top, getWidth() - side, getHeight() - bottom);
                canvas.drawRoundRect(r, corner, corner, paint);
                return;
            }
            // the view reaches 2x past the tile; the halo sits 0.45x out and its blur (0.75x) stays inside the view
            float in = reach * 1.55f;
            r.set(in, in, getWidth() - in, getHeight() - in);
            canvas.drawRoundRect(r, corner, corner, paint);
        }
    }

    /** A glass tile with a line icon and a label, the size of an app tile. */
    private View actionTile(String label, int glyph, int w, int h, Runnable action) {
        LinearLayout tile = new LinearLayout(getContext());
        tile.setOrientation(LinearLayout.VERTICAL);
        tile.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(12));
        bg.setColor(0x1FFFFFFF);
        bg.setStroke(Math.max(1, Math.round(dp)), 0x40FFFFFF);
        tile.setBackground(bg);
        GlyphView icon = new GlyphView(getContext(), glyph);
        tile.addView(icon, new LinearLayout.LayoutParams(dp(18), dp(18)));
        TextView t = text(tile, 11, regular, 0xD9FFFFFF);
        t.setText(label);
        ((LinearLayout.LayoutParams) t.getLayoutParams()).topMargin = dp(4);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(w, h);
        lp.rightMargin = dp(14);
        lp.gravity = Gravity.CENTER_VERTICAL;
        tile.setLayoutParams(lp);
        long ms = reducedMotion ? Motion.REDUCED_MS : 150;
        tile.setFocusable(true);
        tile.setClickable(true);
        tile.setContentDescription(label);
        tile.setOnFocusChangeListener((v, focused) -> {
            v.animate().scaleX(focused ? 1.08f : 1f).scaleY(focused ? 1.08f : 1f).setDuration(ms).start();
            bg.setColor(focused ? 0xF2FFFFFF : 0x1FFFFFFF);
            t.setTextColor(focused ? 0xFF0B1220 : 0xD9FFFFFF);
            icon.setColour(focused ? 0xFF0B1220 : 0xE6FFFFFF);
        });
        tile.setOnClickListener(v -> action.run());
        return tile;
    }

    // ---- Search ----------------------------------------------------------------------------------------

    /** Launcher settings: wallpaper, glass opacity, clock format and the rest. Home rebuilds when it comes back. */
    private void openHomeSettings() {
        try {
            activity.startActivityForResult(new Intent(activity, LauncherSettingsActivity.class), MainActivity.LAUNCHER_SETTINGS_REQUEST);
        } catch (ActivityNotFoundException ignored) {
            // cannot happen: it is this app's own screen
        }
    }

    private void openSearch() {
        new SearchDialog().show();
    }

    /**
     * Type to filter the installed apps and the For you titles; or send the words to YouTube, the Play Store or
     * Google. The mic asks the assistant.
     */
    private final class SearchDialog extends Dialog {
        private final EditText field;
        private final LinearLayout appsBox, showsBox;
        private final TextView appsHead, showsHead;
        private final List<String> apps = dockOrder();

        SearchDialog() {
            super(activity, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
            LinearLayout col = new LinearLayout(activity);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setPadding(dp(86), dp(44), dp(86), dp(24));
            col.setClipChildren(false);
            col.setClipToPadding(false);

            LinearLayout bar = new LinearLayout(activity);
            bar.setOrientation(LinearLayout.HORIZONTAL);
            bar.setGravity(Gravity.CENTER_VERTICAL);
            col.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)));
            field = new EditText(activity);
            field.setSingleLine(true);
            field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
            field.setImeOptions(EditorInfo.IME_ACTION_SEARCH | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
            field.setHint("Search apps, shows and YouTube");
            field.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
            field.setTypeface(regular);
            field.setTextColor(Color.WHITE);
            field.setHintTextColor(0x8CFFFFFF);
            field.setPadding(dp(52), 0, dp(20), 0);
            GradientDrawable fb = new GradientDrawable();
            fb.setCornerRadius(dp(26));
            fb.setColor(0x26FFFFFF);
            fb.setStroke(Math.round(1.5f * dp), 0x59FFFFFF);
            field.setBackground(fb);
            field.setOnFocusChangeListener((v, f) -> fb.setStroke(Math.round((f ? 2.5f : 1.5f) * dp), f ? Color.WHITE : 0x59FFFFFF));
            FrameLayout fieldBox = new FrameLayout(activity);
            fieldBox.addView(field, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            FrameLayout.LayoutParams gl = new FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER_VERTICAL | Gravity.START);
            gl.leftMargin = dp(20);
            fieldBox.addView(new GlyphView(activity, GlyphView.SEARCH), gl);
            bar.addView(fieldBox, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
            View mic = chip(bar, "Voice", GlyphView.MIC, () -> {
                dismiss();
                askJarvis();
            });
            ((LinearLayout.LayoutParams) mic.getLayoutParams()).leftMargin = dp(14);

            TextView in = sectionHead(col, "Search in");
            ((LinearLayout.LayoutParams) in.getLayoutParams()).topMargin = dp(22);
            LinearLayout chips = new LinearLayout(activity);
            chips.setOrientation(LinearLayout.HORIZONTAL);
            chips.setClipChildren(false);
            col.addView(chips, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)));
            if (launchIntent(YOUTUBE) != null) chip(chips, "YouTube", GlyphView.PLAY, () -> searchYouTube(query()));
            if (launchIntent(PLAY_STORE) != null) chip(chips, "Play Store", GlyphView.BAG,
                    () -> openUri("market://search?q=" + Uri.encode(query()) + "&c=apps", PLAY_STORE));
            chip(chips, "Google", GlyphView.GLOBE, () -> searchGoogle(query()));

            appsHead = sectionHead(col, "Apps");
            ((LinearLayout.LayoutParams) appsHead.getLayoutParams()).topMargin = dp(22);
            appsBox = new LinearLayout(activity);
            appsBox.setOrientation(LinearLayout.VERTICAL);
            appsBox.setClipChildren(false);
            col.addView(appsBox, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            showsHead = sectionHead(col, "Shows");
            ((LinearLayout.LayoutParams) showsHead.getLayoutParams()).topMargin = dp(10);
            showsBox = new LinearLayout(activity);
            showsBox.setOrientation(LinearLayout.HORIZONTAL);
            showsBox.setClipChildren(false);
            showsBox.setPadding(dp(4), dp(6), dp(4), dp(4));
            HorizontalScrollView showsScroll = new HorizontalScrollView(activity);
            showsScroll.setHorizontalScrollBarEnabled(false);
            showsScroll.setClipToPadding(false);
            showsScroll.setHorizontalFadingEdgeEnabled(true);
            showsScroll.setFadingEdgeLength(dp(48));
            showsScroll.addView(showsBox, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            col.addView(showsScroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            field.addTextChangedListener(new TextWatcher() {
                @Override
                public void beforeTextChanged(CharSequence s, int a, int b, int c) {}

                @Override
                public void onTextChanged(CharSequence s, int a, int b, int c) {}

                @Override
                public void afterTextChanged(Editable s) {
                    refresh();
                }
            });
            field.setOnEditorActionListener((v, action, event) -> {
                if (action != EditorInfo.IME_ACTION_SEARCH && action != EditorInfo.IME_ACTION_DONE
                        && (event == null || event.getKeyCode() != KeyEvent.KEYCODE_ENTER)) return false;
                if (query().isEmpty()) return true;
                View first = appsBox.getChildCount() > 0 ? ((ViewGroup) appsBox.getChildAt(0)).getChildAt(0)
                        : showsBox.getChildCount() > 0 ? showsBox.getChildAt(0) : null;
                if (first != null) first.requestFocus();
                else searchYouTube(query());
                return true;
            });

            setContentView(col);
            Window w = getWindow();
            if (w != null) {
                w.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT);
                w.setBackgroundDrawable(new ColorDrawable(0xFA070B14));
                w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE
                        | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING);
            }
            refresh();
            field.requestFocus();
        }

        private String query() {
            return field.getText().toString().trim();
        }

        private TextView sectionHead(LinearLayout parent, String s) {
            TextView t = text(parent, 14, medium, 0x99FFFFFF);
            t.setText(s.toUpperCase(Locale.ENGLISH));
            t.setLetterSpacing(0.12f);
            ((LinearLayout.LayoutParams) t.getLayoutParams()).bottomMargin = dp(10);
            return t;
        }

        private View chip(LinearLayout parent, String label, int glyph, Runnable action) {
            LinearLayout c = new LinearLayout(activity);
            c.setOrientation(LinearLayout.HORIZONTAL);
            c.setGravity(Gravity.CENTER_VERTICAL);
            c.setPadding(dp(16), 0, dp(20), 0);
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(dp(22));
            bg.setColor(0x26FFFFFF);
            bg.setStroke(Math.max(1, Math.round(dp)), 0x40FFFFFF);
            c.setBackground(bg);
            GlyphView icon = new GlyphView(activity, glyph);
            c.addView(icon, new LinearLayout.LayoutParams(dp(16), dp(16)));
            TextView t = text(c, 15, medium, Color.WHITE);
            t.setText(label);
            t.setShadowLayer(0, 0, 0, 0);
            ((LinearLayout.LayoutParams) t.getLayoutParams()).leftMargin = dp(8);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44));
            lp.rightMargin = dp(12);
            parent.addView(c, lp);
            c.setFocusable(true);
            c.setClickable(true);
            long ms = reducedMotion ? Motion.REDUCED_MS : 120;
            c.setOnFocusChangeListener((v, f) -> {
                v.animate().scaleX(f ? 1.05f : 1f).scaleY(f ? 1.05f : 1f).setDuration(ms).start();
                bg.setColor(f ? 0xF2FFFFFF : 0x26FFFFFF);
                t.setTextColor(f ? 0xFF0B1220 : Color.WHITE);
                icon.setColour(f ? 0xFF0B1220 : 0xE6FFFFFF);
            });
            c.setOnClickListener(v -> action.run());
            return c;
        }

        private void refresh() {
            String q = query().toLowerCase(Locale.ROOT);
            List<String> starts = new ArrayList<>(), contains = new ArrayList<>();
            for (String pkg : apps) {
                String label = appLabel(pkg).toLowerCase(Locale.ROOT);
                if (q.isEmpty() || label.startsWith(q)) starts.add(pkg);
                else if (label.contains(q) || pkg.contains(q)) contains.add(pkg);
            }
            starts.addAll(contains);
            appsBox.removeAllViews();
            int perRow = 7, n = Math.min(q.isEmpty() ? perRow : perRow * 2, starts.size());
            LinearLayout row = null;
            for (int i = 0; i < n; i++) {
                if (i % perRow == 0) {
                    row = new LinearLayout(activity);
                    row.setOrientation(LinearLayout.HORIZONTAL);
                    row.setClipChildren(false);
                    row.setClipToPadding(false);
                    row.setPadding(dp(4), dp(6), 0, dp(4));
                    appsBox.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                }
                String pkg = starts.get(i);
                View tile = appTile(pkg, dp(112), dp(63), true);
                tile.setOnClickListener(v -> {
                    dismiss();
                    launchPackage(pkg);
                });
                row.addView(tile);
            }
            appsHead.setText(q.isEmpty() ? "YOUR APPS" : n == 0 ? "NO APPS MATCH" : "APPS");

            showsBox.removeAllViews();
            int shows = 0;
            for (Item it : items) {
                if (shows >= 12) break;
                if (!q.isEmpty() && !it.title.toLowerCase(Locale.ROOT).contains(q)
                        && !appLabel(it.pkg).toLowerCase(Locale.ROOT).contains(q)) continue;
                View card = posterTile(it, dp(112), dp(184));
                card.setOnClickListener(v -> {
                    dismiss();
                    openItem(it);
                });
                showsBox.addView(card);
                shows++;
            }
            showsHead.setText(q.isEmpty() ? "PICK UP WHERE YOU LEFT OFF" : "SHOWS");
            showsHead.setVisibility(shows == 0 ? GONE : VISIBLE);
            ((View) showsBox.getParent()).scrollTo(0, 0);
        }

        private void searchYouTube(String q) {
            dismiss();
            if (q.isEmpty()) {
                launchPackage(YOUTUBE);
                return;
            }
            Intent i = new Intent(Intent.ACTION_SEARCH).setPackage(YOUTUBE).putExtra(SearchManager.QUERY, q)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                activity.startActivity(i);
            } catch (Exception e) {
                openUri("https://www.youtube.com/results?search_query=" + Uri.encode(q), YOUTUBE);
            }
        }

        private void searchGoogle(String q) {
            dismiss();
            Intent[] tries = {
                    new Intent(SearchManager.INTENT_ACTION_GLOBAL_SEARCH).putExtra(SearchManager.QUERY, q),
                    new Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, q),
                    new Intent(Intent.ACTION_ASSIST),
            };
            for (Intent i : tries) {
                try {
                    activity.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                    return;
                } catch (Exception e) {
                    // the next one
                }
            }
        }
    }

    private void openUri(String uri, String pkg) {
        try {
            activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(uri)).setPackage(pkg)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception e) {
            launchPackage(pkg);
        }
    }

    // ---- weather ----------------------------------------------------------------------------------------

    /** Reads the companion provider on the worker thread; no provider or stale data shows "Weather offline". */
    private void loadWeather() {
        worker.execute(() -> {
            String temp = null, condition = null, city = null, feels = null;
            int aqi = -1, humidity = -1;
            long updated = 0;
            ContentProviderClient client = null;
            try {
                Uri uri = Uri.parse(WEATHER_URI);
                client = getContext().getContentResolver().acquireUnstableContentProviderClient(uri);
                if (client != null) {
                    try (Cursor cursor = client.query(uri, null, null, null, null)) {
                        if (cursor != null && cursor.moveToFirst()) {
                            temp = str(cursor, cursor.getColumnIndex("temp_c"));
                            condition = str(cursor, cursor.getColumnIndex("condition"));
                            city = str(cursor, cursor.getColumnIndex("city"));
                            feels = str(cursor, cursor.getColumnIndex("feels_c"));
                            int at = cursor.getColumnIndex("updated_at");
                            updated = at < 0 ? 0 : cursor.getLong(at);
                            if (updated > 0 && updated < 100_000_000_000L) updated *= 1000;   // seconds
                            int a = cursor.getColumnIndex("aqi"), h = cursor.getColumnIndex("humidity");
                            if (a >= 0 && !cursor.isNull(a)) aqi = cursor.getInt(a);
                            if (h >= 0 && !cursor.isNull(h)) humidity = cursor.getInt(h);
                            int la = cursor.getColumnIndex("lat"), lo = cursor.getColumnIndex("lon");
                            if (la >= 0 && lo >= 0 && !cursor.isNull(la) && !cursor.isNull(lo)) {
                                double y = cursor.getDouble(la), x = cursor.getDouble(lo);
                                if (Math.abs(y) <= 90 && Math.abs(x) <= 180) {
                                    getContext().getSharedPreferences("sun", Context.MODE_PRIVATE).edit()
                                            .putString("lat", String.valueOf(y)).putString("lon", String.valueOf(x)).apply();
                                    post(this::updateSun);
                                }
                            }
                        }
                    }
                }
            } catch (Exception ignored) {
                // no companion, or it failed: offline
            } finally {
                if (client != null) client.close();
            }
            boolean fresh = temp != null && !temp.isEmpty() && DashboardData.weatherFresh(updated, System.currentTimeMillis());
            String t = temp, cond = condition, fl = feels, place = city == null || city.isEmpty() ? cfg.weatherCity : city;
            int q = aqi, hum = humidity;
            post(() -> {
                if (destroyed) return;
                if (!fresh) {
                    showWeatherOffline();
                    return;
                }
                weatherTemp.setText(formatTemp(t));
                weatherSky.setText(cond == null || cond.isEmpty() ? "Now" : cond);
                weatherPlace.setText(place);
                List<String> bits = new ArrayList<>();
                if (q >= 0) bits.add("Air: " + DashboardData.aqiLabel(q));
                if (fl != null && !fl.isEmpty() && !fl.equals(t)) bits.add("Feels " + formatTemp(fl));
                if (hum >= 0) bits.add("Humidity " + hum + "%");
                weatherMore.setText(TextUtils.join(" · ", bits));
                if (q >= 0) {
                    aqiChip.setText("AQI " + q);
                    GradientDrawable chip = new GradientDrawable();
                    chip.setCornerRadius(dp(12));
                    chip.setColor(DashboardData.aqiColour(q));
                    aqiChip.setBackground(chip);
                    aqiChip.setTextColor(DashboardData.aqiTextColour(q));
                    aqiBox.setVisibility(VISIBLE);
                } else {
                    aqiBox.setVisibility(GONE);
                }
            });
        });
    }

    private void showWeatherOffline() {
        weatherTemp.setText("--°");
        weatherSky.setText("Weather offline");
        weatherPlace.setText(cfg.weatherCity);
        weatherMore.setText("");
        aqiBox.setVisibility(GONE);
    }

    private static String formatTemp(String raw) {
        try {
            return Math.round(Double.parseDouble(raw.trim())) + "°";
        } catch (Exception e) {
            return raw;
        }
    }

    // ---- clock and greeting -----------------------------------------------------------------------------

    private void updateClock() {
        LocalDateTime now = LocalDateTime.now();
        clock.setText(TIME.format(now));
        ampm.setText(AMPM.format(now).toLowerCase(Locale.ENGLISH));
        date.setText(DATE.format(now));
        String g = cfg.festival.active(Seasons.today(activity)) ? cfg.festival.greetingText(getResources()) : "";
        if (g.isEmpty()) {
            g = DashboardData.partOfDay(now.getHour());
            if (!cfg.greetingName.isEmpty()) g = g + ", " + cfg.greetingName;
        }
        greeting.setText(g);
        if (now.getMinute() % 15 == 0) loadWeather();
        updateSun();
    }

    /**
     * The dock's inner well: its scrolling row fades out at whichever end has more tiles beyond it, so tiles slip
     * under the dock's edge instead of being cut by it.
     */
    private final class DockWell extends FrameLayout {
        private final Paint mask = new Paint();
        private LinearGradient left, right;
        private float madeFor = -1;
        private int madeW = -1;
        /** How far the row may draw above and below the well (Arcade). */
        int bleed;

        DockWell(Context context) {
            super(context);
            mask.setXfermode(new android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_OUT));
        }

        @Override
        protected void dispatchDraw(Canvas canvas) {
            View scroll = getChildCount() > 0 ? getChildAt(0) : null;
            boolean l = scroll != null && scroll.canScrollHorizontally(-1), r = scroll != null && scroll.canScrollHorizontally(1);
            if (!l && !r) {
                super.dispatchDraw(canvas);
                return;
            }
            int w = getWidth(), h = getHeight();
            // as long as the scroll view's own fading edge, which is how far it keeps the focused tile from the end
            float f = Math.max(dp(24), scroll.getHorizontalFadingEdgeLength());
            if (f != madeFor || w != madeW) {
                madeFor = f;
                madeW = w;
                left = new LinearGradient(0, 0, f, 0, 0xFF000000, 0x00000000, Shader.TileMode.CLAMP);
                right = new LinearGradient(w - f, 0, w, 0, 0x00000000, 0xFF000000, Shader.TileMode.CLAMP);
            }
            int save = canvas.saveLayer(0, -bleed, w, h + bleed, null);
            super.dispatchDraw(canvas);
            if (l) {
                mask.setShader(left);
                canvas.drawRect(0, -bleed, f, h + bleed, mask);
            }
            if (r) {
                mask.setShader(right);
                canvas.drawRect(w - f, -bleed, w, h + bleed, mask);
            }
            canvas.restoreToCount(save);
        }
    }

    /** The Inputs pill, for the remote's Input button (VIEW_INPUTS) to hang its menu under. */
    View inputsPill() {
        return inputsPill;
    }

    // ---- sunrise and sunset -----------------------------------------------------------------------------

    /**
     * Today's sunrise and sunset, computed offline ({@link Sun}) for the weather add-on's place, else config.xml's
     * latitude and longitude, else New Delhi (the weather add-on's own default).
     */
    private void updateSun() {
        if (sunLine == null) return;
        java.time.LocalDate today = java.time.LocalDate.now();
        android.content.SharedPreferences sp = getContext().getSharedPreferences("sun", Context.MODE_PRIVATE);
        String lat = sp.getString("lat", ""), lon = sp.getString("lon", "");
        if (lat.isEmpty() || lon.isEmpty()) {
            lat = getContext().getString(R.string.latitude).trim();
            lon = getContext().getString(R.string.longitude).trim();
        }
        if (lat.isEmpty() || lon.isEmpty()) {
            lat = "28.6139";
            lon = "77.2090";
        }
        String key = today + "|" + lat + "|" + lon;
        if (!key.equals(sunKey)) {
            sunKey = key;
            int[] t = null;
            try {
                t = Sun.riseAndSet(today, Double.parseDouble(lat), Double.parseDouble(lon), java.time.ZoneId.systemDefault());
            } catch (NumberFormatException ignored) {
                // unusable: no times
            }
            sunLine.rise = t == null ? -1 : t[0];
            sunLine.set = t == null ? -1 : t[1];
            sunriseText.setText(t == null ? "" : minuteText(t[0]));
            sunsetText.setText(t == null ? "" : minuteText(t[1]));
            sunriseText.setContentDescription(t == null ? "" : "Sunrise " + minuteText(t[0]));
            sunsetText.setContentDescription(t == null ? "" : "Sunset " + minuteText(t[1]));
            sunriseText.setVisibility(t == null ? View.GONE : View.VISIBLE);
            sunsetText.setVisibility(t == null ? View.GONE : View.VISIBLE);
        }
        LocalDateTime now = LocalDateTime.now();
        sunLine.now = now.getHour() * 60 + now.getMinute();
        sunLine.invalidate();
    }

    private static String minuteText(int minute) {
        int m = ((minute % 1440) + 1440) % 1440;
        int h = m / 60, h12 = h % 12 == 0 ? 12 : h % 12;
        return String.format(Locale.ENGLISH, "%d:%02d %s", h12, m % 60, h < 12 ? "am" : "pm");
    }

    /** The hairline under the date, brighter where the day has passed, with the sun where it is now (daytime only). */
    private final class SunLine extends View {
        int rise = -1, set = -1, now;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        SunLine(Context context) {
            super(context);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float w = getWidth(), cy = getHeight() / 2f, hair = Math.max(1f, dp);
            paint.setShader(null);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0x40FFFFFF);
            canvas.drawRect(0, cy - hair / 2, w, cy + hair / 2, paint);
            if (rise < 0 || set <= rise) return;
            boolean day = now >= rise && now < set;
            float f = now < rise ? 0f : now >= set ? 1f : (now - rise) / (float) (set - rise);
            float x = f * w;
            if (x > 0) {
                paint.setShader(new LinearGradient(0, 0, x, 0, 0x33FFD27A, 0xCCFFD27A, Shader.TileMode.CLAMP));
                canvas.drawRect(0, cy - hair / 2, x, cy + hair / 2, paint);
                paint.setShader(null);
            }
            if (!day) return;
            float r = 4.5f * dp;
            paint.setShader(new RadialGradient(x, cy, r * 2.6f, 0x66FFC85A, 0x00FFC85A, Shader.TileMode.CLAMP));
            canvas.drawCircle(x, cy, r * 2.6f, paint);
            paint.setShader(null);
            paint.setColor(0xFFFFD98A);
            canvas.drawCircle(x, cy, r, paint);
        }
    }

    // ---- D-pad: bar, top, middle, dock; the dock loops; never out of the dashboard ---------------------------

    private static int direction(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_LEFT: return View.FOCUS_LEFT;
            case KeyEvent.KEYCODE_DPAD_RIGHT: return View.FOCUS_RIGHT;
            case KeyEvent.KEYCODE_DPAD_UP: return View.FOCUS_UP;
            case KeyEvent.KEYCODE_DPAD_DOWN: return View.FOCUS_DOWN;
            default: return 0;
        }
    }

    private List<List<View>> rows() {
        List<List<View>> rows = new ArrayList<>(Arrays.asList(rowBar, rowTop, rowMiddle, rowDock));
        rows.addAll(belowRows);
        return rows;
    }

    @Override
    public View focusSearch(View focused, int direction) {
        View target = navigate(focused, direction);
        return target != null ? target : super.focusSearch(focused, direction);
    }

    private View navigate(View focused, int direction) {
        List<List<View>> rows = rows();
        int r = -1, i = -1;
        for (int k = 0; k < rows.size(); k++) {
            int at = rows.get(k).indexOf(focused);
            if (at >= 0) {
                r = k;
                i = at;
                break;
            }
        }
        if (r < 0) return null;
        List<View> row = rows.get(r);
        boolean loop = row == rowDock && row.size() > 1;
        switch (direction) {
            case View.FOCUS_LEFT:
                if (i > 0) return row.get(i - 1);
                return loop ? row.get(row.size() - 1) : focused;
            case View.FOCUS_RIGHT:
                if (i < row.size() - 1) return row.get(i + 1);
                return loop ? row.get(0) : focused;
            case View.FOCUS_UP:
            case View.FOCUS_DOWN: {
                int step = direction == View.FOCUS_UP ? -1 : 1;
                lastInRow.put(row, focused);
                for (int k = r + step; k >= 0 && k < rows.size(); k += step) {
                    List<View> next = rows.get(k);
                    if (next.isEmpty()) continue;
                    View back = lastInRow.get(next);
                    return back != null && next.contains(back) ? back : nearest(next, focused);
                }
                return focused;
            }
            default:
                return null;
        }
    }

    /** The view in {@code row} nearest {@code from} across the screen, among those on screen if any are. */
    private View nearest(List<View> row, View from) {
        int[] loc = new int[2];
        from.getLocationOnScreen(loc);
        float x = loc[0] + from.getWidth() / 2f;
        int screen = getWidth();
        View best = null;
        float bestDistance = Float.MAX_VALUE;
        for (int pass = 0; pass < 2 && best == null; pass++) {
            for (View v : row) {
                v.getLocationOnScreen(loc);
                boolean visible = loc[0] >= 0 && loc[0] + v.getWidth() <= screen;
                if (pass == 0 && !visible) continue;
                float d = Math.abs(loc[0] + v.getWidth() / 2f - x);
                if (d < bestDistance) {
                    bestDistance = d;
                    best = v;
                }
            }
        }
        return best == null ? row.get(0) : best;
    }

    // ---- panels: fractions of the screen, frosted ------------------------------------------------------

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int w = MeasureSpec.getSize(widthSpec), h = MeasureSpec.getSize(heightSpec);
        for (int k = 0; k < getChildCount(); k++) {
            View child = getChildAt(k);
            if (child instanceof GlassPanel) {
                GlassPanel p = (GlassPanel) child;
                child.measure(MeasureSpec.makeMeasureSpec(Math.round((p.x1 - p.x0) * w), MeasureSpec.EXACTLY),
                        MeasureSpec.makeMeasureSpec(Math.round((p.y1 - p.y0) * h), MeasureSpec.EXACTLY));
            } else {
                measureChild(child, widthSpec, heightSpec);
            }
        }
        setMeasuredDimension(w, h);
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int w = r - l, h = b - t;
        for (int k = 0; k < getChildCount(); k++) {
            View child = getChildAt(k);
            if (child instanceof GlassPanel) {
                GlassPanel p = (GlassPanel) child;
                int left = Math.round(p.x0 * w), top = Math.round(p.y0 * h);
                child.layout(left, top, left + child.getMeasuredWidth(), top + child.getMeasuredHeight());
            } else {
                child.layout(0, 0, child.getMeasuredWidth(), child.getMeasuredHeight());
            }
        }
    }

    private GlassPanel panel(float x0, float y0, float x1, float y1) {
        GlassPanel p = new GlassPanel(getContext(), x0, y0, x1, y1);
        addView(p);
        panels.add(p);
        return p;
    }

    /**
     * Draws its slice of the blurred wallpaper, the tint, a soft highlight across the top, and a rim that is
     * brighter at the top edge (light from above), inside 26 dp corners (a pill when the panel is short).
     */
    private final class GlassPanel extends FrameLayout {
        final float x0, y0, x1;
        float y1;
        private final Paint glass = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint sheen = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint shade = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private final Matrix matrix = new Matrix();
        private Bitmap shaderFor;
        float radius = 26 * dp;

        GlassPanel(Context c, float x0, float y0, float x1, float y1) {
            super(c);
            this.x0 = x0;
            this.y0 = y0;
            this.x1 = x1;
            this.y1 = y1;
            setWillNotDraw(false);
            setClipChildren(false);
            fill.setColor(tint);
            glass.setAlpha(glassAlpha);
            rim.setStyle(Paint.Style.STROKE);
            rim.setStrokeWidth(Math.max(1.5f, 0.9f * dp));
        }

        @Override
        protected void onSizeChanged(int w, int h, int ow, int oh) {
            radius = Math.min(26 * dp, h / 2f);
            // a light catch along the top edge only, not a milky band: on a short panel the old 140 dp sheen frosted the
            // whole header strip
            sheen.setShader(new LinearGradient(0, 0, 0, Math.min(h / 3f, 36 * dp), 0x17FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
            rim.setShader(new LinearGradient(0, 0, 0, h, new int[]{0x8CFFFFFF, 0x26FFFFFF, 0x40FFFFFF},
                    new float[]{0f, 0.6f, 1f}, Shader.TileMode.CLAMP));
            Drawable fg = getForeground();
            if (fg instanceof GradientDrawable) ((GradientDrawable) fg).setCornerRadius(radius);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            rect.set(0.75f, 0.75f, getWidth() - 0.75f, getHeight() - 0.75f);
            Bitmap b = blur;
            if (b != null && !b.isRecycled()) {
                View parent = (View) getParent();
                float sx = parent.getWidth() / (float) b.getWidth(), sy = parent.getHeight() / (float) b.getHeight();
                if (shaderFor != b) {
                    glass.setShader(new BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
                    shaderFor = b;
                    shade.setColor(Color.argb(shadeAlpha(b, getLeft() / sx, getTop() / sy, getRight() / sx, getBottom() / sy), 8, 14, 26));
                }
                matrix.setScale(sx, sy);
                matrix.postTranslate(-getX(), -getY());   // getY: the page scrolls by translation
                glass.getShader().setLocalMatrix(matrix);
                canvas.drawRoundRect(rect, radius, radius, glass);
                if (shade.getAlpha() > 0) canvas.drawRoundRect(rect, radius, radius, shade);
            } else {
                shaderFor = null;
                glass.setShader(null);
            }
            canvas.drawRoundRect(rect, radius, radius, fill);
            canvas.drawRoundRect(rect, radius, radius, sheen);
            canvas.drawRoundRect(rect, radius, radius, rim);
        }

        /**
         * How much to darken this panel's slice of a bright wallpaper (snow, sky) so white text stays readable:
         * none on a dark picture, up to about 60% on a very bright one. Sampled once per wallpaper.
         */
        private int shadeAlpha(Bitmap b, float l, float t, float r, float btm) {
            int x0 = clamp(Math.round(l), 0, b.getWidth() - 1), x1 = clamp(Math.round(r), x0 + 1, b.getWidth());
            int y0 = clamp(Math.round(t), 0, b.getHeight() - 1), y1 = clamp(Math.round(btm), y0 + 1, b.getHeight());
            double sum = 0;
            int n = 0;
            for (int y = y0; y < y1; y += Math.max(1, (y1 - y0) / 10)) {
                for (int x = x0; x < x1; x += Math.max(1, (x1 - x0) / 16)) {
                    int c = b.getPixel(x, y);
                    sum += (0.2126 * Color.red(c) + 0.7152 * Color.green(c) + 0.0722 * Color.blue(c)) / 255.0;
                    n++;
                }
            }
            double luma = n == 0 ? 0 : sum / n;
            return clamp((int) Math.round((luma - 0.30) * 320), 0, 150);
        }
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    /**
     * Top-bar pill focus, in the same tvOS language as the tiles: the pill rises (a little bigger, a touch higher,
     * a soft shadow under it) and light washes across its glass from the top left, with a faint bright rim. No hard
     * white ring.
     */
    private void makeFocusable(View v, float scale) {
        v.setFocusable(true);
        v.setClickable(true);
        float radius = v instanceof GlassPanel ? ((GlassPanel) v).radius : 14 * dp;
        GradientDrawable glare = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0x4DFFFFFF, 0x1FFFFFFF, 0x0AFFFFFF, 0x0AFFFFFF});
        glare.setCornerRadius(radius);
        glare.setStroke(Math.max(1, Math.round(1.2f * dp)), 0x66FFFFFF);
        glare.setAlpha(0);
        v.setForeground(glare);
        v.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override
            public void getOutline(View view, android.graphics.Outline outline) {
                float r = view instanceof GlassPanel ? ((GlassPanel) view).radius : 14 * dp;
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), r);
                outline.setAlpha(0.5f);
            }
        });
        long ms = reducedMotion ? Motion.REDUCED_MS : 260;
        float lift = Math.max(scale, 1.06f);
        v.setOnFocusChangeListener((view, focused) -> {
            view.animate().scaleX(focused ? lift : 1f).scaleY(focused ? lift : 1f)
                    .translationY(focused ? -dp(2) : 0f).translationZ(focused ? dp(10) : 0f)
                    .setInterpolator(focused ? new android.view.animation.OvershootInterpolator(0.9f)
                            : new android.view.animation.DecelerateInterpolator())
                    .setDuration(ms).start();
            android.animation.ValueAnimator g = android.animation.ValueAnimator.ofInt(glare.getAlpha(), focused ? 255 : 0);
            g.setDuration(ms);
            g.addUpdateListener(an -> glare.setAlpha((int) an.getAnimatedValue()));
            g.start();
        });
    }

    // ---- small helpers ----------------------------------------------------------------------------------

    private LinearLayout column(ViewGroup parent, int padH, int padV) {
        LinearLayout l = new LinearLayout(getContext());
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(dp(padH), dp(padV), dp(padH), dp(padV));
        l.setClipChildren(false);
        l.setClipToPadding(false);
        parent.addView(l, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return l;
    }

    /**
     * A sideways-scrolling row that stays inside its panel: it clips its content to its own bounds (the dock's
     * tiles used to spill past the panel) and fades at the edges. Padding leaves room for the focus zoom.
     */
    private LinearLayout scrollRow(FrameLayout parent, int padH, int padV) {
        HorizontalScrollView scroll = new HorizontalScrollView(getContext());
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setClipChildren(true);
        scroll.setClipToPadding(false);
        scroll.setFillViewport(false);
        scroll.setHorizontalFadingEdgeEnabled(true);
        scroll.setFadingEdgeLength(dp(36));
        scroll.setSmoothScrollingEnabled(!reducedMotion);
        scroll.setPadding(dp(padH), dp(padV), dp(padH), dp(padV));
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setClipChildren(false);
        row.setClipToPadding(false);
        row.setPadding(dp(6), 0, dp(6), 0);
        scroll.addView(row, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
        parent.addView(scroll, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        parent.setClipChildren(true);   // the scroll view only clips to its own window when its parent clips it
        if (parent instanceof DockWell) scroll.setOnScrollChangeListener((v, x, y, ox, oy) -> parent.invalidate());
        return row;
    }

    private TextView text(ViewGroup parent, int sp, Typeface face, int color) {
        TextView t = new TextView(getContext());
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTypeface(face);
        t.setTextColor(color);
        t.setShadowLayer(4 * dp, 0, dp, 0x59000000);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        parent.addView(t, parent instanceof LinearLayout
                ? new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                : new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return t;
    }

    private TextView emptyText(FrameLayout parent, String s) {
        TextView t = new TextView(getContext());
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        t.setTypeface(light);
        t.setTextColor(0xB3FFFFFF);
        t.setGravity(Gravity.CENTER);
        t.setVisibility(GONE);
        parent.addView(t, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return t;
    }

    private LinearLayout.LayoutParams wrap(int top, int bottom) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(top);
        lp.bottomMargin = dp(bottom);
        return lp;
    }

    private Typeface font(int res) {
        try {
            return UiFont.get(getContext(), res);
        } catch (Exception e) {
            return Typeface.DEFAULT;
        }
    }

    private int dp(float v) {
        return Math.round(v * dp);
    }

    private Intent launchIntent(String pkg) {
        PackageManager pm = getContext().getPackageManager();
        try {
            Intent i = pm.getLeanbackLaunchIntentForPackage(pkg);
            if (i == null) i = pm.getLaunchIntentForPackage(pkg);
            return i;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private void launchPackage(String pkg) {
        Intent i = launchIntent(pkg);
        if (i == null) return;
        try {
            activity.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (ActivityNotFoundException | SecurityException ignored) {
            // uninstalled or disabled since the dashboard was built
        }
    }

    private String appLabel(String pkg) {
        PackageManager pm = getContext().getPackageManager();
        try {
            String label = String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)));
            String override = cfg.appLabels.get(pkg);
            return override != null && !override.isEmpty() ? override : label;
        } catch (PackageManager.NameNotFoundException e) {
            return pkg;
        }
    }

    /** The app's banner colour, remembered per package. */
    private int colourFor(String pkg) {
        Integer c = colours.get(pkg);
        if (c == null) {
            c = averageColour(banner(getContext().getPackageManager(), pkg));
            colours.put(pkg, c);
        }
        return c;
    }

    private static Drawable banner(PackageManager pm, String pkg) {
        try {
            Intent i = pm.getLeanbackLaunchIntentForPackage(pkg);
            if (i != null) {
                ResolveInfo ri = pm.resolveActivity(i, 0);
                if (ri != null) {
                    Drawable b = ri.activityInfo.loadBanner(pm);
                    if (b != null) return b;
                }
            }
            Drawable b = pm.getApplicationBanner(pkg);
            if (b != null) return b;
            return pm.getApplicationIcon(pkg);
        } catch (Exception e) {
            return null;
        }
    }

    /** The banner's average colour, brightened a little, for the focus glow; soft blue when there is none. */
    private static int averageColour(Drawable d) {
        if (d == null) return 0xFF6FA8FF;
        try {
            Bitmap b = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(b);
            d.setBounds(0, 0, 8, 8);
            d.draw(c);
            long r = 0, g = 0, bl = 0, n = 0;
            for (int y = 0; y < 8; y++) for (int x = 0; x < 8; x++) {
                int p = b.getPixel(x, y);
                if (Color.alpha(p) < 64) continue;
                r += Color.red(p);
                g += Color.green(p);
                bl += Color.blue(p);
                n++;
            }
            b.recycle();
            if (n == 0) return 0xFF6FA8FF;
            float[] hsv = new float[3];
            Color.RGBToHSV((int) (r / n), (int) (g / n), (int) (bl / n), hsv);
            hsv[1] = Math.min(1f, hsv[1] * 1.3f + 0.1f);
            hsv[2] = Math.max(0.7f, hsv[2]);
            return Color.HSVToColor(hsv);
        } catch (Exception e) {
            return 0xFF6FA8FF;
        }
    }

    private static int blend(int a, int b, float t) {
        return Color.argb(Math.round(Color.alpha(a) + (Color.alpha(b) - Color.alpha(a)) * t),
                Math.round(Color.red(a) + (Color.red(b) - Color.red(a)) * t),
                Math.round(Color.green(a) + (Color.green(b) - Color.green(a)) * t),
                Math.round(Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t));
    }

    private static Bitmap decodePoster(File file, int targetWidth) {
        try {
            if (!file.isFile() || file.length() > 10L * 1024 * 1024) return null;
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getPath(), o);
            if (o.outWidth <= 0) return null;
            int sample = 1;
            while (o.outWidth / (sample * 2) >= targetWidth) sample *= 2;
            o.inJustDecodeBounds = false;
            o.inSampleSize = sample;
            o.inPreferredConfig = Bitmap.Config.RGB_565;
            return BitmapFactory.decodeFile(file.getPath(), o);
        } catch (Exception | OutOfMemoryError e) {
            return null;
        }
    }
}
