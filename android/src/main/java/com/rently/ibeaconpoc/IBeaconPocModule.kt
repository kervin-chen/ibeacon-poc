package com.rently.ibeaconpoc

import com.facebook.react.bridge.*
import com.facebook.react.modules.core.DeviceEventManagerModule
import org.altbeacon.beacon.*
import android.os.Build
import android.content.pm.PackageManager

class IBeaconPocModule(private val reactContext: ReactApplicationContext): ReactContextBaseJavaModule(reactContext), BeaconConsumer, RangeNotifier {
    private val beaconManager: BeaconManager by lazy { BeaconManager.getInstanceForApplication(reactContext) }
    private var isScanning = false
    private var region: Region? = null
    private var lastSeen: List<Beacon> = emptyList()

    override fun getName(): String = "IBeaconPoc"

    override fun initialize() {
        super.initialize()
        // iBeacon layout default already included from 2.20+, ensure parser
        if (beaconManager.beaconParsers.none { it.layout.contains("ibeacon") }) {
            val iBeaconParser = BeaconParser().setBeaconLayout("m:2-3=\x02\x15,i:4-19,i:20-21,i:22-23,p:24-24")
            beaconManager.beaconParsers.add(iBeaconParser)
        }
    }

    @ReactMethod
    fun startScanning(options: ReadableMap?, promise: Promise) {
        // Request runtime permissions (Android 12+ requires BLUETOOTH_SCAN/CONNECT + location)
        if (!hasAllPermissions()) {
            requestPermissions()
        }
        if (isScanning) { promise.resolve(null); return }
        val uuids = options?.getArray("uuids")?.toArrayList()?.map { it.toString() }
        region = if (!uuids.isNullOrEmpty()) {
            // Only support first UUID for simple POC; extend later
            Region("IBeaconPocRegion", Identifier.parse(uuids.first()), null, null)
        } else {
            Region("IBeaconPocRegion", null, null, null)
        }
        val scanPeriod = options?.getInt("androidScanPeriodMs") ?: 1100
        val betweenScanPeriod = options?.getInt("androidBetweenScanPeriodMs") ?: 0
        beaconManager.foregroundScanPeriod = scanPeriod.toLong()
        beaconManager.foregroundBetweenScanPeriod = betweenScanPeriod.toLong()
        try {
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
            region?.let { beaconManager.startRangingBeacons(it) }
        } catch (e: Exception) {
            // ignore for POC
        }
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
    override fun unbindService(p0: ServiceConnection?) { reactContext.unbindService(p0!!) }
    override fun bindService(intent: android.content.Intent?, serviceConnection: ServiceConnection?, mode: Int): Boolean {
        return reactContext.bindService(intent, serviceConnection!!, mode)
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
}
