package com.gathin.tvlist;

import android.content.Context;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
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
 * What the list screen shows, set like a printed page rather than an app: a flat, warm near-black field, warm white
 * type, one grey for everything secondary, and red only for a birthday. No boxes: each section is a small label over
 * a hairline rule. Tasks and events are set in a serif, labels and times in Roboto with even-width digits, the
 * greeting in Naskh. On the left today's events (gone when there are none) and the to-do list, every open task shown:
 * one column when they fit, two in smaller type when not, and pages past that (the arrows flip them). On the right,
 * flush right like the Arabic above them: the greeting, birthdays, countdowns as big numerals, and the Lakers' next
 * game with where to watch it. The sheet is read on every start and each minute while it shows. The whole picture
 * drifts a few dp, very slowly, against burn-in.
 */
final class Scene {
    static final int PAPER = 0xFF121110;
    static final int INK = 0xFFEFEBE3;
    static final int MUTED = 0xFF8E887E;
    static final int FAINT = 0xFF5C5852;
    static final int RULE = 0xFF2C2A27;
    static final int SAND = 0xFFD9BE8F;
    static final int RED = 0xFFE5484D;
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
    private final Typeface serif = Typeface.create("serif", Typeface.NORMAL);
    private final Typeface sansLight = sans(300);
    private final Typeface sans = sans(400);
    private final Typeface sansMedium = sans(500);
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
        root.setBackgroundColor(PAPER);
        root.setKeepScreenOn(true);
        stage = new FrameLayout(context);
        root.addView(stage, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout page = new LinearLayout(context);
        page.setOrientation(LinearLayout.HORIZONTAL);
        FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        plp.setMargins(dp(88), dp(64), dp(88), dp(56));
        stage.addView(page, plp);

        // Left: today's events, then the to-do list, the reason for this screen.
        LinearLayout left = new LinearLayout(context);
        left.setOrientation(LinearLayout.VERTICAL);
        page.addView(left, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0.62f));
        events = section(left, R.string.today_heading, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT), 0);
        todos = section(left, R.string.todo_heading, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f), dp(40));

        // Right, flush right: greeting, birthdays, countdowns, the game, and an error line when there is one.
        LinearLayout right = new LinearLayout(context);
        right.setOrientation(LinearLayout.VERTICAL);
        right.setGravity(Gravity.END);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0.38f);
        rlp.setMarginStart(dp(72));
        page.addView(right, rlp);

        // Arabic reads right to left, so it starts at the right edge.
        greeting = text(52, INK, serif);
        greeting.setTextDirection(View.TEXT_DIRECTION_RTL);
        greeting.setTextAlignment(View.TEXT_ALIGNMENT_TEXT_START);
        greeting.setIncludeFontPadding(false);
        right.addView(greeting, margins(0, 0));

        // Today's birthdays, in red, right under the greeting; gone on other days.
        birthdays = text(20, RED, sansMedium);
        birthdays.setMaxLines(3);
        birthdays.setGravity(Gravity.END);
        birthdays.setVisibility(View.GONE);
        right.addView(birthdays, margins(dp(12), 0));

        countdowns = new LinearLayout(context);
        countdowns.setOrientation(LinearLayout.VERTICAL);
        right.addView(countdowns, margins(dp(40), 0));

        game = new LinearLayout(context);
        game.setOrientation(LinearLayout.VERTICAL);
        right.addView(game, margins(dp(40), 0));

        right.addView(new View(context), new LinearLayout.LayoutParams(0, 0, 1f));
        status = text(14, RED, sans);
        status.setMaxLines(3);
        status.setGravity(Gravity.END);
        status.setVisibility(View.GONE);
        right.addView(status, margins(0, 0));
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
        showCountdowns();
        showGame();
        handler.postDelayed(tick, 60_000L);
    }

    /** Good morning from 4 until noon, good evening the rest of the day, as the morning screen does. */
    static int greeting(int hour) {
        return hour >= 4 && hour < 12 ? R.string.greeting_morning : R.string.greeting_evening;
    }

    private void showLists() {
        // Birthdays come in the Today tab with "Birthday" as their time; they go under the greeting, not in the list.
        List<Lists.Item> today = new ArrayList<>();
        List<String> born = new ArrayList<>();
        for (Lists.Item item : Lists.events(Csv.parse(Config.read(new File(dir, Sheet.TODAY_CACHE))))) {
            if (Lists.isBirthday(item)) born.add(item.text);
            else today.add(item);
        }
        birthdays.setVisibility(born.isEmpty() ? View.GONE : View.VISIBLE);
        birthdays.setText(context.getString(R.string.birthday_line, TextUtils.join(" · ", born)));

        boolean anyEvents = !today.isEmpty();
        events.setVisibility(anyEvents ? View.VISIBLE : View.GONE);
        ((LinearLayout.LayoutParams) todos.getLayoutParams()).topMargin = anyEvents ? dp(40) : 0;
        clear(events);
        for (Lists.Item item : today) events.addView(eventRow(item), margins(dp(14), 0));
        fillTodos();
        showCountdowns();
        showGame();
    }

    /** A big serif numeral and what it counts to, flush right; "Today" and "Tomorrow" in words. */
    private void showCountdowns() {
        countdowns.removeAllViews();
        LocalDate today = LocalDate.now();
        boolean first = true;
        for (Lists.Countdown c : Lists.countdowns(
                Csv.parse(Config.read(new File(dir, Sheet.COUNTDOWN_CACHE))), today)) {
            long days = c.daysFrom(today);
            TextView number = text(days <= 1 ? 40 : 64, INK, serif);
            number.setGravity(Gravity.END);
            number.setIncludeFontPadding(false);
            number.setText(days == 0 ? context.getString(R.string.countdown_today_word)
                    : days == 1 ? context.getString(R.string.countdown_tomorrow_word) : String.valueOf(days));
            countdowns.addView(number, margins(first ? 0 : dp(24), 0));
            TextView caption = text(17, MUTED, sans);
            caption.setGravity(Gravity.END);
            caption.setText(days <= 1 ? c.name : context.getString(R.string.countdown_until, c.name));
            countdowns.addView(caption, margins(dp(4), 0));
            first = false;
        }
    }

    /** "Lakers tonight / vs Utah Jazz / 10:00 PM · Prime Video", or the next game when there is none tonight. */
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

        TextView label = label(tonight ? context.getString(R.string.game_tonight, Team.TEAM_NAME)
                : context.getString(R.string.game_next, Team.TEAM_NAME));
        label.setGravity(Gravity.END);
        game.addView(label, margins(0, 0));

        TextView match = text(32, INK, serif);
        match.setGravity(Gravity.END);
        match.setMaxLines(2);
        match.setText((g.home ? "vs " : "at ") + g.opponent);
        game.addView(match, margins(dp(10), 0));

        String when = onNow ? context.getString(R.string.game_on_now)
                : tonight ? time
                : days == 1 ? context.getString(R.string.game_tomorrow, time)
                : start.format(DateTimeFormatter.ofPattern("EEEE", Locale.getDefault())) + " " + time;
        TextView whenLine = text(18, MUTED, sans);
        whenLine.setGravity(Gravity.END);
        whenLine.setFontFeatureSettings("tnum");
        whenLine.setText(when);
        game.addView(whenLine, margins(dp(6), 0));

        TextView watch = text(18, SAND, sansMedium);
        watch.setGravity(Gravity.END);
        watch.setText(g.whereToWatch());
        game.addView(watch, margins(dp(2), 0));
    }

    /** "3:00 PM   ECON office hours": the time in grey, even digits; the event in serif. */
    private View eventRow(Lists.Item item) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setBaselineAligned(true);
        TextView time = text(17, MUTED, sans);
        time.setFontFeatureSettings("tnum");
        time.setText(item.detail);
        row.addView(time, new LinearLayout.LayoutParams(dp(104), ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView title = text(24, INK, serif);
        title.setMaxLines(2);
        title.setText(item.text);
        row.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return row;
    }

    /**
     * Every open task, numbered. One column at full size when they fit; one column a little smaller; two columns;
     * past that, pages of as many as fit, turning every {@link #PAGE_MS}. Done tasks fold into one line at the bottom.
     */
    private void fillTodos() {
        handler.removeCallbacks(nextPage);
        handler.removeCallbacks(relayout);
        TextView title = (TextView) todos.getChildAt(0);
        clear(todos);
        List<Lists.Item> open = new ArrayList<>();
        int done = 0;
        for (Lists.Item item : Lists.todos(Csv.parse(Config.read(new File(dir, Sheet.TODO_CACHE))))) {
            if (item.done) done++;
            else open.add(item);
        }
        title.setText(R.string.todo_heading);
        todoPages = 1;
        if (open.isEmpty()) {
            TextView none = text(28, MUTED, serif);
            none.setTypeface(Typeface.create(serif, Typeface.ITALIC));
            none.setText(R.string.no_todos);
            todos.addView(none, margins(dp(20), 0));
            footer(done);
            return;
        }
        if (todos.getHeight() == 0) {
            // Not laid out yet: measure on the next frame.
            if (running) handler.post(relayout);
            return;
        }
        int used = 0;
        for (int i = 0; i < 2; i++) used += todos.getChildAt(i).getHeight();
        int avail = todos.getHeight() - used - dp(8) - (done > 0 ? dp(36) : 0);
        int width = todos.getWidth();

        int[][] layouts = {{1, 30, 18}, {1, 26, 14}, {2, 24, 14}, {2, 21, 10}};   // columns, sp, gap dp
        int[] chosen = layouts[layouts.length - 1];
        int perPage = 0;
        for (int[] l : layouts) {
            if (fits(grid(open, 0, l), width, avail)) {
                chosen = l;
                perPage = open.size();
                break;
            }
        }
        if (perPage == 0) {
            perPage = 1;
            for (int k = open.size() - 1; k > 1; k--) {
                if (fits(grid(open.subList(0, k), 0, chosen), width, avail)) {
                    perPage = k;
                    break;
                }
            }
        }
        todoPages = (open.size() + perPage - 1) / perPage;
        todoPage = Math.min(todoPage, todoPages - 1);
        int from = todoPage * perPage;
        List<Lists.Item> shown = open.subList(from, Math.min(open.size(), from + perPage));
        if (todoPages > 1) {
            title.setText(context.getString(R.string.todo_heading_paged, context.getString(R.string.todo_heading),
                    todoPage + 1, todoPages));
            handler.postDelayed(nextPage, PAGE_MS);
        }
        todos.addView(grid(shown, from, chosen), margins(dp(8), 0));
        footer(done);
    }

    /** The tasks in {@code layout[0]} balanced columns of {@code layout[1]} sp, {@code layout[2]} dp apart. */
    private LinearLayout grid(List<Lists.Item> items, int firstNumber, int[] layout) {
        int columns = layout[0];
        int perColumn = (items.size() + columns - 1) / columns;   // five tasks go three and two
        LinearLayout grid = new LinearLayout(context);
        grid.setOrientation(LinearLayout.HORIZONTAL);
        grid.setBaselineAligned(false);
        for (int c = 0; c < columns; c++) {
            LinearLayout column = new LinearLayout(context);
            column.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            if (c < columns - 1) lp.setMarginEnd(dp(40));
            grid.addView(column, lp);
            for (int i = c * perColumn; i < Math.min(items.size(), (c + 1) * perColumn); i++) {
                column.addView(taskRow(items.get(i), firstNumber + i + 1, layout[1]),
                        margins(i == c * perColumn ? 0 : dp(layout[2]), 0));
            }
        }
        return grid;
    }

    /** "01   Book NY tickets for Thanksgiving": a quiet number, then the task in serif, wrapped, never cut. */
    private View taskRow(Lists.Item item, int number, int sp) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setBaselineAligned(true);
        TextView num = text(Math.round(sp * 0.6f), FAINT, sansLight);
        num.setFontFeatureSettings("tnum");
        num.setText(String.format(Locale.US, "%02d", number));
        row.addView(num, new LinearLayout.LayoutParams(dp(Math.round(sp * 1.6f)), ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView label = text(sp, INK, serif);
        label.setMaxLines(3);
        label.setLineSpacing(0, 1.08f);
        label.setText(item.text);
        row.addView(label, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return row;
    }

    /** Whether {@code grid}, laid out {@code width} px wide, is at most {@code height} px tall. */
    private static boolean fits(View grid, int width, int height) {
        grid.setLayoutParams(new ViewGroup.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT));
        grid.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        return grid.getMeasuredHeight() <= height;
    }

    /** "2 done" at the bottom of the list. */
    private void footer(int done) {
        if (done == 0) return;
        todos.addView(new View(context), new LinearLayout.LayoutParams(0, 0, 1f));
        TextView line = text(15, FAINT, sans);
        line.setText(context.getResources().getQuantityString(R.plurals.todo_done, done, done));
        todos.addView(line, margins(dp(8), 0));
    }

    /** A section: a small grey label over a hairline rule, added to {@code parent}; content goes under the rule. */
    private LinearLayout section(LinearLayout parent, int heading, LinearLayout.LayoutParams lp, int top) {
        LinearLayout section = new LinearLayout(context);
        section.setOrientation(LinearLayout.VERTICAL);
        lp.topMargin = top;
        parent.addView(section, lp);
        section.addView(label(context.getString(heading)));
        View rule = new View(context);
        rule.setBackgroundColor(RULE);
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                Math.max(1, dp(1)));
        rp.topMargin = dp(12);
        rp.bottomMargin = dp(6);
        section.addView(rule, rp);
        return section;
    }

    /** Removes a section's content, keeping its label and rule. */
    private static void clear(LinearLayout section) {
        if (section.getChildCount() > 2) section.removeViews(2, section.getChildCount() - 2);
    }

    /** Small grey capitals, widely spaced: every label on this screen. */
    private TextView label(String text) {
        TextView t = text(13, MUTED, sansMedium);
        t.setText(text);
        t.setAllCaps(true);
        t.setLetterSpacing(0.22f);
        return t;
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

    private TextView text(int sp, int color, Typeface face) {
        TextView t = new TextView(context);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setTypeface(face);
        t.setEllipsize(TextUtils.TruncateAt.END);
        t.setMaxLines(1);
        return t;
    }

    /** Roboto at a weight; Android 9 and later take any weight, older ones the nearest named style. */
    private static Typeface sans(int weight) {
        if (Build.VERSION.SDK_INT >= 28) return Typeface.create(Typeface.create("sans-serif", Typeface.NORMAL), weight, false);
        return Typeface.create(weight < 400 ? "sans-serif-light" : weight >= 500 ? "sans-serif-medium" : "sans-serif",
                Typeface.NORMAL);
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
