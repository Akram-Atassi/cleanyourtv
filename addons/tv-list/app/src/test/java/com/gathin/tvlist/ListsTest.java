package com.gathin.tvlist;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

public class ListsTest {
    @Test
    public void openTasksFirstThenDone() {
        List<Lists.Item> items = Lists.todos(Csv.parse("Task,Done\nPaid rent,TRUE\nBuy milk,FALSE\n,\nCall dentist,\n"));
        assertEquals(3, items.size());
        assertEquals("Buy milk", items.get(0).text);
        assertEquals("Call dentist", items.get(1).text);
        assertEquals("Paid rent", items.get(2).text);
        assertTrue(items.get(2).done);
    }

    @Test
    public void headerIsOptional() {
        assertEquals("Buy milk", Lists.todos(Csv.parse("Buy milk,FALSE\n")).get(0).text);
    }

    @Test
    public void atMostMaxTasks() {
        StringBuilder csv = new StringBuilder("Task,Done\n");
        for (int i = 0; i < Lists.MAX_TODOS + 10; i++) csv.append("Task ").append(i).append(",FALSE\n");
        assertEquals(Lists.MAX_TODOS, Lists.todos(Csv.parse(csv.toString())).size());
    }

    @Test
    public void doneValues() {
        for (String v : new String[]{"TRUE", "true", "Yes", "x", "done", "1", "✓"}) assertTrue(v, Lists.isDone(v));
        for (String v : new String[]{"FALSE", "", "no", "0", "later"}) assertFalse(v, Lists.isDone(v));
    }

    @Test
    public void eventsWithAndWithoutTimes() {
        List<Lists.Item> events = Lists.events(Csv.parse("Time,Event\n9:00 AM,Standup\nAll day,Mum's birthday\n,\nLunch,\n"));
        assertEquals(3, events.size());
        assertEquals("Standup", events.get(0).text);
        assertEquals("9:00 AM", events.get(0).detail);
        assertEquals("Lunch", events.get(2).text);
        assertEquals("", events.get(2).detail);
    }

    @Test
    public void longTextIsCutAndFlattened() {
        StringBuilder longTask = new StringBuilder();
        for (int i = 0; i < 200; i++) longTask.append('a');
        Lists.Item item = Lists.todos(Csv.parse("\"two\nlines\",\n" + longTask + ",\n")).get(1);
        assertEquals("two lines", Lists.todos(Csv.parse("\"two\nlines\",\n")).get(0).text);
        assertTrue(item.text.length() <= 120);
    }

    @Test
    public void eventTimes() {
        assertEquals(java.time.LocalTime.of(15, 0), Lists.time("3:00 PM"));
        assertEquals(java.time.LocalTime.of(23, 59), Lists.time("11:59 pm"));
        assertEquals(java.time.LocalTime.of(9, 5), Lists.time("09:05"));
        assertEquals(null, Lists.time("All day"));
        assertEquals(null, Lists.time(""));
    }
}