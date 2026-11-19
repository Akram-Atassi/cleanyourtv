package com.gathin.tvlist;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.time.LocalDate;
import java.util.List;

import org.junit.Test;

public class HistoryTest {
    private static String event(int year, String text) {
        return "{\"year\":" + year + ",\"text\":\"" + text + "\"}";
    }

    private static final String FEED = "{\"selected\":["
            + event(2011, "The sandbox video game Minecraft exited beta with version 1.0.") + ","
            + event(1999, "A bonfire collapsed (aftermath pictured), killing 12 people.") + ","
            + event(1991, "The current flag of Uzbekistan (pictured) was adopted.") + ","
            + event(2014, "Two men attacked a synagogue.") + ","
            + event(1863, "Abraham Lincoln delivered the Gettysburg Address.")
            + "]}";

    @Test
    public void grimEventsAreLeftOutAndTheRestSortedByYear() {
        List<History.Event> events = History.parse(FEED);
        assertEquals(3, events.size());
        assertEquals(1863, events.get(0).year);
        assertEquals(1991, events.get(1).year);
        assertEquals(2011, events.get(2).year);
    }

    @Test
    public void picturedNotesAreCut() {
        assertEquals("The current flag of Uzbekistan was adopted.",
                History.parse(FEED).get(1).text);
        assertEquals("A bonfire collapsed, with a picture.",
                History.clean("A bonfire collapsed (left, pictured in 1999), with a picture."));
    }

    @Test
    public void longEventsAreLeftOutNotCut() {
        StringBuilder t = new StringBuilder();
        for (int i = 0; i < History.MAX_TEXT + 1; i++) t.append('a');
        List<History.Event> events = History.parse("{\"selected\":[" + event(1900, t.toString()) + ","
                + event(1901, "Short and sweet.") + "]}");
        assertEquals(1, events.size());
        assertEquals("Short and sweet.", events.get(0).text);
        assertTrue(History.clean(t.toString()).length() > History.MAX_TEXT);   // clean() never shortens
    }

    @Test
    public void urlAndCacheNameUseTheDate() {
        LocalDate day = LocalDate.of(2026, 11, 8);
        assertEquals("https://en.wikipedia.org/api/rest_v1/feed/onthisday/selected/11/08", History.url(day));
        assertEquals("history-1108.json", History.cacheName(day));
    }

    @Test
    public void badInputIsEmpty() {
        assertTrue(History.parse(null).isEmpty());
        assertTrue(History.parse("nope").isEmpty());
    }
}
