package com.programmieramt.olleebadge

import android.app.Notification
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationFilterTest {

    private fun filtered(
        flags: Int = 0,
        category: String? = null,
        template: String? = null,
        hasMediaSession: Boolean = false
    ) = NotificationFilter.isMediaOrPersistent(flags, category, template, hasMediaSession)

    @Test
    fun messagesCount() {
        assertFalse(filtered(category = Notification.CATEGORY_MESSAGE, template = "android.app.Notification\$MessagingStyle"))
        assertFalse(filtered(category = Notification.CATEGORY_EMAIL, template = "android.app.Notification\$InboxStyle"))
        assertFalse(filtered())
    }

    @Test
    fun missedCallsCount() {
        assertFalse(filtered(category = Notification.CATEGORY_MISSED_CALL))
        assertFalse(filtered(category = Notification.CATEGORY_CALL))
    }

    @Test
    fun persistentFlagsAreFiltered() {
        assertTrue(filtered(flags = Notification.FLAG_ONGOING_EVENT))
        assertTrue(filtered(flags = Notification.FLAG_NO_CLEAR))
        // Swipeable since Android 14, still a service notification
        assertTrue(filtered(flags = Notification.FLAG_FOREGROUND_SERVICE or Notification.FLAG_AUTO_CANCEL))
    }

    @Test
    fun pausedMediaIsFiltered() {
        assertTrue(filtered(category = Notification.CATEGORY_TRANSPORT))
        assertTrue(filtered(template = "android.app.Notification\$MediaStyle"))
        assertTrue(filtered(template = "android.app.Notification\$DecoratedMediaCustomViewStyle"))
        assertTrue(filtered(hasMediaSession = true))
    }

    @Test
    fun statusCategoriesAreFiltered() {
        assertTrue(filtered(category = Notification.CATEGORY_NAVIGATION))
        assertTrue(filtered(category = Notification.CATEGORY_PROGRESS))
        assertTrue(filtered(category = Notification.CATEGORY_SERVICE))
        assertTrue(filtered(category = Notification.CATEGORY_STOPWATCH))
        assertTrue(filtered(category = Notification.CATEGORY_LOCATION_SHARING))
        assertTrue(filtered(category = Notification.CATEGORY_STATUS))
        assertTrue(filtered(category = Notification.CATEGORY_SYSTEM))
    }
}
