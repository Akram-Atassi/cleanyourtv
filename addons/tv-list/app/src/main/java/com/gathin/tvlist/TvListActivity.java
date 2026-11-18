package com.gathin.tvlist;

import android.app.Activity;
import android.view.KeyEvent;

/**
 * The module card on cleanyourtv's Home: the same scene as the screensaver, right away. Any key goes back to Home,
 * as the screensaver wakes on any key.
 */
public class TvListActivity extends Activity {

    private Scene scene;

    @Override
    protected void onStart() {
        super.onStart();
        scene = new Scene(this);
        setContentView(scene.root);
        scene.start();
    }

    @Override
    protected void onStop() {
        if (scene != null) scene.stop();
        scene = null;
        super.onStop();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN) finish();
        return true;
    }
}
