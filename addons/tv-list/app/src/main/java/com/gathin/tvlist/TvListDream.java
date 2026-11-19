package com.gathin.tvlist;

import android.service.dreams.DreamService;
import android.view.KeyEvent;

/**
 * The system screensaver: the Scene. The arrows flip the to-do pages when the list has more than one; any other key
 * wakes the TV back to where it was.
 */
public class TvListDream extends DreamService {

    private Scene scene;

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        // Interactive so the arrows reach dispatchKeyEvent; every other key ends the dream there.
        setInteractive(true);
        setFullscreen(true);
        scene = new Scene(this);
        setContentView(scene.root);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int key = event.getKeyCode();
        boolean forward = key == KeyEvent.KEYCODE_DPAD_RIGHT || key == KeyEvent.KEYCODE_DPAD_DOWN;
        boolean back = key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_DPAD_UP;
        if (forward || back) {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return true;
            if (scene != null && scene.page(forward ? 1 : -1)) return true;
        }
        // The waking key is used up here, so it doesn't also act on the app below.
        if (event.getAction() == KeyEvent.ACTION_DOWN) finish();
        return true;
    }

    @Override
    public void onDreamingStarted() {
        super.onDreamingStarted();
        if (scene != null) scene.start();
    }

    @Override
    public void onDreamingStopped() {
        if (scene != null) scene.stop();
        super.onDreamingStopped();
    }

    @Override
    public void onDetachedFromWindow() {
        if (scene != null) scene.stop();
        super.onDetachedFromWindow();
    }
}
