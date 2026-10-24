package com.programmieramt.olleebadge

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Mirrors a notification count onto one Ollee Watch's weekday labels, and plays chimes on it, in
 * short sessions: connect, run a few [Step]s, disconnect.
 *
 * It never stays connected: the official Ollee app shares the link with any other app connected
 * to the watch, its connection setup then stalls behind ours and it gives up ("Connection error
 * in OTP"). For the same reason a session waits while another app is connected to the watch.
 *
 * All state lives on the main thread; GATT callbacks are posted there.
 */
@SuppressLint("MissingPermission") // beginSession() checks BLUETOOTH_CONNECT; SecurityException is caught
class WatchLink(
    private val context: Context,
    private val prefs: Prefs,
    val address: String
) {
    /** One exchange within a session. */
    private sealed interface Step {
        /** Reads [target]; [onReply] gets the reply payload and may queue further steps first. */
        class Read(val target: Int, val onReply: (ByteArray) -> Unit) : Step
        class Write(val target: Int, val payload: ByteArray) : Step
        class Wait(val millis: Long) : Step
    }

    private val handler = Handler(Looper.getMainLooper())
    private val manager = context.getSystemService(BluetoothManager::class.java)
    private val reassembler = FrameReassembler()
    private val writeQueue = ArrayDeque<ByteArray>()
    private val steps = ArrayDeque<Step>()

    private var running = false
    private var stopAfterSession = false

    /** The connection of the session in progress, null between sessions. */
    private var gatt: BluetoothGatt? = null
    private var rx: BluetoothGattCharacteristic? = null
    private var writeInFlight = false
    /** The current step is a write: move on once its chunks are out. */
    private var advanceWhenWritten = false
    private var awaiting: Step.Read? = null
    private var readAttempts = 0

    /** What the watch should show: a two-character badge cell, or "" for the weekday labels. */
    private var wanted = ""
    /** What the last session left on the watch (same form), null if unknown. */
    private var confirmed: String? = null
    /** Alarm chime to play with the next session, null if none. */
    private var pendingChime: Int? = null
    private var failures = 0
    /** Immediate retries used up in the current attempt, see [retryConnectQuickly]. */
    private var quickRetries = 0
    private var busySince = 0L
    private var lastSessionEnd = 0L
    /** The next session was asked for by the user: don't wait for another app. */
    private var urgent = false

    /** Called with what the watch shows ("" = weekday labels) after each successful session. */
    var onSynced: ((String) -> Unit)? = null

    private val startSession = Runnable { beginSession() }
    private val sessionTimeout = Runnable { endSession(success = false) }
    private val readTimeout = Runnable { onReadTimeout() }
    private val finishSession = Runnable { endSession(success = true) }
    private val nextStep = Runnable { runNextStep() }

    private val bluetoothStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                BluetoothAdapter.STATE_ON -> schedule(0)
                BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_OFF -> {
                    handler.removeCallbacks(startSession)
                    closeGatt()
                    setLink(LinkState.BLUETOOTH_OFF)
                }
            }
        }
    }

    fun start() {
        if (running) return
        running = true
        val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(bluetoothStateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(bluetoothStateReceiver, filter)
        }
        // First session also clears a badge left over from before (e.g. an app restart).
        schedule(0)
    }

    fun stop() {
        if (!running) return
        running = false
        try {
            context.unregisterReceiver(bluetoothStateReceiver)
        } catch (_: IllegalArgumentException) {
        }
        handler.removeCallbacksAndMessages(null)
        closeGatt()
    }

    /** Puts the weekday labels back on the watch (if it may be showing a badge), then stops. */
    fun clearAndStop() {
        if (confirmed == "" && gatt == null) return stop()
        stopAfterSession = true
        wanted = ""
        pendingChime = null
        if (gatt == null) schedule(0)
        handler.postDelayed({ stop() }, CLEAR_TIMEOUT_MS)
    }

    /**
     * Shows the two-character [cell] on the watch ("" = weekday labels) with the next session.
     * [urgent] skips the debounce and the wait for another app, for what the user asked to see
     * (the test).
     */
    fun show(cell: String, urgent: Boolean = false) {
        if (cell == wanted && cell == confirmed) return
        wanted = cell
        if (urgent) this.urgent = true
        // A running session picks the new cell up, or schedules a follow-up when it ends.
        if (gatt == null) schedule(if (urgent) 0 else DEBOUNCE_MS)
    }

    /**
     * Plays alarm chime number [chime] on the watch once (a few seconds), as soon as possible.
     * Writing the alarm record again does not stop it early (tried on hardware).
     */
    fun playChime(chime: Int) {
        pendingChime = chime
        urgent = true
        if (gatt == null) schedule(0)
    }

    /** Runs a session now, regardless of what the watch is believed to show. */
    fun syncNow() {
        failures = 0
        urgent = true
        confirmed = null
        if (gatt == null) schedule(0)
    }

    private fun schedule(delayMs: Long) {
        handler.removeCallbacks(startSession)
        if (running) handler.postDelayed(startSession, delayMs)
    }

    private fun beginSession() {
        if (!running || gatt != null) return
        if (context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            return setLink(LinkState.NO_PERMISSION)
        }
        val adapter = manager?.adapter
        if (adapter == null || !adapter.isEnabled) return setLink(LinkState.BLUETOOTH_OFF)

        val now = System.currentTimeMillis()
        // Our own link lingers a moment after a session; that is not another app.
        if (!urgent && now - lastSessionEnd > OWN_LINK_LINGER_MS && otherAppConnected()) {
            if (busySince == 0L) busySince = now
            // The Ollee app only trips over us while it sets its connection up; once it is
            // established (it may then hold it for hours), joining its link briefly is safe.
            if (now - busySince < BUSY_MAX_WAIT_MS) {
                Log.i(TAG, "watch busy with another app, waiting")
                setLink(LinkState.BUSY)
                return schedule(BUSY_RETRY_MS)
            }
        }
        busySince = 0
        urgent = false

        val device = try {
            adapter.getRemoteDevice(address)
        } catch (e: IllegalArgumentException) {
            Status.lastError = e.message
            return setLink(LinkState.UNREACHABLE)
        }
        Log.i(TAG, "session start, showing '$wanted'" + (pendingChime?.let { ", chime $it" } ?: ""))
        setLink(LinkState.SYNCING)
        handler.postDelayed(sessionTimeout, SESSION_TIMEOUT_MS)
        gatt = try {
            device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
        } catch (_: SecurityException) {
            null
        }
        if (gatt == null) endSession(success = false)
    }

    private fun otherAppConnected(): Boolean = try {
        manager.getConnectedDevices(BluetoothProfile.GATT).any { it.address == address }
    } catch (_: SecurityException) {
        false
    }

    private fun endSession(success: Boolean) {
        handler.removeCallbacks(sessionTimeout)
        handler.removeCallbacks(finishSession)
        closeGatt()
        lastSessionEnd = System.currentTimeMillis()
        quickRetries = 0
        if (stopAfterSession) return stop()

        Log.i(TAG, if (success) "session done, watch shows '$confirmed'" else "session failed")
        if (success) {
            failures = 0
            setLink(LinkState.IDLE)
            confirmed?.let { onSynced?.invoke(it) }
            when {
                // Asked for while this session's steps were already planned.
                pendingChime != null -> {
                    urgent = true
                    schedule(0)
                }
                wanted != confirmed -> schedule(DEBOUNCE_MS)
                // Re-assert now and then, in case the Ollee app rewrote the register.
                wanted.isNotEmpty() -> schedule(REFRESH_MS)
            }
        } else {
            val delay = RETRY_DELAYS_MS[failures.coerceAtMost(RETRY_DELAYS_MS.lastIndex)]
            failures++
            Status.nextAttemptMillis = System.currentTimeMillis() + delay
            setLink(LinkState.UNREACHABLE)
            schedule(delay)
        }
    }

    /**
     * The phone reached the watch but the link died in its first connection events. That is a
     * transient radio failure (common while the phone keeps retrying another Bluetooth device),
     * so try again right away instead of backing off as if the watch were out of range.
     */
    private fun retryConnectQuickly() {
        quickRetries++
        Log.i(TAG, "connection setup failed, retry $quickRetries")
        handler.removeCallbacks(sessionTimeout)
        closeGatt()
        urgent = true
        schedule(QUICK_RETRY_MS)
    }

    private fun closeGatt() {
        handler.removeCallbacks(readTimeout)
        handler.removeCallbacks(nextStep)
        rx = null
        writeQueue.clear()
        writeInFlight = false
        advanceWhenWritten = false
        awaiting = null
        steps.clear()
        reassembler.reset()
        val g = gatt ?: return
        gatt = null
        try {
            g.disconnect()
            g.close()
        } catch (_: SecurityException) {
        }
    }

    private fun setLink(state: LinkState) {
        if (!running || stopAfterSession) return
        Status.link = state
        Status.changed()
    }

    private fun onServicesDiscovered(g: BluetoothGatt) {
        val service = g.getService(OlleeProtocol.SERVICE_NUS)
        val rxChar = service?.getCharacteristic(OlleeProtocol.CHARACTERISTIC_RX)
        val txChar = service?.getCharacteristic(OlleeProtocol.CHARACTERISTIC_TX)
        val cccd = txChar?.getDescriptor(OlleeProtocol.CCCD)
        if (rxChar == null || txChar == null || cccd == null) {
            Status.lastError = context.getString(R.string.error_no_service)
            return endSession(success = false)
        }
        rx = rxChar
        val ok = try {
            g.setCharacteristicNotification(txChar, true) &&
                writeDescriptor(g, cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
        } catch (_: SecurityException) {
            false
        }
        if (!ok) endSession(success = false)
    }

    /**
     * The weekday register is always read back first and written from that read-back, which keeps
     * the World Time offset and any weekday names changed in the Ollee app.
     */
    private fun planSession() {
        steps.clear()
        steps += Step.Read(OlleeProtocol.TARGET_GET_WEEKDAYS, ::onWeekdays)
        pendingChime?.let { chime ->
            steps += Step.Read(OlleeProtocol.TARGET_GET_ALARM) { onAlarm(it, chime) }
        }
        runNextStep()
    }

    private fun runNextStep() {
        if (gatt == null) return
        when (val step = steps.removeFirstOrNull()) {
            null -> {
                // Short grace so the last write leaves the radio before the link goes down.
                handler.removeCallbacks(finishSession)
                handler.postDelayed(finishSession, DISCONNECT_GRACE_MS)
            }
            is Step.Read -> {
                readAttempts = 0
                sendRead(step)
            }
            is Step.Write -> {
                advanceWhenWritten = true
                send(OlleeProtocol.buildPacket(step.target, step.payload))
            }
            is Step.Wait -> handler.postDelayed(nextStep, step.millis)
        }
    }

    private fun sendRead(step: Step.Read) {
        awaiting = step
        handler.removeCallbacks(readTimeout)
        handler.postDelayed(readTimeout, READ_TIMEOUT_MS)
        send(OlleeProtocol.buildPacket(step.target))
    }

    /** The watch drops reads that arrive while another reply is in flight; ask again a few times. */
    private fun onReadTimeout() {
        val step = awaiting ?: return
        if (++readAttempts < MAX_READ_ATTEMPTS) {
            sendRead(step)
        } else {
            Status.lastError = context.getString(R.string.error_no_reply)
            endSession(success = false)
        }
    }

    private fun onFrame(frame: Frame) {
        // Every weekday read-back, including the Ollee app's, keeps the stored weekday names current.
        if (frame.target == OlleeProtocol.TARGET_GET_WEEKDAYS + OlleeProtocol.RESPONSE_OFFSET) {
            WeekdayRegister.parse(frame.payload)?.let(::learnWeekdayLabels)
        }
        val step = awaiting ?: return
        // Anything else is a reply to someone else's request on the shared link.
        if (frame.target != step.target + OlleeProtocol.RESPONSE_OFFSET) return
        awaiting = null
        handler.removeCallbacks(readTimeout)
        step.onReply(frame.payload)
        runNextStep()
    }

    private fun learnWeekdayLabels(register: WeekdayRegister) {
        if (!register.showsBadge && !register.labels.contentEquals(prefs.weekdayLabels)) {
            prefs.weekdayLabels = register.labels
        }
    }

    private fun onWeekdays(payload: ByteArray) {
        val current = WeekdayRegister.parse(payload) ?: return
        val update = WeekdayRegister.planWrite(current, wanted, prefs.weekdayLabels)
        Log.i(TAG, "watch labels '${current.labels.toString(Charsets.ISO_8859_1)}', " +
            if (update == null) "no write needed" else "writing '${update.labels.toString(Charsets.ISO_8859_1)}'")
        confirmed = wanted
        Status.shownOnWatch = wanted
        Status.lastSyncMillis = System.currentTimeMillis()
        Status.lastError = null
        Status.changed()
        if (update != null) steps.addFirst(Step.Write(OlleeProtocol.TARGET_SET_WEEKDAYS, update.toPayload()))
    }

    /**
     * Plays [chime] by writing the alarm record back unchanged except chime and playNow. If the
     * watch then keeps [chime] as the alarm's chime, the original one is restored once it has played.
     */
    private fun onAlarm(readback: ByteArray, chime: Int) {
        pendingChime = null
        if (readback.size < AlarmRecord.READBACK_SIZE) {
            Log.w(TAG, "alarm read-back too short (${readback.size} bytes), chime skipped")
            return
        }
        val original = AlarmRecord.chimeOf(readback)
        Log.i(TAG, "playing chime $chime (alarm chime is $original)")
        steps.addAll(0, listOf(
            Step.Write(OlleeProtocol.TARGET_SET_ALARM, AlarmRecord.toWrite(readback, chime, playNow = true)),
            Step.Wait(CHIME_SETTLE_MS),
            Step.Read(OlleeProtocol.TARGET_GET_ALARM) { after ->
                val kept = if (after.size >= AlarmRecord.READBACK_SIZE) AlarmRecord.chimeOf(after) else original
                Log.i(TAG, "after playNow the alarm chime is $kept")
                if (kept != original) {
                    steps.addAll(0, listOf(
                        Step.Wait(CHIME_PLAY_MS),
                        Step.Write(OlleeProtocol.TARGET_SET_ALARM, AlarmRecord.toWrite(readback))
                    ))
                }
            }
        ))
    }

    private fun send(packet: ByteArray) {
        writeQueue.addAll(OlleeProtocol.chunks(packet))
        pump()
    }

    /** Writes queued chunks one at a time, each after the previous write completed. */
    private fun pump() {
        if (writeInFlight) return
        val g = gatt ?: return
        val characteristic = rx ?: return
        val chunk = writeQueue.removeFirstOrNull()
        if (chunk == null) {
            if (advanceWhenWritten) {
                advanceWhenWritten = false
                runNextStep()
            }
            return
        }
        val type = if (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        } else {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        }
        writeInFlight = writeCharacteristic(g, characteristic, chunk, type)
        if (!writeInFlight) endSession(success = false)
    }

    private fun onWriteDone(status: Int) {
        writeInFlight = false
        if (status == BluetoothGatt.GATT_SUCCESS) pump() else endSession(success = false)
    }

    private fun writeCharacteristic(
        g: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray,
        type: Int
    ): Boolean = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeCharacteristic(characteristic, value, type) == BluetoothStatusCodes.SUCCESS
        } else {
            legacyWriteCharacteristic(g, characteristic, value, type)
        }
    } catch (_: SecurityException) {
        false
    }

    @Suppress("DEPRECATION")
    private fun legacyWriteCharacteristic(
        g: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray,
        type: Int
    ): Boolean {
        characteristic.writeType = type
        characteristic.value = value
        return g.writeCharacteristic(characteristic)
    }

    private fun writeDescriptor(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, value: ByteArray): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeDescriptor(descriptor, value) == BluetoothStatusCodes.SUCCESS
        } else {
            legacyWriteDescriptor(g, descriptor, value)
        }

    @Suppress("DEPRECATION")
    private fun legacyWriteDescriptor(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, value: ByteArray): Boolean {
        descriptor.value = value
        return g.writeDescriptor(descriptor)
    }

    private fun onNotify(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
        if (characteristic.uuid != OlleeProtocol.CHARACTERISTIC_TX) return
        handler.post {
            if (g !== gatt) return@post
            var frame = reassembler.accept(value)
            while (frame != null) {
                onFrame(frame)
                frame = reassembler.pending()
            }
        }
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            handler.post {
                if (g !== gatt) return@post
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        val ok = try {
                            // Otherwise the link runs at 45 ms for up to half a minute after a
                            // session, until the watch drops it back to 1 s on its own.
                            g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_LOW_POWER)
                            g.discoverServices()
                        } catch (_: SecurityException) {
                            false
                        }
                        if (!ok) endSession(success = false)
                    }
                    BluetoothProfile.STATE_DISCONNECTED ->
                        if (status in SETUP_FAILED_STATUSES && quickRetries < MAX_QUICK_RETRIES) {
                            retryConnectQuickly()
                        } else {
                            endSession(success = false)
                        }
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            handler.post {
                if (g !== gatt) return@post
                if (status == BluetoothGatt.GATT_SUCCESS) onServicesDiscovered(g) else endSession(success = false)
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            handler.post {
                if (g !== gatt || descriptor.uuid != OlleeProtocol.CCCD) return@post
                if (status == BluetoothGatt.GATT_SUCCESS) planSession() else endSession(success = false)
            }
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            handler.post {
                if (g === gatt) onWriteDone(status)
            }
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            onNotify(g, characteristic, value.copyOf())
        }

        /** Called instead of the ByteArray overload before Android 13. */
        @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
            val value = characteristic.value ?: return
            onNotify(g, characteristic, value.copyOf())
        }
    }

    private companion object {
        const val TAG = "OlleeBadge"
        /** Collects bursts of notification changes into one session. */
        const val DEBOUNCE_MS = 10_000L
        const val SESSION_TIMEOUT_MS = 25_000L
        const val READ_TIMEOUT_MS = 3_000L
        const val MAX_READ_ATTEMPTS = 3
        const val DISCONNECT_GRACE_MS = 300L
        /** Lets the watch acknowledge the playNow write before the alarm is read back. */
        const val CHIME_SETTLE_MS = 500L
        /** Leaves a chime time to play out before the alarm record is restored. */
        const val CHIME_PLAY_MS = 5_000L
        /**
         * The watch didn't answer in the first connection events: HCI 0x3E, which some Bluetooth
         * stacks report as the generic GATT_ERROR 133 instead.
         */
        val SETUP_FAILED_STATUSES = setOf(0x3E, 133)
        const val MAX_QUICK_RETRIES = 2
        const val QUICK_RETRY_MS = 3_000L
        const val OWN_LINK_LINGER_MS = 10_000L
        const val BUSY_RETRY_MS = 15_000L
        const val BUSY_MAX_WAIT_MS = 60_000L
        const val REFRESH_MS = 15 * 60_000L
        const val CLEAR_TIMEOUT_MS = 30_000L
        /** Back-off while the watch is out of range or its Bluetooth is off. */
        val RETRY_DELAYS_MS = longArrayOf(30_000L, 60_000L, 2 * 60_000L, 5 * 60_000L, 10 * 60_000L, 15 * 60_000L)
    }
}
