package com.gathin.tvlist;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The pictures, full bleed and still: each shows for {@link #SHOW_MS}, then crossfades into the next.
 * Two image views take turns; pictures are decoded off the main thread at screen size in 16-bit colour, so two in
 * memory cost a few megabytes. Tells the caption's owner what is showing.
 */
final class BackdropView extends FrameLayout {
    interface Captions {
        void caption(String text);
    }

    static final long SHOW_MS = 45_000L;
    private static final long FADE_MS = 2_500L;

    private final ImageView[] views = new ImageView[2];
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService decoder = Executors.newSingleThreadExecutor();
    private final Captions captions;
    private List<Backdrop.Picture> pictures = new ArrayList<>();
    private int index = -1;
    /** Pictures already shown, kept on disk so a new start carries on instead of beginning again. */
    private final File seenFile;
    private final java.util.Set<String> seen = new java.util.HashSet<>();
    private int front;
    private boolean running;
    private final Runnable next = this::next;

    BackdropView(Context context, Captions captions) {
        super(context);
        this.captions = captions;
        seenFile = new File(Backdrop.folder(context.getFilesDir()), "seen.txt");
        String saved = Config.read(seenFile);
        if (saved != null) for (String name : saved.split("\n")) if (!name.trim().isEmpty()) seen.add(name.trim());
        for (int i = 0; i < 2; i++) {
            ImageView v = new ImageView(context);
            v.setScaleType(ImageView.ScaleType.CENTER_CROP);
            v.setAlpha(0f);
            addView(v, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            views[i] = v;
        }
    }

    /** The pictures on disk now; the first shows at once if nothing is showing yet. */
    void setPictures(List<Backdrop.Picture> list) {
        boolean first = pictures.isEmpty() && !list.isEmpty();
        pictures = list;
        if (first && running) next();
    }

    void start() {
        running = true;
        if (!pictures.isEmpty()) next();
    }

    void stop() {
        running = false;
        handler.removeCallbacks(next);
        for (ImageView v : views) v.animate().cancel();
    }

    void release() {
        stop();
        decoder.shutdownNow();
        for (ImageView v : views) v.setImageDrawable(null);
    }

    private void next() {
        handler.removeCallbacks(next);
        if (!running || pictures.isEmpty()) return;
        index = nextUnseen(pictures, seen);
        if (index < 0) {   // every picture shown: start the round again
            seen.clear();
            index = nextUnseen(pictures, seen);
        }
        Backdrop.Picture p = pictures.get(index);
        seen.add(p.file.getName());
        // Forget pictures that are gone from disk, so the file stays as small as the folder.
        java.util.Set<String> present = new java.util.HashSet<>();
        for (Backdrop.Picture q : pictures) present.add(q.file.getName());
        seen.retainAll(present);
        Config.write(seenFile, String.join("\n", seen));
        int w = Math.max(getWidth(), 1280);
        int h = Math.max(getHeight(), 720);
        decoder.execute(() -> {
            Bitmap b = decode(p.file, w, h);
            handler.post(() -> show(b, p.caption));
        });
    }

    private void show(Bitmap bitmap, String caption) {
        if (!running) return;
        if (bitmap == null) {   // unreadable file: skip it
            handler.postDelayed(next, 1000);
            return;
        }
        ImageView out = views[front];
        front = 1 - front;
        ImageView in = views[front];
        in.animate().cancel();
        in.setImageBitmap(bitmap);
        // Still pictures, no zoom or drift: only the crossfade moves.
        in.setAlpha(0f);
        in.animate().alpha(1f).setDuration(FADE_MS).start();
        out.animate().alpha(0f).setDuration(FADE_MS).withEndAction(() -> out.setImageDrawable(null)).start();
        captions.caption(caption);
        handler.postDelayed(next, SHOW_MS);
    }

    /** The first picture, in order, not shown yet this round; -1 when every one has been. */
    static int nextUnseen(List<Backdrop.Picture> pictures, java.util.Set<String> seen) {
        for (int i = 0; i < pictures.size(); i++) {
            if (!seen.contains(pictures.get(i).file.getName())) return i;
        }
        return -1;
    }

    /** The file at about screen size, 16-bit colour; null if it cannot be read. */
    static Bitmap decode(File file, int width, int height) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getPath(), o);
        if (o.outWidth <= 0 || o.outHeight <= 0) return null;
        int sample = 1;
        while (o.outWidth / (sample * 2) >= width && o.outHeight / (sample * 2) >= height) sample *= 2;
        BitmapFactory.Options d = new BitmapFactory.Options();
        d.inSampleSize = sample;
        d.inPreferredConfig = Bitmap.Config.RGB_565;
        return BitmapFactory.decodeFile(file.getPath(), d);
    }

    @Override
    protected void onDetachedFromWindow() {
        release();
        super.onDetachedFromWindow();
    }

}
