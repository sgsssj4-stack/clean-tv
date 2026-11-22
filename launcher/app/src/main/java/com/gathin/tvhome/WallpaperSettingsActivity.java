package com.gathin.tvhome;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Settings > Wallpaper: which collection Home shows (today's season by default, or one picked), which of its
 * pictures are in the rotation, how often it changes and in what order. Choices go to the launcher's settings
 * and Config applies them over the theme.
 */
public final class WallpaperSettingsActivity extends Activity {
    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(LauncherLocale.wrap(base));
    }

    private static final String AUTO = "";
    private static final String NONE = "none";
    private static final int[] EVERY = {1, 5, 10, 30, 60, 0};
    private static final String[] EVERY_LABELS = {"1 min", "5 min", "10 min", "30 min", "1 hour", "Never"};
    private static final String[] ORDERS = {WallpaperRotation.ORDER_SEQUENTIAL, WallpaperRotation.ORDER_SHUFFLE};
    private static final int GRID_COLUMNS = 6;

    private SharedPreferences choices;
    private File dir;
    private Config config;
    private float dp;
    private Typeface light, regular, medium;
    private final ExecutorService decoder = Executors.newSingleThreadExecutor();
    private final Map<String, Bitmap> thumbs = new HashMap<>();

    private final List<String> collections = new ArrayList<>();
    private final List<View> themeCards = new ArrayList<>();
    private final List<String> themeKeys = new ArrayList<>();
    private final TextView[] everyChoices = new TextView[EVERY.length];
    private final TextView[] orderChoices = new TextView[ORDERS.length];
    private TextView status;
    private TextView gridTitle;
    private TextView useAll;
    private LinearLayout grid;
    private View gridSection;
    private List<File> shown = Collections.emptyList();
    private final List<View> pictureTiles = new ArrayList<>();

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        choices = getSharedPreferences(Config.SETTINGS_NAME, MODE_PRIVATE);
        dir = Config.dir(this);
        config = Config.load(this);
        dp = getResources().getDisplayMetrics().density;
        light = UiFont.get(this, R.string.font_light);
        regular = UiFont.get(this, R.string.font_regular);
        medium = UiFont.get(this, R.string.font_medium);
        collections.addAll(collectionNames(dir));

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF0A0E16);
        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setClipToPadding(false);
        scroll.setFillViewport(true);
        root.addView(scroll, new FrameLayout.LayoutParams(MATCH, MATCH));
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(px(64), px(36), px(64), px(48));
        page.setClipChildren(false);
        page.setClipToPadding(false);
        scroll.addView(page, new FrameLayout.LayoutParams(MATCH, WRAP));

        TextView title = text(page, 40, light, Color.WHITE);
        title.setText(getString(R.string.wallpaper_label));
        title.setIncludeFontPadding(false);
        status = text(page, 15, regular, 0xB3FFFFFF);
        ((LinearLayout.LayoutParams) status.getLayoutParams()).topMargin = px(8);

        // Themes: a picture and a name each; Automatic follows the calendar
        heading(page, "THEME", 24);
        HorizontalScrollView themeScroll = new HorizontalScrollView(this);
        themeScroll.setHorizontalScrollBarEnabled(false);
        themeScroll.setClipToPadding(false);
        themeScroll.setClipChildren(false);
        themeScroll.setPadding(px(10), px(10), px(10), px(10));
        LinearLayout.LayoutParams tsl = new LinearLayout.LayoutParams(MATCH, WRAP);
        tsl.leftMargin = -px(10);
        tsl.rightMargin = -px(10);
        page.addView(themeScroll, tsl);
        LinearLayout themes = new LinearLayout(this);
        themes.setOrientation(LinearLayout.HORIZONTAL);
        themes.setClipChildren(false);
        themeScroll.addView(themes, new FrameLayout.LayoutParams(WRAP, WRAP));
        if (!collections.isEmpty()) {
            String today = todaysCollection();
            themeCard(themes, AUTO, "Automatic", "Today: " + nice(today), first(today), true);
            for (String c : collections) {
                int n = Seasons.images(dir, c).size();
                themeCard(themes, c, nice(c), n + (n == 1 ? " picture" : " pictures"), first(c), false);
            }
        } else {
            List<File> pics = plainWallpapers();
            themeCard(themes, AUTO, "Theme", pics.size() + " pictures", pics.isEmpty() ? null : pics.get(0), false);
        }
        themeCard(themes, NONE, getString(R.string.wallpaper_none), "Plain background", null, false);

        // Timing and order
        LinearLayout timing = new LinearLayout(this);
        timing.setOrientation(LinearLayout.HORIZONTAL);
        timing.setGravity(Gravity.CENTER_VERTICAL);
        timing.setBaselineAligned(false);
        timing.setPadding(px(24), px(14), px(24), px(14));
        timing.setBackground(glass(px(22), 0x17FFFFFF, 0x24FFFFFF));
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(MATCH, WRAP);
        tl.topMargin = px(14);
        page.addView(timing, tl);
        TextView everyLabel = text(timing, 16, regular, 0xE6FFFFFF);
        everyLabel.setText("Change every");
        ((LinearLayout.LayoutParams) everyLabel.getLayoutParams()).rightMargin = px(16);
        timing.addView(Segments.build(this, dp, medium, EVERY_LABELS, everyChoices, i -> {
            choices.edit().putInt(Config.WALLPAPER_MINUTES_KEY, EVERY[i]).apply();
        }, this::saved), new LinearLayout.LayoutParams(WRAP, WRAP));
        timing.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));
        TextView orderLabel = text(timing, 16, regular, 0xE6FFFFFF);
        orderLabel.setText("Order");
        ((LinearLayout.LayoutParams) orderLabel.getLayoutParams()).rightMargin = px(16);
        timing.addView(Segments.build(this, dp, medium, new String[]{"In order", "Shuffle"}, orderChoices, i -> {
            choices.edit().putString(Config.WALLPAPER_ORDER_KEY, ORDERS[i]).apply();
        }, this::saved), new LinearLayout.LayoutParams(WRAP, WRAP));

        // The pictures of the collection on Home: each one in or out of the rotation
        LinearLayout section = new LinearLayout(this);
        section.setOrientation(LinearLayout.VERTICAL);
        section.setClipChildren(false);
        section.setClipToPadding(false);
        page.addView(section, new LinearLayout.LayoutParams(MATCH, WRAP));
        gridSection = section;
        LinearLayout gridHead = new LinearLayout(this);
        gridHead.setOrientation(LinearLayout.HORIZONTAL);
        gridHead.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams ghl = new LinearLayout.LayoutParams(MATCH, px(44));
        ghl.topMargin = px(18);
        section.addView(gridHead, ghl);
        gridTitle = text(gridHead, 12, medium, 0x8CFFFFFF);
        gridTitle.setLetterSpacing(0.16f);
        gridHead.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));
        useAll = text(gridHead, 14, medium, Color.WHITE);
        useAll.setText("Use all");
        useAll.setGravity(Gravity.CENTER);
        useAll.setPadding(px(16), 0, px(16), 0);
        useAll.getLayoutParams().height = px(34);
        GradientDrawable useBg = glass(px(17), 0x24FFFFFF, 0x33FFFFFF);
        useAll.setBackground(useBg);
        useAll.setFocusable(true);
        useAll.setClickable(true);
        useAll.setOnFocusChangeListener((v, f) -> {
            useBg.setColor(f ? 0xF2FFFFFF : 0x24FFFFFF);
            useAll.setTextColor(f ? 0xFF0B1220 : Color.WHITE);
        });
        useAll.setOnClickListener(v -> {
            Set<String> out = new HashSet<>(excluded());
            for (File f : shown) out.remove(f.getName());
            choices.edit().putStringSet(Config.WALLPAPER_EXCLUDED_KEY, out).apply();
            saved();
        });
        TextView hint = text(section, 13, regular, 0x80FFFFFF);
        hint.setText("Select a picture to take it out of the rotation, or to put it back.");
        grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        grid.setClipChildren(false);
        grid.setClipToPadding(false);
        LinearLayout.LayoutParams gl = new LinearLayout.LayoutParams(MATCH, WRAP);
        gl.topMargin = px(12);
        section.addView(grid, gl);

        setContentView(root);
        refresh();
        int selected = themeKeys.indexOf(currentKey());
        (selected >= 0 ? themeCards.get(selected) : themeCards.get(0)).requestFocus();
    }

    @Override
    protected void onDestroy() {
        decoder.shutdownNow();
        super.onDestroy();
    }

    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT, WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;

    private int px(float v) {
        return Math.round(v * dp);
    }

    // ---- what is chosen ---------------------------------------------------------------------------------

    private String currentKey() {
        if (NONE.equals(choices.getString(Config.WALLPAPER_CHOICE_KEY, null))) return NONE;
        String c = choices.getString(Config.WALLPAPER_COLLECTION_KEY, null);
        return c != null && collections.contains(c) ? c : AUTO;
    }

    /** The collection whose pictures Home shows now (Automatic: today's). */
    private String shownCollection() {
        String key = currentKey();
        return AUTO.equals(key) ? todaysCollection() : key;
    }

    private String todaysCollection() {
        if (collections.isEmpty()) return "";
        Seasons.Season season = Seasons.activeOn(Seasons.load(dir), Seasons.today(this));
        String c = season == null ? Seasons.EVERYDAY : season.collection;
        if (Seasons.images(dir, c).isEmpty()) c = Seasons.EVERYDAY;
        return c;
    }

    private Set<String> excluded() {
        return choices.getStringSet(Config.WALLPAPER_EXCLUDED_KEY, Collections.emptySet());
    }

    private void pickTheme(String key) {
        SharedPreferences.Editor e = choices.edit();
        if (NONE.equals(key)) {
            e.putString(Config.WALLPAPER_CHOICE_KEY, NONE).remove(Config.WALLPAPER_COLLECTION_KEY);
        } else {
            e.remove(Config.WALLPAPER_CHOICE_KEY);   // the old one-picture pick gives way to the collection
            if (AUTO.equals(key)) e.remove(Config.WALLPAPER_COLLECTION_KEY);
            else e.putString(Config.WALLPAPER_COLLECTION_KEY, key);
        }
        e.apply();
        saved();
    }

    private void toggle(File picture) {
        Set<String> out = new HashSet<>(excluded());
        String name = picture.getName();
        if (out.contains(name)) {
            out.remove(name);
        } else {
            int left = 0;
            for (File f : shown) if (!out.contains(f.getName())) left++;
            if (left <= 1) {
                Toast.makeText(this, "Keep at least one picture", Toast.LENGTH_SHORT).show();
                return;
            }
            out.add(name);
        }
        choices.edit().putStringSet(Config.WALLPAPER_EXCLUDED_KEY, out).apply();
        saved();
    }

    /** A choice was saved: Home rebuilds when it next resumes. */
    private void saved() {
        setResult(RESULT_OK);
        choices.edit().putLong(Config.SETTINGS_REVISION_KEY, choices.getLong(Config.SETTINGS_REVISION_KEY, 0) + 1).apply();
        config = Config.load(this);
        refresh();
    }

    private void refresh() {
        String key = currentKey();
        for (int i = 0; i < themeCards.size(); i++) paintTheme(themeCards.get(i), themeKeys.get(i).equals(key));
        for (int i = 0; i < EVERY.length; i++) Segments.mark(everyChoices[i], config.wallpaperRotationMinutes == EVERY[i], dp);
        for (int i = 0; i < ORDERS.length; i++) Segments.mark(orderChoices[i], ORDERS[i].equals(config.wallpaperOrder), dp);
        status.setText(summary(this, config));

        boolean none = NONE.equals(key);
        gridSection.setVisibility(none ? View.GONE : View.VISIBLE);
        if (none) return;
        String collection = shownCollection();
        List<File> pictures = collection.isEmpty() ? plainWallpapers() : Seasons.images(dir, collection);
        if (!samePictures(pictures, shown)) buildGrid(pictures);
        Set<String> out = excluded();
        int in = 0;
        for (int i = 0; i < pictureTiles.size(); i++) {
            boolean on = !out.contains(shown.get(i).getName());
            if (on) in++;
            paintPicture(pictureTiles.get(i), on);
        }
        gridTitle.setText(String.format(Locale.ROOT, "%s · %d OF %d IN ROTATION",
                (collection.isEmpty() ? "PICTURES" : nice(collection).toUpperCase(Locale.ROOT)), in, shown.size()));
        useAll.setVisibility(in < shown.size() ? View.VISIBLE : View.INVISIBLE);
    }

    private static boolean samePictures(List<File> a, List<File> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) if (!a.get(i).equals(b.get(i))) return false;
        return true;
    }

    /** One line for Settings and this screen's header: the collection, its count, how often it changes. */
    static String summary(Context ctx, Config config) {
        SharedPreferences choices = ctx.getSharedPreferences(Config.SETTINGS_NAME, MODE_PRIVATE);
        if (NONE.equals(choices.getString(Config.WALLPAPER_CHOICE_KEY, null)) || !config.wallpaperEnabled) {
            return ctx.getString(R.string.wallpaper_none);
        }
        String c = config.seasonCollection == null || config.seasonCollection.isEmpty() ? "Theme" : nice(config.seasonCollection);
        boolean auto = choices.getString(Config.WALLPAPER_COLLECTION_KEY, null) == null;
        int n = config.wallpapers.size();
        String every = config.wallpaperRotationMinutes <= 0 || n < 2 ? "stays put"
                : config.wallpaperRotationMinutes % 60 == 0 ? "every " + (config.wallpaperRotationMinutes / 60) + " h"
                : "every " + config.wallpaperRotationMinutes + " min";
        return (auto ? "Automatic · " : "") + c + " · " + n + (n == 1 ? " picture · " : " pictures · ") + every;
    }

    // ---- collections on the TV ----------------------------------------------------------------------------

    /** Folders under collections/ with pictures: everyday first, then by name. */
    private static List<String> collectionNames(File dir) {
        List<String> out = new ArrayList<>();
        File[] folders = dir == null ? null : new File(dir, Seasons.COLLECTIONS).listFiles(File::isDirectory);
        if (folders == null) return out;
        for (File f : folders) if (!Seasons.images(dir, f.getName()).isEmpty()) out.add(f.getName());
        Collections.sort(out, (a, b) -> Seasons.EVERYDAY.equals(a) ? -1 : Seasons.EVERYDAY.equals(b) ? 1 : a.compareTo(b));
        return out;
    }

    private List<File> plainWallpapers() {
        return dir == null ? Collections.emptyList() : WallpaperRotation.images(new File(dir, "wallpapers"));
    }

    private File first(String collection) {
        List<File> pics = Seasons.images(dir, collection);
        return pics.isEmpty() ? null : pics.get(0);
    }

    static String nice(String name) {
        if (name == null || name.isEmpty()) return "";
        StringBuilder b = new StringBuilder();
        for (String w : name.replace('_', '-').split("-")) {
            if (w.isEmpty()) continue;
            if (b.length() > 0) b.append(' ');
            b.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
        }
        return b.toString();
    }

    // ---- views ------------------------------------------------------------------------------------------

    private TextView text(ViewGroup parent, int sp, Typeface face, int colour) {
        TextView t = new TextView(this);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTypeface(face);
        t.setTextColor(colour);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        parent.addView(t, new LinearLayout.LayoutParams(WRAP, WRAP));
        return t;
    }

    private void heading(LinearLayout page, String label, int top) {
        TextView h = text(page, 12, medium, 0x8CFFFFFF);
        h.setText(label);
        h.setLetterSpacing(0.16f);
        ((LinearLayout.LayoutParams) h.getLayoutParams()).topMargin = px(top);
    }

    private GradientDrawable glass(float radius, int fill, int stroke) {
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(radius);
        g.setColor(fill);
        g.setStroke(Math.max(1, px(1)), stroke);
        return g;
    }

    private void rounded(View v, float radius) {
        v.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
            }
        });
        v.setClipToOutline(true);
    }

    /** A theme: its first picture, a name and a quiet line under it; a tick on the chosen one. */
    private void themeCard(LinearLayout row, String key, String name, String hint, File picture, boolean auto) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setClipChildren(false);
        card.setClipToPadding(false);
        FrameLayout frame = new FrameLayout(this);
        frame.setBackground(glass(px(14), 0x1FFFFFFF, 0x26FFFFFF));
        rounded(frame, px(14));
        card.addView(frame, new LinearLayout.LayoutParams(px(176), px(99)));
        ImageView image = new ImageView(this);
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        frame.addView(image, new FrameLayout.LayoutParams(MATCH, MATCH));
        if (picture != null) thumb(picture, image, 352);
        else {
            image.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                    new int[]{0xFF1A2130, 0xFF0B0F18}));
        }
        if (auto) {
            TextView badge = new TextView(this);
            badge.setText("AUTO");
            badge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
            badge.setTypeface(medium);
            badge.setLetterSpacing(0.12f);
            badge.setTextColor(Color.WHITE);
            badge.setPadding(px(8), px(3), px(8), px(3));
            badge.setBackground(glass(px(9), 0xB30A84FF, 0x00000000));
            FrameLayout.LayoutParams bl = new FrameLayout.LayoutParams(WRAP, WRAP, Gravity.BOTTOM | Gravity.START);
            bl.setMargins(px(8), 0, 0, px(8));
            frame.addView(badge, bl);
        }
        View tick = tickView(false);
        FrameLayout.LayoutParams kl = new FrameLayout.LayoutParams(px(24), px(24), Gravity.TOP | Gravity.END);
        kl.setMargins(0, px(8), px(8), 0);
        frame.addView(tick, kl);
        View ring = new View(this);
        frame.addView(ring, new FrameLayout.LayoutParams(MATCH, MATCH));
        TextView n = text(card, 15, medium, Color.WHITE);
        n.setText(name);
        ((LinearLayout.LayoutParams) n.getLayoutParams()).topMargin = px(8);
        TextView h = text(card, 12, regular, 0x99FFFFFF);
        h.setText(hint);
        n.setMaxWidth(px(176));
        h.setMaxWidth(px(176));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(WRAP, WRAP);
        if (row.getChildCount() > 0) lp.leftMargin = px(16);
        row.addView(card, lp);
        card.setFocusable(true);
        card.setClickable(true);
        card.setContentDescription(name);
        card.setTag(new View[]{tick, ring, frame});
        card.setOnClickListener(v -> pickTheme(key));
        card.setOnFocusChangeListener((v, f) -> {
            frame.animate().scaleX(f ? 1.06f : 1f).scaleY(f ? 1.06f : 1f).translationZ(f ? px(10) : 0)
                    .setDuration(160).start();
            paintTheme(v, themeKeys.indexOf(key) >= 0 && key.equals(currentKey()));
        });
        themeCards.add(card);
        themeKeys.add(key);
    }

    private void paintTheme(View card, boolean chosen) {
        View[] parts = (View[]) card.getTag();
        parts[0].setVisibility(chosen ? View.VISIBLE : View.GONE);
        paintTick(parts[0], true);
        boolean f = card.hasFocus();
        GradientDrawable ring = new GradientDrawable();
        ring.setCornerRadius(px(14));
        if (f) ring.setStroke(px(3), Color.WHITE);
        else if (chosen) ring.setStroke(px(2), 0xFF0A84FF);
        parts[1].setBackground(f || chosen ? ring : null);
    }

    private View tickView(boolean on) {
        TextView t = new TextView(this);
        t.setText("✓");
        t.setGravity(Gravity.CENTER);
        t.setIncludeFontPadding(false);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        paintTick(t, on);
        return t;
    }

    private void paintTick(View v, boolean on) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(on ? 0xFF0A84FF : 0x59000000);
        g.setStroke(px(2), on ? 0xFFFFFFFF : 0xCCFFFFFF);
        v.setBackground(g);
        ((TextView) v).setTextColor(on ? Color.WHITE : Color.TRANSPARENT);
    }

    private void buildGrid(List<File> pictures) {
        shown = new ArrayList<>(pictures);
        grid.removeAllViews();
        pictureTiles.clear();
        LinearLayout row = null;
        for (int i = 0; i < shown.size(); i++) {
            if (i % GRID_COLUMNS == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setClipChildren(false);
                LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(MATCH, WRAP);
                if (i > 0) rl.topMargin = px(16);
                grid.addView(row, rl);
            }
            pictureTiles.add(pictureTile(row, shown.get(i), i % GRID_COLUMNS));
        }
        // a short last row keeps the tiles the same size
        if (row != null) {
            for (int k = shown.size() % GRID_COLUMNS; k != 0 && k < GRID_COLUMNS; k++) {
                LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(0, 1, 1f);
                sl.leftMargin = px(16);
                row.addView(new View(this), sl);
            }
        }
    }

    private View pictureTile(LinearLayout row, File picture, int column) {
        FrameLayout tile = new FrameLayout(this);
        tile.setBackground(glass(px(12), 0x1FFFFFFF, 0x00000000));
        rounded(tile, px(12));
        ImageView image = new ImageView(this);
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        tile.addView(image, new FrameLayout.LayoutParams(MATCH, MATCH));
        thumb(picture, image, 300);
        View veil = new View(this);
        veil.setBackgroundColor(0x99060A12);
        tile.addView(veil, new FrameLayout.LayoutParams(MATCH, MATCH));
        View tick = tickView(true);
        FrameLayout.LayoutParams kl = new FrameLayout.LayoutParams(px(24), px(24), Gravity.TOP | Gravity.END);
        kl.setMargins(0, px(7), px(7), 0);
        tile.addView(tick, kl);
        View ring = new View(this);
        tile.addView(ring, new FrameLayout.LayoutParams(MATCH, MATCH));
        int width = (getResources().getDisplayMetrics().widthPixels - px(128) - px(16) * (GRID_COLUMNS - 1)) / GRID_COLUMNS;
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, Math.round(width * 9f / 16f), 1f);
        if (column > 0) lp.leftMargin = px(16);
        row.addView(tile, lp);
        tile.setFocusable(true);
        tile.setClickable(true);
        tile.setContentDescription(picture.getName());
        tile.setTag(new View[]{veil, tick, ring});
        tile.setOnClickListener(v -> toggle(picture));
        tile.setOnFocusChangeListener((v, f) -> {
            v.animate().scaleX(f ? 1.06f : 1f).scaleY(f ? 1.06f : 1f).translationZ(f ? px(10) : 0).setDuration(160).start();
            paintPicture(v, !excluded().contains(picture.getName()));
            // room for the lifted tile and its ring below the screen's edge
            if (f) v.requestRectangleOnScreen(new android.graphics.Rect(0, -px(24), v.getWidth(), v.getHeight() + px(40)));
        });
        return tile;
    }

    private void paintPicture(View tile, boolean on) {
        View[] parts = (View[]) tile.getTag();
        parts[0].setVisibility(on ? View.GONE : View.VISIBLE);
        paintTick(parts[1], on);
        GradientDrawable ring = new GradientDrawable();
        ring.setCornerRadius(px(12));
        ring.setStroke(px(3), Color.WHITE);
        parts[2].setBackground(tile.hasFocus() ? ring : null);
    }

    /** Decodes a small copy of the picture off the main thread. */
    private void thumb(File file, ImageView into, int widthPx) {
        String key = file.getPath() + "@" + widthPx;
        Bitmap cached = thumbs.get(key);
        if (cached != null) {
            into.setImageBitmap(cached);
            return;
        }
        decoder.execute(() -> {
            try {
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inJustDecodeBounds = true;
                BitmapFactory.decodeFile(file.getPath(), o);
                int sample = 1;
                while (o.outWidth / (sample * 2) >= widthPx) sample *= 2;
                BitmapFactory.Options d = new BitmapFactory.Options();
                d.inSampleSize = sample;
                d.inPreferredConfig = Bitmap.Config.RGB_565;
                Bitmap b = BitmapFactory.decodeFile(file.getPath(), d);
                if (b == null) return;
                into.post(() -> {
                    if (isDestroyed()) return;
                    thumbs.put(key, b);
                    into.setImageBitmap(b);
                });
            } catch (Throwable ignored) {
                // a damaged picture keeps its plain tile
            }
        });
    }
}
