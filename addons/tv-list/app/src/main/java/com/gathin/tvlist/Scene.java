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
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * What the screensaver and the card show, in the colours of the morning screen: a deep teal field with a warm amber
 * accent. On the left an Arabic greeting that follows the time of day, a large clock and the date, today's birthdays
 * in red, the countdowns and the Lakers' next game with where to watch it. On the right today's events (hidden when
 * there are none) and the to-do list. Every open task is shown: the list goes to two columns and smaller type when it
 * is long, and pages by itself (Left/Right or Up/Down flip pages) when even that does not fit. The sheet is read on
 * every start and each minute while it shows; the last good copy shows at once. The whole picture drifts a few dp,
 * very slowly, against burn-in.
 */
final class Scene {
    static final int AMBER = 0xFFF2B35B;
    static final int CORAL = 0xFFFF7F6A;
    static final int RED = 0xFFFF5C5C;
    static final int INK = 0xFFF4EFE6;
    static final int INK_DIM = 0x99F4EFE6;
    static final int INK_FAINT = 0x66F4EFE6;
    private static final long RETRY_MS = 60_000L;
    private static final long PAGE_MS = 12_000L;
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
    private final TextView birthdays;
    private final LinearLayout countdowns;
    private final LinearLayout game;
    private final LinearLayout events;
    private final LinearLayout todos;
    private final TextView status;

    private String sheetId;
    private boolean running;
    private boolean failed;
    private int shiftStep;
    private int todoPage;
    private int todoPages = 1;
    private ExecutorService executor;

    private final Runnable refresh = this::refresh;
    private final Runnable shift = this::shift;
    private final Runnable tick = this::tick;
    private final Runnable nextPage = () -> page(1);
    private final Runnable relayout = this::fillTodos;

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

        // Left: greeting, birthdays, countdowns, the game, and an error line at the bottom when there is one.
        LinearLayout left = new LinearLayout(context);
        left.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0.36f);
        llp.setMarginEnd(dp(40));
        page.addView(left, llp);

        // Arabic reads right to left, so it starts at the right edge of the column.
        greeting = text(44, AMBER);
        greeting.setTextDirection(View.TEXT_DIRECTION_RTL);
        greeting.setTextAlignment(View.TEXT_ALIGNMENT_TEXT_START);
        left.addView(greeting, margins(0, 0));

        // Today's birthdays, in red, right under the greeting; gone on other days.
        birthdays = text(24, RED);
        birthdays.setMaxLines(3);
        birthdays.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        birthdays.setVisibility(View.GONE);
        left.addView(birthdays, margins(dp(10), 0));

        countdowns = new LinearLayout(context);
        countdowns.setOrientation(LinearLayout.VERTICAL);
        left.addView(countdowns, margins(dp(30), 0));

        game = new LinearLayout(context);
        game.setOrientation(LinearLayout.VERTICAL);
        left.addView(game, margins(dp(34), 0));

        left.addView(new View(context), new LinearLayout.LayoutParams(0, 0, 1f));
        status = text(14, CORAL);
        status.setMaxLines(3);
        status.setVisibility(View.GONE);
        left.addView(status, margins(0, 0));

        // Right: today's events at full width, then the to-do list, the reason for this screen.
        LinearLayout right = new LinearLayout(context);
        right.setOrientation(LinearLayout.VERTICAL);
        page.addView(right, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0.64f));
        events = panel(right, R.string.today_heading, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT), 0);
        todos = panel(right, R.string.todo_heading, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f), dp(20));
    }

    void start() {
        if (running) return;
        running = true;
        executor = Executors.newSingleThreadExecutor();
        sheetId = Config.sheetId(dir);
        handler.postDelayed(shift, SHIFT_EVERY_MS);
        // The last copy at once, then the sheet itself: every start reads it, so an edit shows the next time.
        showLists();
        if (sheetId == null) {
            status.setText(R.string.no_sheet);
            status.setVisibility(View.VISIBLE);
        } else {
            refresh();
        }
        tick();
    }

    void stop() {
        running = false;
        handler.removeCallbacksAndMessages(null);
        stage.animate().cancel();
        if (executor != null) executor.shutdownNow();
        executor = null;
    }

    /** Flips the to-do pages by {@code delta}; false when there is only one page, so the key can do something else. */
    boolean page(int delta) {
        if (todoPages <= 1) return false;
        todoPage = ((todoPage + delta) % todoPages + todoPages) % todoPages;
        fillTodos();
        return true;
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
            try {
                Team.refresh(dir, System.currentTimeMillis());
            } catch (IOException | RuntimeException e) {
                Log.w("TvList", "team: " + e);   // the last copy stays
            }
            int result = error;
            handler.post(() -> loaded(result));
        });
    }

    private void loaded(int error) {
        if (!running) return;
        handler.removeCallbacks(refresh);
        failed = error != 0;
        showLists();
        // Only a problem is worth a line; when all is well there is none.
        status.setVisibility(failed ? View.VISIBLE : View.GONE);
        if (failed) {
            status.setText(error);
            handler.postDelayed(refresh, RETRY_MS);
            return;
        }
        tick();
        handler.postDelayed(refresh, Sheet.REFRESH_MS);
    }

    /** Once a minute: the greeting for the time of day, the countdowns' day and the game's start. */
    private void tick() {
        handler.removeCallbacks(tick);
        if (!running) return;
        greeting.setText(greeting(Calendar.getInstance().get(Calendar.HOUR_OF_DAY)));
        showCountdowns();   // the day may have changed
        showGame();         // and the game may have started
        handler.postDelayed(tick, 60_000L);
    }

    /** Good morning from 4 until noon, good evening the rest of the day, as the morning screen does. */
    static int greeting(int hour) {
        return hour >= 4 && hour < 12 ? R.string.greeting_morning : R.string.greeting_evening;
    }

    private void showLists() {
        // Birthdays come in the Today tab with "Birthday" as their time; they go under the date, not in the list.
        List<Lists.Item> today = new ArrayList<>();
        List<String> born = new ArrayList<>();
        for (Lists.Item item : Lists.events(Csv.parse(Config.read(new File(dir, Sheet.TODAY_CACHE))))) {
            if (Lists.isBirthday(item)) born.add(item.text);
            else today.add(item);
        }
        birthdays.setVisibility(born.isEmpty() ? View.GONE : View.VISIBLE);
        birthdays.setText("🎂  " + TextUtils.join("  ·  ", born));

        boolean anyEvents = !today.isEmpty();
        events.setVisibility(anyEvents ? View.VISIBLE : View.GONE);
        ((LinearLayout.LayoutParams) todos.getLayoutParams()).topMargin = anyEvents ? dp(20) : 0;
        fill(events, today);
        fillTodos();
        showCountdowns();
        showGame();
    }

    /** "23  days · Finals start" under the date, soonest first; nothing when the tab is empty. */
    private void showCountdowns() {
        countdowns.removeAllViews();
        LocalDate today = LocalDate.now();
        for (Lists.Countdown c : Lists.countdowns(
                Csv.parse(Config.read(new File(dir, Sheet.COUNTDOWN_CACHE))), today)) {
            long days = c.daysFrom(today);
            LinearLayout line = new LinearLayout(context);
            line.setOrientation(LinearLayout.HORIZONTAL);
            line.setGravity(Gravity.CENTER_VERTICAL);
            line.setBaselineAligned(false);
            TextView label = text(19, INK_DIM);
            if (days <= 1) {
                label.setTextColor(AMBER);
                label.setText(context.getString(days == 0 ? R.string.countdown_today : R.string.countdown_tomorrow,
                        c.name));
            } else {
                TextView number = text(30, AMBER);
                number.setText(String.valueOf(days));
                number.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
                LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                np.setMarginEnd(dp(10));
                line.addView(number, np);
                label.setText(context.getString(R.string.countdown_days, c.name));
            }
            line.addView(label, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            countdowns.addView(line, margins(dp(6), 0));
        }
    }

    /** "LAKERS TONIGHT / @ Utah Jazz / 10:00 PM · Prime Video", or the next game when there is none tonight. */
    private void showGame() {
        game.removeAllViews();
        Team.Game g = Team.next(dir, Instant.now());
        if (g == null) return;
        ZoneId zone = ZoneId.systemDefault();
        ZonedDateTime start = g.start.atZone(zone);
        long days = ChronoUnit.DAYS.between(LocalDate.now(zone), start.toLocalDate());
        boolean tonight = days <= 0;
        boolean onNow = !Instant.now().isBefore(g.start);
        String time = android.text.format.DateFormat.getTimeFormat(context).format(Date.from(g.start));

        TextView title = heading(tonight ? context.getString(R.string.game_tonight, Team.TEAM_NAME)
                : context.getString(R.string.game_next, Team.TEAM_NAME));
        game.addView(title);

        TextView match = text(26, INK);
        match.setText((g.home ? "vs " : "@ ") + g.opponent);
        match.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        game.addView(match, margins(dp(6), 0));

        String when = onNow ? context.getString(R.string.game_on_now)
                : tonight ? time
                : days == 1 ? context.getString(R.string.game_tomorrow, time)
                : start.format(DateTimeFormatter.ofPattern("EEEE", Locale.getDefault())) + " " + time;
        TextView watch = text(19, INK_DIM);
        watch.setMaxLines(2);
        watch.setText(when + "  ·  " + g.whereToWatch());
        game.addView(watch, margins(dp(4), 0));
    }

    /** Today's events under their heading; the heading (child 0) stays. */
    private void fill(LinearLayout panel, List<Lists.Item> items) {
        panel.removeViews(1, panel.getChildCount() - 1);
        for (Lists.Item item : items) panel.addView(row(item, true, 19), margins(dp(8), 0));
    }

    /**
     * Every open task. One column at full size when they fit; otherwise two columns in smaller type; otherwise pages
     * of two columns, turning every {@link #PAGE_MS}. Done tasks fold into one line at the bottom.
     */
    private void fillTodos() {
        handler.removeCallbacks(nextPage);
        handler.removeCallbacks(relayout);
        TextView title = (TextView) todos.getChildAt(0);
        todos.removeViews(1, todos.getChildCount() - 1);
        List<Lists.Item> open = new ArrayList<>();
        int done = 0;
        for (Lists.Item item : Lists.todos(Csv.parse(Config.read(new File(dir, Sheet.TODO_CACHE))))) {
            if (item.done) done++;
            else open.add(item);
        }
        title.setText(R.string.todo_heading);
        todoPages = 1;
        if (open.isEmpty()) {
            TextView none = text(22, INK_FAINT);
            none.setText(R.string.no_todos);
            todos.addView(none, margins(dp(18), 0));
            footer(done);
            return;
        }
        if (todos.getHeight() == 0) {
            // Not laid out yet: measure on the next frame.
            if (running) handler.post(relayout);
            return;
        }
        int avail = todos.getHeight() - todos.getPaddingTop() - todos.getPaddingBottom() - title.getHeight()
                - (done > 0 ? dp(40) : 0);
        int width = todos.getWidth() - todos.getPaddingLeft() - todos.getPaddingRight();

        // The first layout that holds every task, measured with the tasks wrapped as they really wrap: one column
        // at full size, one column a little smaller, two columns. Only when even two columns of small type are not
        // enough does the list page, as many tasks per page as fit.
        int[][] layouts = {{1, 23, 14}, {1, 20, 10}, {2, 20, 10}, {2, 18, 8}};   // columns, sp, gap dp
        int[] chosen = layouts[layouts.length - 1];
        int perPage = 0;
        for (int[] l : layouts) {
            if (fits(grid(open, l), width, avail)) {
                chosen = l;
                perPage = open.size();
                break;
            }
        }
        if (perPage == 0) {
            perPage = 1;
            for (int k = open.size() - 1; k > 1; k--) {
                if (fits(grid(open.subList(0, k), chosen), width, avail)) {
                    perPage = k;
                    break;
                }
            }
        }
        todoPages = (open.size() + perPage - 1) / perPage;
        todoPage = Math.min(todoPage, todoPages - 1);
        List<Lists.Item> shown = open.subList(todoPage * perPage, Math.min(open.size(), (todoPage + 1) * perPage));
        if (todoPages > 1) {
            title.setText(context.getString(R.string.todo_heading_paged, context.getString(R.string.todo_heading),
                    todoPage + 1, todoPages));
            handler.postDelayed(nextPage, PAGE_MS);
        }
        todos.addView(grid(shown, chosen), margins(0, 0));
        footer(done);
    }

    /** The tasks in {@code layout[0]} balanced columns of {@code layout[1]} sp, {@code layout[2]} dp apart. */
    private LinearLayout grid(List<Lists.Item> items, int[] layout) {
        int columns = layout[0];
        int perColumn = (items.size() + columns - 1) / columns;   // five tasks go three and two
        LinearLayout grid = new LinearLayout(context);
        grid.setOrientation(LinearLayout.HORIZONTAL);
        grid.setBaselineAligned(false);
        for (int c = 0; c < columns; c++) {
            LinearLayout column = new LinearLayout(context);
            column.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            if (c < columns - 1) lp.setMarginEnd(dp(24));
            grid.addView(column, lp);
            for (int i = c * perColumn; i < Math.min(items.size(), (c + 1) * perColumn); i++) {
                column.addView(row(items.get(i), false, layout[1]), margins(dp(layout[2]), 0));
            }
        }
        return grid;
    }

    /** Whether {@code grid}, laid out {@code width} px wide, is at most {@code height} px tall. */
    private static boolean fits(View grid, int width, int height) {
        grid.setLayoutParams(new ViewGroup.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT));
        grid.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        return grid.getMeasuredHeight() <= height;
    }

    /** "✓ 3 done" at the bottom of the to-do panel. */
    private void footer(int done) {
        if (done == 0) return;
        todos.addView(new View(context), new LinearLayout.LayoutParams(0, 0, 1f));
        TextView line = text(15, INK_FAINT);
        line.setText(context.getResources().getQuantityString(R.plurals.todo_done, done, done));
        todos.addView(line, margins(dp(8), 0));
    }

    private View row(Lists.Item item, boolean event, int sp) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.TOP);
        // No baseline alignment: it would push the empty checkbox down to the text's baseline and clip it.
        row.setBaselineAligned(false);
        if (event) {
            TextView time = text(15, AMBER);
            time.setText(item.detail);
            time.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            time.setPadding(0, dp(3), 0, 0);
            row.addView(time, new LinearLayout.LayoutParams(dp(78), ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        TextView label = text(sp, item.done ? INK_FAINT : INK);
        label.setText(item.text);
        // Wrap rather than cut a task or an event short with "…".
        label.setMaxLines(event ? 2 : 3);
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

    /** The box centred on the first line of its task. */
    private LinearLayout.LayoutParams boxParams(int lineHeight) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(20), dp(20));
        lp.topMargin = Math.max(0, (lineHeight - dp(20)) / 2);
        lp.setMarginEnd(dp(16));
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
        panel.setPadding(dp(30), dp(22), dp(30), dp(20));
        lp.topMargin = top;
        parent.addView(panel, lp);
        panel.addView(heading(context.getString(heading)));
        return panel;
    }

    /** Small amber capitals, as every heading on this screen. */
    private TextView heading(String text) {
        TextView title = text(14, AMBER);
        title.setText(text);
        title.setAllCaps(true);
        title.setLetterSpacing(0.16f);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return title;
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
