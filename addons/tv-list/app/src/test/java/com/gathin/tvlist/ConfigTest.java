package com.gathin.tvlist;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.util.Arrays;
import java.util.Collections;

import org.junit.Test;

public class ConfigTest {
    private static final String ID = "1Uuz9OlQhFvKex1cvhoNeEYeNLhet6QWonC9NcDHbVEQ";

    @Test
    public void idFromLinkOrBareId() {
        assertEquals(ID, Config.sheetId("https://docs.google.com/spreadsheets/d/" + ID + "/edit?usp=sharing"));
        assertEquals(ID, Config.sheetId("  " + ID + "\n"));
        assertNull(Config.sheetId("https://example.com/not-a-sheet"));
        assertNull(Config.sheetId("short"));
        assertNull(Config.sheetId(ID + "; rm -rf /"));
        assertNull(Config.sheetId((String) null));
    }

    @Test
    public void csvUrlNamesTheTab() {
        assertEquals("https://docs.google.com/spreadsheets/d/" + ID + "/gviz/tq?tqx=out:csv&headers=1&sheet=To-do",
                Config.csvUrl(ID, Config.TODO_TAB));
        assertEquals("https://docs.google.com/spreadsheets/d/" + ID + "/gviz/tq?tqx=out:csv&headers=1&sheet=My+list",
                Config.csvUrl(ID, "My list"));
    }

    @Test
    public void providerOnlyServesSheetTxt() {
        assertEquals("sheet.txt", Config.path(Collections.singletonList("sheet.txt")));
        assertNull(Config.path(Collections.singletonList("todo.csv")));
        assertNull(Config.path(Arrays.asList("..", "sheet.txt")));
        assertNull(Config.path(null));
    }
}
