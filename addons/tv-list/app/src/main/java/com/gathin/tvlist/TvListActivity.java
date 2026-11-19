package com.gathin.tvlist;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

/**
 * "Show it now": starts the TV's screensaver, which setup.sh makes the TV list (TvListDream), and closes at once.
 * It is what a remote button mapped to "launch TV list" (Google TV's star shortcut, Button Mapper, the launcher's
 * app_keys) opens, and the module card on cleanyourtv's Home, so the button and waiting idle show the same screen.
 * It has no screen of its own: the list lives in the screensaver.
 */
public class TvListActivity extends Activity {

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        startScreensaver();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        startScreensaver();
    }

    private void startScreensaver() {
        try {
            // SystemUI's "start the screensaver now" activity, the one `am start -n ...Somnambulator` runs.
            startActivity(new Intent(Intent.ACTION_MAIN)
                    .setComponent(new ComponentName("com.android.systemui", "com.android.systemui.Somnambulator"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (RuntimeException e) {
            Log.w("TvList", "screensaver: " + e);
        }
        finish();
        overridePendingTransition(0, 0);
    }
}
