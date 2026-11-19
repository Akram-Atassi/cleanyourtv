package com.gathin.tvlist;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class ClockTest {
    @Test
    public void arabicTwelveHourClock() {
        assertEquals("٩:٠٥ م", Scene.arabicTime(21, 5));
        assertEquals("١١:١٠ ص", Scene.arabicTime(11, 10));
        assertEquals("١٢:٠٠ ص", Scene.arabicTime(0, 0));
        assertEquals("١٢:٣٠ م", Scene.arabicTime(12, 30));
    }

    @Test
    public void arabicDigits() {
        assertEquals("٢٣", Scene.arabicDigits(23));
        assertEquals("٠", Scene.arabicDigits(0));
    }
}
