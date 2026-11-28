package tw.geowifi.launcher.core

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tw.geowifi.launcher.core.geo.GeoMath
import tw.geowifi.launcher.core.rule.Action
import tw.geowifi.launcher.core.rule.AtomState
import tw.geowifi.launcher.core.rule.BluetoothAtom
import tw.geowifi.launcher.core.rule.ChargeType
import tw.geowifi.launcher.core.rule.ChargingAtom
import tw.geowifi.launcher.core.rule.ChargingSnapshot
import tw.geowifi.launcher.core.rule.Clause
import tw.geowifi.launcher.core.rule.Defaults
import tw.geowifi.launcher.core.rule.Evaluation
import tw.geowifi.launcher.core.rule.FireReason
import tw.geowifi.launcher.core.rule.GeofenceSignal
import tw.geowifi.launcher.core.rule.Inputs
import tw.geowifi.launcher.core.rule.LocationSample
import tw.geowifi.launcher.core.rule.LocationTransition
import tw.geowifi.launcher.core.rule.LogicCodec
import tw.geowifi.launcher.core.rule.MemoryCodec
import tw.geowifi.launcher.core.rule.RegionAtom
import tw.geowifi.launcher.core.rule.RuleEngine
import tw.geowifi.launcher.core.rule.RuleMemory
import tw.geowifi.launcher.core.rule.RuleSpec
import tw.geowifi.launcher.core.rule.WifiAtom
import tw.geowifi.launcher.core.rule.WifiInputs
import tw.geowifi.launcher.core.rule.WifiMatchMode
import tw.geowifi.launcher.core.schedule.ScheduleCodec
import tw.geowifi.launcher.core.schedule.TimeRange
import tw.geowifi.launcher.core.schedule.TimeWindows
import tw.geowifi.launcher.core.schedule.WeekSchedule
import tw.geowifi.launcher.core.wifi.RawAccessPoint
import tw.geowifi.launcher.core.wifi.SsidMatchMode
import tw.geowifi.launcher.core.wifi.WifiCatalog
import tw.geowifi.launcher.core.wifi.WifiIds
import tw.geowifi.launcher.core.wifi.WifiSight

class RuleLogicTest {
    @Test
    fun distanceOfOneDegreeLatitudeIsAbout111Km() {
        val meters = GeoMath.distanceMeters(0.0, 0.0, 1.0, 0.0)
        assertEquals(111_195.0, meters, 5.0)
        assertEquals(0.0, GeoMath.distanceMeters(25.0, 121.0, 25.0, 121.0), 0.001)
    }

    @Test
    fun ssidQuotesAndUnknownValues() {
        assertEquals("Home", WifiIds.normalizeSsid("\"Home\""))
        assertNull(WifiIds.normalizeSsid("<unknown ssid>"))
        assertNull(WifiIds.normalizeSsid("\"\""))
        assertTrue(WifiIds.bssidAllowed(null, "aa:bb:cc:dd:ee:ff"))
        assertTrue(WifiIds.bssidAllowed("AA:BB:CC:DD:EE:FF", "aa:bb:cc:dd:ee:ff"))
        assertFalse(WifiIds.bssidAllowed("aa:bb:cc:dd:ee:ff", "02:00:00:00:00:00"))
    }

    @Test
    fun weekdayWindowBoundariesAreInclusiveAtStartOnly() {
        val schedule = weekdays(9 to 18)
        assertFalse(TimeWindows.isActiveAt(schedule, at(DayOfWeek.MONDAY, 8, 59)))
        assertTrue(TimeWindows.isActiveAt(schedule, at(DayOfWeek.MONDAY, 9, 0)))
        assertTrue(TimeWindows.isActiveAt(schedule, at(DayOfWeek.FRIDAY, 17, 59)))
        assertFalse(TimeWindows.isActiveAt(schedule, at(DayOfWeek.MONDAY, 18, 0)))
        assertFalse(TimeWindows.isActiveAt(schedule, at(DayOfWeek.SATURDAY, 10, 0)))
        assertFalse(TimeWindows.isActiveAt(schedule, at(DayOfWeek.SUNDAY, 12, 0)))
    }

    @Test
    fun crossMidnightBelongsToTheStartDay() {
        val fridayNight = WeekSchedule(setOf(DayOfWeek.FRIDAY), listOf(range(22, 0, 2, 0)))
        assertFalse(TimeWindows.isActiveAt(fridayNight, at(DayOfWeek.FRIDAY, 21, 59)))
        assertTrue(TimeWindows.isActiveAt(fridayNight, at(DayOfWeek.FRIDAY, 22, 0)))
        assertTrue(TimeWindows.isActiveAt(fridayNight, at(DayOfWeek.SATURDAY, 0, 0)))
        assertTrue(TimeWindows.isActiveAt(fridayNight, at(DayOfWeek.SATURDAY, 1, 59)))
        assertFalse(TimeWindows.isActiveAt(fridayNight, at(DayOfWeek.SATURDAY, 2, 0)))
        assertFalse(TimeWindows.isActiveAt(fridayNight, at(DayOfWeek.SATURDAY, 22, 0)))
        val weeknights = WeekSchedule(
            setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY),
            listOf(range(22, 0, 2, 0)),
        )
        assertFalse(TimeWindows.isActiveAt(weeknights, at(DayOfWeek.MONDAY, 1, 0)))
        assertTrue(TimeWindows.isActiveAt(weeknights, at(DayOfWeek.SATURDAY, 1, 0)))
        assertTrue(TimeWindows.isActiveAt(weeknights, at(DayOfWeek.MONDAY, 22, 0)))
    }

    @Test
    fun multipleRangesAndFullDay() {
        val split = WeekSchedule(setOf(DayOfWeek.MONDAY), listOf(range(9, 0, 12, 0), range(13, 0, 18, 0)))
        assertTrue(TimeWindows.isActiveAt(split, at(DayOfWeek.MONDAY, 11, 0)))
        assertFalse(TimeWindows.isActiveAt(split, at(DayOfWeek.MONDAY, 12, 0)))
        assertFalse(TimeWindows.isActiveAt(split, at(DayOfWeek.MONDAY, 12, 30)))
        assertTrue(TimeWindows.isActiveAt(split, at(DayOfWeek.MONDAY, 13, 0)))
        val allDay = WeekSchedule(setOf(DayOfWeek.TUESDAY), listOf(TimeRange(0, 0)))
        assertTrue(TimeWindows.isActiveAt(allDay, at(DayOfWeek.TUESDAY, 0, 0)))
        assertTrue(TimeWindows.isActiveAt(allDay, at(DayOfWeek.TUESDAY, 23, 59)))
        assertFalse(TimeWindows.isActiveAt(allDay, at(DayOfWeek.WEDNESDAY, 0, 0)))
        val emptyRanges = WeekSchedule(setOf(DayOfWeek.TUESDAY), emptyList())
        assertTrue(TimeWindows.isActiveAt(emptyRanges, at(DayOfWeek.TUESDAY, 15, 0)))
        assertTrue(TimeWindows.isActiveAt(null, at(DayOfWeek.SUNDAY, 3, 0)))
    }

    @Test
    fun nextBoundarySkipsGapsAndWrapsTheWeek() {
        val schedule = weekdays(9 to 18)
        assertEquals(at(DayOfWeek.WEDNESDAY, 18, 0), TimeWindows.nextBoundary(schedule, at(DayOfWeek.WEDNESDAY, 10, 0)))
        assertEquals(at(DayOfWeek.THURSDAY, 9, 0), TimeWindows.nextBoundary(schedule, at(DayOfWeek.WEDNESDAY, 18, 0)))
        assertEquals(at(DayOfWeek.MONDAY, 9, 0).plusWeeks(1), TimeWindows.nextBoundary(schedule, at(DayOfWeek.FRIDAY, 19, 0)))
        val fridayNight = WeekSchedule(setOf(DayOfWeek.FRIDAY), listOf(range(22, 0, 2, 0)))
        assertEquals(at(DayOfWeek.SATURDAY, 2, 0), TimeWindows.nextBoundary(fridayNight, at(DayOfWeek.FRIDAY, 22, 30)))
        assertEquals(at(DayOfWeek.SATURDAY, 2, 0), TimeWindows.nextBoundary(fridayNight, at(DayOfWeek.SATURDAY, 1, 0)))
        assertEquals(at(DayOfWeek.FRIDAY, 22, 0).plusWeeks(1), TimeWindows.nextBoundary(fridayNight, at(DayOfWeek.SATURDAY, 3, 0)))
    }

    @Test
    fun codecsRoundTrip() {
        val clauses = listOf(
            Clause(
                listOf(
                    RegionAtom("r1", 25.033, 121.565, 50.0, 60_000, 30.0, LocationTransition.ENTER),
                    WifiAtom("w1", "家裡 Wi-Fi", "AA:BB:CC:DD:EE:FF", WifiMatchMode.EITHER, -65, 60_000, 10, 180_000, 300_000),
                ),
            ),
            Clause(listOf(ChargingAtom("c1", setOf(ChargeType.USB, ChargeType.AC)))),
            Clause(listOf(BluetoothAtom("b1", "11:22:33:44:55:66", "車機"))),
        )
        val stored = LogicCodec.decode(LogicCodec.encode(clauses, 1_800_000))
        assertEquals(clauses, stored.clauses)
        assertEquals(1_800_000L, stored.cooldownMillis)
        val memory = RuleMemory(
            armed = false,
            windowActive = true,
            formulaKnown = true,
            formulaTrue = true,
            atoms = mapOf("r1" to AtomState(known = true, latched = true, pendingSinceMillis = 5, lastRssi = -60)),
        )
        assertEquals(memory, MemoryCodec.decode(MemoryCodec.encode(memory)))
        val schedule = weekdays(9 to 18)
        assertEquals(schedule.days, ScheduleCodec.decodeDays(ScheduleCodec.encodeDays(schedule.days)))
        assertEquals(schedule.ranges, ScheduleCodec.decodeRanges(ScheduleCodec.encodeRanges(schedule.ranges)))
    }

    @Test
    fun wifiDwellThresholdAndHysteresis() {
        val spec = rule(wifi())
        var memory = RuleMemory()
        var step = eval(spec, memory, wifiLevel(-64), 0)
        assertTrue(step.action is Action.None)
        memory = step.memory
        step = eval(spec, memory, wifiLevel(-70), 30_000)
        assertTrue(step.action is Action.None)
        assertFalse(step.memory.atoms.getValue("w").latched)
        memory = step.memory
        step = eval(spec, memory, wifiLevel(-60), 40_000)
        memory = step.memory
        step = eval(spec, memory, wifiLevel(-60), 99_999)
        assertTrue(step.action is Action.None)
        step = eval(spec, step.memory, wifiLevel(-60), 100_000)
        assertFire(step, FireReason.CONDITIONS_MET)
        memory = step.memory
        step = eval(spec, memory, wifiLevel(-70), 130_000)
        assertTrue(step.action is Action.None)
        assertTrue(step.memory.atoms.getValue("w").latched)
        step = eval(spec, step.memory, wifiLevel(-75), 140_000)
        assertTrue(step.memory.atoms.getValue("w").latched)
        step = eval(spec, step.memory, wifiLevel(-76), 150_000)
        assertFalse(step.memory.atoms.getValue("w").latched)
        assertTrue(step.memory.armed)
    }

    @Test
    fun wifiPassByDoesNotFireAndDisappearRearms() {
        val spec = rule(wifi(disappearMillis = 180_000))
        var step = eval(spec, RuleMemory(), wifiLevel(-50), 0)
        step = eval(spec, step.memory, wifiLevel(-50), 20_000)
        step = eval(spec, step.memory, wifiAbsent(), 25_000)
        assertTrue(step.action is Action.None)
        assertFalse(step.memory.atoms.getValue("w").latched)
        var memory = latchWifi(spec)
        step = eval(spec, memory, wifiAbsent(), 200_000)
        assertTrue(step.memory.atoms.getValue("w").latched)
        step = eval(spec, step.memory, wifiAbsent(), 180_000 + 120_000)
        assertFalse(step.memory.atoms.getValue("w").latched)
        memory = latchWifi(spec)
        step = eval(spec, memory, Inputs(nowMillis = 400_000), noon())
        assertTrue(step.memory.atoms.getValue("w").latched)
        assertTrue(step.action is Action.None)
    }

    @Test
    fun connectedModeAlsoWaitsForDwell() {
        val spec = rule(wifi(mode = WifiMatchMode.CONNECTED))
        var step = eval(spec, RuleMemory(), connected(-60), 0)
        assertTrue(step.action is Action.None)
        step = eval(spec, step.memory, connected(-60), 60_000)
        assertFire(step, FireReason.CONDITIONS_MET)
        step = eval(spec, step.memory, connected(-60), 70_000)
        assertTrue(step.action is Action.None)
        step = eval(spec, step.memory, WifiInputs(null, connectedKnown = true, emptyList(), visibleFresh = false).at(80_000), noon())
        assertTrue(step.memory.atoms.getValue("w").latched)
    }

    @Test
    fun largeObservationGapRestartsWifiDwell() {
        val spec = rule(wifi(maxGapMillis = 180_000))
        val started = eval(spec, RuleMemory(), wifiLevel(-50), 0)
        val later = eval(spec, started.memory, wifiLevel(-50), 600_000)
        assertTrue(later.action is Action.None)
        assertFalse(later.memory.atoms.getValue("w").latched)
        val done = eval(spec, later.memory, wifiLevel(-50), 660_000)
        assertFire(done, FireReason.CONDITIONS_MET)
    }

    @Test
    fun locationIgnoresInaccurateFixesAndRequiresDwell() {
        val spec = rule(region(radius = 50.0, dwell = 60_000))
        val bad = eval(spec, RuleMemory(), sample(metersNorth = 10.0, accuracy = 51.0), 0)
        assertFalse(bad.memory.atoms.getValue("r").known)
        var step = eval(spec, RuleMemory(), sample(metersNorth = 10.0, accuracy = 50.0), 0)
        assertFalse(step.memory.atoms.getValue("r").latched)
        step = eval(spec, step.memory, sample(metersNorth = 200.0, accuracy = 10.0), 20_000)
        assertEquals(null, step.memory.atoms.getValue("r").pendingSinceMillis)
        step = eval(spec, step.memory, sample(metersNorth = 10.0, accuracy = 20.0), 30_000)
        val pending = step.memory.atoms.getValue("r").pendingSinceMillis
        step = eval(spec, step.memory, sample(metersNorth = 10.0, accuracy = 80.0), 40_000)
        assertEquals(pending, step.memory.atoms.getValue("r").pendingSinceMillis)
        step = eval(spec, step.memory, sample(metersNorth = 10.0, accuracy = 20.0), 90_000)
        assertFire(step, FireReason.CONDITIONS_MET)
        step = eval(spec, step.memory, sample(metersNorth = 70.0, accuracy = 10.0), 100_000)
        assertTrue(step.memory.atoms.getValue("r").latched)
        step = eval(spec, step.memory, sample(metersNorth = 90.0, accuracy = 10.0), 110_000)
        assertFalse(step.memory.atoms.getValue("r").latched)
        assertTrue(step.memory.armed)
    }

    @Test
    fun passByDoesNotArmEnterOrExit() {
        val enter = rule(region(dwell = 60_000))
        var step = eval(enter, RuleMemory(), sample(0.0, 10.0), 0)
        step = eval(enter, step.memory, sample(0.0, 10.0), 20_000)
        step = eval(enter, step.memory, sample(500.0, 10.0), 25_000)
        assertTrue(step.action is Action.None)
        assertFalse(step.memory.atoms.getValue("r").latched)
        val exitRule = rule(region(dwell = 60_000, transition = LocationTransition.EXIT))
        step = eval(exitRule, RuleMemory(), sample(0.0, 10.0), 0)
        step = eval(exitRule, step.memory, sample(400.0, 10.0), 10_000)
        assertTrue(step.action is Action.None)
    }

    @Test
    fun exitFiresOnlyAfterDwellThenLeaveInsideWindow() {
        val spec = rule(region(dwell = 60_000, transition = LocationTransition.EXIT))
        var step = eval(spec, RuleMemory(), sample(0.0, 10.0), 0)
        step = eval(spec, step.memory, sample(0.0, 10.0), 60_000)
        assertTrue(step.action is Action.None)
        assertTrue(step.memory.atoms.getValue("r").latched)
        step = eval(spec, step.memory, sample(400.0, 10.0), 70_000)
        assertFire(step, FireReason.LEFT_REGION)
    }

    @Test
    fun geofenceDwellConfirmsAndPlainEnterDoesNot() {
        val spec = rule(region(dwell = 60_000))
        val enter = eval(spec, RuleMemory(), Inputs(0, fixes = mapOf("r" to GeofenceSignal.ENTER)), noon())
        assertFalse(enter.memory.atoms.getValue("r").latched)
        val dwell = eval(spec, enter.memory, Inputs(60_000, fixes = mapOf("r" to GeofenceSignal.DWELL)), noon())
        assertFire(dwell, FireReason.CONDITIONS_MET)
        val left = eval(spec, dwell.memory, Inputs(70_000, fixes = mapOf("r" to GeofenceSignal.EXIT)), noon())
        assertFalse(left.memory.atoms.getValue("r").latched)
        val instant = rule(region(dwell = 0))
        val now = eval(instant, RuleMemory(), Inputs(0, fixes = mapOf("r" to GeofenceSignal.ENTER)), noon())
        assertFire(now, FireReason.CONDITIONS_MET)
    }

    @Test
    fun chargingAndWifiBothRequired() {
        val spec = RuleSpec(
            id = 1,
            enabled = true,
            clauses = listOf(Clause(listOf(ChargingAtom("c"))), Clause(listOf(wifi()))),
            schedule = null,
        )
        var step = eval(spec, RuleMemory(), charging(ChargeType.USB).at(0), noon())
        assertTrue(step.action is Action.None)
        step = eval(spec, step.memory, wifiLevel(-50).at(0), noon())
        step = eval(spec, step.memory, Inputs(nowMillis = 60_000, wifi = wifiLevel(-50), charging = ChargingSnapshot(true, ChargeType.USB)), noon())
        assertFire(step, FireReason.CONDITIONS_MET)
        step = eval(spec, step.memory, charging(null).at(70_000), noon())
        assertTrue(step.memory.armed)
        assertTrue(step.action is Action.None)
    }

    @Test
    fun chargingTypeFilterAndBluetooth() {
        val usb = rule(ChargingAtom("c", setOf(ChargeType.USB)))
        assertTrue(eval(usb, RuleMemory(), charging(ChargeType.AC).at(0), noon()).action is Action.None)
        assertFire(eval(usb, RuleMemory(), charging(ChargeType.USB).at(0), noon()), FireReason.CONDITIONS_MET)
        val any = rule(ChargingAtom("c"))
        assertFire(eval(any, RuleMemory(), charging(ChargeType.WIRELESS).at(0), noon()), FireReason.CONDITIONS_MET)
        val car = rule(BluetoothAtom("b", "AA:BB:CC:DD:EE:FF", "車機"))
        val on = eval(car, RuleMemory(), Inputs(0, bluetoothAddresses = setOf("aa:bb:cc:dd:ee:ff")), noon())
        assertFire(on, FireReason.CONDITIONS_MET)
        val off = eval(car, on.memory, Inputs(1, bluetoothAddresses = emptySet()), noon())
        assertTrue(off.memory.armed)
        val again = eval(car, off.memory, Inputs(2, bluetoothAddresses = setOf("AA:BB:CC:DD:EE:FF")), noon())
        assertFire(again, FireReason.CONDITIONS_MET)
    }

    @Test
    fun officeIsGeofenceOrWifiAndCharging() {
        val spec = RuleSpec(
            id = 1,
            enabled = true,
            clauses = listOf(
                Clause(listOf(ChargingAtom("c"))),
                Clause(listOf(region(dwell = 0), wifi(dwellMillis = 0))),
            ),
            schedule = null,
        )
        val onlyFence = eval(spec, RuleMemory(), sample(0.0, 10.0).at(0), noon())
        assertTrue(onlyFence.action is Action.None)
        val chargingAndFence = eval(
            spec,
            onlyFence.memory,
            Inputs(1, sample = sample(0.0, 10.0), charging = ChargingSnapshot(true, ChargeType.AC)),
            noon(),
        )
        assertFire(chargingAndFence, FireReason.CONDITIONS_MET)
        val wifiOnly = eval(spec, RuleMemory(), wifiLevel(-40).at(0), noon())
        assertTrue(wifiOnly.action is Action.None)
        val chargingAndWifi = eval(
            spec,
            wifiOnly.memory,
            Inputs(1, wifi = wifiLevel(-40), charging = ChargingSnapshot(true, ChargeType.USB)),
            noon(),
        )
        assertFire(chargingAndWifi, FireReason.CONDITIONS_MET)
    }

    @Test
    fun windowStartFiresOnceWhenAlreadyMatching() {
        val schedule = weekdays(9 to 18)
        val spec = RuleSpec(1, true, listOf(Clause(listOf(wifi(dwellMillis = 0)))), schedule, cooldownMillis = 0)
        var step = eval(spec, RuleMemory(), wifiLevel(-40), 0, at(DayOfWeek.SUNDAY, 12, 0))
        assertTrue(step.action is Action.None)
        assertTrue(step.memory.formulaTrue)
        assertTrue(step.memory.armed)
        step = eval(spec, step.memory, Inputs(nowMillis = 1), at(DayOfWeek.MONDAY, 9, 0))
        assertFire(step, FireReason.WINDOW_OPENED)
        step = eval(spec, step.memory, Inputs(nowMillis = 2), at(DayOfWeek.MONDAY, 10, 0))
        assertTrue(step.action is Action.None)
        step = eval(spec, step.memory, Inputs(nowMillis = 3), at(DayOfWeek.MONDAY, 18, 0))
        assertTrue(step.memory.armed)
        step = eval(spec, step.memory, Inputs(nowMillis = 4), at(DayOfWeek.TUESDAY, 9, 0))
        assertFire(step, FireReason.WINDOW_OPENED)
    }

    @Test
    fun scanMatchUsesStrongestNetworkAndDoesNotNeedAConnection() {
        val spec = rule(
            wifi(ssid = "7-11", ssidMatch = SsidMatchMode.CONTAINS, caseInsensitive = true, dwellMillis = 0),
        )
        val weak = WifiSight("shop-7-11", null, -80)
        val strong = WifiSight("7-11_FREE", null, -55)
        val other = WifiSight("Home", null, -30)
        val inputs = WifiInputs(
            connected = WifiSight("Home", null, -30),
            connectedKnown = true,
            visible = listOf(weak, other, strong),
            visibleFresh = true,
        )
        val step = eval(spec, RuleMemory(), inputs.at(0), noon())
        assertFire(step, FireReason.CONDITIONS_MET)
        assertEquals(-55, step.memory.atoms.getValue("w").lastRssi)
        val onlyWeak = eval(
            spec,
            RuleMemory(),
            WifiInputs(null, true, listOf(weak), true).at(0),
            noon(),
        )
        assertTrue(onlyWeak.action is Action.None)
    }

    @Test
    fun ssidMatchModes() {
        assertTrue(WifiIds.ssidMatches("7-11", "Guest-7-11", SsidMatchMode.CONTAINS, true))
        assertTrue(WifiIds.ssidMatches("7-11", "7-11_WiFi", SsidMatchMode.CONTAINS, true))
        assertFalse(WifiIds.ssidMatches("7-11", "711", SsidMatchMode.CONTAINS, true))
        assertFalse(WifiIds.ssidMatches("7-11", "xx7-11", SsidMatchMode.EXACT, true))
        assertTrue(WifiIds.ssidMatches("7-11", "7-11 Store", SsidMatchMode.STARTS_WITH, true))
        assertFalse(WifiIds.ssidMatches("7-11", "A7-11", SsidMatchMode.STARTS_WITH, true))
        assertTrue(WifiIds.ssidMatches("7-11.*", "7-11_FREE", SsidMatchMode.REGEX, true))
        assertFalse(WifiIds.ssidMatches("[", "7-11", SsidMatchMode.REGEX, true))
        assertFalse(WifiIds.ssidMatches("7-Eleven", "7-eleven guest", SsidMatchMode.CONTAINS, false))
        assertTrue(WifiIds.ssidMatches("7-Eleven", "7-eleven guest", SsidMatchMode.CONTAINS, true))
    }

    @Test
    fun cooldownBlocksTheNextStoreUntilItElapses() {
        val spec = rule(
            wifi(ssid = "7-11", ssidMatch = SsidMatchMode.CONTAINS, dwellMillis = 0, disappearMillis = 30_000),
        ).copy(cooldownMillis = 30L * 60L * 1000L)
        var step = eval(spec, RuleMemory(), sight("7-11 A", -50), 0)
        assertFire(step, FireReason.CONDITIONS_MET)
        step = eval(spec, step.memory, wifiAbsent(), 60_000)
        assertTrue(step.memory.armed)
        step = eval(spec, step.memory, sight("7-11 B", -48), 5 * 60_000)
        assertTrue(step.action is Action.None)
        assertFalse(step.memory.armed)
        step = eval(spec, step.memory, sight("7-11 B", -48), 31 * 60_000)
        assertTrue(step.action is Action.None)
        step = eval(spec, step.memory, wifiAbsent(), 32 * 60_000)
        step = eval(spec, step.memory, sight("7-11 C", -40), 33 * 60_000)
        assertFire(step, FireReason.CONDITIONS_MET)
    }

    @Test
    fun catalogKeepsEveryNamedNetworkAndMergesTheConnection() {
        val snapshot = WifiCatalog.summarize(
            listOf(
                RawAccessPoint("\"Home WiFi\"", "AA:BB:CC:DD:EE:01", -70, 2412, connected = false),
                RawAccessPoint("Home WiFi", "AA:BB:CC:DD:EE:02", -48, 5180, connected = false),
                RawAccessPoint("Cafe", "11:22:33:44:55:66", -90, 5745, connected = false),
                RawAccessPoint("6ghz-lab", null, -62, 6275, connected = false),
                RawAccessPoint("<unknown ssid>", "02:00:00:00:00:00", -40, 2437, connected = false),
                RawAccessPoint("", null, -30, 2412, connected = false),
                RawAccessPoint("\"Only Connected\"", "10:00:00:00:00:01", -55, 2412, connected = true),
            ),
        )
        assertEquals(7, snapshot.rawCount)
        assertEquals(2, snapshot.hiddenCount)
        assertEquals(listOf("Home WiFi", "Only Connected", "6ghz-lab", "Cafe"), snapshot.networks.map { it.ssid })
        val home = snapshot.networks.first()
        assertEquals(-48, home.rssiDbm)
        assertEquals(listOf("2.4", "5"), home.bands)
        assertFalse(home.connected)
        assertTrue(snapshot.networks[1].connected)
        assertEquals(listOf("6"), snapshot.networks[2].bands)
        assertTrue(snapshot.networks.any { it.rssiDbm == -90 })
    }

    @Test
    fun visibleRuleMatchesTheConnectedSsidWhenItIsMissingFromTheScan() {
        val spec = rule(wifi(ssid = "Home WiFi", ssidMatch = SsidMatchMode.EXACT, dwellMillis = 0))
        val inputs = WifiInputs(
            connected = WifiSight("\"Home WiFi\"", "aa:bb:cc:dd:ee:10", -42),
            connectedKnown = true,
            visible = emptyList(),
            visibleFresh = true,
        )
        assertFire(eval(spec, RuleMemory(), inputs.at(0), noon()), FireReason.CONDITIONS_MET)
    }

    @Test
    fun disabledRuleDoesNotFire() {
        val spec = rule(wifi(dwellMillis = 0)).copy(enabled = false)
        val step = eval(spec, RuleMemory(), wifiLevel(-40), 0)
        assertTrue(step.action is Action.None)
        assertTrue(step.memory.atoms.getValue("w").latched)
    }

    private fun latchWifi(spec: RuleSpec): RuleMemory {
        val started = eval(spec, RuleMemory(), wifiLevel(-50), 0)
        return eval(spec, started.memory, wifiLevel(-50), 60_000).memory
    }

    private fun assertFire(step: Evaluation, reason: FireReason) {
        val action = step.action
        assertTrue(action is Action.Fire)
        assertEquals(reason, (action as Action.Fire).reason)
        assertFalse(step.memory.armed)
    }

    private fun rule(atom: tw.geowifi.launcher.core.rule.Atom) = RuleSpec(
        id = 1,
        enabled = true,
        clauses = listOf(Clause(listOf(atom))),
        schedule = null,
        cooldownMillis = 0,
    )

    private fun wifi(
        mode: WifiMatchMode = WifiMatchMode.VISIBLE,
        dwellMillis: Long = 60_000,
        disappearMillis: Long = 180_000,
        maxGapMillis: Long = Defaults.maxGapMillis,
        ssid: String = "Home",
        ssidMatch: SsidMatchMode = SsidMatchMode.EXACT,
        caseInsensitive: Boolean = false,
    ) = WifiAtom(
        id = "w",
        ssid = ssid,
        bssid = null,
        mode = mode,
        minRssiDbm = -65,
        dwellMillis = dwellMillis,
        rearmDropDbm = 10,
        disappearMillis = disappearMillis,
        maxGapMillis = maxGapMillis,
        ssidMatch = ssidMatch,
        caseInsensitive = caseInsensitive,
    )

    private fun sight(ssid: String, rssi: Int) = WifiInputs(
        connected = null,
        connectedKnown = true,
        visible = listOf(WifiSight(ssid, null, rssi)),
        visibleFresh = true,
    )

    private fun region(
        radius: Double = 100.0,
        dwell: Long = 60_000,
        transition: LocationTransition = LocationTransition.ENTER,
    ) = RegionAtom("r", 0.0, 0.0, radius, dwell, 30.0, transition)

    private fun eval(spec: RuleSpec, memory: RuleMemory, inputs: Inputs, local: LocalDateTime = noon()): Evaluation {
        return RuleEngine.evaluate(spec, memory, inputs, local)
    }

    private fun eval(
        spec: RuleSpec,
        memory: RuleMemory,
        wifi: WifiInputs,
        now: Long,
        local: LocalDateTime = noon(),
    ): Evaluation {
        return RuleEngine.evaluate(spec, memory, Inputs(nowMillis = now, wifi = wifi), local)
    }

    private fun eval(
        spec: RuleSpec,
        memory: RuleMemory,
        sample: LocationSample,
        now: Long,
        local: LocalDateTime = noon(),
    ): Evaluation {
        return RuleEngine.evaluate(spec, memory, Inputs(nowMillis = now, sample = sample), local)
    }

    private fun eval(spec: RuleSpec, memory: RuleMemory, inputs: Inputs, now: Long, local: LocalDateTime = noon()): Evaluation {
        return eval(spec, memory, inputs.copy(nowMillis = now), local)
    }

    private fun wifiLevel(rssi: Int) = WifiInputs(
        connected = null,
        connectedKnown = false,
        visible = listOf(WifiSight("Home", "aa:bb:cc:dd:ee:01", rssi)),
        visibleFresh = true,
    )

    private fun wifiAbsent() = WifiInputs(null, false, emptyList(), visibleFresh = true)

    private fun connected(rssi: Int) = WifiInputs(
        connected = WifiSight("\"Home\"", "aa:bb:cc:dd:ee:01", rssi),
        connectedKnown = true,
        visible = emptyList(),
        visibleFresh = false,
    )

    private fun charging(type: ChargeType?) = ChargingSnapshot(pluggedIn = type != null, type = type)

    private fun sample(metersNorth: Double, accuracy: Double?) = LocationSample(
        latitude = metersNorth / GeoMath.distanceMeters(0.0, 0.0, 1.0, 0.0),
        longitude = 0.0,
        accuracyMeters = accuracy,
    )

    private fun WifiInputs.at(now: Long) = Inputs(nowMillis = now, wifi = this)
    private fun ChargingSnapshot.at(now: Long) = Inputs(nowMillis = now, charging = this)
    private fun LocationSample.at(now: Long) = Inputs(nowMillis = now, sample = this)

    private fun noon() = at(DayOfWeek.WEDNESDAY, 12, 0)

    private fun weekdays(hours: Pair<Int, Int>) = WeekSchedule(
        setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY),
        listOf(range(hours.first, 0, hours.second, 0)),
    )

    private fun range(startHour: Int, startMinute: Int, endHour: Int, endMinute: Int): TimeRange {
        return TimeRange(startHour * 60 + startMinute, endHour * 60 + endMinute)
    }

    private fun at(day: DayOfWeek, hour: Int, minute: Int): LocalDateTime {
        var date = LocalDate.of(2026, 11, 23)
        while (date.dayOfWeek != day) date = date.plusDays(1)
        return date.atTime(hour, minute)
    }
}
