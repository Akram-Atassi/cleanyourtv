package com.gathin.tvlist;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.time.Instant;

import org.junit.Test;

public class TeamTest {
    /** The shape of ESPN's schedule feed, cut down to what Team reads. */
    private static String event(String date, boolean completed, String lakersSide, String opponent, String casts) {
        String other = lakersSide.equals("home") ? "away" : "home";
        return "{\"date\":\"" + date + "\",\"competitions\":[{\"status\":{\"type\":{\"completed\":" + completed + "}},"
                + "\"competitors\":[{\"homeAway\":\"" + lakersSide + "\",\"team\":{\"abbreviation\":\"LAL\","
                + "\"displayName\":\"Los Angeles Lakers\"}},{\"homeAway\":\"" + other + "\",\"team\":{"
                + "\"abbreviation\":\"X\",\"displayName\":\"" + opponent + "\"}}],\"broadcasts\":[" + casts + "]}]}";
    }

    private static String cast(String name, String market) {
        return "{\"type\":{\"shortName\":\"TV\"},\"market\":{\"type\":\"" + market + "\"},\"media\":{\"shortName\":\""
                + name + "\"}}";
    }

    private static final String SCHEDULE = "{\"events\":["
            + event("2026-11-17T03:00Z", true, "home", "Old Game", "") + ","
            + event("2026-11-19T03:00Z", false, "away", "Utah Jazz",
                    cast("Spectrum Sports Net", "Home") + "," + cast("KJZZ-TV", "Away")) + ","
            + event("2026-11-28T02:30Z", false, "away", "San Antonio Spurs", cast("Prime Video", "National")) + ","
            + event("2026-12-02T01:00Z", false, "home", "Boston Celtics",
                    cast("NBC", "National") + "," + cast("Spectrum Sports Net", "Home"))
            + "]}";

    @Test
    public void nextGameSkipsFinishedOnes() {
        Team.Game g = Team.next(SCHEDULE, Instant.parse("2026-11-18T20:00:00Z"));
        assertEquals("Utah Jazz", g.opponent);
        assertFalse(g.home);
        assertEquals(Instant.parse("2026-11-19T03:00:00Z"), g.start);
    }

    @Test
    public void regionalOnlyMeansLeaguePass() {
        Team.Game g = Team.next(SCHEDULE, Instant.parse("2026-11-18T20:00:00Z"));
        assertTrue(g.national.isEmpty());
        assertEquals("NBA League Pass", g.whereToWatch());
    }

    @Test
    public void nationalChannelWins() {
        Team.Game g = Team.next(SCHEDULE, Instant.parse("2026-11-20T00:00:00Z"));
        assertEquals("San Antonio Spurs", g.opponent);
        assertEquals("Prime Video", g.whereToWatch());
        Team.Game c = Team.next(SCHEDULE, Instant.parse("2026-11-29T00:00:00Z"));
        assertTrue(c.home);
        assertEquals("Peacock", c.whereToWatch());
    }

    @Test
    public void myServiceBeforeOtherNationalChannels() {
        String json = "{\"events\":[" + event("2026-12-05T01:00Z", false, "home", "Phoenix Suns",
                cast("NBA TV", "National") + "," + cast("Prime Video", "National")) + "]}";
        assertEquals("Prime Video", Team.next(json, Instant.parse("2026-12-01T00:00:00Z")).whereToWatch());
        String espn = "{\"events\":[" + event("2026-12-06T01:00Z", false, "home", "Denver Nuggets",
                cast("ESPN", "National")) + "]}";
        // Not one of the owner's services: the channel itself, not League Pass (blacked out for national games).
        assertEquals("ESPN", Team.next(espn, Instant.parse("2026-12-01T00:00:00Z")).whereToWatch());
    }

    @Test
    public void aGameOnNowStillCounts() {
        // Tip-off was an hour ago: still tonight's game.
        assertEquals("Utah Jazz", Team.next(SCHEDULE, Instant.parse("2026-11-19T04:00:00Z")).opponent);
    }

    @Test
    public void badInputIsNull() {
        assertNull(Team.next((String) null, Instant.now()));
        assertNull(Team.next("not json", Instant.now()));
        assertNull(Team.next("{\"events\":[]}", Instant.now()));
    }
}
