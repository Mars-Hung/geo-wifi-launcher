package tw.geowifi.launcher.monitor

import tw.geowifi.launcher.core.rule.ChargingSnapshot
import tw.geowifi.launcher.core.rule.Inputs
import tw.geowifi.launcher.core.rule.LocationSample
import tw.geowifi.launcher.core.rule.WifiInputs

class SensorHub {
    private val lock = Any()
    private var sample: LocationSample? = null
    private var wifi: WifiInputs? = null
    private var charging: ChargingSnapshot? = null
    private var bluetooth: Set<String>? = null

    fun setLocation(value: LocationSample) = synchronized(lock) { sample = value }

    fun setWifi(value: WifiInputs) = synchronized(lock) { wifi = value }

    fun setCharging(value: ChargingSnapshot) = synchronized(lock) { charging = value }

    fun setBluetooth(value: Set<String>) = synchronized(lock) { bluetooth = value }

    fun inputs(nowMillis: Long): Inputs = synchronized(lock) {
        Inputs(
            nowMillis = nowMillis,
            sample = sample,
            wifi = wifi,
            charging = charging,
            bluetoothAddresses = bluetooth,
        )
    }
}
