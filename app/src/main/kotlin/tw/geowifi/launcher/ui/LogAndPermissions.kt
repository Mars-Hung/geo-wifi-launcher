package tw.geowifi.launcher.ui

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import tw.geowifi.launcher.GeoWifiApp
import tw.geowifi.launcher.R
import tw.geowifi.launcher.monitor.Permissions
import tw.geowifi.launcher.monitor.settingsIntent

@Composable
fun EventLogScreen() {
    val context = LocalContext.current
    val app = context.applicationContext as GeoWifiApp
    val model = viewModel<LogViewModel>(factory = containerFactory { LogViewModel(it.repository) })
    val events by model.events.collectAsState()
    val formatter = remember { DateTimeFormatter.ofPattern("M/d HH:mm") }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = { model.clear() }, enabled = events.isNotEmpty()) {
                Text(stringResource(R.string.log_clear))
            }
        }
        if (events.isEmpty()) {
            Text(stringResource(R.string.log_empty), modifier = Modifier.padding(16.dp))
        } else {
            LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(events, key = { it.id }) { event ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            val time = Instant.ofEpochMilli(event.createdAt).atZone(ZoneId.systemDefault()).format(formatter)
                            Text("$time · ${event.ruleName}", style = MaterialTheme.typography.labelLarge)
                            Text(event.summary)
                            Text(event.detail, color = if (event.success) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }
    }
}

class LogViewModel(private val repository: tw.geowifi.launcher.data.RuleRepository) : androidx.lifecycle.ViewModel() {
    val events = repository.observeEvents().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun clear() {
        viewModelScope.launch { repository.clearEvents() }
    }
}

@Composable
fun PermissionsScreen() {
    val context = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) tick++
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val generation = tick
    val fine = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { tick++ }
    val single = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { tick++ }
    val rows = listOf(
        RowSpec(R.string.permissions_fine_title, R.string.permissions_fine_body, Permissions.fine(context)) {
            fine.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        },
        RowSpec(R.string.permissions_background_title, R.string.permissions_background_body, Permissions.background(context)) {
            if (Build.VERSION.SDK_INT >= 29) single.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            context.startActivity(settingsIntent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, context.packageName))
        },
        RowSpec(R.string.permissions_wifi_title, R.string.permissions_wifi_body, Permissions.nearbyWifi(context), Build.VERSION.SDK_INT >= 33) {
            if (Build.VERSION.SDK_INT >= 33) single.launch(Manifest.permission.NEARBY_WIFI_DEVICES)
        },
        RowSpec(R.string.permissions_notification_title, R.string.permissions_notification_body, Permissions.notifications(context), Build.VERSION.SDK_INT >= 33) {
            if (Build.VERSION.SDK_INT >= 33) single.launch(Manifest.permission.POST_NOTIFICATIONS)
        },
        RowSpec(R.string.permissions_overlay_title, R.string.permissions_overlay_body, Permissions.overlay(context)) {
            context.startActivity(settingsIntent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, context.packageName))
        },
        RowSpec(R.string.permissions_battery_title, R.string.permissions_battery_body, Permissions.ignoringBattery(context)) {
            context.startActivity(settingsIntent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, context.packageName))
        },
        RowSpec(R.string.permissions_alarm_title, R.string.permissions_alarm_body, Permissions.exactAlarm(context), Build.VERSION.SDK_INT >= 31) {
            if (Build.VERSION.SDK_INT >= 31) {
                context.startActivity(settingsIntent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, context.packageName))
            }
        },
        RowSpec(R.string.permissions_fullscreen_title, R.string.permissions_fullscreen_body, Permissions.fullScreenIntent(context), Build.VERSION.SDK_INT >= 34) {
            if (Build.VERSION.SDK_INT >= 34) context.startActivity(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT))
        },
        RowSpec(R.string.permissions_bt_title, R.string.permissions_bt_body, Permissions.bluetooth(context), Build.VERSION.SDK_INT >= 31) {
            if (Build.VERSION.SDK_INT >= 31) single.launch(Manifest.permission.BLUETOOTH_CONNECT)
        },
        RowSpec(R.string.permissions_location_toggle_title, R.string.permissions_location_toggle_body, Permissions.locationEnabled(context)) {
            context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
        },
    )
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Text(stringResource(R.string.permissions_intro)) }
        items(rows.filter { it.visible }) { row ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(row.title), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        Text(if (row.granted) stringResource(R.string.permissions_granted) else "")
                    }
                    Text(stringResource(row.body))
                    if (!row.granted) {
                        TextButton(onClick = row.action) { Text(stringResource(R.string.permissions_grant)) }
                    }
                }
            }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(stringResource(R.string.permissions_oem_title), style = MaterialTheme.typography.titleSmall)
                    Text(stringResource(R.string.permissions_oem_body))
                    Text(stringResource(R.string.permissions_charge_note))
                }
            }
        }
    }
}

private data class RowSpec(
    val title: Int,
    val body: Int,
    val granted: Boolean,
    val visible: Boolean = true,
    val action: () -> Unit,
)
