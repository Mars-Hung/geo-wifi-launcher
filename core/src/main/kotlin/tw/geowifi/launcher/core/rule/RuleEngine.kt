package tw.geowifi.launcher.core.rule

import java.time.LocalDateTime
import tw.geowifi.launcher.core.geo.GeoMath
import tw.geowifi.launcher.core.schedule.TimeWindows
import tw.geowifi.launcher.core.wifi.WifiIds

object RuleEngine {
    fun evaluate(
        spec: RuleSpec,
        memory: RuleMemory,
        inputs: Inputs,
        localNow: LocalDateTime,
    ): Evaluation {
        val states = HashMap(memory.atoms)
        val values = HashMap<String, Boolean?>()
        var exitEdge = false
        for (clause in spec.clauses) {
            for (atom in clause.options) {
                val previous = states[atom.id] ?: AtomState()
                val resolved = resolve(atom, previous, inputs)
                states[atom.id] = resolved.state
                values[atom.id] = resolved.value
                if (resolved.exitEdge) exitEdge = true
            }
        }
        val formula = formulaValue(spec.clauses, values)
        val window = TimeWindows.isActiveAt(spec.schedule, localNow)
        var armed = memory.armed
        var lastFiredAt = memory.lastFiredAtMillis
        var action: Action = Action.None
        val coolingDown = lastFiredAt != null &&
            spec.cooldownMillis > 0 &&
            inputs.nowMillis - lastFiredAt < spec.cooldownMillis
        if (spec.enabled && formula == true && window && armed) {
            if (coolingDown) {
                armed = false
            } else {
                val reason = when {
                    exitEdge && memory.windowActive -> FireReason.LEFT_REGION
                    memory.formulaKnown && memory.formulaTrue && !memory.windowActive -> FireReason.WINDOW_OPENED
                    else -> FireReason.CONDITIONS_MET
                }
                action = Action.Fire(reason)
                armed = false
                lastFiredAt = inputs.nowMillis
            }
        } else if (formula == false || (formula == true && !window)) {
            armed = true
        }
        return Evaluation(
            memory = RuleMemory(
                armed = armed,
                windowActive = window,
                formulaKnown = formula != null || memory.formulaKnown,
                formulaTrue = when (formula) {
                    true -> true
                    false -> false
                    null -> memory.formulaTrue
                },
                lastFiredAtMillis = lastFiredAt,
                atoms = states,
            ),
            action = action,
        )
    }

    private data class Resolved(val value: Boolean?, val state: AtomState, val exitEdge: Boolean = false)

    private fun resolve(atom: Atom, previous: AtomState, inputs: Inputs): Resolved {
        return when (atom) {
            is RegionAtom -> resolveRegion(atom, previous, inputs)
            is WifiAtom -> resolveWifi(atom, previous, inputs)
            is ChargingAtom -> resolveCharging(atom, previous, inputs.charging)
            is BluetoothAtom -> resolveBluetooth(atom, previous, inputs.bluetoothAddresses)
        }
    }

    private fun resolveRegion(atom: RegionAtom, previous: AtomState, inputs: Inputs): Resolved {
        val signal = inputs.fixes[atom.id]
        if (signal != null) {
            return when (signal) {
                GeofenceSignal.DWELL -> confirmed(atom, previous, inputs.nowMillis)
                GeofenceSignal.ENTER ->
                    if (atom.dwellMillis <= 0L) {
                        confirmed(atom, previous, inputs.nowMillis)
                    } else {
                        pending(atom, previous, inputs.nowMillis)
                    }
                GeofenceSignal.EXIT -> cleared(atom, previous, inputs.nowMillis, wasInside = previous.known && previous.latched)
            }
        }
        val sample = inputs.sample ?: return keep(atom, previous)
        if (sample.accuracyMeters != null && sample.accuracyMeters > atom.radiusMeters) {
            return keep(atom, previous)
        }
        if (atom.radiusMeters <= 0.0) {
            return cleared(atom, previous, inputs.nowMillis, wasInside = previous.known && previous.latched)
        }
        val distance = GeoMath.distanceMeters(sample.latitude, sample.longitude, atom.latitude, atom.longitude)
        val now = inputs.nowMillis
        val inside = distance <= atom.radiusMeters
        val clearlyOutside = distance > atom.radiusMeters + atom.hysteresisMeters.coerceAtLeast(0.0)
        if (previous.latched) {
            if (clearlyOutside) {
                return cleared(atom, previous, now, wasInside = true)
            }
            val state = previous.copy(known = true, lastObservationMillis = now, lastSeenMillis = now)
            val value = if (atom.transition == LocationTransition.EXIT) false else true
            return Resolved(value, state)
        }
        if (!inside) {
            val state = previous.copy(
                known = true,
                latched = false,
                pendingSinceMillis = null,
                lastObservationMillis = now,
            )
            return Resolved(false, state)
        }
        if (atom.dwellMillis <= 0L) {
            return confirmed(atom, previous, now)
        }
        val gapTooBig = previous.lastObservationMillis != null &&
            now - previous.lastObservationMillis > Defaults.maxGapMillis
        val pendingSince = if (previous.pendingSinceMillis == null || gapTooBig) now else previous.pendingSinceMillis
        if (!gapTooBig && now - pendingSince >= atom.dwellMillis) {
            return confirmed(atom, previous, now)
        }
        val state = previous.copy(
            known = true,
            latched = false,
            pendingSinceMillis = pendingSince,
            lastObservationMillis = now,
            lastSeenMillis = now,
        )
        return Resolved(false, state)
    }

    private fun confirmed(atom: RegionAtom, previous: AtomState, now: Long): Resolved {
        val state = AtomState(known = true, latched = true, lastSeenMillis = now, lastObservationMillis = now)
        val value = atom.transition != LocationTransition.EXIT
        return Resolved(value, state)
    }

    private fun pending(atom: RegionAtom, previous: AtomState, now: Long): Resolved {
        val state = previous.copy(
            known = true,
            latched = false,
            pendingSinceMillis = previous.pendingSinceMillis ?: now,
            lastObservationMillis = now,
            lastSeenMillis = now,
        )
        return Resolved(false, state)
    }

    private fun cleared(atom: RegionAtom, previous: AtomState, now: Long, wasInside: Boolean): Resolved {
        val state = AtomState(known = true, latched = false, lastObservationMillis = now)
        val edge = atom.transition == LocationTransition.EXIT && wasInside
        return Resolved(if (edge) true else false, state, exitEdge = edge)
    }

    private fun keep(atom: RegionAtom, previous: AtomState): Resolved {
        if (!previous.known) return Resolved(null, previous)
        val value = if (atom.transition == LocationTransition.EXIT) false else previous.latched
        return Resolved(value, previous)
    }

    private fun resolveWifi(atom: WifiAtom, previous: AtomState, inputs: Inputs): Resolved {
        val wifi = inputs.wifi ?: return keepLevel(previous)
        val reading = wifiReading(atom, wifi)
        val now = inputs.nowMillis
        return when (reading) {
            WifiReading.Keep -> keepLevel(previous)
            WifiReading.Absent -> onWifiAbsent(previous, now, atom.disappearMillis)
            is WifiReading.Level -> onWifiLevel(atom, previous, reading.rssi, now)
        }
    }

    private sealed interface WifiReading {
        data object Keep : WifiReading
        data object Absent : WifiReading
        data class Level(val rssi: Int) : WifiReading
    }

    private fun wifiReading(atom: WifiAtom, wifi: WifiInputs): WifiReading {
        val connected = wifi.connected?.let { sight ->
            WifiIds.strongestMatch(
                atom.ssid,
                atom.bssid,
                listOf(sight),
                atom.ssidMatch,
                atom.caseInsensitive,
            )
        }
        val visible = WifiIds.strongestMatch(
            atom.ssid,
            atom.bssid,
            wifi.visible,
            atom.ssidMatch,
            atom.caseInsensitive,
        )
        return when (atom.mode) {
            WifiMatchMode.CONNECTED -> when {
                !wifi.connectedKnown -> WifiReading.Keep
                connected == null -> WifiReading.Absent
                else -> WifiReading.Level(connected.rssiDbm)
            }
            WifiMatchMode.VISIBLE -> when {
                !wifi.visibleFresh -> WifiReading.Keep
                visible == null -> WifiReading.Absent
                else -> WifiReading.Level(visible.rssiDbm)
            }
            WifiMatchMode.EITHER -> {
                val levels = ArrayList<Int>()
                if (wifi.connectedKnown && connected != null) levels += connected.rssiDbm
                if (wifi.visibleFresh && visible != null) levels += visible.rssiDbm
                when {
                    levels.isNotEmpty() -> WifiReading.Level(levels.max())
                    wifi.visibleFresh -> WifiReading.Absent
                    else -> WifiReading.Keep
                }
            }
        }
    }

    private fun onWifiLevel(atom: WifiAtom, previous: AtomState, rssi: Int, now: Long): Resolved {
        val clearBelow = atom.minRssiDbm - atom.rearmDropDbm
        if (previous.latched) {
            if (rssi < clearBelow) {
                return Resolved(false, AtomState(known = true, latched = false, lastRssi = rssi, lastObservationMillis = now))
            }
            val state = previous.copy(
                known = true,
                latched = true,
                pendingSinceMillis = null,
                lastSeenMillis = now,
                lastObservationMillis = now,
                lastRssi = rssi,
            )
            return Resolved(true, state)
        }
        if (rssi < atom.minRssiDbm) {
            val state = previous.copy(
                known = true,
                latched = false,
                pendingSinceMillis = null,
                lastObservationMillis = now,
                lastRssi = rssi,
            )
            return Resolved(false, state)
        }
        if (atom.dwellMillis <= 0L) {
            return Resolved(
                true,
                AtomState(known = true, latched = true, lastSeenMillis = now, lastObservationMillis = now, lastRssi = rssi),
            )
        }
        val gapTooBig = previous.lastObservationMillis != null && now - previous.lastObservationMillis > atom.maxGapMillis
        val pendingSince = if (previous.pendingSinceMillis == null || gapTooBig) now else previous.pendingSinceMillis
        if (!gapTooBig && now - pendingSince >= atom.dwellMillis) {
            return Resolved(
                true,
                AtomState(known = true, latched = true, lastSeenMillis = now, lastObservationMillis = now, lastRssi = rssi),
            )
        }
        val state = previous.copy(
            known = true,
            latched = false,
            pendingSinceMillis = pendingSince,
            lastSeenMillis = now,
            lastObservationMillis = now,
            lastRssi = rssi,
        )
        return Resolved(false, state)
    }

    private fun onWifiAbsent(previous: AtomState, now: Long, disappearMillis: Long): Resolved {
        if (!previous.latched) {
            val state = previous.copy(
                known = true,
                latched = false,
                pendingSinceMillis = null,
                lastObservationMillis = now,
                lastRssi = null,
            )
            return Resolved(false, state)
        }
        val lastSeen = previous.lastSeenMillis
        if (lastSeen != null && now - lastSeen >= disappearMillis) {
            return Resolved(false, AtomState(known = true, latched = false, lastObservationMillis = now))
        }
        val state = previous.copy(known = true, lastObservationMillis = now)
        return Resolved(true, state)
    }

    private fun resolveCharging(atom: ChargingAtom, previous: AtomState, charging: ChargingSnapshot?): Resolved {
        if (charging == null) return keepLevel(previous)
        val matched = charging.pluggedIn && (atom.types.isEmpty() || (charging.type != null && charging.type in atom.types))
        return level(previous, matched)
    }

    private fun resolveBluetooth(atom: BluetoothAtom, previous: AtomState, addresses: Set<String>?): Resolved {
        if (addresses == null) return keepLevel(previous)
        val want = WifiIds.normalizeBssid(atom.address) ?: atom.address.trim().lowercase()
        val matched = addresses.any { raw ->
            val got = WifiIds.normalizeBssid(raw) ?: raw.trim().lowercase()
            got == want
        }
        return level(previous, matched)
    }

    private fun level(previous: AtomState, value: Boolean): Resolved {
        return Resolved(value, previous.copy(known = true, latched = value, pendingSinceMillis = null))
    }

    private fun keepLevel(previous: AtomState): Resolved {
        if (!previous.known) return Resolved(null, previous)
        return Resolved(previous.latched, previous)
    }

    private fun formulaValue(clauses: List<Clause>, values: Map<String, Boolean?>): Boolean? {
        if (clauses.isEmpty()) return null
        val clauseValues = clauses.map { clause -> anyOf(clause.options.map { values[it.id] }) }
        return allOf(clauseValues)
    }

    private fun allOf(values: List<Boolean?>): Boolean? {
        if (values.any { it == false }) return false
        if (values.any { it == null }) return null
        return true
    }

    private fun anyOf(values: List<Boolean?>): Boolean? {
        if (values.any { it == true }) return true
        if (values.any { it == null }) return null
        return false
    }
}
