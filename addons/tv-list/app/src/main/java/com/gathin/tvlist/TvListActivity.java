package com.gathin.tvlist;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.util.Log;
import android.view.KeyEvent;
import android.view.animation.DecelerateInterpolator;

/**
 * The list on its own screen: the module card on cleanyourtv's Home, and the target of a remote button mapped to
 * "launch TV list" (Google TV's star-button shortcut, Button Mapper, or the launcher's app_keys). Pressing that
 * button again while the list shows starts the TV's own screensaver, so one button flips between the two: the
 * shortcut launches this activity again, which arrives here as onNewIntent while it is on screen, or the key itself
 * arrives when nothing intercepts it. Any other key goes back, as a screensaver wakes on any key. Both ways fade, so
 * the switch from and to the screensaver has no hard cut.
 */
public class TvListActivity extends Activity {
    private static final long FADE_IN_MS = 700;
    private static final long FADE_OUT_MS = 400;

    private Scene scene;
    private boolean started;
    private boolean leaving;
    private boolean faded;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        // Launched while the screensaver runs (the button pressed over it), the list would open behind the
        // screensaver. Turning the screen on wakes the device out of the dream, so the list is what you see.
        setTurnScreenOn(true);
        // Black behind the fade, and no system slide: the scene fades in over black instead.
        getWindow().setBackgroundDrawable(new ColorDrawable(Color.BLACK));
        overridePendingTransition(0, 0);
    }

    @Override
    protected void onStart() {
        super.onStart();
        started = true;
        leaving = false;
        scene = new Scene(this);
        setContentView(scene.root);
        scene.root.setAlpha(0f);
        faded = false;
        // Fade in once the window is really on screen (it has focus), not when it is built: waking out of the
        // screensaver takes a moment, and a fade started now would be over before anyone sees it. The fallback
        // covers a window that never gets focus.
        scene.root.postDelayed(this::fadeIn, 1500);
        scene.start();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) fadeIn();
    }

    private void fadeIn() {
        if (faded || scene == null || leaving) return;
        faded = true;
        scene.root.animate().alpha(1f).setStartDelay(150).setDuration(FADE_IN_MS)
                .setInterpolator(new DecelerateInterpolator()).start();
    }

    @Override
    protected void onStop() {
        started = false;
        if (scene != null) scene.stop();
        scene = null;
        super.onStop();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // Launched again while already showing: the toggle button was pressed a second time.
        if (started) toScreensaver();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() != KeyEvent.ACTION_DOWN || leaving) return true;
        int key = event.getKeyCode();
        if (key == KeyEvent.KEYCODE_MACRO_1 || key == KeyEvent.KEYCODE_BOOKMARK) toScreensaver();
        else leave();
        return true;
    }

    /** Fades out, then back to wherever the list was opened from. */
    private void leave() {
        fadeOut(this::finishQuietly);
    }

    /** Fades out, then the TV's own screensaver (Google's Ambient mode, say), which fades itself in. */
    private void toScreensaver() {
        fadeOut(() -> {
            try {
                // SystemUI's "start the screensaver now" activity, the one `am start -n ...Somnambulator` runs.
                startActivity(new Intent(Intent.ACTION_MAIN)
                        .setComponent(new ComponentName("com.android.systemui", "com.android.systemui.Somnambulator"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (RuntimeException e) {
                Log.w("TvList", "screensaver: " + e);
            }
            finishQuietly();
        });
    }

    private void fadeOut(Runnable then) {
        if (leaving) return;
        leaving = true;
        if (scene == null) {
            then.run();
            return;
        }
        scene.root.animate().cancel();
        scene.root.animate().alpha(0f).setDuration(FADE_OUT_MS).withEndAction(then).start();
    }

    private void finishQuietly() {
        finish();
        overridePendingTransition(0, 0);
    }
}
