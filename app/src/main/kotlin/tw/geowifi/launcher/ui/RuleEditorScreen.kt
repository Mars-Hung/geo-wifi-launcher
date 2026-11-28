package tw.geowifi.launcher.ui

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.android.gms.location.LocationServices
import java.time.DayOfWeek
import java.util.UUID
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tw.geowifi.launcher.monitor.WifiSnapshots
import tw.geowifi.launcher.monitor.WifiSurveyor
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import tw.geowifi.launcher.R
import tw.geowifi.launcher.core.rule.BluetoothAtom
import tw.geowifi.launcher.core.rule.ChargeType
import tw.geowifi.launcher.core.rule.ChargingAtom
import tw.geowifi.launcher.core.rule.Clause
import tw.geowifi.launcher.core.rule.LocationTransition
import tw.geowifi.launcher.core.rule.RegionAtom
import tw.geowifi.launcher.core.rule.WifiAtom
import tw.geowifi.launcher.core.rule.WifiMatchMode
import tw.geowifi.launcher.core.schedule.TimeRange
import tw.geowifi.launcher.core.schedule.WeekSchedule
import tw.geowifi.launcher.core.wifi.SsidMatchMode
import tw.geowifi.launcher.core.wifi.WifiIds
import tw.geowifi.launcher.data.RuleDraft
import tw.geowifi.launcher.monitor.Permissions

internal enum class DraftKind { REGION, WIFI, CHARGING, BLUETOOTH }

internal data class AtomDraft(
    val id: String = UUID.randomUUID().toString().replace("-", "").take(10),
    val kind: DraftKind,
    val latitude: String = "",
    val longitude: String = "",
    val radiusMeters: Int = 100,
    val locationDwellSec: Int = 60,
    val transition: LocationTransition = LocationTransition.ENTER,
    val ssid: String = "",
    val bssid: String = "",
    val wifiMode: WifiMatchMode = WifiMatchMode.VISIBLE,
    val ssidMatch: SsidMatchMode = SsidMatchMode.CONTAINS,
    val caseInsensitive: Boolean = true,
    val minRssi: Int = -65,
    val wifiDwellSec: Int = 60,
    val rearmDrop: Int = 10,
    val disappearMin: Int = 3,
    val chargeTypes: Set<ChargeType> = emptySet(),
    val btAddress: String = "",
    val btName: String = "",
)

internal data class ClauseDraft(val options: List<AtomDraft>)

@Composable
fun RuleEditorScreen(ruleId: Long, onDone: () -> Unit) {
    val context = LocalContext.current
    val model = viewModel<EditorViewModel>(factory = containerFactory { EditorViewModel(it.repository, it.monitor, ruleId) })
    LaunchedEffect(model.finished) { if (model.finished) onDone() }
    if (!model.loaded) {
        Text(stringResource(R.string.app_name), modifier = Modifier.padding(16.dp))
        return
    }
    var picking by remember { mutableStateOf<Int?>(null) }
    var pickingApp by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OutlinedTextField(
            value = model.name,
            onValueChange = { model.name = it },
            label = { Text(stringResource(R.string.editor_name)) },
            placeholder = { Text(stringResource(R.string.editor_name_hint)) },
            modifier = Modifier.fillMaxWidth(),
        )
        val cooldownLabel = if (model.cooldownMinutes == 0) {
            stringResource(R.string.editor_cooldown_off)
        } else {
            stringResource(R.string.editor_cooldown, model.cooldownMinutes)
        }
        Text(cooldownLabel)
        Slider(
            value = model.cooldownMinutes.toFloat(),
            onValueChange = { model.cooldownMinutes = (it / 5f).roundToInt() * 5 },
            valueRange = 0f..180f,
        )
        Text(stringResource(R.string.editor_cooldown_help), style = MaterialTheme.typography.bodySmall)
        Text(stringResource(R.string.editor_conditions), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.editor_conditions_help))
        model.clauses.forEachIndexed { index, clause ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.editor_clause, index + 1), style = MaterialTheme.typography.titleSmall)
                    clause.options.forEachIndexed { optionIndex, atom ->
                        if (optionIndex > 0) Text(stringResource(R.string.editor_or))
                        AtomFields(atom) { updated -> model.updateAtom(index, optionIndex, updated) }
                        TextButton(onClick = { model.removeOption(index, optionIndex) }) {
                            Text(stringResource(R.string.editor_remove_option))
                        }
                    }
                    OutlinedButton(onClick = { picking = index }) { Text(stringResource(R.string.editor_add_or)) }
                    if (model.clauses.size > 1) {
                        TextButton(onClick = { model.removeClause(index) }) { Text(stringResource(R.string.editor_remove_clause)) }
                    }
                }
            }
        }
        Button(onClick = { picking = -1 }) { Text(stringResource(R.string.editor_add_and)) }
        ScheduleFields(model)
        Text(model.targetLabel.ifBlank { stringResource(R.string.editor_app_none) })
        OutlinedButton(onClick = { pickingApp = true }) { Text(stringResource(R.string.editor_pick_app)) }
        ChargingInfoCard()
        Text(stringResource(R.string.editor_behavior), style = MaterialTheme.typography.bodySmall)
        model.error?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
        Button(onClick = { model.save() }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.save)) }
    }
    picking?.let { target ->
        AlertDialog(
            onDismissRequest = { picking = null },
            title = { Text(stringResource(R.string.editor_pick_kind)) },
            text = {
                Column {
                    DraftKind.entries.forEach { kind ->
                        TextButton(onClick = {
                            val draft = AtomDraft(kind = kind)
                            if (target < 0) model.addClause(draft) else model.addOption(target, draft)
                            picking = null
                        }) {
                            Text(
                                stringResource(
                                    when (kind) {
                                        DraftKind.REGION -> R.string.kind_region
                                        DraftKind.WIFI -> R.string.kind_wifi
                                        DraftKind.CHARGING -> R.string.kind_charging
                                        DraftKind.BLUETOOTH -> R.string.kind_bluetooth
                                    },
                                ),
                            )
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { picking = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    if (pickingApp) {
        AppPicker(onPick = { pkg, label ->
            model.targetPackage = pkg
            model.targetLabel = label
            pickingApp = false
        }, onDismiss = { pickingApp = false })
    }
}

@Composable
private fun AtomFields(atom: AtomDraft, onChange: (AtomDraft) -> Unit) {
    when (atom.kind) {
        DraftKind.REGION -> RegionFields(atom, onChange)
        DraftKind.WIFI -> WifiFields(atom, onChange)
        DraftKind.CHARGING -> ChargeFields(atom, onChange)
        DraftKind.BLUETOOTH -> BluetoothFields(atom, onChange)
    }
}

@Composable
private fun RegionFields(atom: AtomDraft, onChange: (AtomDraft) -> Unit) {
    val context = LocalContext.current
    val lat = atom.latitude.toDoubleOrNull()
    val lng = atom.longitude.toDoubleOrNull()
    LocationMap(lat, lng) { newLat, newLng ->
        onChange(atom.copy(latitude = "%.6f".format(newLat), longitude = "%.6f".format(newLng)))
    }
    Text(stringResource(R.string.editor_map_hint), style = MaterialTheme.typography.bodySmall)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = atom.latitude,
            onValueChange = { onChange(atom.copy(latitude = it)) },
            label = { Text(stringResource(R.string.editor_lat)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.weight(1f),
        )
        OutlinedTextField(
            value = atom.longitude,
            onValueChange = { onChange(atom.copy(longitude = it)) },
            label = { Text(stringResource(R.string.editor_lng)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.weight(1f),
        )
    }
    OutlinedButton(onClick = { readCurrentLocation(context) { la, ln -> onChange(atom.copy(latitude = "%.6f".format(la), longitude = "%.6f".format(ln))) } }) {
        Text(stringResource(R.string.editor_use_location))
    }
    Text(stringResource(R.string.editor_radius, atom.radiusMeters))
    Slider(
        value = atom.radiusMeters.toFloat(),
        onValueChange = { onChange(atom.copy(radiusMeters = (it / 10f).roundToInt() * 10)) },
        valueRange = 30f..2000f,
    )
    if (atom.radiusMeters < 100) Text(stringResource(R.string.editor_radius_warning), color = MaterialTheme.colorScheme.secondary)
    Text(stringResource(R.string.editor_dwell, formatDwell(atom.locationDwellSec, stringResource(R.string.editor_dwell_immediate))))
    Slider(
        value = atom.locationDwellSec.toFloat(),
        onValueChange = { onChange(atom.copy(locationDwellSec = (it / 15f).roundToInt() * 15)) },
        valueRange = 0f..600f,
    )
    Text(stringResource(R.string.editor_accuracy_note), style = MaterialTheme.typography.bodySmall)
    Text(stringResource(R.string.editor_hysteresis_note), style = MaterialTheme.typography.bodySmall)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = atom.transition == LocationTransition.ENTER,
            onClick = { onChange(atom.copy(transition = LocationTransition.ENTER)) },
            label = { Text(stringResource(R.string.editor_transition_enter)) },
        )
        FilterChip(
            selected = atom.transition == LocationTransition.EXIT,
            onClick = { onChange(atom.copy(transition = LocationTransition.EXIT)) },
            label = { Text(stringResource(R.string.editor_transition_exit)) },
        )
    }
}

@Composable
private fun WifiFields(atom: AtomDraft, onChange: (AtomDraft) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var survey by remember { mutableStateOf<WifiSurveyor.Outcome?>(null) }
    var scanning by remember { mutableStateOf(false) }
    Text(stringResource(R.string.editor_wifi_scan_help), style = MaterialTheme.typography.bodySmall)
    OutlinedTextField(
        value = atom.ssid,
        onValueChange = { onChange(atom.copy(ssid = it)) },
        label = { Text(stringResource(R.string.editor_wifi_ssid)) },
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = atom.bssid,
        onValueChange = { onChange(atom.copy(bssid = it)) },
        label = { Text(stringResource(R.string.editor_wifi_bssid)) },
        modifier = Modifier.fillMaxWidth(),
    )
    LaunchedEffect(Unit) {
        scanning = true
        survey = WifiSurveyor.refresh(context)
        scanning = false
    }
    OutlinedButton(onClick = {
        if (scanning) return@OutlinedButton
        scanning = true
        scope.launch {
            survey = WifiSurveyor.refresh(context)
            scanning = false
        }
    }) { Text(stringResource(if (scanning) R.string.editor_wifi_scanning else R.string.editor_wifi_refresh)) }
    survey?.let { outcome ->
        Text(outcome.status, style = MaterialTheme.typography.bodySmall)
        Text(outcome.detail, style = MaterialTheme.typography.bodySmall)
        if (outcome.networks.isEmpty()) {
            Text(stringResource(R.string.editor_wifi_empty), style = MaterialTheme.typography.bodySmall)
        }
        Text(stringResource(R.string.editor_wifi_pick_hint), style = MaterialTheme.typography.bodySmall)
        outcome.networks.forEach { network ->
            val bands = if (network.bands.isEmpty()) "—" else network.bands.joinToString("+") + " GHz"
            val label = if (network.connected) {
                stringResource(R.string.editor_wifi_row_connected, network.ssid, network.rssiDbm, bands)
            } else {
                stringResource(R.string.editor_wifi_row, network.ssid, network.rssiDbm, bands)
            }
            TextButton(onClick = { onChange(atom.copy(ssid = network.ssid)) }) { Text(label) }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
        WifiModeChip(WifiMatchMode.VISIBLE, R.string.editor_wifi_mode_visible, atom, onChange)
        WifiModeChip(WifiMatchMode.CONNECTED, R.string.editor_wifi_mode_connected, atom, onChange)
        WifiModeChip(WifiMatchMode.EITHER, R.string.editor_wifi_mode_either, atom, onChange)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
        SsidMatchChip(SsidMatchMode.CONTAINS, R.string.editor_match_contains, atom, onChange)
        SsidMatchChip(SsidMatchMode.EXACT, R.string.editor_match_exact, atom, onChange)
        SsidMatchChip(SsidMatchMode.STARTS_WITH, R.string.editor_match_prefix, atom, onChange)
        SsidMatchChip(SsidMatchMode.REGEX, R.string.editor_match_regex, atom, onChange)
    }
    if (atom.ssidMatch == SsidMatchMode.REGEX) {
        Text(stringResource(R.string.editor_regex_hint), style = MaterialTheme.typography.bodySmall)
    }
    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Text(stringResource(R.string.editor_case_insensitive), modifier = Modifier.weight(1f))
        Switch(checked = atom.caseInsensitive, onCheckedChange = { onChange(atom.copy(caseInsensitive = it)) })
    }
    Text(stringResource(R.string.editor_rssi, atom.minRssi))
    Slider(
        value = atom.minRssi.toFloat(),
        onValueChange = { onChange(atom.copy(minRssi = it.roundToInt())) },
        valueRange = -90f..-40f,
    )
    SignalReadout(atom)
    Text(stringResource(R.string.editor_dwell, formatDwell(atom.wifiDwellSec, stringResource(R.string.editor_dwell_immediate))))
    Slider(
        value = atom.wifiDwellSec.toFloat(),
        onValueChange = { onChange(atom.copy(wifiDwellSec = (it / 15f).roundToInt() * 15)) },
        valueRange = 0f..600f,
    )
    Text(stringResource(R.string.editor_rearm_drop, atom.minRssi - atom.rearmDrop))
    Slider(
        value = atom.rearmDrop.toFloat(),
        onValueChange = { onChange(atom.copy(rearmDrop = it.roundToInt().coerceIn(5, 25))) },
        valueRange = 5f..25f,
    )
    Text(stringResource(R.string.editor_disappear, atom.disappearMin))
    Slider(
        value = atom.disappearMin.toFloat(),
        onValueChange = { onChange(atom.copy(disappearMin = it.roundToInt().coerceIn(1, 15))) },
        valueRange = 1f..15f,
    )
}

@Composable
private fun WifiModeChip(mode: WifiMatchMode, label: Int, atom: AtomDraft, onChange: (AtomDraft) -> Unit) {
    FilterChip(selected = atom.wifiMode == mode, onClick = { onChange(atom.copy(wifiMode = mode)) }, label = { Text(stringResource(label)) })
}

@Composable
private fun SsidMatchChip(mode: SsidMatchMode, label: Int, atom: AtomDraft, onChange: (AtomDraft) -> Unit) {
    FilterChip(selected = atom.ssidMatch == mode, onClick = { onChange(atom.copy(ssidMatch = mode)) }, label = { Text(stringResource(label)) })
}

@Composable
private fun SignalReadout(atom: AtomDraft) {
    val context = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    DisposableEffect(atom.ssid, atom.ssidMatch, atom.caseInsensitive) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) { tick++ }
        }
        val filter = IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
        if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED) else context.registerReceiver(receiver, filter)
        onDispose {
            try {
                context.unregisterReceiver(receiver)
            } catch (_: IllegalArgumentException) {
            }
        }
    }
    if (!Permissions.fine(context)) {
        Text(stringResource(R.string.editor_signal_need_permission))
        return
    }
    val level = remember(tick, atom.ssid, atom.bssid, atom.ssidMatch, atom.caseInsensitive) {
        currentRssi(context, atom)
    }
    Text(
        if (level == null || atom.ssid.isBlank()) {
            stringResource(R.string.editor_signal_missing)
        } else {
            stringResource(R.string.editor_signal_now, level)
        },
    )
}

@Composable
private fun ChargeFields(atom: AtomDraft, onChange: (AtomDraft) -> Unit) {
    Text(stringResource(R.string.editor_charge_help))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ChargeType.entries.forEach { type ->
            FilterChip(
                selected = type in atom.chargeTypes,
                onClick = {
                    val next = atom.chargeTypes.toMutableSet()
                    if (!next.add(type)) next.remove(type)
                    onChange(atom.copy(chargeTypes = next))
                },
                label = { Text(chargeLabel(LocalContext.current, type)) },
            )
        }
    }
    if (atom.chargeTypes.isEmpty()) Text(stringResource(R.string.editor_charge_any))
}

@Composable
private fun BluetoothFields(atom: AtomDraft, onChange: (AtomDraft) -> Unit) {
    val context = LocalContext.current
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    var devices by remember { mutableStateOf(loadBonded(context)) }
    if (Build.VERSION.SDK_INT >= 31 && !Permissions.bluetooth(context)) {
        OutlinedButton(onClick = { permission.launch(Manifest.permission.BLUETOOTH_CONNECT) }) {
            Text(stringResource(R.string.permissions_grant))
        }
    }
    OutlinedButton(onClick = { devices = loadBonded(context) }) { Text(stringResource(R.string.editor_bt_refresh)) }
    if (atom.btAddress.isNotBlank()) Text(stringResource(R.string.editor_bt_selected, atom.btName.ifBlank { atom.btAddress }))
    if (devices.isEmpty()) Text(stringResource(R.string.editor_bt_empty))
    devices.forEach { (name, address) ->
        TextButton(onClick = { onChange(atom.copy(btAddress = address, btName = name)) }) { Text("$name  $address") }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScheduleFields(model: EditorViewModel) {
    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Text(stringResource(R.string.editor_schedule), modifier = Modifier.weight(1f))
        Switch(checked = model.scheduleEnabled, onCheckedChange = { model.scheduleEnabled = it })
    }
    if (!model.scheduleEnabled) return
    Text(stringResource(R.string.editor_schedule_help), style = MaterialTheme.typography.bodySmall)
    val labels = stringResourceArray()
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        DayOfWeek.entries.forEachIndexed { index, day ->
            FilterChip(
                selected = day in model.days,
                onClick = {
                    model.days = if (day in model.days) model.days - day else model.days + day
                },
                label = { Text(labels[index]) },
            )
        }
    }
    model.ranges.forEachIndexed { index, range ->
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TimeField(stringResource(R.string.editor_lat).let { range.first }) { minute ->
                model.ranges = model.ranges.toMutableList().also { it[index] = minute to range.second }
            }
            TimeField(range.second) { minute ->
                model.ranges = model.ranges.toMutableList().also { it[index] = range.first to minute }
            }
            TextButton(onClick = { model.ranges = model.ranges.filterIndexed { i, _ -> i != index } }) {
                Text(stringResource(R.string.editor_remove_option))
            }
        }
        val start = range.first
        val end = range.second
        if (start == end) Text(stringResource(R.string.editor_all_day_hint))
        if (end < start) Text(stringResource(R.string.editor_cross_hint))
    }
    TextButton(onClick = { model.ranges = model.ranges + (9 * 60 to 18 * 60) }) { Text(stringResource(R.string.editor_add_range)) }
}

@Composable
private fun stringResourceArray(): List<String> {
    val context = LocalContext.current
    return context.resources.getStringArray(R.array.weekday_labels).toList()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimeField(minuteOfDay: Int, onChange: (Int) -> Unit) {
    var show by remember { mutableStateOf(false) }
    OutlinedButton(onClick = { show = true }) { Text(clock(minuteOfDay)) }
    if (show) {
        val state = rememberTimePickerState(minuteOfDay / 60, minuteOfDay % 60, true)
        AlertDialog(
            onDismissRequest = { show = false },
            confirmButton = {
                TextButton(onClick = {
                    onChange(state.hour * 60 + state.minute)
                    show = false
                }) { Text(stringResource(R.string.confirm)) }
            },
            dismissButton = { TextButton(onClick = { show = false }) { Text(stringResource(R.string.cancel)) } },
            text = { TimePicker(state) },
        )
    }
}

@Composable
private fun LocationMap(latitude: Double?, longitude: Double?, onPick: (Double, Double) -> Unit) {
    val context = LocalContext.current
    val pick = rememberUpdatedState(onPick)
    val map = remember {
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            controller.setZoom(16.0)
            val marker = Marker(this)
            marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            marker.icon = ContextCompat.getDrawable(context, R.drawable.ic_map_pin)
            overlays.add(marker)
            overlays.add(MapEventsOverlay(object : MapEventsReceiver {
                override fun singleTapConfirmedHelper(p: GeoPoint): Boolean {
                    pick.value(p.latitude, p.longitude)
                    return true
                }

                override fun longPressHelper(p: GeoPoint): Boolean = false
            }))
        }
    }
    DisposableEffect(map) {
        map.onResume()
        onDispose {
            map.onPause()
            map.onDetach()
        }
    }
    AndroidView(factory = { map }, modifier = Modifier.fillMaxWidth().height(220.dp), update = { view ->
        val marker = view.overlays.filterIsInstance<Marker>().first()
        if (latitude != null && longitude != null) {
            val point = GeoPoint(latitude, longitude)
            marker.position = point
            marker.setVisible(true)
            if (view.zoomLevelDouble < 3) view.controller.setCenter(point)
        }
    })
    LaunchedEffect(latitude, longitude) {
        if (latitude != null && longitude != null) map.controller.animateTo(GeoPoint(latitude, longitude))
    }
}

@Composable
private fun AppPicker(onPick: (String, String) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    var apps by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.Default) { loadApps(context) }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
        title = { Text(stringResource(R.string.editor_pick_app)) },
        text = {
            Column(Modifier.height(420.dp).verticalScroll(rememberScrollState())) {
                OutlinedTextField(value = query, onValueChange = { query = it }, label = { Text(stringResource(R.string.editor_app_search)) })
                apps.filter { query.isBlank() || it.second.contains(query, ignoreCase = true) || it.first.contains(query, ignoreCase = true) }
                    .take(40)
                    .forEach { (pkg, label) ->
                        TextButton(onClick = { onPick(pkg, label) }) { Text(label) }
                    }
            }
        },
    )
}

private fun loadApps(context: Context): List<Pair<String, String>> {
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    val resolved = if (Build.VERSION.SDK_INT >= 33) {
        context.packageManager.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
    } else {
        context.packageManager.queryIntentActivities(intent, 0)
    }
    return resolved.map { it.activityInfo.packageName to it.loadLabel(context.packageManager).toString() }
        .filter { it.first != context.packageName }
        .distinctBy { it.first }
        .sortedBy { it.second.lowercase() }
}

@SuppressLint("MissingPermission")
private fun readCurrentLocation(context: Context, onPoint: (Double, Double) -> Unit) {
    if (!Permissions.fine(context)) return
    try {
        LocationServices.getFusedLocationProviderClient(context).lastLocation.addOnSuccessListener { location ->
            if (location != null) onPoint(location.latitude, location.longitude)
        }
    } catch (_: SecurityException) {
        val manager = context.getSystemService(LocationManager::class.java)
        val location = manager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            ?: manager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
        if (location != null) onPoint(location.latitude, location.longitude)
    }
}

private fun currentRssi(context: Context, atom: AtomDraft): Int? {
    if (atom.ssid.isBlank() || !Permissions.fine(context)) return null
    return WifiIds.strongestMatch(
        atom.ssid,
        atom.bssid.ifBlank { null },
        WifiSnapshots.sights(context),
        atom.ssidMatch,
        atom.caseInsensitive,
    )?.rssiDbm
}

private fun loadBonded(context: Context): List<Pair<String, String>> {
    if (Build.VERSION.SDK_INT >= 31 && !Permissions.bluetooth(context)) return emptyList()
    val manager = context.getSystemService(BluetoothManager::class.java) ?: return emptyList()
    return try {
        manager.adapter?.bondedDevices.orEmpty().map { device ->
            (device.name ?: device.address) to device.address
        }.sortedBy { it.first }
    } catch (_: SecurityException) {
        emptyList()
    }
}

internal class EditorViewModel(
    private val repository: tw.geowifi.launcher.data.RuleRepository,
    private val monitor: tw.geowifi.launcher.monitor.MonitorController,
    private val ruleId: Long,
) : ViewModel() {
    var loaded by mutableStateOf(ruleId < 0)
    var finished by mutableStateOf(false)
    var name by mutableStateOf("")
    var clauses by mutableStateOf(listOf<ClauseDraft>())
    var scheduleEnabled by mutableStateOf(false)
    var days by mutableStateOf(setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY))
    var ranges by mutableStateOf(listOf(9 * 60 to 18 * 60))
    var targetPackage by mutableStateOf("")
    var targetLabel by mutableStateOf("")
    var cooldownMinutes by mutableStateOf(30)
    var error by mutableStateOf<Int?>(null)

    init {
        if (ruleId >= 0) {
            viewModelScope.launch {
                val rule = repository.get(ruleId)
                if (rule != null) {
                    name = rule.name
                    clauses = rule.clauses.map { clause -> ClauseDraft(clause.options.map { it.toDraft() }) }
                    scheduleEnabled = rule.schedule != null
                    rule.schedule?.let {
                        if (it.days.isNotEmpty()) days = it.days
                        if (it.ranges.isNotEmpty()) ranges = it.ranges.map { range -> range.startMinute to range.endMinute }
                    }
                    targetPackage = rule.targetPackage
                    targetLabel = rule.targetLabel
                    cooldownMinutes = (rule.cooldownMillis / 60_000L).toInt()
                }
                loaded = true
            }
        }
    }

    fun addClause(atom: AtomDraft) {
        clauses = clauses + ClauseDraft(listOf(atom))
    }

    fun addOption(index: Int, atom: AtomDraft) {
        clauses = clauses.toMutableList().also { list ->
            val clause = list[index]
            list[index] = clause.copy(options = clause.options + atom)
        }
    }

    fun updateAtom(clauseIndex: Int, optionIndex: Int, atom: AtomDraft) {
        clauses = clauses.toMutableList().also { list ->
            val clause = list[clauseIndex]
            list[clauseIndex] = clause.copy(options = clause.options.toMutableList().also { it[optionIndex] = atom })
        }
    }

    fun removeOption(clauseIndex: Int, optionIndex: Int) {
        val clause = clauses[clauseIndex]
        val next = clause.options.filterIndexed { index, _ -> index != optionIndex }
        clauses = if (next.isEmpty()) clauses.filterIndexed { index, _ -> index != clauseIndex } else {
            clauses.toMutableList().also { it[clauseIndex] = clause.copy(options = next) }
        }
    }

    fun removeClause(index: Int) {
        clauses = clauses.filterIndexed { i, _ -> i != index }
    }

    fun save() {
        val message = validate()
        error = message
        if (message != null) return
        viewModelScope.launch {
            repository.save(
                RuleDraft(
                    id = ruleId.takeIf { it >= 0 },
                    name = name,
                    clauses = clauses.map { clause -> Clause(clause.options.map { it.toAtom() }) },
                    schedule = if (scheduleEnabled) {
                        WeekSchedule(days, ranges.map { TimeRange(it.first.coerceIn(0, 1439), it.second.coerceIn(0, 1439)) })
                    } else {
                        null
                    },
                    cooldownMillis = cooldownMinutes * 60_000L,
                    targetPackage = targetPackage,
                    targetLabel = targetLabel,
                ),
            )
            monitor.sync()
            finished = true
        }
    }

    private fun validate(): Int? {
        if (name.isBlank()) return R.string.editor_error_name
        if (clauses.isEmpty() || clauses.any { it.options.isEmpty() }) return R.string.editor_error_conditions
        for (clause in clauses) {
            for (atom in clause.options) {
                when (atom.kind) {
                    DraftKind.REGION -> if (atom.latitude.toDoubleOrNull() == null || atom.longitude.toDoubleOrNull() == null) return R.string.editor_error_location
                    DraftKind.WIFI -> if (WifiIds.normalizeSsid(atom.ssid) == null) return R.string.editor_error_wifi
                    DraftKind.BLUETOOTH -> if (atom.btAddress.isBlank()) return R.string.editor_error_bt
                    DraftKind.CHARGING -> Unit
                }
            }
        }
        if (targetPackage.isBlank()) return R.string.editor_error_app
        if (scheduleEnabled && days.isEmpty()) return R.string.editor_error_days
        return null
    }
}

private fun tw.geowifi.launcher.core.rule.Atom.toDraft(): AtomDraft {
    return when (this) {
        is RegionAtom -> AtomDraft(
            id = id,
            kind = DraftKind.REGION,
            latitude = latitude.toString(),
            longitude = longitude.toString(),
            radiusMeters = radiusMeters.toInt(),
            locationDwellSec = (dwellMillis / 1000).toInt(),
            transition = transition,
        )
        is WifiAtom -> AtomDraft(
            id = id,
            kind = DraftKind.WIFI,
            ssid = ssid,
            bssid = bssid.orEmpty(),
            wifiMode = mode,
            ssidMatch = ssidMatch,
            caseInsensitive = caseInsensitive,
            minRssi = minRssiDbm,
            wifiDwellSec = (dwellMillis / 1000).toInt(),
            rearmDrop = rearmDropDbm,
            disappearMin = (disappearMillis / 60_000).toInt().coerceAtLeast(1),
        )
        is ChargingAtom -> AtomDraft(id = id, kind = DraftKind.CHARGING, chargeTypes = types)
        is BluetoothAtom -> AtomDraft(id = id, kind = DraftKind.BLUETOOTH, btAddress = address, btName = deviceName)
    }
}

private fun AtomDraft.toAtom(): tw.geowifi.launcher.core.rule.Atom {
    return when (kind) {
        DraftKind.REGION -> RegionAtom(
            id = id,
            latitude = latitude.toDouble(),
            longitude = longitude.toDouble(),
            radiusMeters = radiusMeters.toDouble(),
            dwellMillis = locationDwellSec * 1000L,
            transition = transition,
        )
        DraftKind.WIFI -> WifiAtom(
            id = id,
            ssid = WifiIds.normalizeSsid(ssid) ?: ssid,
            bssid = bssid.ifBlank { null },
            mode = wifiMode,
            minRssiDbm = minRssi,
            dwellMillis = wifiDwellSec * 1000L,
            rearmDropDbm = rearmDrop,
            disappearMillis = disappearMin * 60_000L,
            ssidMatch = ssidMatch,
            caseInsensitive = caseInsensitive,
        )
        DraftKind.CHARGING -> ChargingAtom(id, chargeTypes)
        DraftKind.BLUETOOTH -> BluetoothAtom(id, btAddress, btName)
    }
}
