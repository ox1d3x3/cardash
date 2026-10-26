package xyz.ox1de.cardash;

import android.service.notification.NotificationListenerService;

/**
 * Empty notification listener. Android only hands out the list of active media
 * sessions (AIMP, Spotify, radio apps...) to apps with notification access.
 * We never read or touch notifications themselves.
 */
public class MediaListener extends NotificationListenerService {
}
