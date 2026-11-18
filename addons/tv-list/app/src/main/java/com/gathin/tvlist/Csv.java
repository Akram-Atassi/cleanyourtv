package com.gathin.tvlist;

import java.util.ArrayList;
import java.util.List;

/** Comma-separated values as Google writes them: quoted fields may hold commas, newlines and doubled quotes. */
final class Csv {
    private Csv() {}

    static List<List<String>> parse(String text) {
        List<List<String>> rows = new ArrayList<>();
        if (text == null || text.isEmpty()) return rows;
        List<String> row = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        boolean any = false;   // something on this row yet, so a trailing newline adds no empty row
        int start = text.charAt(0) == '﻿' ? 1 : 0;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '"') {
                        field.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    field.append(c);
                }
            } else if (c == '"') {
                quoted = true;
                any = true;
            } else if (c == ',') {
                row.add(field.toString());
                field.setLength(0);
                any = true;
            } else if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') i++;
                if (any || field.length() > 0) {
                    row.add(field.toString());
                    rows.add(row);
                }
                row = new ArrayList<>();
                field.setLength(0);
                any = false;
            } else {
                field.append(c);
                any = true;
            }
        }
        if (any || field.length() > 0) {
            row.add(field.toString());
            rows.add(row);
        }
        return rows;
    }
}
