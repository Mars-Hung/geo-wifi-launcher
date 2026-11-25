package tw.geowifi.launcher.core.wifi

data class WifiSight(
    val ssid: String,
    val bssid: String?,
    val rssiDbm: Int,
)

object WifiIds {
    const val unknownSsid = "<unknown ssid>"
    const val unknownBssid = "02:00:00:00:00:00"

    fun normalizeSsid(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        var value = raw.trim()
        if (value.length >= 2 && value.first() == '"' && value.last() == '"') {
            value = value.substring(1, value.length - 1)
        }
        if (value.isBlank() || value == unknownSsid) return null
        return value
    }

    fun normalizeBssid(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val value = raw.trim().lowercase()
        if (value == unknownBssid || value == "00:00:00:00:00:00") return null
        return value
    }

    fun sameSsid(expected: String, observed: String): Boolean {
        val left = normalizeSsid(expected) ?: return false
        val right = normalizeSsid(observed) ?: return false
        return left == right
    }

    fun bssidAllowed(expected: String?, observed: String?): Boolean {
        val want = normalizeBssid(expected) ?: return true
        val got = normalizeBssid(observed) ?: return false
        return want == got
    }

    fun matches(expectedSsid: String, expectedBssid: String?, sight: WifiSight): Boolean {
        return sameSsid(expectedSsid, sight.ssid) && bssidAllowed(expectedBssid, sight.bssid)
    }
}
