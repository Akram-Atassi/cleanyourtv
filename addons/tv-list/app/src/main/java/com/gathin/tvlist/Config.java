package com.gathin.tvlist;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The one setting: which Google Sheet to read, kept as its id in sheet.txt in the app's files dir. setup.sh writes
 * it through FilesProvider. The sheet has two tabs, "To-do" (Task, Done) and "Today" (Time, Event), read as CSV
 * from the sheet's public link, so the sheet must be shared as "Anyone with the link: Viewer".
 */
final class Config {
    static final String SHEET = "sheet.txt";
    static final String TODO_TAB = "To-do";
    static final String TODAY_TAB = "Today";
    static final String COUNTDOWN_TAB = "Countdowns";

    private static final Pattern ID = Pattern.compile("[A-Za-z0-9_-]{20,128}");
    private static final Pattern IN_LINK = Pattern.compile("/spreadsheets/d/([A-Za-z0-9_-]+)");

    private Config() {}

    /** The file a provider path names: only sheet.txt. */
    static String path(List<String> segments) {
        if (segments == null || segments.size() != 1) return null;
        return SHEET.equals(segments.get(0)) ? SHEET : null;
    }

    /**
     * The sheet id from what was pasted: the id itself or any docs.google.com/spreadsheets/d/<id>/... link.
     * Null when there is none.
     */
    static String sheetId(String text) {
        if (text == null) return null;
        String t = text.trim();
        Matcher m = IN_LINK.matcher(t);
        String id = m.find() ? m.group(1) : t;
        return ID.matcher(id).matches() ? id : null;
    }

    /** The tab as CSV, through Google's visualization endpoint, which serves link-shared sheets without a sign-in. */
    static String csvUrl(String id, String tab) {
        try {
            return "https://docs.google.com/spreadsheets/d/" + id + "/gviz/tq?tqx=out:csv&headers=1&sheet="
                    + URLEncoder.encode(tab, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    static String sheetId(File dir) {
        return sheetId(read(new File(dir, SHEET)));
    }

    /** A small text file, or null when it is missing or unreadable. */
    static String read(File file) {
        if (!file.isFile() || file.length() > 512 * 1024) return null;
        try {
            return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }

    /** Written beside, then renamed over, so a reader never sees half a file. */
    static void write(File file, String text) {
        File tmp = new File(file.getParentFile(), file.getName() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            tmp.delete();
            return;
        }
        if (!tmp.renameTo(file)) tmp.delete();
    }
}
