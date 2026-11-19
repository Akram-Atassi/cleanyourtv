package com.gathin.tvlist;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.time.LocalDate;
import java.util.List;

import org.junit.Test;

public class CountdownTest {
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 18);

    @Test
    public void soonestFirstPastLeftOut() {
        List<Lists.Countdown> list = Lists.countdowns(Csv.parse(
                "Countdown,Date\nFinals start,2026-12-11\nMidterm 2,2026-11-12\nChess,11/25/2026\nToday thing,2026-11-18\n"),
                TODAY);
        assertEquals(3, list.size());
        assertEquals("Today thing", list.get(0).name);
        assertEquals(0, list.get(0).daysFrom(TODAY));
        assertEquals("Chess", list.get(1).name);
        assertEquals(7, list.get(1).daysFrom(TODAY));
        assertEquals("Finals start", list.get(2).name);
        assertEquals(23, list.get(2).daysFrom(TODAY));
    }

    @Test
    public void atMostThree() {
        StringBuilder csv = new StringBuilder();
        for (int i = 1; i <= 6; i++) csv.append("Thing ").append(i).append(",2026-12-0").append(i).append('\n');
        assertEquals(Lists.MAX_COUNTDOWNS, Lists.countdowns(Csv.parse(csv.toString()), TODAY).size());
    }

    @Test
    public void unreadableDatesAreSkipped() {
        assertNull(Lists.date("next week"));
        assertNull(Lists.date("2026-13-40"));
        assertNull(Lists.date(""));
        assertEquals(0, Lists.countdowns(Csv.parse("Party,soon\n,2026-12-01\n"), TODAY).size());
    }
}
