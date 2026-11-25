package tw.geowifi.launcher.core.rule

import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

object LogicCodec {
    fun encode(clauses: List<Clause>): String {
        val lines = ArrayList<String>()
        lines += "V1"
        for (clause in clauses) {
            lines += "CLAUSE"
            for (atom in clause.options) {
                lines += encodeAtom(atom)
            }
        }
        return lines.joinToString("\n")
    }

    fun decode(raw: String): List<Clause> {
        val lines = raw.split('\n').map { it.trimEnd() }.filter { it.isNotBlank() }
        require(lines.firstOrNull() == "V1") { "logic version" }
        val clauses = ArrayList<Clause>()
        var current = ArrayList<Atom>()
        var started = false
        for (line in lines.drop(1)) {
            if (line == "CLAUSE") {
                if (started) clauses += Clause(current.toList())
                current = ArrayList()
                started = true
            } else {
                require(started) { line }
                current += decodeAtom(line)
            }
        }
        if (started) clauses += Clause(current.toList())
        return clauses
    }

    private fun encodeAtom(atom: Atom): String {
        return when (atom) {
            is RegionAtom -> listOf(
                "REGION",
                atom.id,
                atom.latitude.toString(),
                atom.longitude.toString(),
                atom.radiusMeters.toString(),
                atom.dwellMillis.toString(),
                atom.hysteresisMeters.toString(),
                atom.transition.name,
            ).joinToString(" ")
            is WifiAtom -> listOf(
                "WIFI",
                atom.id,
                atom.mode.name,
                atom.minRssiDbm.toString(),
                atom.dwellMillis.toString(),
                atom.rearmDropDbm.toString(),
                atom.disappearMillis.toString(),
                atom.maxGapMillis.toString(),
                enc(atom.bssid ?: ""),
                enc(atom.ssid),
            ).joinToString(" ")
            is ChargingAtom -> {
                val types = if (atom.types.isEmpty()) "*" else atom.types.sortedBy { it.name }.joinToString("|") { it.name }
                "CHARGE ${atom.id} $types"
            }
            is BluetoothAtom -> listOf(
                "BT",
                atom.id,
                enc(atom.address),
                enc(atom.deviceName),
            ).joinToString(" ")
        }
    }

    private fun decodeAtom(line: String): Atom {
        val parts = line.split(' ')
        return when (parts[0]) {
            "REGION" -> RegionAtom(
                id = parts[1],
                latitude = parts[2].toDouble(),
                longitude = parts[3].toDouble(),
                radiusMeters = parts[4].toDouble(),
                dwellMillis = parts[5].toLong(),
                hysteresisMeters = parts[6].toDouble(),
                transition = LocationTransition.valueOf(parts[7]),
            )
            "WIFI" -> WifiAtom(
                id = parts[1],
                mode = WifiMatchMode.valueOf(parts[2]),
                minRssiDbm = parts[3].toInt(),
                dwellMillis = parts[4].toLong(),
                rearmDropDbm = parts[5].toInt(),
                disappearMillis = parts[6].toLong(),
                maxGapMillis = parts[7].toLong(),
                bssid = dec(parts[8]).ifBlank { null },
                ssid = dec(parts[9]),
            )
            "CHARGE" -> ChargingAtom(
                id = parts[1],
                types = if (parts[2] == "*") {
                    emptySet()
                } else {
                    parts[2].split('|').filter { it.isNotBlank() }.map { ChargeType.valueOf(it) }.toSet()
                },
            )
            "BT" -> BluetoothAtom(
                id = parts[1],
                address = dec(parts[2]),
                deviceName = dec(parts[3]),
            )
            else -> error("Unknown atom ${parts[0]}")
        }
    }

    private fun enc(value: String): String {
        return URLEncoder.encode(value, StandardCharsets.UTF_8.name())
    }

    private fun dec(value: String): String {
        return URLDecoder.decode(value, StandardCharsets.UTF_8.name())
    }
}

object MemoryCodec {
    fun encode(memory: RuleMemory): String {
        val lines = ArrayList<String>()
        lines += "V1"
        lines += listOf(
            if (memory.armed) "1" else "0",
            if (memory.windowActive) "1" else "0",
            if (memory.formulaKnown) "1" else "0",
            if (memory.formulaTrue) "1" else "0",
        ).joinToString(" ")
        for ((id, state) in memory.atoms.toSortedMap()) {
            lines += listOf(
                "ATOM",
                id,
                if (state.known) "1" else "0",
                if (state.latched) "1" else "0",
                state.pendingSinceMillis?.toString() ?: "-",
                state.lastSeenMillis?.toString() ?: "-",
                state.lastObservationMillis?.toString() ?: "-",
                state.lastRssi?.toString() ?: "-",
            ).joinToString(" ")
        }
        return lines.joinToString("\n")
    }

    fun decode(raw: String): RuleMemory {
        if (raw.isBlank()) return RuleMemory()
        val lines = raw.split('\n').map { it.trim() }.filter { it.isNotBlank() }
        require(lines.first() == "V1")
        val header = lines[1].split(' ')
        val atoms = LinkedHashMap<String, AtomState>()
        for (line in lines.drop(2)) {
            val parts = line.split(' ')
            require(parts[0] == "ATOM")
            atoms[parts[1]] = AtomState(
                known = parts[2] == "1",
                latched = parts[3] == "1",
                pendingSinceMillis = num(parts[4]),
                lastSeenMillis = num(parts[5]),
                lastObservationMillis = num(parts[6]),
                lastRssi = num(parts[7])?.toInt(),
            )
        }
        return RuleMemory(
            armed = header[0] == "1",
            windowActive = header[1] == "1",
            formulaKnown = header[2] == "1",
            formulaTrue = header[3] == "1",
            atoms = atoms,
        )
    }

    private fun num(token: String): Long? = if (token == "-") null else token.toLong()
}
