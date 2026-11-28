package tw.geowifi.launcher.monitor

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.LocationManager
import android.net.wifi.ScanResult
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import tw.geowifi.launcher.R
import tw.geowifi.launcher.core.wifi.RawAccessPoint
import tw.geowifi.launcher.core.wifi.WifiCatalog
import tw.geowifi.launcher.core.wifi.WifiIds
import tw.geowifi.launcher.core.wifi.WifiSight
import kotlin.coroutines.resume

data class WifiScanDump(
    val points: List<RawAccessPoint>,
    val rawCount: Int,
    val newestAgeSeconds: Long?,
)

object WifiSnapshots {
    fun scanDump(context: Context): WifiScanDump {
        val manager = context.applicationContext.getSystemService(WifiManager::class.java)
        val results = try {
            manager.scanResults.orEmpty()
        } catch (_: SecurityException) {
            emptyList()
        }
        val connected = connectedPoint(context, manager)
        val points = ArrayList<RawAccessPoint>(results.size + 1)
        for (result in results) {
            points += RawAccessPoint(
                ssid = ssidOf(result),
                bssid = result.BSSID,
                rssiDbm = result.level,
                frequencyMhz = result.frequency,
                connected = false,
            )
        }
        if (connected != null) points += connected
        val newest = results.maxOfOrNull { it.timestamp } ?: 0L
        val age = if (newest <= 0L) {
            null
        } else {
            val nowMicros = SystemClock.elapsedRealtime() * 1000L
            ((nowMicros - newest) / 1_000_000L).coerceAtLeast(0L)
        }
        return WifiScanDump(points, results.size, age)
    }

    fun sights(context: Context): List<WifiSight> {
        return scanDump(context).points.mapNotNull { point ->
            val ssid = WifiIds.normalizeSsid(point.ssid) ?: return@mapNotNull null
            WifiSight(ssid, point.bssid, point.rssiDbm)
        }
    }

    fun connectedSight(context: Context): WifiSight? {
        val manager = context.applicationContext.getSystemService(WifiManager::class.java)
        val point = connectedPoint(context, manager) ?: return null
        val ssid = WifiIds.normalizeSsid(point.ssid) ?: return null
        return WifiSight(ssid, point.bssid, point.rssiDbm)
    }

    suspend fun awaitScan(context: Context, timeoutMs: Long): Boolean {
        val appContext = context.applicationContext
        val updated = withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { continuation ->
                val receiver = object : BroadcastReceiver() {
                    override fun onReceive(receiverContext: Context, intent: Intent) {
                        unregister(appContext, this)
                        val fresh = intent.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, false)
                        if (continuation.isActive) continuation.resume(fresh)
                    }
                }
                val filter = IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
                if (Build.VERSION.SDK_INT >= 33) {
                    appContext.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
                } else {
                    appContext.registerReceiver(receiver, filter)
                }
                continuation.invokeOnCancellation { unregister(appContext, receiver) }
            }
        }
        return updated == true
    }

    fun requestScan(context: Context): Boolean {
        if (!Permissions.fine(context)) return false
        val manager = context.applicationContext.getSystemService(WifiManager::class.java)
        return try {
            @Suppress("DEPRECATION")
            manager.startScan()
        } catch (_: SecurityException) {
            false
        }
    }

    private fun connectedPoint(context: Context, manager: WifiManager): RawAccessPoint? {
        val info = connectedInfo(context) ?: run {
            @Suppress("DEPRECATION")
            manager.connectionInfo
        } ?: return null
        val ssid = ssidOf(info) ?: return null
        val frequency = if (Build.VERSION.SDK_INT >= 21) info.frequency else 0
        return RawAccessPoint(ssid, info.bssid, info.rssi, frequency, connected = true)
    }

    private fun connectedInfo(context: Context): WifiInfo? {
        if (Build.VERSION.SDK_INT < 29) return null
        val connectivity = context.applicationContext.getSystemService(android.net.ConnectivityManager::class.java)
        for (network in connectivity.allNetworks) {
            val caps = connectivity.getNetworkCapabilities(network) ?: continue
            if (!caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)) continue
            val info = caps.transportInfo as? WifiInfo ?: continue
            if (ssidOf(info) != null) return info
        }
        return null
    }

    private fun ssidOf(result: ScanResult): String? {
        if (Build.VERSION.SDK_INT >= 33) {
            WifiIds.normalizeSsid(result.wifiSsid?.toString())?.let { return it }
        }
        @Suppress("DEPRECATION")
        return WifiIds.normalizeSsid(result.SSID)
    }

    private fun ssidOf(info: WifiInfo): String? {
        @Suppress("DEPRECATION")
        return WifiIds.normalizeSsid(info.ssid)
    }

    private fun unregister(context: Context, receiver: BroadcastReceiver) {
        try {
            context.unregisterReceiver(receiver)
        } catch (_: IllegalArgumentException) {
            // Already unregistered.
        }
    }
}

object WifiSurveyor {
    data class Outcome(
        val networks: List<tw.geowifi.launcher.core.wifi.ListedWifi>,
        val status: String,
        val detail: String,
    )

    suspend fun refresh(context: Context): Outcome {
        val accepted = WifiSnapshots.requestScan(context)
        val updated = if (accepted) WifiSnapshots.awaitScan(context, 8_000) else false
        val dump = WifiSnapshots.scanDump(context)
        val snapshot = WifiCatalog.summarize(dump.points)
        val manager = context.applicationContext.getSystemService(WifiManager::class.java)
        val locationManager = context.applicationContext.getSystemService(LocationManager::class.java)
        val locationOn = if (Build.VERSION.SDK_INT >= 28) {
            locationManager.isLocationEnabled
        } else {
            locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        }
        val scanAlways = Settings.Global.getInt(
            context.contentResolver,
            "wifi_scan_always_enabled",
            0,
        ) == 1
        val yes = context.getString(R.string.editor_yes)
        val no = context.getString(R.string.editor_no)
        fun flag(value: Boolean) = if (value) yes else no
        val detail = context.getString(
            R.string.editor_wifi_diag,
            flag(Permissions.fine(context)),
            flag(Permissions.nearbyWifi(context)),
            flag(locationOn),
            flag(manager.isWifiEnabled),
            flag(scanAlways),
            dump.rawCount,
            snapshot.networks.size,
            snapshot.hiddenCount,
        )
        val age = dump.newestAgeSeconds?.let { context.getString(R.string.editor_wifi_age, it) }.orEmpty()
        val status = when {
            !Permissions.fine(context) -> context.getString(R.string.editor_signal_need_permission)
            !locationOn -> context.getString(R.string.editor_wifi_location_off)
            accepted && updated -> context.getString(R.string.editor_scan_updated) + age
            accepted -> context.getString(R.string.editor_scan_cached) + age
            else -> context.getString(R.string.editor_scan_throttled) + age
        }
        return Outcome(snapshot.networks, status, detail)
    }
}
