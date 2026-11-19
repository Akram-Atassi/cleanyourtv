package com.gathin.tvlist;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * "On this day": Wikipedia's selected anniversaries for today's date, from its public feed (no key, no account),
 * fetched once a day. Events about killings, attacks or disasters are left out, since this is something to glance at
 * every morning, and a note pointing at a picture that is not on the TV ("(aftermath pictured)") is cut.
 */
final class History {
    static final class Event {
        final int year;
        final String text;

        Event(int year, String text) {
            this.year = year;
            this.text = text;
        }
    }

    private static final Pattern PICTURED = Pattern.compile("\\s*\\([^()]*\\bpictured\\b[^()]*\\)", Pattern.CASE_INSENSITIVE);
    private static final Pattern GRIM = Pattern.compile(
            "\\b(kill\\w*|killing|death|deaths|dead|died|attack\\w*|massacre\\w*|murder\\w*|assassinat\\w*|bomb\\w*|"
                    + "shoot\\w*|shot|terror\\w*|genocide|execut\\w*|disaster|crash\\w*|collaps\\w*|earthquake|"
                    + "tsunami|famine|war crimes?|hostage\\w*|suicide)\\b",
            Pattern.CASE_INSENSITIVE);
    /** Longer events are left out rather than cut short; the screen also drops any that do not fit its space. */
    static final int MAX_TEXT = 170;

    private History() {}

    static String url(LocalDate day) {
        return String.format(Locale.US, "https://en.wikipedia.org/api/rest_v1/feed/onthisday/selected/%02d/%02d",
                day.getMonthValue(), day.getDayOfMonth());
    }

    static String cacheName(LocalDate day) {
        return String.format(Locale.US, "history-%02d%02d.json", day.getMonthValue(), day.getDayOfMonth());
    }

    /** Fetches today's events unless they are already cached; drops other days' caches. Blocking. */
    static void refresh(File dir, LocalDate day) throws IOException {
        File cache = new File(dir, cacheName(day));
        if (cache.isFile() && cache.length() > 0) return;
        Config.write(cache, Sheet.fetch(url(day), 2 * 1024 * 1024));
        File[] old = dir.listFiles((d, name) -> name.startsWith("history-") && !name.equals(cache.getName()));
        if (old != null) for (File f : old) f.delete();
    }

    static List<Event> load(File dir, LocalDate day) {
        return parse(Config.read(new File(dir, cacheName(day)), 2L * 1024 * 1024));
    }

    /** The pleasant events from the feed, oldest first. */
    static List<Event> parse(String json) {
        List<Event> events = new ArrayList<>();
        if (json == null) return events;
        try {
            JSONArray selected = new JSONObject(json).optJSONArray("selected");
            for (int i = 0; selected != null && i < selected.length(); i++) {
                JSONObject e = selected.getJSONObject(i);
                String text = clean(e.optString("text"));
                if (!e.has("year") || text.isEmpty() || text.length() > MAX_TEXT || GRIM.matcher(text).find()) continue;
                events.add(new Event(e.getInt("year"), text));
            }
        } catch (JSONException e) {
            return events;
        }
        events.sort((a, b) -> Integer.compare(a.year, b.year));
        return events;
    }

    /** Without "(… pictured)" notes, on one line. Never shortened: too long an event is left out instead. */
    static String clean(String text) {
        return PICTURED.matcher(text).replaceAll("").replaceAll("\\s+", " ").trim();
    }
}
