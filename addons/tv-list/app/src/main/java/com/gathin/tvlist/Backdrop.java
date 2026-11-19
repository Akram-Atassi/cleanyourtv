package com.gathin.tvlist;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The pictures behind the list: paintings from the Art Institute of Chicago's public-domain collection, from its free
 * public API (no key, no account). Only paintings wide enough for a TV are kept. Each week two new pages of the
 * collection are read and a few paintings are downloaded per refresh, so a refresh stays short; the folder
 * ("backdrop/", a JPEG and a caption beside each) is pruned to {@link #MAX_ART}, oldest first.
 */
final class Backdrop {
    static final int MAX_ART = 120;
    /** Per refresh, so the first fill takes a few minutes and later refreshes almost nothing. */
    static final int DOWNLOADS_PER_REFRESH = 5;
    static final long ART_LIST_MS = 7L * 24 * 3_600_000L;
    /** The search only pages through its first 1,000 results (10 pages of 100); about 400 are wide enough. */
    static final int ART_PAGES = 10;
    private static final int MAX_IMAGE_BYTES = 6 * 1024 * 1024;

    static final class Picture {
        final File file;
        final String caption;

        Picture(File file, String caption) {
            this.file = file;
            this.caption = caption;
        }
    }

    private Backdrop() {}

    static File folder(File dir) {
        File f = new File(dir, "backdrop");
        if (!f.isDirectory()) f.mkdirs();
        return f;
    }

    /** A page of the Art Institute's public-domain paintings. */
    static String artListUrl(int page) {
        return "https://api.artic.edu/api/v1/artworks/search?query[bool][must][][term][is_public_domain]=true"
                + "&query[bool][must][][term][artwork_type_title.keyword]=Painting"
                + "&fields=id,title,artist_title,image_id,thumbnail&limit=100&page=" + page;
    }

    static String artImageUrl(String imageId) {
        return "https://www.artic.edu/iiif/2/" + imageId + "/full/1686,/0/default.jpg";
    }

    /** This week's first page: the week number spread over the pages, so ten weeks walk through all of them. */
    static int artPage(LocalDate today) {
        long week = today.toEpochDay() / 7;
        return (int) ((week * 7919L) % ART_PAGES) + 1;
    }

    /** The week's second page, half the collection away from the first. */
    static int secondPage(int first) {
        return (first - 1 + ART_PAGES / 2) % ART_PAGES + 1;
    }

    /** Fetches what is missing, a few paintings at most; blocking. Failures leave what is already there. */
    static void refresh(File dir, LocalDate today) {
        File folder = folder(dir);
        int budget = DOWNLOADS_PER_REFRESH;
        int first = artPage(today);
        for (int page : new int[]{first, secondPage(first)}) {
            File list = new File(folder, "art-page-" + page + ".json");
            long now = System.currentTimeMillis();
            try {
                if (!list.isFile() || now - list.lastModified() > ART_LIST_MS || now < list.lastModified()) {
                    Config.write(list, Sheet.fetch(artListUrl(page), 2 * 1024 * 1024));
                }
                for (Art a : art(Config.read(list, 2L * 1024 * 1024))) {
                    if (budget <= 0) break;
                    File jpg = new File(folder, "art-" + a.id + ".jpg");
                    if (jpg.isFile()) continue;
                    budget--;
                    download(artImageUrl(a.imageId), jpg);
                    Config.write(new File(folder, "art-" + a.id + ".txt"), a.caption);
                }
            } catch (IOException | RuntimeException e) {
                android.util.Log.w("TvList", "paintings page " + page + ": " + e);
            }
        }
        prune(folder, first);
    }

    /**
     * Keeps at most {@link #MAX_ART} paintings, dropping the oldest downloads; also clears out the pictures of the
     * day an earlier version kept, and page lists other than this week's two.
     */
    private static void prune(File folder, int first) {
        File[] files = folder.listFiles();
        if (files == null) return;
        String keepA = "art-page-" + first + ".json";
        String keepB = "art-page-" + secondPage(first) + ".json";
        for (File f : files) {
            String n = f.getName();
            if (n.startsWith("potd-") || n.equals("art.json")
                    || (n.startsWith("art-page-") && !n.equals(keepA) && !n.equals(keepB))) {
                f.delete();
            }
        }
        File[] art = folder.listFiles((d, n) -> n.startsWith("art-") && n.endsWith(".jpg"));
        if (art != null && art.length > MAX_ART) {
            Arrays.sort(art, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
            for (int i = 0; i < art.length - MAX_ART; i++) {
                art[i].delete();
                new File(folder, art[i].getName().replace(".jpg", ".txt")).delete();
            }
        }
    }

    /** The paintings ready to show, in a fixed order; BackdropView keeps track of which were shown. */
    static List<Picture> pictures(File dir) {
        File folder = folder(dir);
        List<Picture> out = new ArrayList<>();
        File[] files = folder.listFiles((d, n) -> n.startsWith("art-") && n.endsWith(".jpg"));
        if (files == null) return out;
        Arrays.sort(files, (a, b) -> a.getName().compareTo(b.getName()));
        for (File f : files) {
            String caption = Config.read(new File(folder, f.getName().replace(".jpg", ".txt")));
            out.add(new Picture(f, caption == null ? "" : caption.trim()));
        }
        return out;
    }

    static final class Art {
        final int id;
        final String imageId;
        final String caption;

        Art(int id, String imageId, String caption) {
            this.id = id;
            this.imageId = imageId;
            this.caption = caption;
        }
    }

    /** The landscape paintings in a page of the Art Institute's list, as "Claude Monet · Stacks of Wheat". */
    static List<Art> art(String json) {
        List<Art> out = new ArrayList<>();
        if (json == null) return out;
        try {
            JSONArray data = new JSONObject(json).optJSONArray("data");
            for (int i = 0; data != null && i < data.length(); i++) {
                JSONObject a = data.getJSONObject(i);
                JSONObject thumb = a.optJSONObject("thumbnail");
                String imageId = a.optString("image_id", "");
                if (thumb == null || imageId.isEmpty() || "null".equals(imageId)) continue;
                if (thumb.optInt("width") < thumb.optInt("height") * 1.15) continue;   // portraits crop badly
                String artist = a.optString("artist_title", "");
                String title = a.optString("title", "");
                out.add(new Art(a.getInt("id"), imageId,
                        artist.isEmpty() || "null".equals(artist) ? title : artist + " · " + title));
            }
        } catch (JSONException e) {
            return out;
        }
        return out;
    }

    /** A picture to a file, written beside and renamed, so a half-downloaded file is never shown. */
    static void download(String url, File to) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15_000);
        c.setReadTimeout(30_000);
        c.setRequestProperty("User-Agent", "cleanyourtv-tvlist/1.0 (Android TV screensaver)");
        File tmp = new File(to.getParentFile(), to.getName() + ".part");
        try {
            if (c.getResponseCode() != HttpURLConnection.HTTP_OK) throw new IOException("HTTP " + c.getResponseCode());
            try (InputStream in = c.getInputStream(); FileOutputStream out = new FileOutputStream(tmp)) {
                byte[] buf = new byte[16384];
                int n;
                long total = 0;
                while ((n = in.read(buf)) > 0) {
                    total += n;
                    if (total > MAX_IMAGE_BYTES) throw new IOException("picture too large");
                    out.write(buf, 0, n);
                }
            }
            if (!tmp.renameTo(to)) throw new IOException("could not save " + to.getName());
        } finally {
            tmp.delete();
            c.disconnect();
        }
    }
}
