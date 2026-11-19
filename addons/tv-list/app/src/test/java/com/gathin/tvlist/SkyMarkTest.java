package com.gathin.tvlist;

import static org.junit.Assert.assertTrue;

import java.time.Instant;

import org.junit.Test;

public class SkyMarkTest {
    private static double phaseAt(String instant) {
        return SkyMark.phase(Instant.parse(instant).toEpochMilli());
    }

    @Test
    public void newMoonAtTheEclipse() {
        // The total solar eclipse of 8 April 2024 happened at a new moon (18:21 UTC).
        double p = phaseAt("2024-04-08T18:21:00Z");
        assertTrue("phase " + p, p < 0.03 || p > 0.97);
    }

    @Test
    public void fullMoonHalfAMonthLater() {
        // Half a synodic month after that new moon the moon is full.
        double p = phaseAt("2024-04-23T23:49:00Z");
        assertTrue("phase " + p, Math.abs(p - 0.5) < 0.03);
    }

    @Test
    public void alwaysBetweenZeroAndOne() {
        for (String t : new String[]{"1990-01-01T00:00:00Z", "2000-01-06T18:14:00Z", "2026-11-18T12:00:00Z"}) {
            double p = phaseAt(t);
            assertTrue(t + " " + p, p >= 0 && p < 1);
        }
    }
}
