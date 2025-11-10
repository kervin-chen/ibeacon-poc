package com.rently.ibeaconpoc

import com.facebook.react.bridge.*
import com.facebook.react.modules.core.DeviceEventManagerModule
import android.bluetooth.le.*
import android.bluetooth.BluetoothAdapter
import android.os.ParcelUuid
import android.os.Handler
import android.os.Looper
import android.content.pm.PackageManager
import android.os.Build

// Optional module: raw BLE scanner capable of receiving extended advertising to extract iBeacon manufacturer data.
// This does NOT integrate with AltBeacon library; can be removed without affecting main module.
// iOS parity is not possible (extended adv inaccessible); this module is Android-only.

class IBeaconExtendedScannerModule(private val reactContext: ReactApplicationContext): ReactContextBaseJavaModule(reactContext) {
    private var scanning = false
    private var scanner: BluetoothLeScanner? = null
    private var callback: ScanCallback? = null
    private val handler = Handler(Looper.getMainLooper())
    private var lastSeen: MutableMap<String, WritableMap> = mutableMapOf()

    override fun getName(): String = "IBeaconExtendedScanner"

    @ReactMethod
    fun start(options: ReadableMap?, promise: Promise) {
        if (scanning) { promise.resolve(null); return }
        if (!hasAllPermissions()) { promise.reject("perm", "Missing BLE permissions"); return }
        val adapter = BluetoothAdapter.getDefaultAdapter()
        if (adapter == null || !adapter.isEnabled) { promise.reject("ble_off", "Bluetooth disabled"); return }
        scanner = adapter.bluetoothLeScanner
        if (scanner == null) { promise.reject("scanner_null", "No scanner available"); return }
        val legacy = options?.getBoolean("legacyOnly") ?: false
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setLegacy(legacy)
            .build()
        // Filters optional; if UUID filter provided we can set manufacturer data prefix
        val filters = mutableListOf<ScanFilter>()
        callback = object: ScanCallback() {
            override fun onScanResult(type: Int, result: ScanResult) {
                parseResult(result)
            }
            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                results.forEach { parseResult(it) }
            }
            override fun onScanFailed(errorCode: Int) {
                sendError("scan_failed", errorCode.toString())
            }
        }
        try {
            scanner!!.startScan(filters, settings, callback!!)
            scanning = true
            promise.resolve(null)
        } catch (e: Exception) {
            promise.reject("start_error", e)
        }
    }

    @ReactMethod
    fun stop(promise: Promise) {
        if (!scanning) { promise.resolve(null); return }
        try {
            scanner?.stopScan(callback!!)
        } catch (_: Exception) {}
        scanning = false
        promise.resolve(null)
    }

    @ReactMethod
    fun isScanning(promise: Promise) { promise.resolve(scanning) }

    @ReactMethod
    fun getLastSeen(promise: Promise) {
        val arr = Arguments.createArray()
        lastSeen.values.forEach { arr.pushMap(it) }
        promise.resolve(arr)
    }

    @ReactMethod
    fun addListener(eventName: String) { /* RN requires stub */ }

    @ReactMethod
    fun removeListeners(count: Int) { /* RN requires stub */ }

    private fun parseResult(result: ScanResult) {
        val record = result.scanRecord ?: return
        // Manufacturer data for Apple (0x004C little endian 4C 00)
        val data = record.getManufacturerSpecificData(0x004C) ?: return
        // Expect first two bytes 0x02 0x15 for iBeacon
        if (data.size < 23 || data[0] != 0x02.toByte() || data[1] != 0x15.toByte()) return
        try {
            val uuidBytes = data.copyOfRange(2, 18)
            val majorBytes = data.copyOfRange(18, 20)
            val minorBytes = data.copyOfRange(20, 22)
            val txPower = data[22].toInt() // signed
            val uuid = bytesToUuid(uuidBytes)
            val major = ((majorBytes[0].toInt() and 0xFF) shl 8) + (majorBytes[1].toInt() and 0xFF)
            val minor = ((minorBytes[0].toInt() and 0xFF) shl 8) + (minorBytes[1].toInt() and 0xFF)
            val rssi = result.rssi
            val distance = estimateDistance(rssi, txPower)
            val key = "$uuid-$major-$minor"
            val map = Arguments.createMap()
            map.putString("uuid", uuid)
            map.putInt("major", major)
            map.putInt("minor", minor)
            map.putInt("rssi", rssi)
            map.putInt("txPower", txPower)
            map.putDouble("distance", distance)
            lastSeen[key] = map
            // Throttle events to avoid spamming: post aggregated every 500ms
            scheduleEmit()
        } catch (_: Exception) {}
    }

    private var emitScheduled = false
    private fun scheduleEmit() {
        if (emitScheduled) return
        emitScheduled = true
        handler.postDelayed({
            emitScheduled = false
            val arr = Arguments.createArray()
            lastSeen.values.forEach { arr.pushMap(it) }
            sendEvent("extendedBeaconsUpdated", arr)
        }, 500)
    }

    private fun sendEvent(event: String, params: WritableArray) {
        reactContext.getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
            .emit(event, params)
    }
    private fun sendError(code: String, msg: String) {
        val arr = Arguments.createArray()
        val map = Arguments.createMap(); map.putString("code", code); map.putString("message", msg); arr.pushMap(map)
        sendEvent("extendedScannerError", arr)
    }

    private fun estimateDistance(rssi: Int, txPower: Int, n: Double = 2.0): Double {
        return Math.pow(10.0, ((txPower - rssi).toDouble()) / (10.0 * n))
    }

    private fun bytesToUuid(bytes: ByteArray): String {
        // Convert 16 bytes to UUID string (8-4-4-4-12)
        return String.format(
            "%02x%02x%02x%02x-%02x%02x-%02x%02x-%02x%02x-%02x%02x%02x%02x%02x%02x",
            bytes[0], bytes[1], bytes[2], bytes[3],
            bytes[4], bytes[5],
            bytes[6], bytes[7],
            bytes[8], bytes[9],
            bytes[10], bytes[11], bytes[12], bytes[13], bytes[14], bytes[15]
        ).lowercase()
    }

    private fun hasAllPermissions(): Boolean {
        val needed = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            needed += listOf("android.permission.BLUETOOTH_SCAN", "android.permission.BLUETOOTH_CONNECT")
        } else {
            needed += listOf("android.permission.BLUETOOTH", "android.permission.BLUETOOTH_ADMIN")
        }
        needed += listOf("android.permission.ACCESS_FINE_LOCATION")
        return needed.all { reactContext.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
    }
}
