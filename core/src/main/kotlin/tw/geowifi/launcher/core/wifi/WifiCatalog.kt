package tw.geowifi.launcher.core.wifi

data class RawAccessPoint(
    val ssid: String?,
    val bssid: String?,
    val rssiDbm: Int,
    val frequencyMhz: Int = 0,
    val connected: Boolean = false,
)

data class ListedWifi(
    val ssid: String,
    val bssid: String?,
    val rssiDbm: Int,
    val bands: List<String>,
    val connected: Boolean,
)

data class WifiListSnapshot(
    val networks: List<ListedWifi>,
    val rawCount: Int,
    val hiddenCount: Int,
)

object WifiCatalog {
    fun summarize(points: List<RawAccessPoint>): WifiListSnapshot {
        var hidden = 0
        val grouped = LinkedHashMap<String, Acc>()
        for (point in points) {
            val name = WifiIds.normalizeSsid(point.ssid)
            if (name == null) {
                hidden += 1
                continue
            }
            val key = name.lowercase()
            val band = bandLabel(point.frequencyMhz)
            val current = grouped[key]
            if (current == null) {
                grouped[key] = Acc(
                    ssid = name,
                    bssid = WifiIds.normalizeBssid(point.bssid),
                    rssiDbm = point.rssiDbm,
                    bands = if (band == null) mutableSetOf() else mutableSetOf(band),
                    connected = point.connected,
                )
            } else {
                if (band != null) current.bands += band
                if (point.connected) current.connected = true
                if (point.rssiDbm > current.rssiDbm) {
                    current.rssiDbm = point.rssiDbm
                    current.bssid = WifiIds.normalizeBssid(point.bssid)
                    current.ssid = name
                }
            }
        }
        val networks = grouped.values
            .map { item ->
                ListedWifi(
                    ssid = item.ssid,
                    bssid = item.bssid,
                    rssiDbm = item.rssiDbm,
                    bands = item.bands.sortedBy { bandOrder(it) },
                    connected = item.connected,
                )
            }
            .sortedWith(compareByDescending<ListedWifi> { it.rssiDbm }.thenBy { it.ssid.lowercase() })
        return WifiListSnapshot(networks, points.size, hidden)
    }

    fun bandLabel(frequencyMhz: Int): String? {
        return when (frequencyMhz) {
            in 2400..2500 -> "2.4"
            in 4900..5900 -> "5"
            in 5925..7125 -> "6"
            else -> null
        }
    }

    private fun bandOrder(band: String): Int {
        return when (band) {
            "2.4" -> 0
            "5" -> 1
            "6" -> 2
            else -> 3
        }
    }

    private data class Acc(
        var ssid: String,
        var bssid: String?,
        var rssiDbm: Int,
        val bands: MutableSet<String>,
        var connected: Boolean,
    )
}
