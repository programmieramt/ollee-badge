package com.programmieramt.olleebadge

import android.app.Notification

/** Media players and persistent notifications: state, not something to read, so they never count. */
object NotificationFilter {

    /**
     * Flags of notifications that stay until the app removes them. Checked explicitly because
     * since Android 14 users can swipe away foreground-service notifications, so they can look
     * clearable.
     */
    private const val PERSISTENT_FLAGS =
        Notification.FLAG_ONGOING_EVENT or Notification.FLAG_NO_CLEAR or Notification.FLAG_FOREGROUND_SERVICE

    /**
     * Categories that describe ongoing state. CATEGORY_CALL is left out on purpose: some dialers
     * file missed calls under it, and ongoing calls carry FLAG_ONGOING_EVENT anyway.
     */
    private val PERSISTENT_CATEGORIES = setOf(
        Notification.CATEGORY_TRANSPORT, // media playback
        Notification.CATEGORY_SERVICE,
        Notification.CATEGORY_PROGRESS, // downloads, uploads
        Notification.CATEGORY_NAVIGATION,
        Notification.CATEGORY_STOPWATCH,
        Notification.CATEGORY_LOCATION_SHARING,
        Notification.CATEGORY_STATUS,
        Notification.CATEGORY_SYSTEM,
    )

    /**
     * Paused players often drop the ongoing flag and become clearable, so media is recognised by
     * its media session or MediaStyle template (Notification$MediaStyle,
     * Notification$DecoratedMediaCustomViewStyle) as well as by category.
     */
    fun isMediaOrPersistent(flags: Int, category: String?, template: String?, hasMediaSession: Boolean): Boolean =
        flags and PERSISTENT_FLAGS != 0 ||
            category in PERSISTENT_CATEGORIES ||
            hasMediaSession ||
            template?.contains("Media") == true

    fun isMediaOrPersistent(notification: Notification): Boolean {
        // Extras come from other apps; reading them can fail on parcels this app can't unpack.
        val (template, hasMediaSession) = try {
            val extras = notification.extras
            extras?.getString(Notification.EXTRA_TEMPLATE) to (extras?.containsKey(Notification.EXTRA_MEDIA_SESSION) == true)
        } catch (_: RuntimeException) {
            null to false
        }
        return isMediaOrPersistent(notification.flags, notification.category, template, hasMediaSession)
    }
}
