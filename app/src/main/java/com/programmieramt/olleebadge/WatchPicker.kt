package com.programmieramt.olleebadge

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView

/** Dialog that scans for Ollee watches and reports the one the user taps. */
@SuppressLint("MissingPermission") // only shown once BLUETOOTH_SCAN and BLUETOOTH_CONNECT are granted
class WatchPicker(
    private val activity: Activity,
    private val onPicked: (address: String, name: String) -> Unit
) {
    private val manager = activity.getSystemService(BluetoothManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    /** address -> name, in the order found */
    private val found = LinkedHashMap<String, String>()
    private val rows = mutableListOf<String>()
    private lateinit var rowAdapter: ArrayAdapter<String>
    private lateinit var hintView: TextView
    private var dialog: AlertDialog? = null
    private var scanning = false

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) = onResult(result)

        override fun onBatchScanResults(results: MutableList<ScanResult>) = results.forEach(::onResult)

        override fun onScanFailed(errorCode: Int) {
            scanning = false
            hintView.text = activity.getString(R.string.picker_failed, errorCode)
        }
    }

    fun show() {
        val padding = (20 * activity.resources.displayMetrics.density).toInt()
        hintView = TextView(activity).apply {
            setText(R.string.picker_hint)
            setPadding(padding, padding / 2, padding, padding / 2)
        }
        rowAdapter = ArrayAdapter(activity, android.R.layout.simple_list_item_1, rows)
        val list = ListView(activity).apply {
            adapter = rowAdapter
            setOnItemClickListener { _, _, position, _ ->
                val address = found.keys.elementAt(position)
                onPicked(address, found.getValue(address))
                dismiss()
            }
        }
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(hintView)
            addView(list)
        }
        dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.picker_title)
            .setView(content)
            .setNegativeButton(android.R.string.cancel, null)
            .setOnDismissListener { stopScan() }
            .show()

        // A watch already connected by another app (e.g. the Ollee app) no longer advertises.
        manager.getConnectedDevices(BluetoothProfile.GATT)
            .filter { isOlleeName(it.name) }
            .forEach { add(it.address, it.name) }

        val scanner = manager.adapter?.bluetoothLeScanner ?: return
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanner.startScan(null, settings, scanCallback)
        scanning = true
        handler.postDelayed(::stopScan, SCAN_DURATION_MS)
    }

    fun dismiss() {
        stopScan()
        dialog?.dismiss()
        dialog = null
    }

    private fun onResult(result: ScanResult) {
        val name = result.scanRecord?.deviceName ?: result.device.name
        val advertisesNus = result.scanRecord?.serviceUuids
            ?.contains(ParcelUuid(OlleeProtocol.SERVICE_NUS)) == true
        if (isOlleeName(name) || advertisesNus) {
            add(result.device.address, name ?: activity.getString(R.string.default_watch_name))
        }
    }

    private fun add(address: String, name: String) {
        if (found.containsKey(address)) return
        found[address] = name
        rows += "$name\n$address"
        rowAdapter.notifyDataSetChanged()
    }

    private fun stopScan() {
        handler.removeCallbacksAndMessages(null)
        if (!scanning) return
        scanning = false
        try {
            manager.adapter?.bluetoothLeScanner?.stopScan(scanCallback)
        } catch (_: IllegalStateException) { // Bluetooth turned off meanwhile
        }
        if (found.isEmpty()) hintView.setText(R.string.picker_nothing_found)
    }

    private fun isOlleeName(name: String?) = name?.startsWith("Ollee", ignoreCase = true) == true

    private companion object {
        const val SCAN_DURATION_MS = 20_000L
    }
}
