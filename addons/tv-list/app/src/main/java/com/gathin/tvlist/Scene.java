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
import java.util.Calendar;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * What the screensaver and the card show, in the colours of the morning screen: a deep teal field with a warm amber
 * accent. On the left an Arabic greeting that follows the time of day, a large clock and the date, and today's events
 * in a panel; on the right the to-do list in a larger panel, done tasks struck through at the end. The sheet is read
 * on every start and each minute while it shows; the last good copy shows at once. The whole picture drifts a few dp,
 * very slowly, against burn-in.
 */
final class Scene {
    static final int AMBER = 0xFFF2B35B;
    static final int CORAL = 0xFFFF7F6A;
    static final int INK = 0xFFF4EFE6;
    static final int INK_DIM = 0x99F4EFE6;
    static final int INK_FAINT = 0x66F4EFE6;
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
    private final TextView greeting;
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
        root.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0xFF0F3B3E, 0xFF0A2629, 0xFF061517}));
        root.setKeepScreenOn(true);
        stage = new FrameLayout(context);
        root.addView(stage, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout page = new LinearLayout(context);
        page.setOrientation(LinearLayout.HORIZONTAL);
        FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        plp.setMargins(dp(64), dp(48), dp(64), dp(40));
        stage.addView(page, plp);

        // Left: greeting, clock, date, today's events, and the status line at the bottom.
        LinearLayout left = new LinearLayout(context);
        left.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0.42f);
        llp.setMarginEnd(dp(40));
        page.addView(left, llp);

        // Left-aligned with the clock, though Arabic would otherwise start on the right.
        greeting = text(26, AMBER);
        greeting.setTextAlignment(View.TEXT_ALIGNMENT_GRAVITY);
        greeting.setGravity(Gravity.LEFT);
        left.addView(greeting, margins(0, 0));

        TextClock clock = new TextClock(context);
        clock.setFormat12Hour("h:mm");
        clock.setFormat24Hour("HH:mm");
        clock.setTextColor(INK);
        clock.setTextSize(TypedValue.COMPLEX_UNIT_SP, 96);
        clock.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        clock.setLetterSpacing(-0.02f);
        clock.setIncludeFontPadding(false);
        left.addView(clock, margins(dp(2), 0));

        TextClock date = new TextClock(context);
        date.setFormat12Hour("EEEE, MMMM d");
        date.setFormat24Hour("EEEE, d MMMM");
        date.setTextColor(INK_DIM);
        date.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        left.addView(date, margins(dp(6), 0));

        events = panel(left, R.string.today_heading, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f), dp(28));

        status = text(13, INK_FAINT);
        left.addView(status, margins(dp(14), 0));

        // Right: the to-do list, the reason for this screen.
        todos = panel(page, R.string.todo_heading, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 0.58f), 0);
    }

    void start() {
        if (running) return;
        running = true;
        executor = Executors.newSingleThreadExecutor();
        sheetId = Config.sheetId(dir);
        handler.postDelayed(shift, SHIFT_EVERY_MS);
        // The last copy at once, then the sheet itself: every start reads it, so an edit shows the next time.
        showLists();
        if (sheetId == null) status.setText(R.string.no_sheet);
        else refresh();
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
            status.setTextColor(CORAL);
            handler.postDelayed(refresh, RETRY_MS);
            return;
        }
        status.setTextColor(INK_FAINT);
        showLists();
        tick();
        handler.postDelayed(refresh, Sheet.REFRESH_MS);
    }

    /** Once a minute: the greeting for the time of day, and "Updated 3 minutes ago" unless an error is showing. */
    private void tick() {
        handler.removeCallbacks(tick);
        if (!running) return;
        greeting.setText(greeting(Calendar.getInstance().get(Calendar.HOUR_OF_DAY)));
        if (sheetId != null && !failed) {
            long when = Sheet.cacheTime(dir);
            long now = System.currentTimeMillis();
            if (when == 0) status.setText(R.string.loading);
            else if (now - when < 60_000L) status.setText(R.string.updated_now);
            else status.setText(context.getString(R.string.updated, DateUtils.getRelativeTimeSpanString(
                    Math.min(when, now), now, DateUtils.MINUTE_IN_MILLIS)));
        }
        handler.postDelayed(tick, 60_000L);
    }

    /** Good morning from 4 until noon, good evening the rest of the day, as the morning screen does. */
    static int greeting(int hour) {
        return hour >= 4 && hour < 12 ? R.string.greeting_morning : R.string.greeting_evening;
    }

    private void showLists() {
        List<Lists.Item> today = Lists.events(Csv.parse(Config.read(new File(dir, Sheet.TODAY_CACHE))));
        List<Lists.Item> list = Lists.todos(Csv.parse(Config.read(new File(dir, Sheet.TODO_CACHE))));
        fill(events, today, R.string.no_events, true);
        fill(todos, list, R.string.no_todos, false);
    }

    /** Rows under a heading; the heading (child 0) stays. */
    private void fill(LinearLayout panel, List<Lists.Item> items, int empty, boolean event) {
        panel.removeViews(1, panel.getChildCount() - 1);
        if (items.isEmpty()) {
            TextView none = text(22, INK_FAINT);
            none.setText(empty);
            panel.addView(none, margins(dp(18), 0));
            return;
        }
        for (Lists.Item item : items) panel.addView(row(item, event), margins(dp(event ? 14 : 18), 0));
    }

    private View row(Lists.Item item, boolean event) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.TOP);
        // No baseline alignment: it would push the empty checkbox down to the text's baseline and clip it.
        row.setBaselineAligned(false);
        if (event) {
            TextView time = text(17, AMBER);
            time.setText(item.detail);
            time.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            time.setPadding(0, dp(3), 0, 0);
            row.addView(time, new LinearLayout.LayoutParams(dp(92), ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        TextView label = text(event ? 21 : 25, item.done ? INK_FAINT : INK);
        label.setText(item.text);
        label.setMaxLines(2);
        label.setLineSpacing(0, 1.05f);
        if (!event) row.addView(checkbox(item.done), boxParams(label.getLineHeight()));
        if (item.done) label.setPaintFlags(label.getPaintFlags() | Paint.STRIKE_THRU_TEXT_FLAG);
        row.addView(label, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return row;
    }

    /** An amber ring for an open task; a filled, faded one with a tick for a done task. */
    private View checkbox(boolean done) {
        TextView box = new TextView(context);
        GradientDrawable ring = new GradientDrawable();
        ring.setShape(GradientDrawable.OVAL);
        if (done) {
            ring.setColor(0x4DF2B35B);
            box.setText("✓");
            box.setTextColor(0xCC0A2629);
            box.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            box.setGravity(Gravity.CENTER);
            box.setIncludeFontPadding(false);
        } else {
            ring.setStroke(dp(2), AMBER);
        }
        box.setBackground(ring);
        return box;
    }

    /** The box centred on the first line of its task, however many lines the task takes. */
    private LinearLayout.LayoutParams boxParams(int lineHeight) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(20), dp(20));
        lp.topMargin = Math.max(0, (lineHeight - dp(20)) / 2);
        lp.setMarginEnd(dp(20));
        return lp;
    }

    /** A soft, rounded panel with an amber heading, added to {@code parent}; rows go under the heading. */
    private LinearLayout panel(LinearLayout parent, int heading, LinearLayout.LayoutParams lp, int top) {
        LinearLayout panel = new LinearLayout(context);
        panel.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable card = new GradientDrawable();
        card.setColor(0x14FFFFFF);
        card.setStroke(dp(1), 0x1FFFFFFF);
        card.setCornerRadius(dp(24));
        panel.setBackground(card);
        panel.setPadding(dp(32), dp(26), dp(32), dp(24));
        lp.topMargin = top;
        parent.addView(panel, lp);
        TextView title = text(14, AMBER);
        title.setText(heading);
        title.setAllCaps(true);
        title.setLetterSpacing(0.16f);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        panel.addView(title);
        return panel;
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
