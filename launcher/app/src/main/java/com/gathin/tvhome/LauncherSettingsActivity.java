package com.gathin.tvhome;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.LinearLayout;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Settings: the launcher's own look and behaviour, with the TV's settings and inputs at the top. Built in code. */
public final class LauncherSettingsActivity extends Activity {
    @Override
    protected void attachBaseContext(android.content.Context base) {
        super.attachBaseContext(LauncherLocale.wrap(base));   // locale
    }

    static final String EXTRA_REORDER = "com.gathin.tvhome.extra.REORDER_FROM_SETTINGS";
    private SharedPreferences choices;
    private final TextView[] columnChoices = new TextView[4];
    private final TextView[] rowChoices = new TextView[4];
    private static final int[] DOCK_OPACITIES = {100, 70, 50, 30};
    private final TextView[] dockChoices = new TextView[DOCK_OPACITIES.length];
    private static final String[] CLOCK_FORMATS = {"system", "12", "24"};
    private final TextView[] clockChoices = new TextView[CLOCK_FORMATS.length];
    private TextView wallpaperChoice;
    private TextView animationsChoice;
    private TextView advancedChoice;
    private TextView followThemeChoice;
    private TextView openOnStartChoice;
    private int columns;
    private int rowsBelowDock;
    private int dockOpacity;
    private String clockFormat;
    private boolean animations;
    private boolean advancedEffects;
    private boolean renewalsTile;
    private TextView renewalsChoice;
    private boolean arcadeTile;
    private TextView arcadeChoice;
    private boolean fadeWhenIdle;
    private TextView fadeChoice;
    private static final int[] WALLPAPER_EVERY = {1, 5, 10, 30, 60, 0};
    private static final String[] WALLPAPER_EVERY_LABELS = {"1 min", "5 min", "10 min", "30 min", "1 hour", "Never"};
    private final TextView[] wallpaperEveryChoices = new TextView[WALLPAPER_EVERY.length];
    private static final int[] CLOCK_AFTER = {1, 2, 5, 10, 0};
    private static final String[] CLOCK_AFTER_LABELS = {"1 min", "2 min", "5 min", "10 min", "Never"};
    private final TextView[] clockAfterChoices = new TextView[CLOCK_AFTER.length];
    private static final int[] FADE_AFTER = {10, 20, 30, 60};
    private static final String[] FADE_AFTER_LABELS = {"10 sec", "20 sec", "30 sec", "1 min"};
    private final TextView[] fadeAfterChoices = new TextView[FADE_AFTER.length];
    private LinearLayout fadeAfterLine;

    private Typeface light, regular, medium;
    private float dp;
    private Config config;
    private TextView wallpaperValue;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        choices = getSharedPreferences(Config.SETTINGS_NAME, MODE_PRIVATE);
        config = Config.load(this);
        dp = getResources().getDisplayMetrics().density;
        light = UiFont.get(this, R.string.font_light);
        regular = UiFont.get(this, R.string.font_regular);
        medium = UiFont.get(this, R.string.font_medium);
        boolean dashboard = "dashboard".equals(config.homeLayout);
        columns = config.columns;
        rowsBelowDock = config.rowsBelowDock;
        dockOpacity = config.dockOpacity;
        clockFormat = config.clockFormat;
        animations = config.animations;
        advancedEffects = config.animations && config.advancedEffects;
        renewalsTile = config.renewalsTile;
        arcadeTile = config.arcadeTile;
        fadeWhenIdle = config.fadeWhenIdle;

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF0A0E16);
        ImageView back = new ImageView(this);
        back.setScaleType(ImageView.ScaleType.CENTER_CROP);
        root.addView(back, new FrameLayout.LayoutParams(MATCH, MATCH));
        View scrim = new View(this);
        scrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0xE6060A12, 0xB3060A12, 0xCC060A12}));
        root.addView(scrim, new FrameLayout.LayoutParams(MATCH, MATCH));
        loadBackdrop(back);

        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setClipToPadding(false);
        scroll.setFillViewport(true);
        scroll.setSmoothScrollingEnabled(true);
        root.addView(scroll, new FrameLayout.LayoutParams(MATCH, MATCH));
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(px(64), px(40), px(64), px(48));
        page.setClipChildren(false);
        page.setClipToPadding(false);
        scroll.addView(page, new FrameLayout.LayoutParams(MATCH, WRAP));

        // Title
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.BOTTOM);
        page.addView(head, new LinearLayout.LayoutParams(MATCH, WRAP));
        TextView title = text(head, 46, light, Color.WHITE);
        title.setText("Settings");
        title.setIncludeFontPadding(false);
        TextView sub = text(head, 15, regular, 0xA6FFFFFF);
        sub.setText(dashboard ? "How Home looks and moves. The TV's own settings and inputs are right here too."
                : "How Home looks and moves.");
        LinearLayout.LayoutParams sl = (LinearLayout.LayoutParams) sub.getLayoutParams();
        sl.leftMargin = px(20);
        sl.bottomMargin = px(7);

        // The TV's own first: settings, inputs; then the two Home basics
        LinearLayout quick = new LinearLayout(this);
        quick.setOrientation(LinearLayout.HORIZONTAL);
        quick.setClipChildren(false);
        LinearLayout.LayoutParams ql = new LinearLayout.LayoutParams(MATCH, px(118));
        ql.topMargin = px(26);
        page.addView(quick, ql);
        View tvTile = quickTile(quick, DashboardHome.GlyphView.SLIDERS, "TV settings", "Picture, sound, network",
                v -> open(SystemSettings.intent(this, Settings.ACTION_SETTINGS)));
        quickTile(quick, DashboardHome.GlyphView.INPUT, "Inputs", "HDMI, AV, Live TV", v -> openInputs());
        View wp = quickTile(quick, DashboardHome.GlyphView.GRID, getString(R.string.wallpaper_label), "", v -> open(new Intent(this, WallpaperSettingsActivity.class)));
        wallpaperValue = (TextView) wp.getTag();
        quickTile(quick, DashboardHome.GlyphView.GEAR, "Default home", "Which home the TV opens",
                v -> open(new Intent(this, HomeSettingsActivity.class)));

        // Two columns of cards, each as tall as its own lines: Layout, Motion, Clock | Home, Timings
        LinearLayout columnsRow = cardRow(page);
        columnsRow.setBaselineAligned(false);
        LinearLayout left = column(columnsRow);
        LinearLayout right = column(columnsRow);
        LinearLayout layout = card(left, "LAYOUT");
        segmented(layout, dashboard ? "All apps columns" : "Columns", new String[]{"4", "5", "6", "7"}, columnChoices,
                i -> {
                    if (columns == i + 4) return;
                    columns = i + 4;
                    choices.edit().putInt("columns", columns).apply();
                });
        if (!dashboard) {
            segmented(layout, "Rows below dock", new String[]{"0", "1", "2", "3"}, rowChoices, i -> {
                if (rowsBelowDock == i) return;
                rowsBelowDock = i;
                choices.edit().putInt(Config.ROWS_BELOW_DOCK_KEY, i).apply();
            });
        }
        segmented(layout, dashboard ? "Glass opacity" : "Dock opacity", new String[]{"100%", "70%", "50%", "30%"}, dockChoices, i -> {
            if (dockOpacity == DOCK_OPACITIES[i]) return;
            dockOpacity = DOCK_OPACITIES[i];
            choices.edit().putInt(Config.DOCK_OPACITY_KEY, dockOpacity).apply();
        });
        LinearLayout motion = card(left, "MOTION");
        animationsChoice = action(motion, "Animations", v -> {
            animations = !animations;
            if (!animations) advancedEffects = false;
            choices.edit().putBoolean("animations", animations)
                    .putBoolean("advanced_effects", advancedEffects).apply();
            changed();
            refresh();
        });
        advancedChoice = action(motion, getString(R.string.advanced_effects), v -> {
            if (!animations) return;
            if (advancedEffects) {
                setAdvanced(false);
            } else {
                new AlertDialog.Builder(this)
                        .setTitle(R.string.advanced_effects)
                        .setMessage(R.string.advanced_effects_warning)
                        .setPositiveButton(R.string.enable, (dialog, which) -> setAdvanced(true))
                        .setNegativeButton(android.R.string.cancel, null)
                        .show();
            }
        });
        LinearLayout clockCard = card(left, "CLOCK");
        segmented(clockCard, "Format", new String[]{getString(R.string.clock_format_system), "12h", "24h"}, clockChoices, i -> {
            if (CLOCK_FORMATS[i].equals(clockFormat)) return;
            clockFormat = CLOCK_FORMATS[i];
            choices.edit().putString(Config.CLOCK_FORMAT_KEY, clockFormat).apply();
        });
        openOnStartChoice = action(clockCard, getString(R.string.open_on_start_label), v -> showOpenOnStartPicker());
        ((View) openOnStartChoice.getParent()).setVisibility(config.openOnStartChoice ? View.VISIBLE : View.GONE);

        LinearLayout extras = card(right, "HOME");
        arcadeChoice = action(extras, "Arcade panel", v -> {
            arcadeTile = !arcadeTile;
            choices.edit().putBoolean("arcade_tile", arcadeTile).apply();
            changed();
            refresh();
        });
        ((View) arcadeChoice.getParent()).setVisibility(dashboard ? View.VISIBLE : View.GONE);
        // after a while without a key Home fades to a faint veil so the wallpaper shows; any key brings it back
        fadeChoice = action(extras, "Fade when idle", v -> {
            fadeWhenIdle = !fadeWhenIdle;
            choices.edit().putBoolean("fade_when_idle", fadeWhenIdle).apply();
            changed();
            refresh();
        });
        ((View) fadeChoice.getParent()).setVisibility(dashboard ? View.VISIBLE : View.GONE);
        renewalsChoice = action(extras, "Renewals panel", v -> {
            renewalsTile = !renewalsTile;
            choices.edit().putBoolean("renewals_tile", renewalsTile).apply();
            changed();
            refresh();
        });
        ((View) renewalsChoice.getParent()).setVisibility(dashboard ? View.VISIBLE : View.GONE);
        followThemeChoice = action(extras, "Follow theme", v -> {
            if (!hasOnTvChoices()) return;
            SharedPreferences.Editor e = choices.edit();
            for (String key : ON_TV_KEYS) e.remove(key);
            e.commit();
            Config current = Config.load(this);
            columns = current.columns;
            rowsBelowDock = current.rowsBelowDock;
            dockOpacity = current.dockOpacity;
            clockFormat = current.clockFormat;
            animations = current.animations;
            advancedEffects = current.animations && current.advancedEffects;
            config = current;
            changed();
            refresh();
        });
        TextView reorder = action(extras, getString(R.string.reorder), v -> {
            setResult(RESULT_OK, new Intent().putExtra(EXTRA_REORDER, true));
            finish();
        });
        ((View) reorder.getParent()).setVisibility(config.launcherSettingsButton ? View.VISIBLE : View.GONE);
        reorder.setText("›");

        LinearLayout timings = card(right, "TIMINGS");
        stacked(timings, "Wallpaper changes every", WALLPAPER_EVERY_LABELS, wallpaperEveryChoices, i -> {
            choices.edit().putInt(Config.WALLPAPER_MINUTES_KEY, WALLPAPER_EVERY[i]).apply();
            config = Config.load(this);
        });
        stacked(timings, "Clock screen after Home is idle for", CLOCK_AFTER_LABELS, clockAfterChoices, i -> {
            choices.edit().putInt(Config.IDLE_CLOCK_KEY, CLOCK_AFTER[i]).apply();
            config = Config.load(this);
        });
        fadeAfterLine = stacked(timings, "Home fades after", FADE_AFTER_LABELS, fadeAfterChoices, i -> {
            choices.edit().putInt(Config.FADE_SECONDS_KEY, FADE_AFTER[i]).apply();
            config = Config.load(this);
        });
        fadeAfterLine.setVisibility(dashboard ? View.VISIBLE : View.GONE);

        if (getResources().getBoolean(R.bool.rtl_menu_bar)) root.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        setContentView(root);
        refresh();
        tvTile.requestFocus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        config = Config.load(this);   // the Wallpaper screen may have changed the collection or its timing
        if (wallpaperValue != null) refresh();
    }

    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT, WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;

    private int px(float v) {
        return Math.round(v * dp);
    }

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

    /** The picture Home is showing, blurred, behind everything. */
    private void loadBackdrop(ImageView into) {
        File file = null;
        if (!config.wallpapers.isEmpty()) {
            String shown = getSharedPreferences("wallpaper_rotation", MODE_PRIVATE).getString("image", "");
            file = WallpaperRotation.find(config.wallpapers, shown);
            if (file == null) file = config.wallpapers.get(0);
        }
        if (file == null && config.wallpaper != null && config.wallpaper.isFile()) file = config.wallpaper;
        if (file == null) return;
        File pick = file;
        int w = getResources().getDisplayMetrics().widthPixels, h = getResources().getDisplayMetrics().heightPixels;
        new Thread(() -> {
            Bitmap b = GlassBlur.make(pick, w, h, 10);
            if (b != null) into.post(() -> {
                if (!isFinishing()) into.setImageBitmap(b);
            });
        }, "ux-backdrop").start();
    }

    private GradientDrawable glass(float radius, int fill, int stroke) {
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(radius);
        g.setColor(fill);
        g.setStroke(Math.max(1, px(1)), stroke);
        return g;
    }

    /** A big frosted tile: icon, name, a quiet line. Focus fills it white. The hint TextView is its tag. */
    private View quickTile(LinearLayout row, int glyph, String name, String hint, View.OnClickListener click) {
        LinearLayout t = new LinearLayout(this);
        t.setOrientation(LinearLayout.VERTICAL);
        t.setGravity(Gravity.CENTER_VERTICAL);
        t.setPadding(px(22), px(14), px(18), px(14));
        GradientDrawable bg = glass(px(22), 0x24FFFFFF, 0x33FFFFFF);
        t.setBackground(bg);
        DashboardHome.GlyphView icon = new DashboardHome.GlyphView(this, glyph);
        t.addView(icon, new LinearLayout.LayoutParams(px(26), px(26)));
        TextView n = text(t, 18, medium, Color.WHITE);
        n.setText(name);
        ((LinearLayout.LayoutParams) n.getLayoutParams()).topMargin = px(12);
        TextView h = text(t, 12, regular, 0x99FFFFFF);
        h.setText(hint);
        ((LinearLayout.LayoutParams) h.getLayoutParams()).topMargin = px(2);
        t.setTag(h);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, MATCH, 1f);
        if (row.getChildCount() > 0) lp.leftMargin = px(16);
        row.addView(t, lp);
        t.setFocusable(true);
        t.setClickable(true);
        t.setContentDescription(name);
        t.setOnClickListener(click);
        t.setOnFocusChangeListener((v, f) -> {
            v.animate().scaleX(f ? 1.05f : 1f).scaleY(f ? 1.05f : 1f).translationZ(f ? px(8) : 0).setDuration(150).start();
            bg.setColor(f ? 0xF2FFFFFF : 0x24FFFFFF);
            bg.setStroke(Math.max(1, px(1)), f ? 0xFFFFFFFF : 0x33FFFFFF);
            n.setTextColor(f ? 0xFF0B1220 : Color.WHITE);
            h.setTextColor(f ? 0xB30B1220 : 0x99FFFFFF);
            icon.setColour(f ? 0xFF0B1220 : 0xE6FFFFFF);
        });
        return t;
    }

    private LinearLayout cardRow(LinearLayout page) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setClipChildren(false);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(MATCH, WRAP);
        lp.topMargin = px(18);
        page.addView(r, lp);
        return r;
    }

    private LinearLayout card(LinearLayout row, String heading) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(px(24), px(16), px(24), px(8));
        c.setBackground(glass(px(22), 0x17FFFFFF, 0x24FFFFFF));
        c.setClipChildren(false);
        c.setClipToPadding(false);
        TextView h = text(c, 12, medium, 0x8CFFFFFF);
        h.setText(heading);
        h.setLetterSpacing(0.16f);
        ((LinearLayout.LayoutParams) h.getLayoutParams()).bottomMargin = px(6);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(MATCH, WRAP);
        if (row.getChildCount() > 0) lp.topMargin = px(18);
        row.addView(c, lp);
        return c;
    }

    /** One of the two columns of cards. */
    private LinearLayout column(LinearLayout row) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setClipChildren(false);
        c.setClipToPadding(false);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, WRAP, 1f);
        if (row.getChildCount() > 0) lp.leftMargin = px(18);
        row.addView(c, lp);
        return c;
    }

    private LinearLayout line(LinearLayout card, String label) {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        l.setClipChildren(false);
        l.setClipToPadding(false);
        card.addView(l, new LinearLayout.LayoutParams(MATCH, px(58)));
        TextView t = text(l, 16, regular, 0xE6FFFFFF);
        t.setText(label);
        l.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));
        return l;
    }

    interface Pick {
        void at(int index);
    }

    /** A label and a segmented control; the chosen segment is a white pill. Returns the line. */
    private LinearLayout segmented(LinearLayout card, String label, String[] options, TextView[] into, Pick pick) {
        LinearLayout l = line(card, label);
        l.addView(Segments.build(this, dp, medium, options, into, pick::at, () -> {
            changed();
            refresh();
        }), new LinearLayout.LayoutParams(WRAP, WRAP));
        return l;
    }

    /** A label with a full-width segmented control under it, for options too many to sit beside the label. */
    private LinearLayout stacked(LinearLayout card, String label, String[] options, TextView[] into, Pick pick) {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams ll = new LinearLayout.LayoutParams(MATCH, WRAP);
        ll.topMargin = px(6);
        ll.bottomMargin = px(10);
        card.addView(l, ll);
        TextView t = text(l, 16, regular, 0xE6FFFFFF);
        t.setText(label);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(MATCH, WRAP);
        tl.bottomMargin = px(8);
        t.setLayoutParams(tl);
        l.addView(Segments.build(this, dp, medium, options, into, pick::at, () -> {
            changed();
            refresh();
        }, true), new LinearLayout.LayoutParams(MATCH, WRAP));
        return l;
    }

    /** A whole focusable line with its value (On, Off, a name) in a pill on the right. */
    private TextView action(LinearLayout card, String label, View.OnClickListener click) {
        LinearLayout l = line(card, label);
        TextView value = new TextView(this);
        value.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        value.setTypeface(medium);
        value.setGravity(Gravity.CENTER);
        value.setMinWidth(px(64));
        value.setPadding(px(14), 0, px(14), 0);
        value.setSingleLine(true);
        value.setEllipsize(TextUtils.TruncateAt.END);
        value.setMaxWidth(px(220));
        l.addView(value, new LinearLayout.LayoutParams(WRAP, px(36)));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(px(16));
        l.setBackground(bg);
        l.setPadding(px(14), 0, px(8), 0);
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) l.getLayoutParams();
        lp.leftMargin = -px(14);
        lp.rightMargin = -px(10);
        l.setFocusable(true);
        l.setClickable(true);
        l.setContentDescription(label);
        l.setOnClickListener(click);
        l.setOnFocusChangeListener((v, f) -> {
            bg.setColor(f ? 0x2EFFFFFF : 0x00000000);
            v.animate().scaleX(f ? 1.02f : 1f).scaleY(f ? 1.02f : 1f).setDuration(140).start();
        });
        return value;
    }

    private void paintValue(TextView value, String text, boolean on) {
        value.setText(text);
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(px(18));
        g.setColor(on ? 0xFF34C77B : 0x24FFFFFF);
        value.setBackground(g);
        value.setTextColor(on ? 0xFF06120B : 0xD9FFFFFF);
    }

    /** Closes this screen and has Home open the TV's input list under its Inputs pill. */
    private void openInputs() {
        Intent i = new Intent(MainActivity.ACTION_VIEW_INPUTS).setClass(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        finish();
        try {
            startActivity(i);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_LONG).show();
        }
    }

    /** Off, then every app with a TV entry point by name. Saved at once; it is read on the next boot. */
    private void showOpenOnStartPicker() {
        PackageManager pm = getPackageManager();
        List<String[]> apps = new ArrayList<>();
        Intent tv = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        for (ResolveInfo r : pm.queryIntentActivities(tv, 0)) {
            String pkg = r.activityInfo.packageName;
            if (pkg.equals(getPackageName())) continue;
            boolean seen = false;
            for (String[] a : apps) seen |= a[1].equals(pkg);
            if (!seen) apps.add(new String[]{r.loadLabel(pm).toString(), pkg});
        }
        Collections.sort(apps, (a, b) -> a[0].compareToIgnoreCase(b[0]));
        String[] labels = new String[apps.size() + 1];
        labels[0] = getString(R.string.off);
        String current = choices.getString(OpenOnStart.KEY, "");
        int checked = 0;
        for (int i = 0; i < apps.size(); i++) {
            labels[i + 1] = apps.get(i)[0];
            if (apps.get(i)[1].equals(current)) checked = i + 1;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.open_on_start_label)
                .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                    dialog.dismiss();
                    if (which == 0) choices.edit().remove(OpenOnStart.KEY).apply();
                    else {
                        OpenOnStart.skipThisBoot(this);
                        choices.edit().putString(OpenOnStart.KEY, apps.get(which - 1)[1]).apply();
                    }
                    refresh();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** The picked app's name, its package when it is gone, or Off. */
    private String openOnStartLabel() {
        String pkg = choices.getString(OpenOnStart.KEY, "");
        if (pkg.isEmpty()) return getString(R.string.off);
        try {
            PackageManager pm = getPackageManager();
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (PackageManager.NameNotFoundException e) {
            return pkg;
        }
    }

    /**
     * A choice was saved. Home rebuilds when it next resumes because the change count moved, whether these
     * settings are left with Back or with the remote's Home button (which cancels the activity result).
     */
    private void changed() {
        setResult(RESULT_OK);
        choices.edit().putLong(Config.SETTINGS_REVISION_KEY,
                choices.getLong(Config.SETTINGS_REVISION_KEY, 0) + 1).apply();
    }

    private void setAdvanced(boolean enabled) {
        advancedEffects = enabled;
        choices.edit().putBoolean("advanced_effects", enabled).apply();
        changed();
        refresh();
    }

    private void refresh() {
        for (int i = 0; i < columnChoices.length; i++) mark(columnChoices[i], columns == i + 4);
        for (int i = 0; i < rowChoices.length; i++) mark(rowChoices[i], rowsBelowDock == i);
        for (int i = 0; i < dockChoices.length; i++) mark(dockChoices[i], dockOpacity == DOCK_OPACITIES[i]);
        for (int i = 0; i < clockChoices.length; i++) mark(clockChoices[i], CLOCK_FORMATS[i].equals(clockFormat));
        paintValue(animationsChoice, getString(animations ? R.string.on : R.string.off), animations);
        paintValue(advancedChoice, getString(advancedEffects ? R.string.on : R.string.off), advancedEffects);
        ((View) advancedChoice.getParent()).setAlpha(animations ? 1f : 0.45f);
        paintValue(renewalsChoice, getString(renewalsTile ? R.string.on : R.string.off), renewalsTile);
        paintValue(arcadeChoice, getString(arcadeTile ? R.string.on : R.string.off), arcadeTile);
        paintValue(fadeChoice, getString(fadeWhenIdle ? R.string.on : R.string.off), fadeWhenIdle);
        boolean following = !hasOnTvChoices();
        paintValue(followThemeChoice, following ? "Following" : "Reset", following);
        wallpaperValue.setText(WallpaperSettingsActivity.summary(this, config));
        for (int i = 0; i < WALLPAPER_EVERY.length; i++) {
            mark(wallpaperEveryChoices[i], config.wallpaperRotationMinutes == WALLPAPER_EVERY[i]);
        }
        for (int i = 0; i < CLOCK_AFTER.length; i++) mark(clockAfterChoices[i], config.homeIdleClockMinutes == CLOCK_AFTER[i]);
        for (int i = 0; i < FADE_AFTER.length; i++) mark(fadeAfterChoices[i], config.fadeSeconds == FADE_AFTER[i]);
        fadeAfterLine.setAlpha(fadeWhenIdle ? 1f : 0.45f);
        paintValue(openOnStartChoice, openOnStartLabel(), !choices.getString(OpenOnStart.KEY, "").isEmpty());
    }

    private void mark(TextView chip, boolean on) {
        Segments.mark(chip, on, dp);
    }

    /** Every choice made on the TV; Follow theme removes them all. */
    static final String[] ON_TV_KEYS = {"columns", Config.ROWS_BELOW_DOCK_KEY, "animations", "advanced_effects",
            Config.WALLPAPER_CHOICE_KEY, Config.DOCK_OPACITY_KEY, Config.CLOCK_FORMAT_KEY, OpenOnStart.KEY,
            Config.WALLPAPER_COLLECTION_KEY, Config.WALLPAPER_EXCLUDED_KEY, Config.WALLPAPER_MINUTES_KEY,
            Config.WALLPAPER_ORDER_KEY, Config.IDLE_CLOCK_KEY, Config.FADE_SECONDS_KEY};

    private boolean hasOnTvChoices() {
        for (String key : ON_TV_KEYS) if (choices.contains(key)) return true;
        return false;
    }

    private void open(Intent intent) {
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_LONG).show();
        }
    }
}
