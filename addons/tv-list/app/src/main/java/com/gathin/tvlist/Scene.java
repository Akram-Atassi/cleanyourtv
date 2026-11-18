package com.gathin.tvlist;

import android.content.Context;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextClock;
import android.widget.TextView;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * What the screensaver and the card show: a large clock and the date at the top, today's events on the left and the
 * to-do list on the right, done tasks struck through at the end. The sheet is read on start and every five minutes
 * while it shows; the last good copy shows at once. The whole picture drifts a few dp, very slowly, against burn-in.
 */
final class Scene {
    private static final int ACCENT = 0xFF7FD4C1;
    private static final long RETRY_MS = 60_000L;
    private static final int SHIFT_DP = 6;
    private static final int SHIFT_STEPS = 8;
    private static final long SHIFT_EVERY_MS = 2 * 60_000L;
    private static final long SHIFT_SLIDE_MS = 30_000L;

    final FrameLayout root;
    private final Context context;
    private final File dir;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final FrameLayout stage;
    private final LinearLayout events;
    private final LinearLayout todos;
    private final TextView status;

    private String sheetId;
    private boolean running;
    private boolean failed;
    private int shiftStep;
    private ExecutorService executor;

    private final Runnable refresh = this::refresh;
    private final Runnable shift = this::shift;
    private final Runnable tick = this::tick;

    Scene(Context context) {
        this.context = context;
        this.dir = context.getFilesDir();

        root = new FrameLayout(context);
        root.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xFF10242B, 0xFF060B0E}));
        root.setKeepScreenOn(true);
        stage = new FrameLayout(context);
        root.addView(stage, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout page = new LinearLayout(context);
        page.setOrientation(LinearLayout.VERTICAL);
        FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        plp.setMargins(dp(80), dp(44), dp(80), dp(40));
        stage.addView(page, plp);

        TextClock clock = new TextClock(context);
        clock.setFormat12Hour("h:mm");
        clock.setFormat24Hour("HH:mm");
        clock.setTextColor(0xFFFFFFFF);
        clock.setTextSize(TypedValue.COMPLEX_UNIT_SP, 76);
        clock.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        clock.setIncludeFontPadding(false);
        page.addView(clock);

        TextClock date = new TextClock(context);
        date.setFormat12Hour("EEEE, d MMMM");
        date.setFormat24Hour("EEEE, d MMMM");
        date.setTextColor(0xB3FFFFFF);
        date.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        page.addView(date, margins(dp(4), 0));

        LinearLayout columns = new LinearLayout(context);
        columns.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        clp.topMargin = dp(36);
        page.addView(columns, clp);

        events = column(columns, R.string.today_heading, dp(48));
        todos = column(columns, R.string.todo_heading, 0);

        status = text(14, 0x80FFFFFF);
        page.addView(status, margins(dp(8), 0));
    }

    void start() {
        if (running) return;
        running = true;
        executor = Executors.newSingleThreadExecutor();
        sheetId = Config.sheetId(dir);
        handler.postDelayed(shift, SHIFT_EVERY_MS);
        if (sheetId == null) {
            showLists();
            status.setText(R.string.no_sheet);
            return;
        }
        showLists();
        long age = System.currentTimeMillis() - Sheet.cacheTime(dir);
        if (age >= Sheet.REFRESH_MS || age < 0) refresh();
        else handler.postDelayed(refresh, Sheet.REFRESH_MS - age);
        tick();
    }

    void stop() {
        running = false;
        handler.removeCallbacksAndMessages(null);
        stage.animate().cancel();
        if (executor != null) executor.shutdownNow();
        executor = null;
    }

    private void refresh() {
        if (!running || executor == null) return;
        String id = sheetId;
        executor.execute(() -> {
            int error = 0;
            try {
                Sheet.refresh(dir, id);
            } catch (Sheet.NotSharedException e) {
                error = R.string.not_shared;
            } catch (IOException | RuntimeException e) {
                // `adb logcat -s TvList` says why.
                Log.w("TvList", "refresh: " + e);
                error = R.string.offline;
            }
            int result = error;
            handler.post(() -> loaded(result));
        });
    }

    private void loaded(int error) {
        if (!running) return;
        handler.removeCallbacks(refresh);
        failed = error != 0;
        if (failed) {
            status.setText(error);
            handler.postDelayed(refresh, RETRY_MS);
            return;
        }
        showLists();
        tick();
        handler.postDelayed(refresh, Sheet.REFRESH_MS);
    }

    /** "Updated 3 minutes ago", once a minute, unless an error is showing. */
    private void tick() {
        handler.removeCallbacks(tick);
        if (!running || sheetId == null) return;
        if (!failed) {
            long when = Sheet.cacheTime(dir);
            status.setText(when == 0 ? context.getString(R.string.loading)
                    : context.getString(R.string.updated, DateUtils.getRelativeTimeSpanString(
                            Math.min(when, System.currentTimeMillis()), System.currentTimeMillis(),
                            DateUtils.MINUTE_IN_MILLIS)));
        }
        handler.postDelayed(tick, 60_000L);
    }

    private void showLists() {
        List<Lists.Item> today = Lists.events(Csv.parse(Config.read(new File(dir, Sheet.TODAY_CACHE))));
        List<Lists.Item> list = Lists.todos(Csv.parse(Config.read(new File(dir, Sheet.TODO_CACHE))));
        fill(events, today, R.string.no_events, true);
        fill(todos, list, R.string.no_todos, false);
    }

    /** Rows under a heading; the heading (child 0) stays. */
    private void fill(LinearLayout column, List<Lists.Item> items, int empty, boolean event) {
        column.removeViews(1, column.getChildCount() - 1);
        if (items.isEmpty()) {
            TextView none = text(22, 0x80FFFFFF);
            none.setText(empty);
            column.addView(none, margins(dp(14), 0));
            return;
        }
        for (Lists.Item item : items) column.addView(row(item, event), margins(dp(12), 0));
    }

    private View row(Lists.Item item, boolean event) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.TOP);
        TextView mark = text(event ? 18 : 22, item.done ? 0x80FFFFFF : ACCENT);
        if (event) {
            mark.setText(item.detail);
            mark.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            mark.setPadding(0, dp(3), 0, 0);
        } else {
            mark.setText(item.done ? "✓" : "○");
        }
        row.addView(mark, new LinearLayout.LayoutParams(dp(event ? 96 : 36), ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView label = text(24, item.done ? 0x73FFFFFF : 0xF2FFFFFF);
        label.setText(item.text);
        label.setMaxLines(2);
        if (item.done) label.setPaintFlags(label.getPaintFlags() | Paint.STRIKE_THRU_TEXT_FLAG);
        row.addView(label, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return row;
    }

    private LinearLayout column(LinearLayout parent, int heading, int endMargin) {
        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        lp.setMarginEnd(endMargin);
        parent.addView(column, lp);
        TextView title = text(16, ACCENT);
        title.setText(heading);
        title.setAllCaps(true);
        title.setLetterSpacing(0.12f);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        column.addView(title);
        return column;
    }

    private void shift() {
        if (!running) return;
        shiftStep = (shiftStep + 1) % SHIFT_STEPS;
        double angle = 2 * Math.PI * shiftStep / SHIFT_STEPS;
        stage.animate()
                .translationX((float) (dp(SHIFT_DP) * Math.cos(angle)))
                .translationY((float) (dp(SHIFT_DP) * Math.sin(angle)))
                .setDuration(SHIFT_SLIDE_MS).setInterpolator(new LinearInterpolator()).start();
        handler.postDelayed(shift, SHIFT_EVERY_MS);
    }

    private TextView text(int sp, int color) {
        TextView t = new TextView(context);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setEllipsize(TextUtils.TruncateAt.END);
        t.setMaxLines(1);
        t.setShadowLayer(dp(6), 0, dp(1), 0x66000000);
        return t;
    }

    private static LinearLayout.LayoutParams margins(int top, int bottom) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = top;
        lp.bottomMargin = bottom;
        return lp;
    }

    private int dp(int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
