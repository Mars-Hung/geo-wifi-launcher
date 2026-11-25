package tw.geowifi.launcher.core.rule

import tw.geowifi.launcher.core.schedule.WeekSchedule
import tw.geowifi.launcher.core.wifi.WifiSight

object Defaults {
    const val minRssiDbm = -65
    const val wifiDwellMillis = 60_000L
    const val rearmDropDbm = 10
    const val disappearMillis = 180_000L
    const val maxGapMillis = 300_000L
    const val locationDwellMillis = 60_000L
    const val locationHysteresisMeters = 30.0
    const val defaultRadiusMeters = 100.0
}

enum class WifiMatchMode { CONNECTED, VISIBLE, EITHER }

enum class LocationTransition { ENTER, EXIT }

enum class ChargeType { USB, AC, WIRELESS }

enum class GeofenceSignal { ENTER, DWELL, EXIT }

enum class FireReason { CONDITIONS_MET, WINDOW_OPENED, LEFT_REGION }

data class Clause(val options: List<Atom>)

sealed interface Atom {
    val id: String
}

data class RegionAtom(
    override val id: String,
    val latitude: Double,
    val longitude: Double,
    val radiusMeters: Double,
    val dwellMillis: Long = Defaults.locationDwellMillis,
    val hysteresisMeters: Double = Defaults.locationHysteresisMeters,
    val transition: LocationTransition = LocationTransition.ENTER,
) : Atom

data class WifiAtom(
    override val id: String,
    val ssid: String,
    val bssid: String?,
    val mode: WifiMatchMode,
    val minRssiDbm: Int = Defaults.minRssiDbm,
    val dwellMillis: Long = Defaults.wifiDwellMillis,
    val rearmDropDbm: Int = Defaults.rearmDropDbm,
    val disappearMillis: Long = Defaults.disappearMillis,
    val maxGapMillis: Long = Defaults.maxGapMillis,
) : Atom

data class ChargingAtom(
    override val id: String,
    val types: Set<ChargeType> = emptySet(),
) : Atom

data class BluetoothAtom(
    override val id: String,
    val address: String,
    val deviceName: String = "",
) : Atom

data class RuleSpec(
    val id: Long,
    val enabled: Boolean,
    val clauses: List<Clause>,
    val schedule: WeekSchedule?,
)

data class AtomState(
    val known: Boolean = false,
    val latched: Boolean = false,
    val pendingSinceMillis: Long? = null,
    val lastSeenMillis: Long? = null,
    val lastObservationMillis: Long? = null,
    val lastRssi: Int? = null,
)

data class RuleMemory(
    val armed: Boolean = true,
    val windowActive: Boolean = false,
    val formulaKnown: Boolean = false,
    val formulaTrue: Boolean = false,
    val atoms: Map<String, AtomState> = emptyMap(),
)

data class LocationSample(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Double?,
)

data class WifiInputs(
    val connected: WifiSight?,
    val connectedKnown: Boolean,
    val visible: List<WifiSight>,
    val visibleFresh: Boolean,
)

data class ChargingSnapshot(
    val pluggedIn: Boolean,
    val type: ChargeType?,
)

data class Inputs(
    val nowMillis: Long,
    val fixes: Map<String, GeofenceSignal> = emptyMap(),
    val sample: LocationSample? = null,
    val wifi: WifiInputs? = null,
    val charging: ChargingSnapshot? = null,
    val bluetoothAddresses: Set<String>? = null,
)

sealed interface Action {
    data object None : Action
    data class Fire(val reason: FireReason) : Action
}

data class Evaluation(
    val memory: RuleMemory,
    val action: Action,
)
