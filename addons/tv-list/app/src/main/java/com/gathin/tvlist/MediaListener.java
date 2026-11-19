package com.gathin.tvlist;

import android.service.notification.NotificationListenerService;

/**
 * Exists only so the list can see what is playing: Android hands the active media sessions (Spotify and the like) to
 * an app whose notification listener the owner has allowed. setup.sh allows it over ADB
 * (`cmd notification allow_listener com.gathin.tvlist/com.gathin.tvlist.MediaListener`). Notifications themselves are
 * never read: none of the callbacks are overridden.
 */
public class MediaListener extends NotificationListenerService {
}
