package tw.geowifi.launcher.core.wifi

enum class SsidMatchMode { EXACT, CONTAINS, STARTS_WITH, REGEX }

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

    fun ssidMatches(
        pattern: String,
        observed: String,
        mode: SsidMatchMode,
        caseInsensitive: Boolean,
    ): Boolean {
        val rawObserved = normalizeSsid(observed) ?: return false
        val rawPattern = normalizeSsid(pattern) ?: return false
        return when (mode) {
            SsidMatchMode.REGEX -> regexMatches(rawPattern, rawObserved, caseInsensitive)
            SsidMatchMode.EXACT -> fold(rawPattern, caseInsensitive) == fold(rawObserved, caseInsensitive)
            SsidMatchMode.CONTAINS -> fold(rawObserved, caseInsensitive).contains(fold(rawPattern, caseInsensitive))
            SsidMatchMode.STARTS_WITH -> fold(rawObserved, caseInsensitive).startsWith(fold(rawPattern, caseInsensitive))
        }
    }

    fun matches(
        pattern: String,
        expectedBssid: String?,
        sight: WifiSight,
        mode: SsidMatchMode = SsidMatchMode.EXACT,
        caseInsensitive: Boolean = false,
    ): Boolean {
        return ssidMatches(pattern, sight.ssid, mode, caseInsensitive) && bssidAllowed(expectedBssid, sight.bssid)
    }

    fun strongestMatch(
        pattern: String,
        expectedBssid: String?,
        sights: List<WifiSight>,
        mode: SsidMatchMode,
        caseInsensitive: Boolean,
    ): WifiSight? {
        return sights
            .filter { matches(pattern, expectedBssid, it, mode, caseInsensitive) }
            .maxByOrNull { it.rssiDbm }
    }

    private fun fold(value: String, caseInsensitive: Boolean): String {
        return if (caseInsensitive) value.lowercase() else value
    }

    private fun regexMatches(pattern: String, observed: String, caseInsensitive: Boolean): Boolean {
        return try {
            val options = if (caseInsensitive) setOf(RegexOption.IGNORE_CASE) else emptySet()
            Regex(pattern, options).matches(observed)
        } catch (_: IllegalArgumentException) {
            false
        }
    }
}
