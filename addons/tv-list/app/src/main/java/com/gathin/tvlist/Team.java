package com.gathin.tvlist;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/**
 * One NBA team's next game, from ESPN's public schedule feed (no key, no account): who they play, home or away, when,
 * and where to watch from outside the team's own area. Regional channels (the team's home sports network) are left
 * out because they are blacked out elsewhere; a game with no national broadcaster is on NBA League Pass.
 */
final class Team {
    static final String TEAM = "lal";
    static final String TEAM_NAME = "Lakers";
    static final String CACHE = "team.json";
    static final long REFRESH_MS = 30 * 60_000L;
    /** A game that started up to this long ago still counts as tonight's (it is on now). */
    static final long IN_PROGRESS_MS = 3 * 3_600_000L;

    static final class Game {
        final String opponent;
        final boolean home;
        final Instant start;
        final List<String> national;

        Game(String opponent, boolean home, Instant start, List<String> national) {
            this.opponent = opponent;
            this.home = home;
            this.start = start;
            this.national = national;
        }

        /**
         * Where to watch, best first: one of {@link #MY_SERVICES} when a national broadcaster streams there, else the
         * national channel itself, else NBA League Pass (which has every out-of-market game without a national one).
         */
        String whereToWatch() {
            for (String channel : national) {
                String service = service(channel);
                if (service != null) return service;
            }
            if (!national.isEmpty()) return String.join(" / ", national);
            return "NBA League Pass";
        }
    }

    private Team() {}

    static String url() {
        return "https://site.api.espn.com/apis/site/v2/sports/basketball/nba/teams/" + TEAM + "/schedule";
    }

    /** Fetches the schedule into the cache when it is older than {@link #REFRESH_MS}; blocking. */
    static void refresh(File dir, long now) throws IOException {
        File cache = new File(dir, CACHE);
        if (cache.isFile() && now - cache.lastModified() < REFRESH_MS && now >= cache.lastModified()) return;
        // The season schedule is about a megabyte.
        Config.write(cache, Sheet.fetch(url(), 4 * 1024 * 1024));
    }

    /** The next game from the cached schedule, or null. */
    static Game next(File dir, Instant now) {
        return next(Config.read(new File(dir, CACHE), 4L * 1024 * 1024), now);
    }

    /** The first game not finished and not started more than {@link #IN_PROGRESS_MS} ago; null when none. */
    static Game next(String json, Instant now) {
        if (json == null) return null;
        try {
            JSONArray events = new JSONObject(json).optJSONArray("events");
            if (events == null) return null;
            Game best = null;
            for (int i = 0; i < events.length(); i++) {
                Game g = game(events.getJSONObject(i));
                if (g == null || g.start.toEpochMilli() < now.toEpochMilli() - IN_PROGRESS_MS) continue;
                if (best == null || g.start.isBefore(best.start)) best = g;
            }
            return best;
        } catch (JSONException e) {
            return null;
        }
    }

    private static Game game(JSONObject event) throws JSONException {
        Instant start;
        try {
            start = OffsetDateTime.parse(event.optString("date")).toInstant();
        } catch (DateTimeParseException e) {
            return null;
        }
        JSONArray comps = event.optJSONArray("competitions");
        if (comps == null || comps.length() == 0) return null;
        JSONObject comp = comps.getJSONObject(0);
        JSONObject status = comp.optJSONObject("status");
        if (status != null && status.optJSONObject("type") != null
                && status.getJSONObject("type").optBoolean("completed")) return null;
        String opponent = null;
        boolean home = false;
        JSONArray teams = comp.optJSONArray("competitors");
        if (teams == null) return null;
        for (int i = 0; i < teams.length(); i++) {
            JSONObject c = teams.getJSONObject(i);
            JSONObject team = c.optJSONObject("team");
            if (team == null) continue;
            if (TEAM.equalsIgnoreCase(team.optString("abbreviation"))) {
                home = "home".equals(c.optString("homeAway"));
            } else {
                opponent = team.optString("displayName", team.optString("shortDisplayName"));
            }
        }
        if (opponent == null || opponent.isEmpty()) return null;
        List<String> national = new ArrayList<>();
        JSONArray casts = comp.optJSONArray("broadcasts");
        for (int i = 0; casts != null && i < casts.length(); i++) {
            JSONObject b = casts.getJSONObject(i);
            JSONObject market = b.optJSONObject("market");
            JSONObject media = b.optJSONObject("media");
            if (market == null || media == null || !"National".equalsIgnoreCase(market.optString("type"))) continue;
            String name = media.optString("shortName").trim();
            if (!name.isEmpty() && !national.contains(name)) national.add(name);
        }
        return new Game(opponent, home, start, national);
    }

    /** The streaming services this TV's owner pays for, in the order to suggest them. */
    static final List<String> MY_SERVICES = java.util.Arrays.asList(
            "Peacock", "Prime Video", "Netflix", "Disney+", "Hulu", "HBO Max");

    /**
     * The service in {@link #MY_SERVICES} that streams a national broadcaster's games, or null. NBC's NBA games
     * stream on Peacock; Prime Video is its own. ESPN and ABC games need ESPN's own subscription (in Disney+ only
     * with the ESPN tier), so they are not claimed for Disney+.
     */
    static String service(String channel) {
        String service;
        switch (channel) {
            case "NBC":
            case "Peacock":
                service = "Peacock";
                break;
            case "Prime Video":
            case "Amazon Prime Video":
                service = "Prime Video";
                break;
            default:
                service = channel;
        }
        return MY_SERVICES.contains(service) ? service : null;
    }
}
