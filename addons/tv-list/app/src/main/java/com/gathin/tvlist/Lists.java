package com.gathin.tvlist;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** The two tabs turned into what the screen shows. */
final class Lists {
    static final int MAX_TODOS = 12;
    static final int MAX_EVENTS = 8;
    private static final int MAX_TEXT = 120;

    private Lists() {}

    /** One line on the screen: a to-do (detail empty) or an event (detail = its time). */
    static final class Item {
        final String text;
        final String detail;
        final boolean done;

        Item(String text, String detail, boolean done) {
            this.text = text;
            this.detail = detail;
            this.done = done;
        }
    }

    /** The To-do tab: open tasks first in the sheet's order, then the done ones, at most {@link #MAX_TODOS}. */
    static List<Item> todos(List<List<String>> rows) {
        List<Item> open = new ArrayList<>();
        List<Item> done = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            List<String> row = rows.get(i);
            String task = cell(row, 0);
            if (task.isEmpty() || (i == 0 && task.equalsIgnoreCase("task"))) continue;
            boolean isDone = isDone(cell(row, 1));
            (isDone ? done : open).add(new Item(task, "", isDone));
        }
        List<Item> all = new ArrayList<>(open);
        all.addAll(done);
        return all.size() > MAX_TODOS ? new ArrayList<>(all.subList(0, MAX_TODOS)) : all;
    }

    /** The Today tab: time and event, in the sheet's order, at most {@link #MAX_EVENTS}. */
    static List<Item> events(List<List<String>> rows) {
        List<Item> events = new ArrayList<>();
        for (int i = 0; i < rows.size() && events.size() < MAX_EVENTS; i++) {
            List<String> row = rows.get(i);
            String time = cell(row, 0);
            String event = cell(row, 1);
            if (i == 0 && time.equalsIgnoreCase("time")) continue;
            if (event.isEmpty()) {
                if (time.isEmpty()) continue;
                event = time;
                time = "";
            }
            events.add(new Item(event, time, false));
        }
        return events;
    }

    /** A Done cell that counts as done: a ticked checkbox (TRUE) or yes, x, done, 1, ✓. */
    static boolean isDone(String value) {
        String v = value.trim().toLowerCase(Locale.ROOT);
        return v.equals("true") || v.equals("yes") || v.equals("y") || v.equals("x") || v.equals("done")
                || v.equals("1") || v.equals("✓") || v.equals("✔");
    }

    /** A cell, trimmed, on one line and not too long; "" when the row is shorter. */
    private static String cell(List<String> row, int index) {
        if (index >= row.size()) return "";
        String v = row.get(index).replaceAll("\\s+", " ").trim();
        return v.length() > MAX_TEXT ? v.substring(0, MAX_TEXT - 1).trim() + "…" : v;
    }
}
