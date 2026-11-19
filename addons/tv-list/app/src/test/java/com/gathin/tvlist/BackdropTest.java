package com.gathin.tvlist;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

public class BackdropTest {
    @Test
    public void landscapePaintingsWithArtist() {
        String json = "{\"data\":["
                + "{\"id\":1,\"title\":\"Stacks of Wheat\",\"artist_title\":\"Claude Monet\",\"image_id\":\"abc\","
                + "\"thumbnail\":{\"width\":3000,\"height\":1800}},"
                + "{\"id\":2,\"title\":\"A Portrait\",\"artist_title\":\"Someone\",\"image_id\":\"def\","
                + "\"thumbnail\":{\"width\":1800,\"height\":3000}},"
                + "{\"id\":3,\"title\":\"No Image\",\"artist_title\":\"X\",\"image_id\":null,"
                + "\"thumbnail\":{\"width\":3000,\"height\":1800}}]}";
        List<Backdrop.Art> art = Backdrop.art(json);
        assertEquals(1, art.size());
        assertEquals("Claude Monet · Stacks of Wheat", art.get(0).caption);
        assertEquals("https://www.artic.edu/iiif/2/abc/full/1686,/0/default.jpg", Backdrop.artImageUrl("abc"));
    }

    @Test
    public void secondPageIsHalfwayRound() {
        for (int p = 1; p <= Backdrop.ART_PAGES; p++) {
            int q = Backdrop.secondPage(p);
            assertTrue(q >= 1 && q <= Backdrop.ART_PAGES && q != p);
        }
    }

    @Test
    public void artPageStaysInRange() {
        LocalDate d = LocalDate.of(2026, 1, 1);
        for (int i = 0; i < 400; i++) {
            int page = Backdrop.artPage(d.plusDays(i));
            assertTrue("page " + page, page >= 1 && page <= Backdrop.ART_PAGES);
        }
    }

    @Test
    public void nextUnseenSkipsWhatWasShown() {
        List<Backdrop.Picture> pics = Arrays.asList(
                new Backdrop.Picture(new java.io.File("potd-1.jpg"), ""),
                new Backdrop.Picture(new java.io.File("art-1.jpg"), ""),
                new Backdrop.Picture(new java.io.File("potd-2.jpg"), ""));
        java.util.Set<String> seen = new java.util.HashSet<>(Arrays.asList("potd-1.jpg", "art-1.jpg"));
        assertEquals(2, BackdropView.nextUnseen(pics, seen));
        seen.add("potd-2.jpg");
        assertEquals(-1, BackdropView.nextUnseen(pics, seen));
    }
}