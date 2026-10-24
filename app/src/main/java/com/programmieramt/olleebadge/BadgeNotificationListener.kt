package com.programmieramt.olleebadge

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationManager
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

/**
 * Counts the phone's notifications and owns the [WatchLink] that shows the count, or the marker of
 * a marked Beeper chat ([Markers]), on the watch. The system keeps this service bound while
 * notification access is granted, so it runs in the background without a foreground service.
 */
class BadgeNotificationListener : NotificationListenerService(),
    SharedPreferences.OnSharedPreferenceChangeListener {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var prefs: Prefs
    private var link: WatchLink? = null
    /** Cell shown instead of the real one while a test runs. */
    private var testCell: String? = null

    private val recount = Runnable { pushCount() }
    private val endTest = Runnable {
        testCell = null
        pushCount(urgent = true)
    }

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
    }

    override fun onListenerConnected() {
        instance = this
        Status.listenerConnected = true
        prefs.registerListener(this)
        applySettings()
    }

    override fun onListenerDisconnected() {
        shutDown(clearWatch = true)
    }

    override fun onDestroy() {
        shutDown(clearWatch = false)
        super.onDestroy()
    }

    private fun shutDown(clearWatch: Boolean) {
        if (instance === this) instance = null
        Status.listenerConnected = false
        prefs.unregisterListener(this)
        handler.removeCallbacksAndMessages(null)
        link?.let { if (clearWatch) it.clearAndStop() else it.stop() }
        link = null
        Status.changed()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?, rankingMap: RankingMap?) = scheduleRecount()

    override fun onNotificationRemoved(sbn: StatusBarNotification?, rankingMap: RankingMap?) = scheduleRecount()

    override fun onNotificationRankingUpdate(rankingMap: RankingMap?) = scheduleRecount()

    override fun onSharedPreferenceChanged(sp: SharedPreferences?, key: String?) {
        when (key) {
            Prefs.KEY_ENABLED, Prefs.KEY_WATCH_ADDRESS -> applySettings()
            Prefs.KEY_COUNT_APPS, Prefs.KEY_INCLUDE_SILENT, Prefs.KEY_EXCLUDED_APPS,
            Prefs.KEY_MARKERS -> scheduleRecount(0)
        }
    }

    /** Shows the two-character [cell] on the watch for a few seconds, to check the display. */
    fun showTest(cell: String) {
        testCell = cell
        handler.removeCallbacks(endTest)
        // The test duration runs from when the watch shows it (onSynced); this caps a watch
        // that can't be reached.
        handler.postDelayed(endTest, TEST_MAX_MS)
        pushCount(urgent = true)
    }

    private fun onSynced(shown: String) {
        if (testCell != null && shown == testCell) {
            handler.removeCallbacks(endTest)
            handler.postDelayed(endTest, TEST_DURATION_MS)
        }
    }

    fun syncNow() {
        link?.syncNow() ?: applySettings()
    }

    /** Plays alarm chime number [chime] on the watch once. */
    fun playChime(chime: Int) {
        link?.playChime(chime)
    }

    private fun applySettings() {
        val address = prefs.watchAddress
        if (!prefs.enabled || address == null) {
            link?.clearAndStop()
            link = null
            Status.link = if (address == null) LinkState.NO_WATCH else LinkState.DISABLED
            Status.shownOnWatch = null
            Status.changed()
            pushCount()
            return
        }
        if (link?.address != address) {
            link?.stop()
            // Application context: clearAndStop() may finish after this service is destroyed.
            link = WatchLink(applicationContext, prefs, address).also {
                it.onSynced = ::onSynced
                it.start()
            }
        }
        pushCount()
    }

    private fun scheduleRecount(delayMs: Long = DEBOUNCE_MS) {
        handler.removeCallbacks(recount)
        handler.postDelayed(recount, delayMs)
    }

    private fun pushCount(urgent: Boolean = false) {
        val relevant = relevantNotifications()
        val count = if (prefs.countApps) relevant.distinctBy { it.packageName }.size else relevant.size
        Status.count = count
        Status.changed()
        val cell = testCell
            ?: markerCell(relevant)
            ?: if (count == 0) "" else WeekdayRegister.badgeCell(count)
        link?.show(cell, urgent)
    }

    private fun relevantNotifications(): List<StatusBarNotification> {
        val active = try {
            activeNotifications
        } catch (_: SecurityException) {
            null
        } ?: return emptyList()
        val ranking = currentRanking
        val candidates = active.filter { isCandidate(it, ranking) }
        rememberApps(candidates)
        val excluded = prefs.excludedApps
        return candidates.filter { it.packageName !in excluded }
    }

    /** The marker of the newest notification from a marked Beeper chat, if any. */
    private fun markerCell(relevant: List<StatusBarNotification>): String? {
        val rules = Markers.parse(prefs.markers)
        if (rules.isEmpty()) return null
        return relevant
            .filter { it.packageName == BEEPER_PACKAGE }
            .sortedByDescending { it.postTime }
            .firstNotNullOfOrNull { sbn ->
                val chat = chatName(sbn.notification)
                Markers.cellFor(rules, chat).also {
                    // Chat names are private: only logged after `adb shell setprop log.tag.OlleeBadge DEBUG`.
                    if (Log.isLoggable(TAG, Log.DEBUG)) Log.d(TAG, "Beeper chat '$chat' -> ${it ?: "no marker"}")
                }
            }
    }

    /** Group chats carry their name as the conversation title, one-to-one chats as the title. */
    private fun chatName(notification: Notification): String? = try {
        val extras = notification.extras
        (extras?.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)
            ?: extras?.getCharSequence(Notification.EXTRA_TITLE))?.toString()
    } catch (_: RuntimeException) { // extras from another app can fail to unparcel
        null
    }

    /** Whether [sbn] is something the user would want to see, before the per-app exclusions. */
    private fun isCandidate(sbn: StatusBarNotification, ranking: RankingMap?): Boolean {
        if (sbn.packageName == packageName) return false
        if (!sbn.isClearable) return false
        // Media players (also paused) and persistent notifications (services, navigation, downloads…)
        if (NotificationFilter.isMediaOrPersistent(sbn.notification)) return false
        // A group summary stands for its children, which are counted themselves.
        if (sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return false
        if (!prefs.includeSilent && ranking != null) {
            val r = Ranking()
            if (ranking.getRanking(sbn.key, r) && r.importance < NotificationManager.IMPORTANCE_DEFAULT) {
                return false
            }
        }
        return true
    }

    private fun rememberApps(candidates: List<StatusBarNotification>) {
        val seen = prefs.seenApps
        val updated = seen + candidates.map { it.packageName }
        if (updated.size != seen.size) prefs.seenApps = updated
    }

    companion object {
        private const val TAG = "OlleeBadge"
        private const val BEEPER_PACKAGE = "com.beeper.android"
        private const val DEBOUNCE_MS = 500L
        private const val TEST_DURATION_MS = 10_000L
        private const val TEST_MAX_MS = 60_000L

        /** The running listener, or null while notification access is off or unbound. */
        @SuppressLint("StaticFieldLeak") // cleared in shutDown()
        var instance: BadgeNotificationListener? = null
            private set
    }
}
