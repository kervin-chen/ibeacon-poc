package com.rently.ibeaconpoc

import com.facebook.react.bridge.*
import com.facebook.react.modules.core.DeviceEventManagerModule
import org.altbeacon.beacon.*
import android.os.Build
import android.content.pm.PackageManager
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection

class IBeaconPocModule(private val reactContext: ReactApplicationContext): ReactContextBaseJavaModule(reactContext), BeaconConsumer, RangeNotifier {
    private val beaconManager: BeaconManager by lazy { BeaconManager.getInstanceForApplication(reactContext) }
    private var isScanning = false
    private var region: Region? = null
    private var lastSeen: List<Beacon> = emptyList()

    override fun getName(): String = "IBeaconPoc"

    override fun initialize() {
        super.initialize()
        // iBeacon layout default already included from 2.20+, ensure parser
        if (beaconManager.beaconParsers.none { it.layout.contains("m:2-3=0215") }) {
            val iBeaconParser = BeaconParser().setBeaconLayout("m:2-3=0215,i:4-19,i:20-21,i:22-23,p:24-24")
            beaconManager.beaconParsers.add(iBeaconParser)
        }
    }

    @ReactMethod
    fun startScanning(options: ReadableMap?, promise: Promise) {
        // Request runtime permissions (Android 12+ requires BLUETOOTH_SCAN/CONNECT + location)
        if (!hasAllPermissions()) {
            requestPermissions() // host app should actually request and then retry
        }
        if (isScanning) { promise.resolve(null); return }
        val uuids = options?.getArray("uuids")?.toArrayList()?.map { it.toString() }
        region = if (!uuids.isNullOrEmpty()) {
            Region("IBeaconPocRegion", Identifier.parse(uuids.first()), null, null)
        } else {
            Region("IBeaconPocRegion", null, null, null)
        }
        val scanPeriod = safeGetInt(options, "androidScanPeriodMs", 1100)
        val betweenScanPeriod = safeGetInt(options, "androidBetweenScanPeriodMs", 0)
        try {
            beaconManager.foregroundScanPeriod = scanPeriod.toLong()
            beaconManager.foregroundBetweenScanPeriod = betweenScanPeriod.toLong()
            // Disable scheduled jobs for faster foreground ranging in a library context
            beaconManager.setEnableScheduledScanJobs(false)
            beaconManager.setBackgroundMode(false)
            beaconManager.addRangeNotifier(this)
            beaconManager.bind(this)
            isScanning = true
            promise.resolve(null)
        } catch (e: Exception) {
            promise.reject("start_error", e)
        }
    }

    @ReactMethod
    fun stopScanning(promise: Promise) {
        if (!isScanning) { promise.resolve(null); return }
        try {
            beaconManager.removeRangeNotifier(this)
            beaconManager.unbind(this)
            isScanning = false
            promise.resolve(null)
        } catch (e: Exception) {
            promise.reject("stop_error", e)
        }
    }

    @ReactMethod
    fun isScanning(promise: Promise) { promise.resolve(isScanning) }

    @ReactMethod
    fun getLastSeen(promise: Promise) {
        val arr = Arguments.createArray()
        lastSeen.forEach { b ->
            val map = Arguments.createMap()
            map.putString("uuid", b.id1.toString())
            map.putInt("major", b.id2.toInt())
            map.putInt("minor", b.id3.toInt())
            map.putInt("rssi", b.rssi)
            map.putDouble("distance", b.distance)
            arr.pushMap(map)
        }
        promise.resolve(arr)
    }

    override fun onBeaconServiceConnect() {
        try {
            region?.let { beaconManager.startRangingBeaconsInRegion(it) }
        } catch (_: Exception) { }
    }

    override fun didRangeBeaconsInRegion(beacons: Collection<Beacon>, region: Region) {
        lastSeen = beacons.toList()
        val arr = Arguments.createArray()
        beacons.forEach { b ->
            val map = Arguments.createMap()
            map.putString("uuid", b.id1.toString())
            map.putInt("major", b.id2.toInt())
            map.putInt("minor", b.id3.toInt())
            map.putInt("rssi", b.rssi)
            map.putDouble("distance", b.distance)
            arr.pushMap(map)
        }
        sendEvent("beaconsUpdated", arr)
    }

    private fun sendEvent(event: String, params: WritableArray) {
        reactContext.getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
            .emit(event, params)
    }

    override fun getApplicationContext(): Context = reactContext.applicationContext
    override fun bindService(intent: Intent, serviceConnection: ServiceConnection, mode: Int): Boolean {
        return reactContext.bindService(intent, serviceConnection, mode)
    }
    override fun unbindService(serviceConnection: ServiceConnection) {
        reactContext.unbindService(serviceConnection)
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

    private fun requestPermissions() {
        // Leave to hosting app; cannot show UI here. POC placeholder.
    }

    private fun safeGetInt(map: ReadableMap?, key: String, fallback: Int): Int {
        return try {
            if (map != null && map.hasKey(key) && !map.isNull(key)) map.getInt(key) else fallback
        } catch (_: Exception) { fallback }
    }
}
