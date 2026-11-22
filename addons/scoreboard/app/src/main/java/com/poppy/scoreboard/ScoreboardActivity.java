package com.poppy.scoreboard;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.AppOpsManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Process;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Screen Time for the TV: which apps the family opened over the last six months, for how long and when, drawn as
 * tvOS-style liquid glass over a soft coloured backdrop. Streaming apps are shown by default; "All apps" adds the rest.
 * OK on an app opens a glass sheet with every sitting the system still remembers, grouped by day.
 */
public class ScoreboardActivity extends Activity {
    private boolean filledOnce;

    private static final int WHITE = 0xFFFFFFFF, WHITE_80 = 0xCCFFFFFF, WHITE_60 = 0x99FFFFFF, WHITE_45 = 0x73FFFFFF;

    private int screenW, screenH;
    private float density;
    private GlassView.Source source;

    private FrameLayout root;
    private LinearLayout page;
    private TextView subtitle;
    private final GlassView[] segments = new GlassView[2];
    private boolean showAll;

    private TextView heroTotal, heroPlays, heroAverage, heroTopName, heroTopTime, heroCaption;
    private ImageView heroTopIcon;
    private Charts.Week week;
    private ScrollView listScroll;
    private LinearLayout list;

    private FrameLayout sheetLayer;
    private View lastRow;

    private UsageData.Result data;
    private boolean loading;
    private int loadGeneration;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        DisplayMetrics dm = new DisplayMetrics();
        getWindowManager().getDefaultDisplay().getRealMetrics(dm);
        screenW = Math.max(dm.widthPixels, 1);
        screenH = Math.max(dm.heightPixels, 1);
        density = dm.density;

        Bitmap backdrop = Glass.backdrop(Math.max(screenW / 2, 16), Math.max(screenH / 2, 9));
        source = new GlassView.Source(Glass.frost(backdrop, screenW, screenH, 9), screenW, screenH);

        root = new FrameLayout(this);
        ImageView bg = new ImageView(this);
        bg.setScaleType(ImageView.ScaleType.FIT_XY);
        bg.setImageBitmap(backdrop);
        root.addView(bg, new FrameLayout.LayoutParams(-1, -1));

        page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(48), dp(30), dp(48), dp(26));
        root.addView(page, new FrameLayout.LayoutParams(-1, -1));
        buildHeader();
        buildBody();

        sheetLayer = new FrameLayout(this);
        sheetLayer.setVisibility(View.GONE);
        root.addView(sheetLayer, new FrameLayout.LayoutParams(-1, -1));

        setContentView(root);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (sheetLayer.getVisibility() != View.VISIBLE) reload();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK && sheetLayer.getVisibility() == View.VISIBLE) {
            closeSheet();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    // ---- loading ---------------------------------------------------------------------------------------

    private boolean hasUsageAccess() {
        AppOpsManager ops = (AppOpsManager) getSystemService(APP_OPS_SERVICE);
        if (ops == null) return false;
        int mode;
        if (Build.VERSION.SDK_INT >= 29) {
            mode = ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), getPackageName());
        } else {
            mode = legacyCheckOp(ops);
        }
        if (mode == AppOpsManager.MODE_DEFAULT) {
            return checkCallingOrSelfPermission("android.permission.PACKAGE_USAGE_STATS")
                    == PackageManager.PERMISSION_GRANTED;
        }
        return mode == AppOpsManager.MODE_ALLOWED;
    }

    @SuppressWarnings("deprecation")
    private int legacyCheckOp(AppOpsManager ops) {
        return ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), getPackageName());
    }

    private void reload() {
        if (!hasUsageAccess()) {
            showNoAccess();
            return;
        }
        if (loading) return;
        loading = true;
        if (data == null) subtitle.setText("Reading usage…");
        final int generation = ++loadGeneration;
        new Thread(() -> {
            UsageData.Result r;
            try {
                r = UsageData.load(getApplicationContext());
            } catch (Exception e) {
                android.util.Log.w("Scoreboard", "usage read failed", e);
                r = new UsageData.Result();
                r.now = System.currentTimeMillis();
            }
            final UsageData.Result result = r;
            runOnUiThread(() -> {
                loading = false;
                if (isFinishing() || generation != loadGeneration) return;
                boolean first = data == null;
                data = result;
                render(first);
            });
        }, "scoreboard-load").start();
    }

    // ---- the page ----------------------------------------------------------------------------------------

    private void buildHeader() {
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        TextView title = text("Screen Time", 34, WHITE, 700);
        title.setLetterSpacing(-0.01f);
        titles.addView(title);
        subtitle = text("", 14, WHITE_60, 400);
        titles.addView(subtitle);
        header.addView(titles, new LinearLayout.LayoutParams(0, -2, 1f));

        GlassView track = new GlassView(this).source(source).tint(0x1AFFFFFF, 0x1AFFFFFF);
        track.setPadding(dp(4), dp(4), dp(4), dp(4));
        LinearLayout segRow = new LinearLayout(this);
        segRow.setOrientation(LinearLayout.HORIZONTAL);
        String[] names = {"Streaming", "All apps"};
        for (int i = 0; i < 2; i++) {
            final int index = i;
            GlassView seg = new GlassView(this).source(source).tint(0x00FFFFFF, 0x47FFFFFF);
            seg.setFocusable(true);
            seg.setClickable(true);
            TextView label = text(names[i], 15, WHITE, 600);
            label.setGravity(Gravity.CENTER);
            seg.addView(label, new FrameLayout.LayoutParams(-1, -1));
            seg.setOnFocusChangeListener((v, has) -> {
                if (has) select(index == 1);
                animateScale(v, has ? 1.06f : 1f);
            });
            seg.setOnClickListener(v -> select(index == 1));
            segments[i] = seg;
            segRow.addView(seg, new LinearLayout.LayoutParams(dp(124), dp(38)));
        }
        segments[0].setLit(true);
        track.addView(segRow, new FrameLayout.LayoutParams(-2, -2));
        header.addView(track, new LinearLayout.LayoutParams(-2, -2));
        page.addView(header, new LinearLayout.LayoutParams(-1, -2));
    }

    private void buildBody() {
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams bodyLp = new LinearLayout.LayoutParams(-1, 0, 1f);
        bodyLp.topMargin = dp(22);
        page.addView(body, bodyLp);

        // the summary pane
        GlassView hero = new GlassView(this).source(source).radius(dp(30)).tint(0x1FFFFFFF, 0x1FFFFFFF);
        LinearLayout h = new LinearLayout(this);
        h.setOrientation(LinearLayout.VERTICAL);
        h.setPadding(dp(24), dp(22), dp(24), dp(22));
        heroCaption = caption("STREAMING · 6 MONTHS");
        h.addView(heroCaption);
        heroTotal = text("—", 44, WHITE, 300);
        heroTotal.setLetterSpacing(-0.02f);
        h.addView(heroTotal);
        heroPlays = text("", 14, WHITE_80, 400);
        h.addView(heroPlays);

        LinearLayout weekHead = new LinearLayout(this);
        weekHead.setOrientation(LinearLayout.HORIZONTAL);
        weekHead.addView(caption("LAST 7 DAYS"), new LinearLayout.LayoutParams(0, -2, 1f));
        heroAverage = text("", 11, WHITE_60, 500);
        weekHead.addView(heroAverage);
        LinearLayout.LayoutParams whLp = new LinearLayout.LayoutParams(-1, -2);
        whLp.topMargin = dp(22);
        h.addView(weekHead, whLp);
        week = new Charts.Week(this);
        LinearLayout.LayoutParams weekLp = new LinearLayout.LayoutParams(-1, 0, 1f);
        weekLp.topMargin = dp(10);
        h.addView(week, weekLp);

        LinearLayout.LayoutParams topCapLp = new LinearLayout.LayoutParams(-1, -2);
        topCapLp.topMargin = dp(20);
        h.addView(caption("MOST USED"), topCapLp);
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        heroTopIcon = icon(34, 9);
        top.addView(heroTopIcon);
        LinearLayout topText = new LinearLayout(this);
        topText.setOrientation(LinearLayout.VERTICAL);
        heroTopName = text("", 16, WHITE, 600);
        heroTopName.setSingleLine(true);
        heroTopName.setEllipsize(TextUtils.TruncateAt.END);
        heroTopTime = text("", 12, WHITE_60, 400);
        topText.addView(heroTopName);
        topText.addView(heroTopTime);
        LinearLayout.LayoutParams ttLp = new LinearLayout.LayoutParams(0, -2, 1f);
        ttLp.leftMargin = dp(12);
        top.addView(topText, ttLp);
        LinearLayout.LayoutParams topLp = new LinearLayout.LayoutParams(-1, -2);
        topLp.topMargin = dp(8);
        h.addView(top, topLp);

        hero.addView(h, new FrameLayout.LayoutParams(-1, -1));
        body.addView(hero, new LinearLayout.LayoutParams(dp(290), -1));

        // the ranked list
        listScroll = new ScrollView(this);
        listScroll.setVerticalScrollBarEnabled(false);
        listScroll.setClipToPadding(false);
        listScroll.setClipChildren(false);
        listScroll.setPadding(dp(10), dp(6), dp(10), dp(6));
        listScroll.setOnScrollChangeListener((v, x, y, ox, oy) -> invalidateGlass(list));
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setClipChildren(false); // a focused row grows past the list's edges
        listScroll.addView(list, new FrameLayout.LayoutParams(-1, -2));
        LinearLayout.LayoutParams listLp = new LinearLayout.LayoutParams(0, -1, 1f);
        listLp.leftMargin = dp(14);
        body.addView(listScroll, listLp);
    }

    private void select(boolean all) {
        if (showAll == all && data != null && list.getChildCount() > 0) return;
        showAll = all;
        segments[0].setLit(!all);
        segments[1].setLit(all);
        if (data != null) render(true);
    }

    private List<UsageData.App> shown() {
        List<UsageData.App> out = new ArrayList<>();
        if (data == null) return out;
        for (UsageData.App a : data.apps) if (showAll || a.streaming) out.add(a);
        return out;
    }

    private void render(boolean animate) {
        List<UsageData.App> apps = shown();
        long total = 0;
        int plays = 0;
        for (UsageData.App a : apps) {
            total += a.totalMs;
            plays += a.plays();
        }
        subtitle.setText("Last 6 months  ·  Updated " + new SimpleDateFormat("h:mm a", Locale.getDefault())
                .format(new Date(data.now)) + "  ·  Press OK on an app for its history");

        heroCaption.setText(showAll ? "ALL APPS · 6 MONTHS" : "STREAMING · 6 MONTHS");
        if (animate) countUp(heroTotal, total);
        else heroTotal.setText(duration(total));
        heroPlays.setText(plays + (plays == 1 ? " session" : " sessions") + "  ·  " + apps.size()
                + (apps.size() == 1 ? " app" : " apps"));

        long[] days = new long[7];
        String[] labels = new String[7];
        Calendar day = startOfDay(data.now);
        day.add(Calendar.DAY_OF_YEAR, -6);
        SimpleDateFormat letter = new SimpleDateFormat("EEEEE", Locale.getDefault());
        long weekSum = 0;
        for (int i = 0; i < 7; i++) {
            long from = day.getTimeInMillis();
            labels[i] = letter.format(day.getTime());
            day.add(Calendar.DAY_OF_YEAR, 1);
            long to = day.getTimeInMillis();
            for (UsageData.App a : apps) {
                for (Sessions.Session s : a.sessions) {
                    long o = Math.min(s.end, to) - Math.max(s.start, from);
                    if (o > 0) days[i] += o;
                }
            }
            weekSum += days[i];
        }
        UsageData.App first = apps.isEmpty() ? null : apps.get(0);
        week.set(days, labels, first != null ? first.colour : 0xFF7DD3FC);
        heroAverage.setText(weekSum > 0 ? duration(weekSum / 7) + " a day" : "");
        if (first != null) {
            heroTopIcon.setImageDrawable(first.icon);
            heroTopName.setText(first.label);
            heroTopTime.setText(duration(first.totalMs) + "  ·  " + percent(first.totalMs, total) + " of the time");
        } else {
            heroTopIcon.setImageDrawable(null);
            heroTopName.setText("—");
            heroTopTime.setText("");
        }

        View focused = getCurrentFocus();
        // on the first fill the tabs only hold focus by default, so the top app takes it
        boolean listHadFocus = !filledOnce || (focused != null && focused.getParent() == list);
        filledOnce = true;
        list.removeAllViews();
        if (apps.isEmpty()) {
            TextView empty = text(showAll ? "No app has been used yet." : "Nothing has been watched yet.\n"
                    + "Switch to All apps to see everything.", 16, WHITE_60, 400);
            empty.setGravity(Gravity.CENTER);
            list.addView(empty, new LinearLayout.LayoutParams(-1, dp(200)));
            if (focused == null || listHadFocus) segments[showAll ? 1 : 0].requestFocus();
            return;
        }
        long max = Math.max(1, first.totalMs);
        for (int i = 0; i < apps.size(); i++) {
            View row = row(apps.get(i), i + 1, apps.get(i).totalMs / (float) max, animate);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(62));
            lp.bottomMargin = dp(10);
            list.addView(row, lp);
            if (animate && i < 12) {
                row.setAlpha(0f);
                row.setTranslationY(dp(14));
                row.animate().alpha(1f).translationY(0).setStartDelay(40L * i).setDuration(320)
                        .setInterpolator(new DecelerateInterpolator(1.6f)).start();
            }
        }
        listScroll.scrollTo(0, 0);
        if (focused == null || listHadFocus) list.getChildAt(0).requestFocus();
    }

    private View row(UsageData.App app, int rank, float fraction, boolean animate) {
        GlassView row = new GlassView(this).source(source).radius(dp(20)).tint(0x17FFFFFF, 0x3DFFFFFF)
                .accent(app.colour);
        row.setFocusable(true);
        row.setClickable(true);
        LinearLayout in = new LinearLayout(this);
        in.setOrientation(LinearLayout.HORIZONTAL);
        in.setGravity(Gravity.CENTER_VERTICAL);
        in.setPadding(dp(14), 0, dp(18), 0);

        TextView rankView = text(String.valueOf(rank), 14, rank <= 3 ? 0xFF1A1405 : WHITE_60, 700);
        rankView.setGravity(Gravity.CENTER);
        if (rank <= 3) {
            int[] medal = {0xFFFFD66B, 0xFFE3E8F0, 0xFFF2A766};
            rankView.setBackground(oval(medal[rank - 1]));
        }
        in.addView(rankView, new LinearLayout.LayoutParams(dp(26), dp(26)));

        ImageView iconView = icon(38, 10);
        iconView.setImageDrawable(app.icon);
        LinearLayout.LayoutParams iconLp = (LinearLayout.LayoutParams) iconView.getLayoutParams();
        iconLp.leftMargin = dp(14);
        iconLp.rightMargin = dp(14);
        in.addView(iconView);

        LinearLayout middle = new LinearLayout(this);
        middle.setOrientation(LinearLayout.VERTICAL);
        TextView name = text(app.label, 16, WHITE, 600);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        middle.addView(name);
        Charts.Meter meter = new Charts.Meter(this);
        meter.set(fraction, app.colour);
        LinearLayout.LayoutParams mLp = new LinearLayout.LayoutParams(-1, dp(5));
        mLp.topMargin = dp(7);
        mLp.rightMargin = dp(24);
        middle.addView(meter, mLp);
        in.addView(middle, new LinearLayout.LayoutParams(0, -2, 1f));
        if (animate) {
            meter.setShown(0f);
            ValueAnimator grow = ValueAnimator.ofFloat(0f, 1f).setDuration(700);
            grow.setStartDelay(Math.min(rank, 12) * 40L + 120);
            grow.setInterpolator(new DecelerateInterpolator(2f));
            grow.addUpdateListener(a -> meter.setShown((float) a.getAnimatedValue()));
            grow.start();
        } else {
            meter.setShown(1f);
        }

        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        right.setGravity(Gravity.END);
        right.addView(text(duration(app.totalMs), 17, WHITE, 600));
        String sub = app.plays() + (app.plays() == 1 ? " session" : " sessions");
        if (app.lastUsed() > 0) sub += "  ·  " + ago(app.lastUsed(), data.now);
        right.addView(text(sub, 12, WHITE_60, 400));
        in.addView(right, new LinearLayout.LayoutParams(dp(190), -2));

        TextView chevron = text("›", 22, WHITE_45, 400);
        LinearLayout.LayoutParams cLp = new LinearLayout.LayoutParams(-2, -2);
        cLp.leftMargin = dp(12);
        in.addView(chevron, cLp);

        row.addView(in, new FrameLayout.LayoutParams(-1, -1));
        row.setOnFocusChangeListener((v, has) -> {
            animateScale(v, has ? 1.03f : 1f);
            chevron.setTextColor(has ? WHITE : WHITE_45);
        });
        row.setOnClickListener(v -> openSheet(app, v));
        return row;
    }

    // ---- the details sheet -------------------------------------------------------------------------------

    private void openSheet(UsageData.App app, View from) {
        lastRow = from;
        // freeze what is on screen, blur it, and use it as both the dimmed background and the sheet's glass
        int w = Math.max(16, screenW / 8), h = Math.max(9, screenH / 8);
        Bitmap snap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(snap);
        c.scale(w / (float) root.getWidth(), h / (float) root.getHeight());
        root.draw(c);
        Glass.blur(snap, 5);
        GlassView.Source behind = new GlassView.Source(snap, screenW, screenH);

        sheetLayer.removeAllViews();
        ImageView frozen = new ImageView(this);
        frozen.setScaleType(ImageView.ScaleType.FIT_XY);
        frozen.setImageBitmap(snap);
        sheetLayer.addView(frozen, new FrameLayout.LayoutParams(-1, -1));
        View scrim = new View(this);
        scrim.setBackgroundColor(0x52000010);
        sheetLayer.addView(scrim, new FrameLayout.LayoutParams(-1, -1));

        GlassView sheet = new GlassView(this).source(behind).radius(dp(34)).tint(0x24FFFFFF, 0x24FFFFFF);
        LinearLayout in = new LinearLayout(this);
        in.setOrientation(LinearLayout.HORIZONTAL);
        in.setPadding(dp(30), dp(28), dp(30), dp(26));
        in.setClipToPadding(false);
        in.setClipChildren(false);

        // left: the app and its numbers
        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        LinearLayout ident = new LinearLayout(this);
        ident.setOrientation(LinearLayout.HORIZONTAL);
        ident.setGravity(Gravity.CENTER_VERTICAL);
        ImageView iconView = icon(56, 14);
        iconView.setImageDrawable(app.icon);
        ident.addView(iconView);
        LinearLayout names = new LinearLayout(this);
        names.setOrientation(LinearLayout.VERTICAL);
        TextView name = text(app.label, 22, WHITE, 700);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        names.addView(name);
        TextView pkg = text(app.pkg, 11, WHITE_45, 400);
        pkg.setSingleLine(true);
        pkg.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        names.addView(pkg);
        LinearLayout.LayoutParams nLp = new LinearLayout.LayoutParams(0, -2, 1f);
        nLp.leftMargin = dp(14);
        ident.addView(names, nLp);
        left.addView(ident);

        TextView big = text(duration(app.totalMs), 40, WHITE, 300);
        big.setLetterSpacing(-0.02f);
        LinearLayout.LayoutParams bigLp = new LinearLayout.LayoutParams(-2, -2);
        bigLp.topMargin = dp(18);
        left.addView(big, bigLp);
        left.addView(text("in the last 6 months", 12, WHITE_60, 400));

        long longest = 0, sum = 0;
        for (Sessions.Session s : app.sessions) {
            longest = Math.max(longest, s.length());
            sum += s.length();
        }
        int n = app.plays();
        LinearLayout grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        LinearLayout r1 = new LinearLayout(this), r2 = new LinearLayout(this);
        r1.addView(tile(behind, String.valueOf(n), n == 1 ? "session" : "sessions"), tileLp(false));
        r1.addView(tile(behind, n > 0 ? duration(sum / n) : "—", "average"), tileLp(true));
        r2.addView(tile(behind, longest > 0 ? duration(longest) : "—", "longest"), tileLp(false));
        r2.addView(tile(behind, app.lastUsed() > 0 ? ago(app.lastUsed(), data.now) : "—", "last opened"),
                tileLp(true));
        grid.addView(r1);
        LinearLayout.LayoutParams r2Lp = new LinearLayout.LayoutParams(-1, -2);
        r2Lp.topMargin = dp(10);
        grid.addView(r2, r2Lp);
        LinearLayout.LayoutParams gLp = new LinearLayout.LayoutParams(-1, -2);
        gLp.topMargin = dp(18);
        left.addView(grid, gLp);

        left.addView(new View(this), new LinearLayout.LayoutParams(1, 0, 1f));
        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setClipChildren(false); // the focused button grows a little past its slot
        left.setClipChildren(false);
        GlassView open = button(behind, "Open " + shortName(app.label), true);
        open.setOnClickListener(v -> launch(app.pkg));
        GlassView close = button(behind, "Close", false);
        close.setOnClickListener(v -> closeSheet());
        buttons.addView(open, new LinearLayout.LayoutParams(0, dp(44), 1.4f));
        LinearLayout.LayoutParams closeLp = new LinearLayout.LayoutParams(0, dp(44), 1f);
        closeLp.leftMargin = dp(10);
        buttons.addView(close, closeLp);
        left.addView(buttons, new LinearLayout.LayoutParams(-1, -2));
        in.addView(left, new LinearLayout.LayoutParams(dp(290), -1));

        // right: every sitting, newest first, by day
        LinearLayout historyCol = new LinearLayout(this);
        historyCol.setOrientation(LinearLayout.VERTICAL);
        LinearLayout histHead = new LinearLayout(this);
        histHead.setOrientation(LinearLayout.HORIZONTAL);
        histHead.addView(caption("HISTORY"), new LinearLayout.LayoutParams(0, -2, 1f));
        if (data.historySince > 0 && data.historySince < data.now) {
            histHead.addView(text("kept since " + new SimpleDateFormat("d MMM", Locale.getDefault())
                    .format(new Date(data.historySince)), 11, WHITE_45, 500));
        }
        historyCol.addView(histHead);
        GlassView historyPane = new GlassView(this).source(behind).radius(dp(22)).tint(0x12FFFFFF, 0x1FFFFFFF);
        ScrollView scroll = new ScrollView(this);
        scroll.setFocusable(true);
        scroll.setOnFocusChangeListener((v, has) -> historyPane.setLit(has));
        LinearLayout history = new LinearLayout(this);
        history.setOrientation(LinearLayout.VERTICAL);
        history.setPadding(dp(18), dp(10), dp(18), dp(14));
        fillHistory(history, app);
        scroll.addView(history, new FrameLayout.LayoutParams(-1, -2));
        historyPane.addView(scroll, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout.LayoutParams hpLp = new LinearLayout.LayoutParams(-1, 0, 1f);
        hpLp.topMargin = dp(10);
        historyCol.addView(historyPane, hpLp);
        LinearLayout.LayoutParams hcLp = new LinearLayout.LayoutParams(0, -1, 1f);
        hcLp.leftMargin = dp(28);
        in.addView(historyCol, hcLp);

        sheet.addView(in, new FrameLayout.LayoutParams(-1, -1));
        sheetLayer.addView(sheet, new FrameLayout.LayoutParams(dp(780), dp(452), Gravity.CENTER));

        sheetLayer.setVisibility(View.VISIBLE);
        sheetLayer.setAlpha(0f);
        sheetLayer.animate().alpha(1f).setDuration(200).start();
        sheet.setScaleX(0.92f);
        sheet.setScaleY(0.92f);
        sheet.animate().scaleX(1f).scaleY(1f).setDuration(320).setInterpolator(new OvershootInterpolator(0.9f))
                .start();
        page.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        open.requestFocus();
    }

    private void fillHistory(LinearLayout history, UsageData.App app) {
        if (app.sessions.isEmpty()) {
            TextView none = text("Android keeps only totals this far back,\nso there are no single sittings to show.",
                    14, WHITE_60, 400);
            none.setPadding(0, dp(12), 0, 0);
            history.addView(none);
            return;
        }
        SimpleDateFormat time = new SimpleDateFormat("h:mm a", Locale.getDefault());
        SimpleDateFormat dayName = new SimpleDateFormat("EEEE, d MMMM", Locale.getDefault());
        long today = startOfDay(data.now).getTimeInMillis();
        long yesterday = startOfDay(today - 12L * 60 * 60 * 1000).getTimeInMillis();
        int shownCount = 0;
        long currentDay = Long.MIN_VALUE;
        TextView dayTotal = null;
        long dayMs = 0;
        for (int i = app.sessions.size() - 1; i >= 0 && shownCount < 400; i--, shownCount++) {
            Sessions.Session s = app.sessions.get(i);
            long d = startOfDay(s.start).getTimeInMillis();
            if (d != currentDay) {
                if (dayTotal != null) dayTotal.setText(duration(dayMs));
                currentDay = d;
                dayMs = 0;
                String label = d == today ? "Today" : d == yesterday ? "Yesterday" : dayName.format(new Date(d));
                LinearLayout head = new LinearLayout(this);
                head.setOrientation(LinearLayout.HORIZONTAL);
                head.setPadding(0, dp(history.getChildCount() == 0 ? 6 : 16), 0, dp(4));
                head.addView(text(label, 13, WHITE, 700), new LinearLayout.LayoutParams(0, -2, 1f));
                dayTotal = text("", 13, WHITE_60, 600);
                head.addView(dayTotal);
                history.addView(head);
            }
            dayMs += s.length();
            LinearLayout line = new LinearLayout(this);
            line.setOrientation(LinearLayout.HORIZONTAL);
            line.setGravity(Gravity.CENTER_VERTICAL);
            line.setPadding(0, dp(5), 0, dp(5));
            View dot = new View(this);
            dot.setBackground(oval(app.colour));
            LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(dp(6), dp(6));
            dotLp.rightMargin = dp(10);
            line.addView(dot, dotLp);
            line.addView(text(time.format(new Date(s.start)) + "  –  " + time.format(new Date(s.end)), 14, WHITE_80,
                    400), new LinearLayout.LayoutParams(0, -2, 1f));
            line.addView(text(duration(s.length()), 14, WHITE_60, 500));
            history.addView(line);
        }
        if (dayTotal != null) dayTotal.setText(duration(dayMs));
    }

    private void closeSheet() {
        page.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        sheetLayer.animate().alpha(0f).setDuration(160).withEndAction(() -> {
            sheetLayer.setVisibility(View.GONE);
            sheetLayer.removeAllViews();
        }).start();
        if (lastRow != null && lastRow.isAttachedToWindow()) lastRow.requestFocus();
        else if (list.getChildCount() > 0) list.getChildAt(0).requestFocus();
    }

    private void launch(String pkg) {
        PackageManager pm = getPackageManager();
        Intent intent = pm.getLeanbackLaunchIntentForPackage(pkg);
        if (intent == null) intent = pm.getLaunchIntentForPackage(pkg);
        if (intent == null) return;
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        try {
            startActivity(intent);
        } catch (Exception ignored) {
            // the app went away; stay here
        }
    }

    // ---- no usage access ------------------------------------------------------------------------------

    private void showNoAccess() {
        subtitle.setText("Usage access is off");
        list.removeAllViews();
        GlassView card = new GlassView(this).source(source).radius(dp(24)).tint(0x1FFFFFFF, 0x1FFFFFFF);
        LinearLayout in = new LinearLayout(this);
        in.setOrientation(LinearLayout.VERTICAL);
        in.setPadding(dp(26), dp(24), dp(26), dp(24));
        in.setClipToPadding(false);
        in.setClipChildren(false);
        in.addView(text("Allow usage access", 20, WHITE, 700));
        TextView why = text("Scoreboard needs Android's Usage access to see which apps were opened and for how "
                + "long. Nothing leaves the TV.\n\nSettings › Apps › Special app access › Usage access › Scoreboard",
                14, WHITE_80, 400);
        why.setPadding(0, dp(8), 0, dp(16));
        in.addView(why);
        GlassView go = button(source, "Open settings", true);
        go.setOnClickListener(v -> {
            try {
                startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS));
            } catch (Exception e) {
                try {
                    startActivity(new Intent(Settings.ACTION_SETTINGS));
                } catch (Exception ignored) {
                    // no settings screen on this TV
                }
            }
        });
        in.addView(go, new LinearLayout.LayoutParams(dp(200), dp(44)));
        card.addView(in, new FrameLayout.LayoutParams(-1, -2));
        list.addView(card, new LinearLayout.LayoutParams(-1, -2));
        go.requestFocus();
    }

    // ---- small builders ---------------------------------------------------------------------------------

    private GlassView tile(GlassView.Source src, String value, String label) {
        GlassView t = new GlassView(this).source(src).radius(dp(16)).tint(0x14FFFFFF, 0x14FFFFFF);
        LinearLayout in = new LinearLayout(this);
        in.setOrientation(LinearLayout.VERTICAL);
        in.setPadding(dp(14), dp(10), dp(14), dp(10));
        TextView v = text(value, 17, WHITE, 600);
        v.setSingleLine(true);
        v.setEllipsize(TextUtils.TruncateAt.END);
        in.addView(v);
        in.addView(text(label, 11, WHITE_60, 500));
        t.addView(in, new FrameLayout.LayoutParams(-1, -2));
        return t;
    }

    private LinearLayout.LayoutParams tileLp(boolean second) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1f);
        if (second) lp.leftMargin = dp(10);
        return lp;
    }

    /** A capsule button: clear glass at rest; focused it turns solid white with dark text, like tvOS. */
    private GlassView button(GlassView.Source src, String label, boolean primary) {
        GlassView b = new GlassView(this).source(src).radius(dp(22))
                .tint(primary ? 0x33FFFFFF : 0x1AFFFFFF, 0xF2FFFFFF).solidFocus(true);
        b.setFocusable(true);
        b.setClickable(true);
        TextView t = text(label, 15, WHITE, 600);
        t.setGravity(Gravity.CENTER);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        t.setPadding(dp(12), 0, dp(12), 0);
        b.addView(t, new FrameLayout.LayoutParams(-1, -1));
        b.setOnFocusChangeListener((v, has) -> {
            t.setTextColor(has ? 0xFF10131C : WHITE);
            animateScale(v, has ? 1.06f : 1f);
        });
        return b;
    }

    private TextView caption(String s) {
        TextView t = text(s, 11, WHITE_60, 600);
        t.setLetterSpacing(0.12f);
        return t;
    }

    private TextView text(String s, float sp, int colour, int weight) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(colour);
        t.setIncludeFontPadding(false);
        t.setTypeface(font(weight));
        t.setPadding(0, dp(1), 0, dp(1));
        return t;
    }

    private static Typeface font(int weight) {
        if (Build.VERSION.SDK_INT >= 28) return Typeface.create(Typeface.SANS_SERIF, weight, false);
        if (weight >= 600) return Typeface.create("sans-serif-medium", Typeface.NORMAL);
        if (weight <= 300) return Typeface.create("sans-serif-light", Typeface.NORMAL);
        return Typeface.SANS_SERIF;
    }

    private ImageView icon(int sizeDp, int cornerDp) {
        ImageView v = new ImageView(this);
        v.setScaleType(ImageView.ScaleType.FIT_CENTER);
        final float corner = dp(cornerDp);
        v.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), corner);
            }
        });
        v.setClipToOutline(true);
        v.setLayoutParams(new LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp)));
        return v;
    }

    private static Drawable oval(int colour) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(colour);
        return g;
    }

    private static void animateScale(View v, float s) {
        v.animate().scaleX(s).scaleY(s).setDuration(170).setInterpolator(new DecelerateInterpolator()).start();
    }

    private void countUp(TextView view, long ms) {
        ValueAnimator a = ValueAnimator.ofFloat(0f, 1f).setDuration(750);
        a.setInterpolator(new DecelerateInterpolator(2f));
        a.addUpdateListener(an -> view.setText(duration((long) (ms * (float) an.getAnimatedValue()))));
        a.start();
    }

    private static void invalidateGlass(ViewGroup group) {
        if (group == null) return;
        for (int i = 0; i < group.getChildCount(); i++) group.getChildAt(i).invalidate();
    }

    private int dp(float v) {
        return Math.round(v * density);
    }

    // ---- words -----------------------------------------------------------------------------------------

    static String duration(long ms) {
        if (ms <= 0) return "0m";
        long minutes = ms / 60_000L;
        if (minutes < 1) return "<1m";
        long h = minutes / 60, m = minutes % 60;
        if (h == 0) return m + "m";
        return m == 0 ? h + "h" : h + "h " + m + "m";
    }

    static String percent(long part, long whole) {
        if (whole <= 0) return "0%";
        return Math.round(100.0 * part / whole) + "%";
    }

    static String ago(long then, long now) {
        long min = Math.max(0, now - then) / 60_000L;
        if (min < 1) return "just now";
        if (min < 60) return min + "m ago";
        long days = Math.round((startOfDay(now).getTimeInMillis() - startOfDay(then).getTimeInMillis())
                / (24.0 * 60 * 60 * 1000));
        if (days == 0) return (min / 60) + "h ago";
        if (days == 1) return "yesterday";
        if (days < 7) return new SimpleDateFormat("EEEE", Locale.getDefault()).format(new Date(then));
        return new SimpleDateFormat("d MMM", Locale.getDefault()).format(new Date(then));
    }

    private static String shortName(String label) {
        return label.length() > 14 ? "app" : label;
    }

    private static Calendar startOfDay(long t) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(t);
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c;
    }
}
