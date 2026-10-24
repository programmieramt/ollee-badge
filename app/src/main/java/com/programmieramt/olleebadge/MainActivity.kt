package com.programmieramt.olleebadge

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.NotificationManager
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Bundle
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.text.InputType
import android.text.format.DateFormat
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.util.Date

class MainActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var countView: TextView
    private lateinit var countLabel: TextView
    private lateinit var statusView: TextView
    private lateinit var grantListener: Button
    private lateinit var grantBluetooth: Button

    private val renderOnChange: () -> Unit = { render() }
    private var picker: WatchPicker? = null
    private var pickAfterPermission = false

    private val listenerComponent by lazy { ComponentName(this, BadgeNotificationListener::class.java) }
    private val bluetoothPermissions = arrayOf(
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.BLUETOOTH_CONNECT
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)

        findViewById<View>(R.id.root).setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        countView = findViewById(R.id.count)
        countLabel = findViewById(R.id.count_label)
        statusView = findViewById(R.id.status)
        grantListener = findViewById(R.id.grant_listener)
        grantBluetooth = findViewById(R.id.grant_bluetooth)

        grantListener.setOnClickListener { openListenerSettings() }
        grantBluetooth.setOnClickListener { requestPermissions(bluetoothPermissions, REQUEST_BLUETOOTH) }
        findViewById<Button>(R.id.choose_watch).setOnClickListener { chooseWatch() }
        findViewById<Button>(R.id.exclude_apps).setOnClickListener { showExcludeDialog() }
        findViewById<Button>(R.id.markers).setOnClickListener { showMarkersDialog() }
        findViewById<Button>(R.id.test).setOnClickListener { runTest() }
        findViewById<Button>(R.id.sync_now).setOnClickListener {
            BadgeNotificationListener.instance?.syncNow() ?: rebindListener()
        }

        bindSwitch(R.id.enabled, prefs.enabled) { prefs.enabled = it }
        bindSwitch(R.id.count_apps, prefs.countApps) { prefs.countApps = it; render() }
        bindSwitch(R.id.include_silent, prefs.includeSilent) { prefs.includeSilent = it }
    }

    override fun onResume() {
        super.onResume()
        Status.observe(renderOnChange)
        if (hasListenerAccess() && BadgeNotificationListener.instance == null) rebindListener()
        render()
    }

    override fun onPause() {
        Status.unobserve(renderOnChange)
        super.onPause()
    }

    override fun onDestroy() {
        picker?.dismiss()
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_BLUETOOTH) return
        if (hasBluetoothPermissions()) {
            BadgeNotificationListener.instance?.syncNow()
            if (pickAfterPermission) chooseWatch()
        } else {
            Toast.makeText(this, R.string.bluetooth_denied, Toast.LENGTH_LONG).show()
        }
        pickAfterPermission = false
        render()
    }

    private fun bindSwitch(id: Int, initial: Boolean, onChange: (Boolean) -> Unit) {
        findViewById<Switch>(id).apply {
            isChecked = initial
            setOnCheckedChangeListener { _, checked -> onChange(checked) }
        }
    }

    private fun render() {
        val listenerOk = hasListenerAccess()
        grantListener.visibility = if (listenerOk) View.GONE else View.VISIBLE
        grantBluetooth.visibility = if (hasBluetoothPermissions()) View.GONE else View.VISIBLE

        countView.text = Status.count.toString()
        countLabel.text = resources.getQuantityString(
            if (prefs.countApps) R.plurals.count_apps else R.plurals.count_notifications,
            Status.count
        )

        val lines = mutableListOf<String>()
        lines += getString(if (listenerOk) R.string.status_listener_ok else R.string.status_listener_missing)
        val address = prefs.watchAddress
        lines += if (address == null) {
            getString(R.string.status_watch_none)
        } else {
            getString(R.string.status_watch, prefs.watchName ?: getString(R.string.default_watch_name), address)
        }
        lines += when {
            !Status.listenerConnected -> getString(R.string.link_service_stopped)
            Status.link == LinkState.UNREACHABLE ->
                getString(R.string.link_unreachable, formatTime(Status.nextAttemptMillis))
            else -> getString(linkText(Status.link))
        }
        Status.shownOnWatch?.let {
            lines += getString(R.string.status_shown, shownText(it), formatTime(Status.lastSyncMillis))
        }
        Status.lastError?.let { lines += getString(R.string.status_error, it) }
        statusView.text = lines.joinToString("\n")

        findViewById<TextView>(R.id.markers_summary).text = Markers.parse(prefs.markers)
            .joinToString(", ") { "${it.name} → ${it.cell}" }
            .ifEmpty { getString(R.string.markers_none) }
    }

    private fun linkText(state: LinkState) = when (state) {
        LinkState.NO_WATCH -> R.string.link_no_watch
        LinkState.DISABLED -> R.string.link_disabled
        LinkState.NO_PERMISSION -> R.string.link_no_permission
        LinkState.BLUETOOTH_OFF -> R.string.link_bluetooth_off
        LinkState.IDLE -> R.string.link_idle
        LinkState.SYNCING -> R.string.link_syncing
        LinkState.BUSY -> R.string.link_busy
        LinkState.UNREACHABLE -> R.string.link_unreachable_plain
    }

    private fun formatTime(millis: Long): String = DateFormat.getTimeFormat(this).format(Date(millis))

    private fun shownText(shown: String) =
        if (shown.isEmpty()) getString(R.string.shown_weekday) else shown.trim()

    private fun hasListenerAccess() =
        getSystemService(NotificationManager::class.java).isNotificationListenerAccessGranted(listenerComponent)

    private fun hasBluetoothPermissions() =
        bluetoothPermissions.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    private fun rebindListener() {
        if (hasListenerAccess()) NotificationListenerService.requestRebind(listenerComponent)
    }

    private fun openListenerSettings() {
        val detail = Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
            .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, listenerComponent.flattenToString())
        try {
            startActivity(detail)
        } catch (_: ActivityNotFoundException) {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
    }

    private fun chooseWatch() {
        if (!hasBluetoothPermissions()) {
            pickAfterPermission = true
            requestPermissions(bluetoothPermissions, REQUEST_BLUETOOTH)
            return
        }
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null) {
            Toast.makeText(this, R.string.no_bluetooth, Toast.LENGTH_LONG).show()
            return
        }
        if (!adapter.isEnabled) {
            try {
                startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            } catch (_: SecurityException) {
                Toast.makeText(this, R.string.bluetooth_denied, Toast.LENGTH_LONG).show()
            }
            return
        }
        picker?.dismiss()
        picker = WatchPicker(this) { address, name ->
            // Name first: the listener reconnects as soon as the address changes.
            prefs.watchName = name
            prefs.watchAddress = address
            render()
        }.also { it.show() }
    }

    private fun showExcludeDialog() {
        val apps = (prefs.seenApps + prefs.excludedApps)
            .map { it to appLabel(it) }
            .sortedBy { it.second.lowercase() }
        if (apps.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle(R.string.exclude_title)
                .setMessage(R.string.exclude_empty)
                .setPositiveButton(android.R.string.ok, null)
                .show()
            return
        }
        val excluded = prefs.excludedApps.toMutableSet()
        val checked = BooleanArray(apps.size) { apps[it].first in excluded }
        AlertDialog.Builder(this)
            .setTitle(R.string.exclude_title)
            .setMultiChoiceItems(apps.map { it.second }.toTypedArray(), checked) { _, which, isChecked ->
                if (isChecked) excluded += apps[which].first else excluded -= apps[which].first
            }
            .setPositiveButton(R.string.done) { _, _ -> prefs.excludedApps = excluded }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showMarkersDialog() {
        val padding = (20 * resources.displayMetrics.density).toInt()
        val input = EditText(this).apply {
            id = R.id.markers_input
            setText(prefs.markers)
            setHint(R.string.markers_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            minLines = 4
            gravity = Gravity.TOP or Gravity.START
            typeface = Typeface.MONOSPACE
        }
        val container = FrameLayout(this).apply {
            setPadding(padding, padding / 2, padding, 0)
            addView(input)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.markers_title)
            .setMessage(R.string.markers_message)
            .setView(container)
            .setPositiveButton(R.string.save) { _, _ ->
                prefs.markers = input.text.toString().trim()
                render()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun appLabel(packageName: String): String = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(packageName, 0)).toString()
    } catch (_: PackageManager.NameNotFoundException) {
        packageName
    }

    private fun runTest() {
        val listener = BadgeNotificationListener.instance
        if (listener == null) {
            Toast.makeText(this, R.string.test_no_listener, Toast.LENGTH_LONG).show()
            return
        }
        listener.showTest(WeekdayRegister.badgeCell(TEST_COUNT))
        Toast.makeText(this, getString(R.string.test_running, TEST_COUNT), Toast.LENGTH_LONG).show()
    }

    private companion object {
        const val REQUEST_BLUETOOTH = 1
        const val TEST_COUNT = 8
    }
}
