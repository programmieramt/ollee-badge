package com.programmieramt.olleebadge

enum class LinkState {
    NO_WATCH,
    DISABLED,
    NO_PERMISSION,
    BLUETOOTH_OFF,
    /** Between sessions: not connected, nothing to send. */
    IDLE,
    /** Connected for a session. */
    SYNCING,
    /** Waiting for another app (the Ollee app) to let go of the watch. */
    BUSY,
    /** The last session failed; the next attempt is at [Status.nextAttemptMillis]. */
    UNREACHABLE,
}

/** Live state shown in the UI. Written and read on the main thread only. */
object Status {
    var listenerConnected = false
    var link = LinkState.NO_WATCH
    /** Countable notifications right now. */
    var count = 0
    /** What the watch was last set to ("" = weekday labels), or null if not yet synced. */
    var shownOnWatch: String? = null
    var lastSyncMillis = 0L
    var nextAttemptMillis = 0L
    var lastError: String? = null

    private val observers = mutableSetOf<() -> Unit>()

    fun observe(observer: () -> Unit) {
        observers += observer
    }

    fun unobserve(observer: () -> Unit) {
        observers -= observer
    }

    fun changed() {
        observers.toList().forEach { it() }
    }
}
