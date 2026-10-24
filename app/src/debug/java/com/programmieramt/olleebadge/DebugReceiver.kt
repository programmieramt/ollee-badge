package com.programmieramt.olleebadge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Debug builds only: lets adb play a chime on the watch, to find out what each chime number
 * sounds like.
 *
 *     adb shell am broadcast -a com.programmieramt.olleebadge.debug.PLAY_CHIME \
 *         -n com.programmieramt.olleebadge/.DebugReceiver --ei chime 3 --ei label 1
 *
 * The optional label (1..11) is shown on the watch like a test badge, so whoever listens can
 * tell which of several chimes in a row is playing. The optional text (two characters, without a
 * chime) is shown the same way, to see how the LCD renders characters:
 *
 *     adb shell am broadcast -a com.programmieramt.olleebadge.debug.PLAY_CHIME \
 *         -n com.programmieramt.olleebadge/.DebugReceiver --es text 'D*'
 */
class DebugReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val listener = BadgeNotificationListener.instance ?: return
        val label = intent.getIntExtra("label", 0)
        if (label in 1..WeekdayRegister.MAX_COUNT) listener.showTest(WeekdayRegister.badgeCell(label))
        intent.getStringExtra("text")?.let(listener::showTest)
        val chime = intent.getIntExtra("chime", -1)
        if (chime in 0..0xFF) listener.playChime(chime)
    }
}
