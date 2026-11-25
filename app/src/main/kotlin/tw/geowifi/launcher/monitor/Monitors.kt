package tw.geowifi.launcher.monitor

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.os.Looper
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingClient
import com.google.android.gms.location.GeofencingEvent
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import tw.geowifi.launcher.GeoWifiApp
import tw.geowifi.launcher.R
import tw.geowifi.launcher.core.rule.Action
import tw.geowifi.launcher.core.rule.ChargeType
import tw.geowifi.launcher.core.rule.ChargingSnapshot
import tw.geowifi.launcher.core.rule.FireReason
import tw.geowifi.launcher.core.rule.GeofenceSignal
import tw.geowifi.launcher.core.rule.Inputs
import tw.geowifi.launcher.core.rule.LocationSample
import tw.geowifi.launcher.core.rule.RegionAtom
import tw.geowifi.launcher.core.rule.RuleEngine
import tw.geowifi.launcher.core.rule.WifiInputs
import tw.geowifi.launcher.core.schedule.TimeWindows
import tw.geowifi.launcher.core.wifi.WifiIds
import tw.geowifi.launcher.core.wifi.WifiSight
import tw.geowifi.launcher.data.RuleRecord
import tw.geowifi.launcher.data.RuleRepository

class RuleEvaluator(
    private val context: Context,
    private val repository: RuleRepository,
    private val hub: SensorHub,
    private val ensureRunning: () -> Unit,
    private val reschedule: suspend () -> Unit,
) {
    private val mutex = Mutex()

    suspend fun refresh() = mutex.withLock { run(emptyMap()) }

    suspend fun onGeofences(fixes: Map<Long, Map<String, GeofenceSignal>>) = mutex.withLock { run(fixes) }

    private suspend fun run(fixes: Map<Long, Map<String, GeofenceSignal>>) {
        val rules = repository.enabled()
        if (rules.isEmpty()) return
        val now = System.currentTimeMillis()
        val local = LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(now), ZoneId.systemDefault())
        val base = hub.inputs(now)
        for (rule in rules) {
            val inputs = base.copy(fixes = fixes[rule.id] ?: emptyMap())
            val result = RuleEngine.evaluate(rule.toSpec(), rule.memory, inputs, local)
            val firedAt = if (result.action is Action.Fire) now else rule.lastTriggeredAt
            repository.saveMemory(rule.id, result.memory, firedAt)
            val action = result.action
            if (action is Action.Fire) {
                recordLaunch(rule, action.reason)
            }
        }
        reschedule()
        ensureRunning()
    }

    private suspend fun recordLaunch(rule: RuleRecord, reason: FireReason) {
        val summary = when (reason) {
            FireReason.CONDITIONS_MET -> context.getString(R.string.fire_conditions)
            FireReason.WINDOW_OPENED -> context.getString(R.string.fire_window)
            FireReason.LEFT_REGION -> context.getString(R.string.fire_exit)
        }
        val outcome = AppLauncher.launch(context, rule.id, rule.targetPackage, rule.targetLabel)
        val detail = when (outcome) {
            AppLauncher.Outcome.Opened -> context.getString(R.string.launch_ok, rule.targetLabel)
            AppLauncher.Outcome.NeedsTap -> context.getString(R.string.launch_fallback)
            is AppLauncher.Outcome.Failed -> outcome.message
        }
        repository.log(rule.id, rule.name, summary, detail, success = outcome is AppLauncher.Outcome.Opened)
    }
}

class MonitorController(
    private val context: Context,
    private val repository: RuleRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun sync() {
        scope.launch { syncNow() }
    }

    fun ensureRunning() {
        if (MonitorService.running.get()) return
        scope.launch { syncNow() }
    }

    suspend fun syncNow() {
        val rules = repository.enabled()
        if (rules.isEmpty() || !Permissions.canMonitor(context)) {
            context.stopService(Intent(context, MonitorService::class.java))
            geofences().removeGeofences(geofencePending()).addOnCompleteListener { }
            alarmManager().cancel(alarmPending())
            return
        }
        if (!MonitorService.running.get()) {
            ContextCompat.startForegroundService(context, Intent(context, MonitorService::class.java))
        }
        registerGeofences(rules)
        schedule(rules)
    }

    suspend fun scheduleOnly() {
        schedule(repository.enabled())
    }

    @SuppressLint("MissingPermission")
    private fun registerGeofences(rules: List<RuleRecord>) {
        val client = geofences()
        val pending = geofencePending()
        if (!playServices() || !Permissions.fine(context)) {
            client.removeGeofences(pending)
            return
        }
        val fences = rules.flatMap { rule ->
            rule.clauses.flatMap { it.options }.filterIsInstance<RegionAtom>().mapNotNull { atom ->
                if (atom.radiusMeters < 1.0) return@mapNotNull null
                Geofence.Builder()
                    .setRequestId("${rule.id}:${atom.id}")
                    .setCircularRegion(atom.latitude, atom.longitude, atom.radiusMeters.toFloat())
                    .setExpirationDuration(Geofence.NEVER_EXPIRE)
                    .setTransitionTypes(
                        Geofence.GEOFENCE_TRANSITION_ENTER or
                            Geofence.GEOFENCE_TRANSITION_EXIT or
                            Geofence.GEOFENCE_TRANSITION_DWELL,
                    )
                    .setLoiteringDelay(atom.dwellMillis.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt())
                    .setNotificationResponsiveness(5_000)
                    .build()
            }
        }
        client.removeGeofences(pending).addOnCompleteListener {
            if (fences.isEmpty()) return@addOnCompleteListener
            val request = GeofencingRequest.Builder()
                .setInitialTrigger(
                    GeofencingRequest.INITIAL_TRIGGER_ENTER or
                        GeofencingRequest.INITIAL_TRIGGER_EXIT or
                        GeofencingRequest.INITIAL_TRIGGER_DWELL,
                )
                .addGeofences(fences)
                .build()
            client.addGeofences(request, pending)
        }
    }

    private fun schedule(rules: List<RuleRecord>) {
        val alarm = alarmManager()
        val pending = alarmPending()
        val now = LocalDateTime.now()
        val next = rules.mapNotNull { TimeWindows.nextBoundary(it.schedule, now) }.minOrNull()
        if (next == null) {
            alarm.cancel(pending)
            return
        }
        val trigger = next.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        try {
            if (Permissions.exactAlarm(context)) {
                alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pending)
            } else {
                alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pending)
            }
        } catch (_: SecurityException) {
            alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pending)
        }
    }

    private fun geofences(): GeofencingClient = LocationServices.getGeofencingClient(context)

    private fun playServices(): Boolean {
        return GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS
    }

    private fun alarmManager(): AlarmManager = context.getSystemService(AlarmManager::class.java)

    private fun geofencePending(): PendingIntent {
        return PendingIntent.getBroadcast(
            context,
            7,
            Intent(context, GeofenceReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
    }

    private fun alarmPending(): PendingIntent {
        return PendingIntent.getBroadcast(
            context,
            8,
            Intent(context, WindowAlarmReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}

class MonitorService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var ticker: Job? = null
    private var callback: ConnectivityManager.NetworkCallback? = null
    private var locationListener: LocationListener? = null
    private val bluetoothAddresses = LinkedHashSet<String>()
    private var scanFresh = false

    private val scanReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            scanFresh = intent.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, false)
            publishWifi()
        }
    }
    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            hub().setCharging(BatteryReader.snapshot(intent))
            refresh()
        }
    }
    private val bluetoothReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val device = if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
            }
            val address = device?.address ?: return
            if (intent.action == BluetoothDevice.ACTION_ACL_CONNECTED) bluetoothAddresses += address
            if (intent.action == BluetoothDevice.ACTION_ACL_DISCONNECTED) bluetoothAddresses -= address
            hub().setBluetooth(bluetoothAddresses.toSet())
            refresh()
        }
    }
    private val clockReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            scope.launch { container().monitor.scheduleOnly() }
            refresh()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running.set(true)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Notifications.ensureChannels(this)
        val notification = Notifications.monitoring(this)
        val types = foregroundTypes()
        try {
            ServiceCompat.startForeground(this, Notifications.monitorId, notification, types)
        } catch (_: SecurityException) {
            try {
                ServiceCompat.startForeground(
                    this,
                    Notifications.monitorId,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
                )
            } catch (_: RuntimeException) {
                stopSelf()
                return START_NOT_STICKY
            }
        }
        if (ticker == null) {
            registerSensors()
            ticker = scope.launch { loop() }
        }
        refresh()
        return START_STICKY
    }

    override fun onDestroy() {
        running.set(false)
        ticker?.cancel()
        unregisterQuietly(scanReceiver)
        unregisterQuietly(batteryReceiver)
        unregisterQuietly(bluetoothReceiver)
        unregisterQuietly(clockReceiver)
        callback?.let { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it) }
        locationListener?.let { getSystemService(LocationManager::class.java).removeUpdates(it) }
        super.onDestroy()
    }

    private fun registerSensors() {
        register(scanReceiver, IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION), exported = true)
        register(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED), exported = true)
        register(
            bluetoothReceiver,
            IntentFilter().apply {
                addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
                addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            },
            exported = true,
        )
        register(
            clockReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_TIME_CHANGED)
                addAction(Intent.ACTION_TIMEZONE_CHANGED)
            },
            exported = true,
        )
        val sticky = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        if (sticky != null) hub().setCharging(BatteryReader.snapshot(sticky))
        bluetoothAddresses += BluetoothReader.connected(this)
        hub().setBluetooth(bluetoothAddresses.toSet())
        val request = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build()
        callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                publishWifi()
            }

            override fun onLost(network: Network) {
                publishWifi()
            }
        }
        getSystemService(ConnectivityManager::class.java).registerNetworkCallback(request, callback!!)
        if (!playServices()) startLocationFallback()
        publishWifi()
    }

    @SuppressLint("MissingPermission")
    private fun startLocationFallback() {
        if (!Permissions.fine(this)) return
        val manager = getSystemService(LocationManager::class.java)
        val listener = LocationListener { location ->
            hub().setLocation(location.toSample())
            refresh()
        }
        locationListener = listener
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        for (provider in providers) {
            if (!manager.isProviderEnabled(provider)) continue
            manager.requestLocationUpdates(provider, 45_000L, 20f, listener, Looper.getMainLooper())
        }
    }

    private suspend fun loop() {
        var lastScan = 0L
        while (true) {
            delay(15_000)
            publishWifi(scanJustFinished = false)
            val waitingForVisible = repository().enabled().any { rule ->
                rule.clauses.any { clause ->
                    clause.options.any { atom ->
                        atom is tw.geowifi.launcher.core.rule.WifiAtom && !rule.memory.atoms[atom.id].let { it?.latched == true }
                    }
                }
            }
            val now = System.currentTimeMillis()
            if (waitingForVisible && now - lastScan > 30_000) {
                lastScan = now
                requestScan()
            }
            container().evaluator.refresh()
        }
    }

    private fun requestScan() {
        if (!Permissions.fine(this)) return
        val wifi = getSystemService(WifiManager::class.java)
        @Suppress("DEPRECATION")
        val started = try {
            wifi.startScan()
        } catch (_: SecurityException) {
            false
        }
        if (!started) publishWifi(scanJustFinished = false)
    }

    private fun publishWifi(scanJustFinished: Boolean = scanFresh) {
        hub().setWifi(WifiReader.read(this, scanJustFinished))
        scanFresh = false
        refresh()
    }

    private fun refresh() {
        scope.launch { container().evaluator.refresh() }
    }

    private fun foregroundTypes(): Int {
        var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        if (Build.VERSION.SDK_INT >= 34 && Permissions.bluetooth(this)) {
            types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        }
        return types
    }

    private fun register(receiver: BroadcastReceiver, filter: IntentFilter, exported: Boolean) {
        if (Build.VERSION.SDK_INT >= 33) {
            val flag = if (exported) Context.RECEIVER_EXPORTED else Context.RECEIVER_NOT_EXPORTED
            registerReceiver(receiver, filter, flag)
        } else {
            registerReceiver(receiver, filter)
        }
    }

    private fun unregisterQuietly(receiver: BroadcastReceiver) {
        try {
            unregisterReceiver(receiver)
        } catch (_: IllegalArgumentException) {
            // Not registered yet.
        }
    }

    private fun playServices(): Boolean {
        return GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(this) == ConnectionResult.SUCCESS
    }

    private fun hub() = container().hub
    private fun container() = (application as GeoWifiApp).container
    private fun repository() = container().repository

    companion object {
        val running = AtomicBoolean(false)
    }
}

private fun Location.toSample(): LocationSample {
    return LocationSample(
        latitude = latitude,
        longitude = longitude,
        accuracyMeters = if (hasAccuracy()) accuracy.toDouble() else null,
    )
}

object WifiReader {
    fun read(context: Context, visibleFresh: Boolean): WifiInputs {
        if (!Permissions.fine(context)) {
            return WifiInputs(null, connectedKnown = false, emptyList(), visibleFresh = false)
        }
        val manager = context.getSystemService(WifiManager::class.java)
        val connected = connectedSight(context, manager)
        val visible = try {
            manager.scanResults.map { result ->
                WifiSight(result.SSID ?: "", result.BSSID, result.level)
            }
        } catch (_: SecurityException) {
            emptyList()
        }
        return WifiInputs(
            connected = connected,
            connectedKnown = true,
            visible = visible,
            visibleFresh = visibleFresh,
        )
    }

    private fun connectedSight(context: Context, manager: WifiManager): WifiSight? {
        val info = connectedInfo(context) ?: run {
            @Suppress("DEPRECATION")
            manager.connectionInfo
        }
        val ssid = WifiIds.normalizeSsid(info?.ssid) ?: return null
        return WifiSight(ssid, info?.bssid, info?.rssi ?: -127)
    }

    private fun connectedInfo(context: Context): WifiInfo? {
        if (Build.VERSION.SDK_INT < 29) return null
        val cm = context.getSystemService(ConnectivityManager::class.java)
        for (network in cm.allNetworks) {
            val caps = cm.getNetworkCapabilities(network) ?: continue
            if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) continue
            val info = caps.transportInfo as? WifiInfo ?: continue
            if (WifiIds.normalizeSsid(info.ssid) != null) return info
        }
        return null
    }
}

object BatteryReader {
    fun snapshot(intent: Intent): ChargingSnapshot {
        val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
        val type = when {
            plugged and BatteryManager.BATTERY_PLUGGED_USB != 0 -> ChargeType.USB
            plugged and BatteryManager.BATTERY_PLUGGED_AC != 0 -> ChargeType.AC
            plugged and BatteryManager.BATTERY_PLUGGED_WIRELESS != 0 -> ChargeType.WIRELESS
            else -> null
        }
        return ChargingSnapshot(pluggedIn = plugged != 0, type = type)
    }
}

object BluetoothReader {
    fun connected(context: Context): Set<String> {
        if (!Permissions.bluetooth(context)) return emptySet()
        val manager = context.getSystemService(BluetoothManager::class.java) ?: return emptySet()
        val profiles = mutableListOf(BluetoothProfile.HEADSET, BluetoothProfile.A2DP, BluetoothProfile.GATT)
        if (Build.VERSION.SDK_INT >= 28) profiles += BluetoothProfile.HEARING_AID
        val out = LinkedHashSet<String>()
        for (profile in profiles) {
            try {
                manager.getConnectedDevices(profile).forEach { device ->
                    device.address?.let { out += it }
                }
            } catch (_: SecurityException) {
            } catch (_: IllegalArgumentException) {
            }
        }
        return out
    }
}

class GeofenceReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val event = GeofencingEvent.fromIntent(intent) ?: return
        if (event.hasError()) return
        val signal = when (event.geofenceTransition) {
            Geofence.GEOFENCE_TRANSITION_ENTER -> GeofenceSignal.ENTER
            Geofence.GEOFENCE_TRANSITION_DWELL -> GeofenceSignal.DWELL
            Geofence.GEOFENCE_TRANSITION_EXIT -> GeofenceSignal.EXIT
            else -> return
        }
        val grouped = HashMap<Long, MutableMap<String, GeofenceSignal>>()
        for (fence in event.triggeringGeofences.orEmpty()) {
            val parts = fence.requestId.split(":", limit = 2)
            if (parts.size != 2) continue
            val ruleId = parts[0].toLongOrNull() ?: continue
            grouped.getOrPut(ruleId) { HashMap() }[parts[1]] = signal
        }
        val pending = goAsync()
        val app = context.applicationContext as GeoWifiApp
        app.scope.launch {
            try {
                app.container.evaluator.onGeofences(grouped)
            } finally {
                pending.finish()
            }
        }
    }
}

class WindowAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val app = context.applicationContext as GeoWifiApp
        app.scope.launch {
            try {
                app.container.evaluator.refresh()
            } finally {
                pending.finish()
            }
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val pending = goAsync()
        val app = context.applicationContext as GeoWifiApp
        app.scope.launch {
            try {
                app.container.monitor.syncNow()
            } finally {
                pending.finish()
            }
        }
    }
}
