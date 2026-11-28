package tw.geowifi.launcher.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlin.math.abs
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import tw.geowifi.launcher.GeoWifiApp
import tw.geowifi.launcher.R
import tw.geowifi.launcher.data.RuleRecord
import tw.geowifi.launcher.data.RuleRepository
import tw.geowifi.launcher.monitor.MonitorController
import tw.geowifi.launcher.monitor.Permissions

@Composable
fun RulesScreen(onAdd: () -> Unit, onEdit: (Long) -> Unit, onPermissions: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as GeoWifiApp
    val model = viewModel<RulesViewModel>(factory = containerFactory { RulesViewModel(it.repository, it.monitor) })
    val rules by model.rules.collectAsState()
    var pendingDelete by remember { mutableStateOf<RuleRecord?>(null) }
    Scaffold(
        floatingActionButton = {
            FloatingActionButton(onClick = onAdd) {
                Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.rules_add))
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { ChargingInfoCard() }
            if (!Permissions.canMonitor(context)) {
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(stringResource(R.string.rules_need_permission))
                            TextButton(onClick = onPermissions) { Text(stringResource(R.string.rules_open_permissions)) }
                        }
                    }
                }
            }
            if (rules.isEmpty()) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.rules_empty_title), style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(R.string.rules_empty_body))
                    }
                }
            }
            items(rules, key = { it.id }) { rule ->
                RuleCard(rule, onToggle = { model.setEnabled(rule.id, it) }, onDelete = { pendingDelete = rule }, onEdit = { onEdit(rule.id) })
            }
        }
    }
    pendingDelete?.let { rule ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.rule_delete_title)) },
            text = { Text(stringResource(R.string.rule_delete_body, rule.name)) },
            confirmButton = {
                TextButton(onClick = {
                    model.delete(rule.id)
                    pendingDelete = null
                }) { Text(stringResource(R.string.rule_delete)) }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun RuleCard(rule: RuleRecord, onToggle: (Boolean) -> Unit, onDelete: () -> Unit, onEdit: () -> Unit) {
    val context = LocalContext.current
    Card(onClick = onEdit, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(rule.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Switch(checked = rule.enabled, onCheckedChange = onToggle)
            }
            Text(summarizeClauses(context, rule.clauses))
            Text(summarizeSchedule(context, rule.schedule), color = MaterialTheme.colorScheme.primary)
            Text(rule.targetLabel)
            val state = when {
                !rule.memory.formulaKnown -> stringResource(R.string.state_unknown)
                rule.memory.armed -> stringResource(R.string.state_armed)
                else -> stringResource(R.string.state_latched)
            }
            Text(state, style = MaterialTheme.typography.bodySmall)
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.rule_delete))
            }
        }
    }
}

@Composable
fun ChargingInfoCard() {
    val context = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    DisposableEffect(Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                tick++
            }
        }
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }
        onDispose { context.unregisterReceiver(receiver) }
    }
    val info = remember(tick) { readCharge(context) }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.charge_card_title), style = MaterialTheme.typography.titleSmall)
            Text(info.status)
            Text(stringResource(R.string.charge_voltage, info.voltage))
            Text(stringResource(R.string.charge_current, info.current))
            info.watts?.let { Text(stringResource(R.string.charge_watts, it)) }
            Text(stringResource(R.string.charge_disclaimer), style = MaterialTheme.typography.bodySmall)
        }
    }
}

private data class ChargeRead(val status: String, val voltage: String, val current: String, val watts: Double?)

private fun readCharge(context: Context): ChargeRead {
    val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    val unavailable = context.getString(R.string.charge_unavailable)
    if (intent == null) return ChargeRead(context.getString(R.string.charge_unplugged), unavailable, unavailable, null)
    val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
    val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
    val voltageMv = intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1)
    val statusText = when {
        plugged == 0 -> context.getString(R.string.charge_unplugged)
        status == BatteryManager.BATTERY_STATUS_CHARGING -> context.getString(R.string.charge_charging)
        status == BatteryManager.BATTERY_STATUS_FULL -> context.getString(R.string.charge_full)
        else -> context.getString(R.string.charge_unknown)
    }
    val type = when {
        plugged and BatteryManager.BATTERY_PLUGGED_USB != 0 -> context.getString(R.string.editor_charge_usb)
        plugged and BatteryManager.BATTERY_PLUGGED_AC != 0 -> context.getString(R.string.editor_charge_ac)
        plugged and BatteryManager.BATTERY_PLUGGED_WIRELESS != 0 -> context.getString(R.string.editor_charge_wireless)
        plugged != 0 -> context.getString(R.string.charge_type_other)
        else -> context.getString(R.string.charge_type_none)
    }
    val micro = context.getSystemService(BatteryManager::class.java)
        .getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
    val voltage = if (voltageMv > 0) "${voltageMv / 1000.0} V" else unavailable
    val current = if (micro == Int.MIN_VALUE) unavailable else "${micro / 1000} mA"
    val watts = if (micro == Int.MIN_VALUE || voltageMv <= 0) {
        null
    } else {
        abs(micro) / 1_000_000.0 * (voltageMv / 1000.0)
    }
    return ChargeRead("$statusText · $type", voltage, current, watts)
}

class RulesViewModel(private val repository: RuleRepository, private val monitor: MonitorController) : ViewModel() {
    val rules = repository.observeRules().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setEnabled(id: Long, enabled: Boolean) {
        viewModelScope.launch {
            repository.setEnabled(id, enabled)
            monitor.sync()
        }
    }

    fun delete(id: Long) {
        viewModelScope.launch {
            repository.delete(id)
            monitor.sync()
        }
    }
}

@Composable
inline fun <reified T : ViewModel> containerFactory(crossinline create: (tw.geowifi.launcher.AppContainer) -> T): ViewModelProvider.Factory {
    val app = LocalContext.current.applicationContext as GeoWifiApp
    return remember {
        object : ViewModelProvider.Factory {
            override fun <VM : ViewModel> create(modelClass: Class<VM>): VM {
                @Suppress("UNCHECKED_CAST")
                return create(app.container) as VM
            }
        }
    }
}
