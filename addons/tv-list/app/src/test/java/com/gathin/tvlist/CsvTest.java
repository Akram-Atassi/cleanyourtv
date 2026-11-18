package com.gathin.tvlist;

import static org.junit.Assert.assertEquals;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

public class CsvTest {
    @Test
    public void plainRows() {
        assertEquals(Arrays.asList(Arrays.asList("Task", "Done"), Arrays.asList("Milk", "FALSE")),
                Csv.parse("Task,Done\nMilk,FALSE\n"));
    }

    @Test
    public void quotedFieldsKeepCommasQuotesAndNewlines() {
        List<List<String>> rows = Csv.parse("\"Call mum, then dad\",\"TRUE\"\r\n\"Say \"\"hi\"\"\nsoon\",\"\"\r\n");
        assertEquals(Arrays.asList("Call mum, then dad", "TRUE"), rows.get(0));
        assertEquals(Arrays.asList("Say \"hi\"\nsoon", ""), rows.get(1));
        assertEquals(2, rows.size());
    }

    @Test
    public void lastRowWithoutNewlineAndByteOrderMark() {
        assertEquals(Collections.singletonList(Arrays.asList("a", "b")), Csv.parse("﻿a,b"));
    }

    @Test
    public void emptyAndNull() {
        assertEquals(0, Csv.parse("").size());
        assertEquals(0, Csv.parse(null).size());
        assertEquals(0, Csv.parse("\n\n").size());
    }
}
