package com.programmieramt.olleebadge

import android.content.Context
import android.content.SharedPreferences

class Prefs(context: Context) {
    private val sp: SharedPreferences =
        context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** Show the badge on the watch at all. */
    var enabled: Boolean
        get() = sp.getBoolean(KEY_ENABLED, true)
        set(value) = sp.edit().putBoolean(KEY_ENABLED, value).apply()

    var watchAddress: String?
        get() = sp.getString(KEY_WATCH_ADDRESS, null)
        set(value) = sp.edit().putString(KEY_WATCH_ADDRESS, value).apply()

    var watchName: String?
        get() = sp.getString(KEY_WATCH_NAME, null)
        set(value) = sp.edit().putString(KEY_WATCH_NAME, value).apply()

    /** Count apps with notifications instead of individual notifications. */
    var countApps: Boolean
        get() = sp.getBoolean(KEY_COUNT_APPS, false)
        set(value) = sp.edit().putBoolean(KEY_COUNT_APPS, value).apply()

    /** Also count silent (low-importance) notifications. */
    var includeSilent: Boolean
        get() = sp.getBoolean(KEY_INCLUDE_SILENT, false)
        set(value) = sp.edit().putBoolean(KEY_INCLUDE_SILENT, value).apply()

    /** Beeper chats with their own marker instead of the count, one "Name = XY" per line ([Markers]). */
    var markers: String
        get() = sp.getString(KEY_MARKERS, null) ?: ""
        set(value) = sp.edit().putString(KEY_MARKERS, value).apply()

    var excludedApps: Set<String>
        get() = sp.getStringSet(KEY_EXCLUDED_APPS, null)?.toSet() ?: emptySet()
        set(value) = sp.edit().putStringSet(KEY_EXCLUDED_APPS, value).apply()

    /** Packages that have posted countable notifications; the choices for [excludedApps]. */
    var seenApps: Set<String>
        get() = sp.getStringSet(KEY_SEEN_APPS, null)?.toSet() ?: emptySet()
        set(value) = sp.edit().putStringSet(KEY_SEEN_APPS, value).apply()

    /**
     * The weekday labels to restore when there are no notifications, learned from the watch so
     * custom weekday names set in the Ollee app survive.
     */
    var weekdayLabels: ByteArray
        get() = sp.getString(KEY_WEEKDAY_LABELS, null)?.let(::fromHex)
            ?.takeIf { it.size == WeekdayRegister.LABELS_SIZE }
            ?: WeekdayRegister.DEFAULT_LABELS
        set(value) = sp.edit().putString(KEY_WEEKDAY_LABELS, toHex(value)).apply()

    fun registerListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) =
        sp.registerOnSharedPreferenceChangeListener(listener)

    fun unregisterListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) =
        sp.unregisterOnSharedPreferenceChangeListener(listener)

    companion object {
        const val KEY_ENABLED = "enabled"
        const val KEY_WATCH_ADDRESS = "watch_address"
        const val KEY_WATCH_NAME = "watch_name"
        const val KEY_COUNT_APPS = "count_apps"
        const val KEY_INCLUDE_SILENT = "include_silent"
        const val KEY_MARKERS = "markers"
        const val KEY_EXCLUDED_APPS = "excluded_apps"
        const val KEY_SEEN_APPS = "seen_apps"
        const val KEY_WEEKDAY_LABELS = "weekday_labels"

        private fun toHex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

        private fun fromHex(hex: String): ByteArray? {
            if (hex.length % 2 != 0) return null
            return try {
                ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
            } catch (_: NumberFormatException) {
                null
            }
        }
    }
}
