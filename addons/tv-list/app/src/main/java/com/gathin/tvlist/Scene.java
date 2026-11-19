package com.gathin.tvlist;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
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
    private static final int NOW = 0, NEXT = 1, UPCOMING = 2;
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
    /** The system serif: only for the Arabic greeting, which it sets in Noto Naskh. */
    private final Typeface naskh = Typeface.create("serif", Typeface.NORMAL);
    // Fraunces (assets/fonts, SIL Open Font License) at settings for each job: soft and light for the big numerals,
    // a book weight at text size for tasks, the italic for captions and numbers.
    private final Typeface display;
    private final Typeface displayItalic;
    private final Typeface body;
    private final Typeface bodyItalic;
    private final Typeface heading;
    private final Typeface sansLight = sans(300);
    private final Typeface sans = sans(400);
    private final Typeface sansMedium = sans(500);
    private final FrameLayout stage;
    private final TextView greeting;
    private final SkyMark sky;
    private final TextView clock;
    private final TextView date;
    private final LinearLayout mediaBox;
    private final TextView mediaTitle;
    private final TextView mediaArtist;
    private final android.widget.ImageView mediaArt;
    private android.media.session.MediaSessionManager sessions;
    private android.media.session.MediaController playing;
    private boolean mediaShowing;
    private final TextView birthdays;
    private final LinearLayout countdowns;
    private final LinearLayout game;
    private final LinearLayout events;
    private final LinearLayout todos;
    private final LinearLayout historyBox;
    private final TextView historyYear;
    private final TextView historyText;
    private List<History.Event> history = new ArrayList<>();
    private List<Lists.Item> todayItems = new ArrayList<>();
    private int historyIndex;
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
    private final Runnable clockTick = this::clockTick;
    private final Runnable nextHistory = () -> showHistoryEvent(true);
    private static final long HISTORY_MS = 20_000L;
    private static final int HISTORY_LINES = 3;

    Scene(Context context) {
        this.context = context;
        this.dir = context.getFilesDir();
        display = fraunces(context, false, 300, 144, 100, 1);
        displayItalic = fraunces(context, true, 300, 144, 100, 1);
        body = fraunces(context, false, 400, 36, 50, 0);
        bodyItalic = fraunces(context, true, 400, 24, 50, 1);
        heading = fraunces(context, false, 500, 72, 30, 1);

        root = new FrameLayout(context);
        // A faint vignette: a touch lighter in the middle, darker at the edges. Depth without a glow.
        GradientDrawable paper = new GradientDrawable();
        paper.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        paper.setColors(new int[]{0xFF191715, PAPER, 0xFF0B0A09});
        paper.setGradientRadius(context.getResources().getDisplayMetrics().widthPixels * 0.75f);
        // Over it a fine grain, so the black reads as printed rather than flat.
        root.setBackground(new android.graphics.drawable.LayerDrawable(new android.graphics.drawable.Drawable[]{
                paper, grain(context)}));
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
        page.addView(left, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0.60f));
        events = section(left, R.string.today_heading, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT), 0);
        todos = section(left, R.string.todo_heading, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f), dp(40));
        // Whenever the list's room changes (Today appears, an error line goes), fit the tasks to it again.
        todos.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (b - t != ob - ot || r - l != or - ol) handler.post(relayout);
        });

        // Right, flush right: greeting, birthdays, countdowns, the game, and an error line when there is one.
        LinearLayout right = new LinearLayout(context);
        right.setOrientation(LinearLayout.VERTICAL);
        right.setGravity(Gravity.END);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0.40f);
        rlp.setMarginStart(dp(56));
        page.addView(right, rlp);
        // The moon's glow spills past the column and the page margin instead of stopping at a hard edge.
        right.setClipChildren(false);
        page.setClipChildren(false);
        stage.setClipChildren(false);
        right.setClipToPadding(false);

        // Tonight's moon (or the morning sun) in its glow, then the greeting; Arabic ends at the right edge.
        // Layered, not side by side: the greeting has the column's whole width, flush right, and the moon sits at
        // the column's left in the same band, its glow behind.
        FrameLayout top = new FrameLayout(context);
        top.setClipChildren(false);
        sky = new SkyMark(context);
        greeting = text(36, INK, naskh);
        greeting.setTextDirection(View.TEXT_DIRECTION_RTL);
        greeting.setIncludeFontPadding(false);
        greeting.setEllipsize(null);   // never cut the greeting short
        top.addView(greeting, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.END | Gravity.CENTER_VERTICAL));
        LinearLayout.LayoutParams tp = margins(0, 0);
        right.addView(top, tp);

        // The time in Arabic numerals, with ص or م, and the date under it in italic.
        // Tonight's moon (or the sun) rides beside the clock, at the left of the same band, its glow behind.
        FrameLayout timeBand = new FrameLayout(context);
        timeBand.setClipChildren(false);
        timeBand.addView(sky, new FrameLayout.LayoutParams(dp(92), dp(92), Gravity.START | Gravity.CENTER_VERTICAL));
        clock = text(46, INK, naskh);
        clock.setTextDirection(View.TEXT_DIRECTION_RTL);
        clock.setIncludeFontPadding(false);
        clock.setEllipsize(null);
        timeBand.addView(clock, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.END | Gravity.CENTER_VERTICAL));
        right.addView(timeBand, margins(dp(4), 0));
        date = text(18, MUTED, bodyItalic);
        date.setGravity(Gravity.END);
        right.addView(date, margins(dp(2), 0));

        // Today's birthdays, in red, right under the date; gone on other days.
        birthdays = text(20, RED, bodyItalic);
        birthdays.setMaxLines(3);
        birthdays.setGravity(Gravity.END);
        birthdays.setVisibility(View.GONE);
        right.addView(birthdays, margins(dp(8), 0));

        // The column uses its whole height: greeting at the top, countdowns in the middle, the game at the bottom,
        // level with the foot of the list.
        right.addView(new View(context), new LinearLayout.LayoutParams(0, 0, 1f));
        countdowns = new LinearLayout(context);
        countdowns.setOrientation(LinearLayout.VERTICAL);
        right.addView(countdowns, margins(dp(18), 0));
        right.addView(new View(context), new LinearLayout.LayoutParams(0, 0, 1f));

        game = new LinearLayout(context);
        game.setOrientation(LinearLayout.VERTICAL);
        right.addView(game, margins(dp(18), 0));

        // At the foot: on this day in history, one event at a time, crossfading.
        historyBox = new LinearLayout(context);
        historyBox.setOrientation(LinearLayout.VERTICAL);
        historyBox.setGravity(Gravity.END);
        historyBox.setVisibility(View.GONE);
        right.addView(historyBox, margins(dp(18), 0));
        LinearLayout historyHead = new LinearLayout(context);
        historyHead.setOrientation(LinearLayout.HORIZONTAL);
        historyHead.setGravity(Gravity.END | Gravity.BOTTOM);
        historyHead.setBaselineAligned(true);
        historyBox.addView(historyHead, margins(0, 0));
        historyHead.addView(label(context.getString(R.string.history_heading)), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        historyYear = text(24, SAND, displayItalic);
        LinearLayout.LayoutParams yp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        yp.setMarginStart(dp(14));
        historyHead.addView(historyYear, yp);
        historyText = text(16, 0xFFC9C3B8, body);
        historyText.setMaxLines(HISTORY_LINES);
        historyText.setEllipsize(null);
        historyText.setGravity(Gravity.END);
        historyText.setLineSpacing(0, 1.1f);
        historyBox.addView(historyText, margins(dp(4), 0));
        // Now playing, in the history's place while music plays: song and artist, the album art at the edge.
        mediaBox = new LinearLayout(context);
        mediaBox.setOrientation(LinearLayout.HORIZONTAL);
        mediaBox.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        mediaBox.setVisibility(View.GONE);
        right.addView(mediaBox, margins(dp(18), 0));
        LinearLayout mediaText = new LinearLayout(context);
        mediaText.setOrientation(LinearLayout.VERTICAL);
        mediaText.setGravity(Gravity.END);
        mediaBox.addView(mediaText, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView mediaLabel = label(context.getString(R.string.now_playing));
        mediaLabel.setGravity(Gravity.END);
        mediaText.addView(mediaLabel, margins(0, 0));
        mediaTitle = text(20, INK, body);
        mediaTitle.setGravity(Gravity.END);
        mediaText.addView(mediaTitle, margins(dp(6), 0));
        mediaArtist = text(15, MUTED, bodyItalic);
        mediaArtist.setGravity(Gravity.END);
        mediaText.addView(mediaArtist, margins(dp(2), 0));
        mediaArt = new android.widget.ImageView(context);
        mediaArt.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
        mediaArt.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override
            public void getOutline(View view, android.graphics.Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(6));
            }
        });
        mediaArt.setClipToOutline(true);
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(dp(68), dp(68));
        ap.setMarginStart(dp(16));
        mediaBox.addView(mediaArt, ap);

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
        clockTick();
        startMedia();
    }

    void stop() {
        running = false;
        handler.removeCallbacksAndMessages(null);
        stage.animate().cancel();
        historyBox.animate().cancel();
        stopMedia();
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
            try {
                History.refresh(dir, LocalDate.now());
            } catch (IOException | RuntimeException e) {
                Log.w("TvList", "history: " + e);
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
        int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        greeting.setText(context.getString(R.string.greeting_with_name, context.getString(greeting(hour)),
                context.getString(R.string.owner_name)));
        sky.set(greeting(hour) == R.string.greeting_morning, System.currentTimeMillis());
        showToday();        // events leave the minute they end
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

        todayItems = today;
        showToday();
        showHistory();
        fillTodos();
        showCountdowns();
        showGame();
    }

    /**
     * Today's events as a timeline, checked every minute: an event leaves the list the minute it ends (classes
     * included), the one on now shows "Now", and the next one's time is in sand. Gone when nothing is left.
     */
    private void showToday() {
        java.time.LocalTime now = java.time.LocalTime.now();
        clear(events);
        boolean nextFound = false;
        int shown = 0;
        for (Lists.Item item : todayItems) {
            java.time.LocalTime start = Lists.time(item.detail);
            java.time.LocalTime end = Lists.time(item.end);
            // An end before the start runs past midnight: it has not ended today.
            boolean over = end != null && !(start != null && end.isBefore(start)) && !now.isBefore(end);
            if (over) continue;
            int state = UPCOMING;
            if (start != null && !now.isBefore(start)) {
                state = NOW;
            } else if (start != null && !nextFound) {
                state = NEXT;
                nextFound = true;
            }
            events.addView(eventRow(item, state), margins(dp(10), 0));
            shown++;
        }
        boolean any = shown > 0;
        events.setVisibility(any ? View.VISIBLE : View.GONE);
        ((LinearLayout.LayoutParams) todos.getLayoutParams()).topMargin = any ? dp(40) : 0;
        todos.requestLayout();
    }

    /** The time as ٩:١٥, the date in words; again at the turn of the next minute. */
    private void clockTick() {
        handler.removeCallbacks(clockTick);
        if (!running) return;
        java.time.LocalDateTime now = java.time.LocalDateTime.now();
        clock.setText(arabicTime(now.getHour(), now.getMinute()));
        date.setText(now.format(DateTimeFormatter.ofPattern("EEEE, MMMM d", Locale.getDefault())));
        long msIntoMinute = now.getSecond() * 1000L + now.getNano() / 1_000_000L;
        handler.postDelayed(clockTick, 60_000L - msIntoMinute + 50);
    }

    /** 21:05 as ٩:٠٥: a twelve-hour clock in Arabic-Indic digits. */
    static String arabicTime(int hour, int minute) {
        int h = hour % 12 == 0 ? 12 : hour % 12;
        return arabicDigits(h) + ":" + arabicDigits(minute / 10) + arabicDigits(minute % 10);
    }

    private final android.media.session.MediaSessionManager.OnActiveSessionsChangedListener sessionsChanged =
            controllers -> pickPlaying();

    private final android.media.session.MediaController.Callback playingChanged =
            new android.media.session.MediaController.Callback() {
                @Override
                public void onPlaybackStateChanged(android.media.session.PlaybackState state) {
                    pickPlaying();
                }

                @Override
                public void onMetadataChanged(android.media.MediaMetadata metadata) {
                    showMedia();
                }

                @Override
                public void onSessionDestroyed() {
                    pickPlaying();
                }
            };

    /**
     * What is playing on the TV (Spotify, YouTube Music, a cast): Android's active media sessions, which it hands
     * only to an app whose notification listener the owner allowed. Without that, the box simply never shows.
     */
    private void startMedia() {
        sessions = context.getSystemService(android.media.session.MediaSessionManager.class);
        if (sessions == null) return;
        try {
            sessions.addOnActiveSessionsChangedListener(sessionsChanged, listener(), handler);
            pickPlaying();
        } catch (SecurityException e) {
            Log.i("TvList", "now playing: notification access not allowed");
            sessions = null;
        }
    }

    private void stopMedia() {
        if (sessions != null) sessions.removeOnActiveSessionsChangedListener(sessionsChanged);
        if (playing != null) playing.unregisterCallback(playingChanged);
        playing = null;
        sessions = null;
    }

    private android.content.ComponentName listener() {
        return new android.content.ComponentName(context, MediaListener.class);
    }

    /** Follows the session that is playing; when none is, the box goes and the history comes back. */
    private void pickPlaying() {
        if (sessions == null || !running) return;
        android.media.session.MediaController found = null;
        try {
            for (android.media.session.MediaController c : sessions.getActiveSessions(listener())) {
                android.media.session.PlaybackState st = c.getPlaybackState();
                if (st != null && st.getState() == android.media.session.PlaybackState.STATE_PLAYING) {
                    found = c;
                    break;
                }
            }
        } catch (SecurityException e) {
            found = null;
        }
        if (playing != null && (found == null || !playing.getSessionToken().equals(found.getSessionToken()))) {
            playing.unregisterCallback(playingChanged);
            playing = null;
        }
        if (found != null && playing == null) {
            playing = found;
            playing.registerCallback(playingChanged, handler);
        }
        showMedia();
    }

    private void showMedia() {
        android.media.MediaMetadata meta = playing == null ? null : playing.getMetadata();
        android.media.session.PlaybackState st = playing == null ? null : playing.getPlaybackState();
        boolean on = meta != null && st != null && st.getState() == android.media.session.PlaybackState.STATE_PLAYING;
        String title = on ? meta.getString(android.media.MediaMetadata.METADATA_KEY_TITLE) : null;
        on = on && title != null && !title.isEmpty();
        mediaShowing = on;
        mediaBox.setVisibility(on ? View.VISIBLE : View.GONE);
        historyBox.setVisibility(!on && !history.isEmpty() ? View.VISIBLE : View.GONE);
        if (!on) return;
        String artist = meta.getString(android.media.MediaMetadata.METADATA_KEY_ARTIST);
        if (artist == null || artist.isEmpty()) {
            artist = meta.getString(android.media.MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE);
        }
        mediaTitle.setText(title);
        mediaArtist.setText(artist == null ? "" : artist);
        android.graphics.Bitmap art = meta.getBitmap(android.media.MediaMetadata.METADATA_KEY_ALBUM_ART);
        if (art == null) art = meta.getBitmap(android.media.MediaMetadata.METADATA_KEY_ART);
        if (art == null) art = meta.getBitmap(android.media.MediaMetadata.METADATA_KEY_DISPLAY_ICON);
        mediaArt.setImageBitmap(art);
        mediaArt.setVisibility(art == null ? View.GONE : View.VISIBLE);
    }

    /** Today's events in history, from the cache; the box hides when there are none (yet). */
    private void showHistory() {
        history = History.load(dir, LocalDate.now());
        // Only events that fit whole in their lines: never one cut short with "…".
        int width = ((View) historyBox.getParent()).getWidth();
        if (width > 0) {
            List<History.Event> whole = new ArrayList<>();
            for (History.Event e : history) {
                android.text.StaticLayout layout = android.text.StaticLayout.Builder.obtain(e.text, 0,
                        e.text.length(), historyText.getPaint(), width).build();
                if (layout.getLineCount() <= HISTORY_LINES) whole.add(e);
            }
            history = whole;
        } else if (running) {
            handler.post(this::showHistory);   // not laid out yet
        }
        historyBox.setVisibility(history.isEmpty() || mediaShowing ? View.GONE : View.VISIBLE);
        if (history.isEmpty()) return;
        historyIndex = historyIndex % history.size();
        showHistoryEvent(false);
    }

    /** The current event, or the next one with a crossfade; the next turn comes {@link #HISTORY_MS} later. */
    private void showHistoryEvent(boolean advance) {
        handler.removeCallbacks(nextHistory);
        if (!running || history.isEmpty()) return;
        if (advance) historyIndex = (historyIndex + 1) % history.size();
        History.Event e = history.get(historyIndex);
        if (advance) {
            historyBox.animate().alpha(0f).setDuration(500).withEndAction(() -> {
                historyYear.setText(String.valueOf(e.year));
                historyText.setText(e.text);
                historyBox.animate().alpha(1f).setDuration(700).start();
            }).start();
        } else {
            historyYear.setText(String.valueOf(e.year));
            historyText.setText(e.text);
        }
        if (history.size() > 1) handler.postDelayed(nextHistory, HISTORY_MS);
    }

    /**
     * One line per countdown, flush right: "٢٣  days until Finals start", the number small and in sand so it never
     * reads as the clock above it; "Today · Finals start" and "Tomorrow · …" in words.
     */
    private void showCountdowns() {
        countdowns.removeAllViews();
        LocalDate today = LocalDate.now();
        for (Lists.Countdown c : Lists.countdowns(
                Csv.parse(Config.read(new File(dir, Sheet.COUNTDOWN_CACHE))), today)) {
            long days = c.daysFrom(today);
            LinearLayout line = new LinearLayout(context);
            line.setOrientation(LinearLayout.HORIZONTAL);
            line.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
            line.setBaselineAligned(false);
            if (days > 1) {
                TextView number = text(24, SAND, naskh);
                number.setIncludeFontPadding(false);
                number.setText(arabicDigits(days));
                LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                np.setMarginEnd(dp(10));
                line.addView(number, np);
            }
            TextView caption = text(16, MUTED, bodyItalic);
            caption.setText(days == 0 ? context.getString(R.string.countdown_today, c.name)
                    : days == 1 ? context.getString(R.string.countdown_tomorrow, c.name)
                    : context.getString(R.string.countdown_until, c.name));
            line.addView(caption, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            countdowns.addView(line, margins(dp(4), 0));
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

        // The team's colours, a thin purple and gold stripe over the label.
        LinearLayout stripe = new LinearLayout(context);
        stripe.setOrientation(LinearLayout.HORIZONTAL);
        stripe.setGravity(Gravity.END);
        for (int colour : Team.COLOURS) {
            View bar = new View(context);
            bar.setBackgroundColor(colour);
            stripe.addView(bar, new LinearLayout.LayoutParams(dp(20), dp(3)));
        }
        game.addView(stripe, margins(0, dp(8)));

        TextView label = label(tonight ? context.getString(R.string.game_tonight, Team.TEAM_NAME)
                : context.getString(R.string.game_next, Team.TEAM_NAME));
        label.setGravity(Gravity.END);
        game.addView(label, margins(0, 0));

        TextView match = text(22, INK, heading);
        match.setGravity(Gravity.END);
        match.setMaxLines(2);
        android.text.SpannableStringBuilder line = new android.text.SpannableStringBuilder();
        line.append(g.home ? "vs " : "at ");
        line.setSpan(new TypefaceSpanCompat(bodyItalic), 0, line.length(), 0);
        line.setSpan(new android.text.style.ForegroundColorSpan(MUTED), 0, line.length(), 0);
        line.append(g.opponent);
        match.setText(line);
        game.addView(match, margins(dp(6), 0));

        String when = onNow ? context.getString(R.string.game_on_now)
                : tonight ? time
                : days == 1 ? context.getString(R.string.game_tomorrow, time)
                : start.format(DateTimeFormatter.ofPattern("EEEE", Locale.getDefault())) + " " + time;
        // "10:00 PM · Prime Video": the time in grey, where to watch in sand, on one line.
        TextView whenLine = text(15, MUTED, sans);
        whenLine.setGravity(Gravity.END);
        whenLine.setFontFeatureSettings("tnum");
        android.text.SpannableStringBuilder w = new android.text.SpannableStringBuilder(when + "  ·  ");
        int from = w.length();
        w.append(g.whereToWatch());
        w.setSpan(new android.text.style.ForegroundColorSpan(SAND), from, w.length(), 0);
        w.setSpan(new TypefaceSpanCompat(sansMedium), from, w.length(), 0);
        whenLine.setText(w);
        game.addView(whenLine, margins(dp(4), 0));
    }

    /**
     * "3:00 PM   Coffee with Sam": the time in grey with even digits, the event in Fraunces. A timeline: what has
     * passed fades, and the next event's time is in sand.
     */
    private View eventRow(Lists.Item item, int state) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setBaselineAligned(true);
        TextView time = text(17, state == UPCOMING ? MUTED : SAND, state == UPCOMING ? sans : sansMedium);
        time.setFontFeatureSettings("tnum");
        time.setText(state == NOW ? context.getString(R.string.event_now) : item.detail);
        row.addView(time, new LinearLayout.LayoutParams(dp(104), ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView title = text(22, INK, body);
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
            TextView none = text(34, MUTED, displayItalic);
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

        int[][] layouts = {{1, 30, 18}, {1, 26, 14}, {1, 22, 10}, {2, 22, 12}, {2, 19, 8}};   // columns, sp, gap dp
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
        TextView num = text(Math.round(sp * 0.7f), FAINT, bodyItalic);
        num.setFontFeatureSettings("tnum");
        num.setText(String.format(Locale.US, "%02d", number));
        row.addView(num, new LinearLayout.LayoutParams(dp(Math.round(sp * 1.6f)), ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView label = text(sp, INK, body);
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

    /** 23 as ٢٣: the Arabic-Indic digits. */
    static String arabicDigits(long n) {
        String western = String.valueOf(n);
        StringBuilder arabic = new StringBuilder(western.length());
        for (int i = 0; i < western.length(); i++) {
            char c = western.charAt(i);
            arabic.append(c >= '0' && c <= '9' ? (char) ('٠' + (c - '0')) : c);
        }
        return arabic.toString();
    }

    /** A tile of fine film grain: specks of light and dark at a few percent, repeated across the screen. */
    private static android.graphics.drawable.Drawable grain(Context context) {
        int size = 160;
        android.graphics.Bitmap tile = android.graphics.Bitmap.createBitmap(size, size,
                android.graphics.Bitmap.Config.ARGB_8888);
        java.util.Random random = new java.util.Random(7);
        int[] px = new int[size * size];
        for (int i = 0; i < px.length; i++) {
            int a = random.nextInt(14);
            px[i] = random.nextBoolean() ? (a << 24) | 0xFFFFFF : (a << 24);
        }
        tile.setPixels(px, 0, size, 0, 0, size, size);
        android.graphics.drawable.BitmapDrawable d =
                new android.graphics.drawable.BitmapDrawable(context.getResources(), tile);
        d.setTileModeXY(android.graphics.Shader.TileMode.REPEAT, android.graphics.Shader.TileMode.REPEAT);
        return d;
    }

    /** Fraunces from the app's assets at the given weight, optical size, softness and wonk; the system serif if not. */
    private static Typeface fraunces(Context context, boolean italic, int wght, int opsz, int soft, int wonk) {
        try {
            Typeface face = new Typeface.Builder(context.getAssets(),
                    italic ? "fonts/fraunces_italic.ttf" : "fonts/fraunces.ttf")
                    .setFontVariationSettings("'wght' " + wght + ", 'opsz' " + opsz + ", 'SOFT' " + soft
                            + ", 'WONK' " + wonk)
                    .build();
            if (face != null) return face;
        } catch (RuntimeException e) {
            Log.w("TvList", "fraunces: " + e);
        }
        return Typeface.create("serif", italic ? Typeface.ITALIC : Typeface.NORMAL);
    }

    /** A span that sets a whole Typeface; android.text.style.TypefaceSpan takes one only from Android 9. */
    private static final class TypefaceSpanCompat extends android.text.style.MetricAffectingSpan {
        private final Typeface face;

        TypefaceSpanCompat(Typeface face) {
            this.face = face;
        }

        @Override
        public void updateDrawState(android.text.TextPaint paint) {
            paint.setTypeface(face);
        }

        @Override
        public void updateMeasureState(android.text.TextPaint paint) {
            paint.setTypeface(face);
        }
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
