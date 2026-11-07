package com.mulmizero.app.sdk

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.api.model.capability.AreaBounds
import ai.pleos.playground.vehicle.api.model.capability.CapabilityConfig
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.constant.control.VehicleGear
import ai.pleos.playground.vehicle.constant.unit.TemperatureUnit
import ai.pleos.playground.vehicle.listener.BrakePedalStatusListener
import ai.pleos.playground.vehicle.listener.DrivingTimeListener
import ai.pleos.playground.vehicle.listener.GearSelectionListener
import ai.pleos.playground.vehicle.listener.HvacFanSpeedListener
import ai.pleos.playground.vehicle.listener.HvacPowerStateListener
import ai.pleos.playground.vehicle.listener.HvacRecirculationStateListener
import ai.pleos.playground.vehicle.listener.HvacTemperatureListener
import ai.pleos.playground.vehicle.listener.SeatOccupancyListener
import ai.pleos.playground.vehicle.listener.SteeringWheelAngleListener
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.mulmizero.app.core.ActuationResult
import com.mulmizero.app.core.ActuationStatus
import com.mulmizero.app.core.Gear
import java.util.concurrent.ConcurrentHashMap

/** A value received from the vehicle, with the time it arrived. */
data class Sample<T>(val value: T, val atMs: Long = SystemClock.elapsedRealtime())

/**
 * Pleos Vehicle SDK (ai.pleos.playground:Vehicle:2.3.2) adapter.
 * Only APIs verified in the AAR are used. Every call is wrapped: capability check →
 * get + register listener; failures are recorded per signal and the app falls back
 * to simulated values for that signal.
 */
class VehicleBridge(context: Context, private val log: (String) -> Unit) {
    private val ctx = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private var vehicle: Vehicle? = null

    @Volatile var steeringDeg: Sample<Double>? = null; private set
    @Volatile var brake: Sample<Boolean>? = null; private set
    @Volatile var drivingTimeRaw: Sample<Int>? = null; private set
    @Volatile var cabinTempC: Sample<Double>? = null; private set
    @Volatile var hvacTargetC: Sample<Double>? = null; private set
    @Volatile var fanSpeed: Sample<Int>? = null; private set
    @Volatile var recirculation: Sample<Boolean>? = null; private set
    @Volatile var gear: Sample<Gear>? = null; private set
    @Volatile var hvacPower: Sample<Boolean>? = null; private set
    @Volatile private var powerArea: SeatArea? = null
    val occupancy = ConcurrentHashMap<SeatValue, Sample<Boolean>>()

    /** signal → status text shown in the "데이터 출처" panel. */
    val status = ConcurrentHashMap<String, String>()
    @Volatile var initialized = false; private set

    private val driverZone = SeatArea(SeatValue.ROW_1_LEFT)
    @Volatile private var curArea: SeatArea? = null
    @Volatile private var tempArea: SeatArea? = null
    @Volatile private var fanArea: SeatArea? = null
    @Volatile private var recircArea: SeatArea? = null
    @Volatile private var ventArea: SeatArea? = null
    @Volatile private var tempBounds: ClosedFloatingPointRange<Double> = 16.0..30.0
    @Volatile private var fanMax: Int = 8
    val areaInfo = ConcurrentHashMap<String, String>()

    /** Prefer the zone that contains the driver seat, else the first reported zone. */
    private fun pickArea(areas: Collection<SeatArea>): SeatArea? =
        areas.firstOrNull { a -> a.values.any { it == SeatValue.ROW_1_LEFT } } ?: areas.firstOrNull()

    private fun areaName(a: SeatArea) = a.values.joinToString("+") { it.name }
    private val listeners = mutableListOf<() -> Unit>()

    fun start() {
        try {
            vehicle = Vehicle(ctx).also { it.initialize() }
            initialized = true
            status["Vehicle"] = "initialize() 호출됨"
            log("Vehicle SDK initialize()")
        } catch (t: Throwable) {
            status["Vehicle"] = "초기화 실패: ${t.javaClass.simpleName}"
            log("Vehicle SDK 초기화 실패: $t")
            return
        }
        // the service binds asynchronously; subscribe after a short delay and retry once
        main.postDelayed({ subscribeAll() }, 1500)
        main.postDelayed({ if (steeringDeg == null && brake == null && gear == null) subscribeAll() }, 6000)
    }

    fun stop() {
        main.removeCallbacksAndMessages(null)
        listeners.forEach { runCatching(it) }
        listeners.clear()
        runCatching { vehicle?.release() }
        vehicle = null
    }

    private fun fail(key: String, e: Throwable) {
        status[key] = "실패: ${shortError(e)}"
    }

    private inline fun guard(key: String, block: () -> Unit) {
        try { block() } catch (t: Throwable) { fail(key, t) }
    }

    private fun capOk(key: String, cfg: CapabilityConfig?) {
        val acc = cfg?.accessType?.name ?: "?"
        if (status[key]?.startsWith("수신") != true) status[key] = "지원($acc)"
    }

    private fun subscribeAll() {
        val v = vehicle ?: return
        listeners.forEach { runCatching(it) }
        listeners.clear()

        // ---- Steering wheel angle (CAR_DRIVE) ----
        guard("조향각") {
            val sw = v.getSteeringWheel()
            sw.checkSteeringWheelAngleCapabilities(
                { cfg: CapabilityConfig, _: AreaBounds<Float> -> capOk("조향각", cfg) },
                { e: Exception -> fail("조향각", e) },
            )
            sw.getCurrentSteeringWheelAngle({ a: Float? -> a?.let { onSteering(it) } }, { e: Exception -> fail("조향각", e) })
            val l = object : SteeringWheelAngleListener {
                override fun onSteeringWheelAngleUpdated(p0: Float?) { p0?.let { onSteering(it) } }
                override fun onFailed(p0: Exception) { fail("조향각", p0) }
            }
            sw.registerSteeringWheelAngle(l)
            listeners += { sw.unregisterSteeringWheelAngle(l) }
        }

        // ---- Brake pedal (CAR_DRIVE) ----
        guard("브레이크") {
            val br = v.getBrake()
            br.checkBrakePedalStatusCapabilities({ cfg: CapabilityConfig -> capOk("브레이크", cfg) }, { e: Exception -> fail("브레이크", e) })
            br.getCurrentBrakePedalStatus({ b: Boolean? -> b?.let { onBrake(it) } }, { e: Exception -> fail("브레이크", e) })
            val l = object : BrakePedalStatusListener {
                override fun onBrakePedalStatusUpdated(p0: Boolean?) { p0?.let { onBrake(it) } }
                override fun onFailed(p0: Exception) { fail("브레이크", p0) }
            }
            br.registerBrakePedalStatus(l)
            listeners += { br.unregisterBrakePedalStatus(l) }
        }

        // ---- Trip driving time (CAR_DRIVE) ----
        guard("주행시간") {
            val od = v.getOdometer()
            od.checkTripCurrentDrivingTimeCapabilities({ cfg: CapabilityConfig -> capOk("주행시간", cfg) }, { e: Exception -> fail("주행시간", e) })
            od.getTripCurrentDrivingTime({ t: Int? -> t?.let { onDrivingTime(it) } }, { e: Exception -> fail("주행시간", e) })
            val l = object : DrivingTimeListener {
                override fun onDrivingTimeUpdated(p0: Int?) { p0?.let { onDrivingTime(it) } }
                override fun onFailed(p0: Exception) { fail("주행시간", p0) }
            }
            od.registerTripCurrentDrivingTime(l)
            listeners += { od.unregisterTripCurrentDrivingTime(l) }
        }

        // ---- Gear (CAR_DRIVE) ----
        guard("기어") {
            val g = v.getGear()
            g.getCurrentGearSelectionState({ x: VehicleGear? -> x?.let { onGear(it) } }, { e: Exception -> fail("기어", e) })
            val l = object : GearSelectionListener {
                override fun onSelected(p0: VehicleGear?) { p0?.let { onGear(it) } }
                override fun onFailed(p0: Exception) { fail("기어", p0) }
            }
            g.registerGearSelection(l)
            listeners += { g.unregisterGearSelection(l) }
        }

        // ---- HVAC state (CAR_CLIMATE) ----
        // HVAC zones differ per vehicle: take the area ids from the capability results
        // (hard-coding the driver seat fails with "area ID not supported").
        guard("실내온도") {
            val h = v.getHVAC()
            h.checkHvacTemperatureCurrentCapabilities(
                { cfg: CapabilityConfig, areas: List<SeatArea> ->
                    capOk("실내온도", cfg)
                    val a = pickArea(areas) ?: driverZone
                    curArea = a
                    guard("실내온도") {
                        h.getHvacTemperatureCurrent(a, { t: Float? -> t?.let { onCabinTemp(it) } }, { e: Exception -> fail("실내온도", e) })
                        val l = object : HvacTemperatureListener {
                            override fun onHvacTemperatureUpdated(p0: Float?) { p0?.let { onCabinTemp(it) } }
                            override fun onFailed(p0: Exception) { fail("실내온도", p0) }
                        }
                        h.registerHvacTemperatureCurrent(a, l)
                        listeners += { h.unregisterHvacTemperatureCurrent(a, l) }
                    }
                },
                { e: Exception -> fail("실내온도", e) },
            )
        }
        guard("설정온도") {
            val h = v.getHVAC()
            h.checkHvacTemperatureCapabilities(
                { cfg: CapabilityConfig, m: Map<SeatArea, AreaBounds<Float>> ->
                    capOk("설정온도", cfg)
                    val a = pickArea(m.keys) ?: return@checkHvacTemperatureCapabilities
                    tempArea = a
                    m[a]?.let { tempBounds = it.minValue.toDouble()..it.maxValue.toDouble() }
                    areaInfo["설정온도"] = areaName(a)
                    log("HVAC 설정온도 영역(capability): ${areaName(a)}")
                    guard("설정온도") {
                        h.getHvacTemperature(a, TemperatureUnit.CELSIUS, { t: Float? -> t?.let { onTarget(it) } }, { e: Exception -> fail("설정온도", e) })
                        val l = object : HvacTemperatureListener {
                            override fun onHvacTemperatureUpdated(p0: Float?) { p0?.let { onTarget(it) } }
                            override fun onFailed(p0: Exception) { fail("설정온도", p0) }
                        }
                        h.registerHvacTemperature(a, l)
                        listeners += { h.unregisterHvacTemperature(a, l) }
                    }
                },
                { e: Exception -> fail("설정온도", e) },
            )
        }
        guard("풍량") {
            val h = v.getHVAC()
            h.checkHvacFanSpeedCapabilities(
                { cfg: CapabilityConfig, m: Map<SeatArea, AreaBounds<Int>> ->
                    capOk("풍량", cfg)
                    val a = pickArea(m.keys) ?: return@checkHvacFanSpeedCapabilities
                    fanArea = a
                    m[a]?.let { fanMax = it.maxValue }
                    areaInfo["풍량"] = areaName(a)
                    log("HVAC 풍량 영역(capability): ${areaName(a)}")
                    guard("풍량") {
                        h.getHvacFanSpeed(a, { f: Int? -> f?.let { onFan(it) } }, { e: Exception -> fail("풍량", e) })
                        val l = object : HvacFanSpeedListener {
                            override fun onHvacFanSpeedUpdated(p0: Int?) { p0?.let { onFan(it) } }
                            override fun onFailed(p0: Exception) { fail("풍량", p0) }
                        }
                        h.registerHvacFanSpeed(a, l)
                        listeners += { h.unregisterHvacFanSpeed(a, l) }
                    }
                },
                { e: Exception -> fail("풍량", e) },
            )
        }
        guard("내기순환") {
            val h = v.getHVAC()
            h.checkHvacRecirculationCapabilities(
                { cfg: CapabilityConfig, areas: List<SeatArea> ->
                    capOk("내기순환", cfg)
                    val a = pickArea(areas) ?: return@checkHvacRecirculationCapabilities
                    recircArea = a
                    areaInfo["내기순환"] = areaName(a)
                    log("HVAC 내기순환 영역(capability): ${areaName(a)}")
                    guard("내기순환") {
                        h.getHvacRecirculationState(a, { b: Boolean? -> b?.let { onRecirc(it) } }, { e: Exception -> fail("내기순환", e) })
                        val l = object : HvacRecirculationStateListener {
                            override fun onHvacAutoRecirculationStateUpdated(p0: Boolean?) { p0?.let { onRecirc(it) } }
                            override fun onFailed(p0: Exception) { fail("내기순환", p0) }
                        }
                        h.registerHvacRecirculationState(a, l)
                        listeners += { h.unregisterHvacRecirculationState(a, l) }
                    }
                },
                { e: Exception -> fail("내기순환", e) },
            )
        }
        guard("공조전원") {
            val h = v.getHVAC()
            h.checkHvacPowerCapabilities(
                { cfg: CapabilityConfig, areas: List<SeatArea> ->
                    capOk("공조전원", cfg)
                    val a = pickArea(areas) ?: return@checkHvacPowerCapabilities
                    powerArea = a
                    guard("공조전원") {
                        h.getHvacPowerState(a, { b: Boolean? -> b?.let { onPower(it) } }, { e: Exception -> fail("공조전원", e) })
                        val l = object : HvacPowerStateListener {
                            override fun onHvacPowerStateUpdated(p0: Boolean?) { p0?.let { onPower(it) } }
                            override fun onFailed(p0: Exception) { fail("공조전원", p0) }
                        }
                        h.registerHvacPowerState(a, l)
                        listeners += { h.unregisterHvacPowerState(a, l) }
                    }
                },
                { e: Exception -> fail("공조전원", e) },
            )
        }
        guard("통풍시트") {
            v.getSeat().checkSeatVentilationCapabilities(
                { cfg: CapabilityConfig, m: Map<SeatArea, AreaBounds<Int>> ->
                    val rear = m.keys.firstOrNull { k -> k.values.any { it == SeatValue.ROW_2_LEFT || it == SeatValue.ROW_2_RIGHT } }
                    ventArea = rear
                    status["통풍시트"] = if (rear != null) "지원(${cfg.accessType.name}, ${areaName(rear)})" else "2열 미지원(${m.keys.joinToString { areaName(it) }})"
                },
                { e: Exception -> fail("통풍시트", e) },
            )
        }

        // ---- Seat occupancy (CAR_SEATS) ----
        guard("좌석착석") {
            val s = v.getSeat()
            s.checkSeatOccupancyCapabilities(
                { cfg: CapabilityConfig, areas: List<SeatArea> ->
                    capOk("좌석착석", cfg)
                    status["좌석착석"] = "지원(${cfg.accessType.name}, ${areas.size}석)"
                },
                { e: Exception -> fail("좌석착석", e) },
            )
            for (seat in listOf(SeatValue.ROW_1_RIGHT, SeatValue.ROW_2_LEFT, SeatValue.ROW_2_RIGHT)) {
                val area = SeatArea(seat)
                s.getCurrentSeatOccupancy(area, { b: Boolean? -> b?.let { onSeat(seat, it) } }, { e: Exception -> fail("좌석착석", e) })
                val l = object : SeatOccupancyListener {
                    override fun onSeatOccupancyUpdated(p0: Boolean?) { p0?.let { onSeat(seat, it) } }
                    override fun onFailed(p0: Exception) { fail("좌석착석", p0) }
                }
                s.registerSeatOccupancy(area, l)
                listeners += { s.unregisterSeatOccupancy(area, l) }
            }
        }
        log("Vehicle SDK 리스너 등록: 조향각·브레이크·주행시간·기어·HVAC·좌석")
        main.removeCallbacks(seatPoll)
        main.postDelayed(seatPoll, 2000)
    }

    /**
     * SeatOccupancyListener carries no area id and, on the emulator, later changes were delivered to
     * only one of several per-seat registrations. Poll each seat every 2 s as well.
     */
    private val seatPoll = object : Runnable {
        override fun run() {
            val v = vehicle ?: return
            runCatching {
                val s = v.getSeat()
                for (seat in listOf(SeatValue.ROW_1_RIGHT, SeatValue.ROW_2_LEFT, SeatValue.ROW_2_RIGHT)) {
                    s.getCurrentSeatOccupancy(SeatArea(seat), { b: Boolean? -> b?.let { onSeat(seat, it) } }, { _: Exception -> })
                }
            }
            main.postDelayed(this, 2000)
        }
    }

    private fun recv(key: String, text: String) { status[key] = "수신 $text" }
    private fun onSteering(a: Float) { steeringDeg = Sample(a.toDouble()); recv("조향각", "%.0f°".format(a)) }
    private fun onBrake(b: Boolean) { brake = Sample(b); recv("브레이크", if (b) "밟음" else "뗌") }
    private fun onDrivingTime(t: Int) { drivingTimeRaw = Sample(t); recv("주행시간", "$t") }
    private fun onCabinTemp(t: Float) { cabinTempC = Sample(t.toDouble()); recv("실내온도", "%.1f℃".format(t)) }
    private fun onTarget(t: Float) { hvacTargetC = Sample(t.toDouble()); recv("설정온도", "%.1f℃".format(t)) }
    private fun onPower(b: Boolean) { hvacPower = Sample(b); recv("공조전원", if (b) "ON" else "OFF") }
    private fun onFan(f: Int) { fanSpeed = Sample(f); recv("풍량", "$f") }
    private fun onRecirc(b: Boolean) { recirculation = Sample(b); recv("내기순환", if (b) "내기" else "외기") }
    private fun onGear(g: VehicleGear) {
        val m = when (g) {
            VehicleGear.GEAR_PARK -> Gear.PARK
            VehicleGear.GEAR_REVERSE -> Gear.REVERSE
            VehicleGear.GEAR_NEUTRAL -> Gear.NEUTRAL
            VehicleGear.GEAR_UNKNOWN -> Gear.UNKNOWN
            else -> Gear.DRIVE
        }
        gear = Sample(m); recv("기어", g.name.removePrefix("GEAR_"))
    }
    private fun onSeat(seat: SeatValue, raw: Boolean) {
        val b = if (OCCUPANCY_BOOLEAN_INVERTED) !raw else raw
        val prev = occupancy[seat]?.value
        occupancy[seat] = Sample(b)
        if (prev != b) android.util.Log.i("MulmiZero", "SeatOccupancy ${seat.name} sdk=$raw → occupied=$b")
        val txt = occupancy.entries.sortedBy { it.key.ordinal }
            .joinToString(" ") { "${seatLabel(it.key)}${if (it.value.value) "●" else "○"}" }.replace("2열좌", "2L").replace("2열우", "2R").replace("조수석", "조")
        recv("좌석착석", txt)
    }

    // ------------------------------------------------------------------ control
    private data class Saved(val target: Double?, val fan: Int?, val recirc: Boolean?, val power: Boolean?)
    private var saved: Saved? = null

    /**
     * Try to apply care through the Vehicle SDK (needs CONTROL_CAR_CLIMATE / CONTROL_CAR_SEATS,
     * granted only after Pleos App Market approval). [onResult] reports what actually happened.
     */
    fun applyCare(level: Int, onResult: (ActuationResult) -> Unit) {
        val v = vehicle ?: return onResult(ActuationResult(ActuationStatus.UNSUPPORTED, "Vehicle SDK 미연결"))
        if (saved == null) saved = Saved(
            hvacTargetC?.value?.takeIf { it > 0.0 } ?: 24.0, fanSpeed?.value, recirculation?.value, hvacPower?.value,
        )
        val base = saved!!
        val baseTarget = base.target ?: 24.0
        val target = (baseTarget - 2.0).coerceIn(tempBounds.start, tempBounds.endInclusive)
        val fan = ((base.fan ?: 2) + if (level >= 3) 2 else 1).coerceAtMost(fanMax)
        val calls = mutableListOf<Pair<String, (ok: () -> Unit, err: (Exception) -> Unit) -> Unit>>()
        val h = v.getHVAC()
        val ra = recircArea ?: driverZone
        val ta = tempArea ?: driverZone
        val fa = fanArea ?: driverZone
        calls += "setHvacRecirculation(false)" to { ok, err -> h.setHvacRecirculation(ra, false, { _: SeatArea, _: Boolean? -> ok() }, { e: Exception -> err(e) }) }
        calls += "setHvacTemperature(%.1f)".format(target) to { ok, err ->
            h.setHvacTemperature(ta, target.toFloat(), TemperatureUnit.CELSIUS, { _: SeatArea, _: Float? -> ok() }, { e: Exception -> err(e) })
        }
        val pa = powerArea
        if (pa != null && hvacPower?.value == false) {
            // fan speed can only be changed with the HVAC powered on
            calls += "setHvacPower(true)+setHvacFanSpeed($fan)" to { ok, err ->
                h.setHvacPower(pa, true, { _: SeatArea, _: Boolean? ->
                    main.postDelayed({
                        h.setHvacFanSpeed(fa, fan, { _: SeatArea, _: Int? -> ok() }, { e: Exception -> err(e) })
                    }, 800)
                }, { e: Exception -> err(e) })
            }
        } else {
            calls += "setHvacFanSpeed($fan)" to { ok, err -> h.setHvacFanSpeed(fa, fan, { _: SeatArea, _: Int? -> ok() }, { e: Exception -> err(e) }) }
        }
        val va = ventArea
        if (level >= 3 && va != null) {
            val s = v.getSeat()
            calls += "setHvacSeatVentilation(2열, 1)" to { ok, err ->
                s.setHvacSeatVentilation(va, 1, { _: SeatArea, _: Int? -> ok() }, { e: Exception -> err(e) })
            }
        }
        runCalls(calls, onResult)
    }

    fun restore(onResult: (ActuationResult) -> Unit) {
        val v = vehicle ?: return onResult(ActuationResult(ActuationStatus.UNSUPPORTED, "Vehicle SDK 미연결"))
        val s = saved ?: return onResult(ActuationResult(ActuationStatus.SIMULATED, "복귀할 저장값 없음"))
        saved = null
        val h = v.getHVAC()
        val calls = mutableListOf<Pair<String, (ok: () -> Unit, err: (Exception) -> Unit) -> Unit>>()
        val ra = recircArea ?: driverZone
        val ta = tempArea ?: driverZone
        val fa = fanArea ?: driverZone
        s.recirc?.let { r -> calls += "setHvacRecirculation($r)" to { ok, err -> h.setHvacRecirculation(ra, r, { _: SeatArea, _: Boolean? -> ok() }, { e: Exception -> err(e) }) } }
        s.target?.takeIf { it > 0.0 }?.let { t -> calls += "setHvacTemperature(%.1f)".format(t) to { ok, err -> h.setHvacTemperature(ta, t.toFloat(), TemperatureUnit.CELSIUS, { _: SeatArea, _: Float? -> ok() }, { e: Exception -> err(e) }) } }
        val pa = powerArea
        if (s.power == false && pa != null) {
            calls += "setHvacPower(false)" to { ok, err -> h.setHvacPower(pa, false, { _: SeatArea, _: Boolean? -> ok() }, { e: Exception -> err(e) }) }
        } else {
            s.fan?.let { f -> calls += "setHvacFanSpeed($f)" to { ok, err -> h.setHvacFanSpeed(fa, f, { _: SeatArea, _: Int? -> ok() }, { e: Exception -> err(e) }) } }
        }
        if (calls.isEmpty()) return onResult(ActuationResult(ActuationStatus.SIMULATED, "SDK에서 읽은 원래 값 없음"))
        runCalls(calls, onResult)
    }

    private fun runCalls(
        calls: List<Pair<String, (ok: () -> Unit, err: (Exception) -> Unit) -> Unit>>,
        onResult: (ActuationResult) -> Unit,
    ) {
        val results = ConcurrentHashMap<String, String>()
        var done = false
        fun finish() {
            if (done) return
            done = true
            val okCount = results.values.count { it == "OK" }
            val errs = results.filterValues { it != "OK" }
            val permission = errs.values.any { it.contains("PERMISSION", true) || it.contains("Security", true) }
            val status = when {
                calls.isNotEmpty() && okCount == calls.size -> ActuationStatus.APPLIED_SDK
                okCount > 0 -> ActuationStatus.PARTIAL_SDK
                permission -> ActuationStatus.NEEDS_APPROVAL
                else -> ActuationStatus.UNSUPPORTED
            }
            val detail = calls.joinToString(", ") { (name, _) -> "$name→${results[name] ?: "응답 없음"}" }
            onResult(ActuationResult(status, detail))
        }
        for ((name, call) in calls) {
            try {
                call({ main.post { results[name] = "OK"; if (results.size == calls.size) finish() } },
                    { e -> main.post { results[name] = shortError(e); if (results.size == calls.size) finish() } })
            } catch (t: Throwable) {
                results[name] = shortError(t)
            }
        }
        if (results.size == calls.size) main.post { finish() }
        main.postDelayed({ finish() }, 3000)
    }

    companion object {
        /**
         * Observed on Pleos Connect Emulator v2.1 with Vehicle SDK 2.3.2: the Boolean delivered by
         * getCurrentSeatOccupancy / SeatOccupancyListener is `true` for VHAL SEAT_OCCUPANCY=1 (VACANT)
         * and `false` for 2 (OCCUPIED) — see README "알려진 이슈". Re-verify on a real vehicle.
         */
        const val OCCUPANCY_BOOLEAN_INVERTED = true

        fun shortError(e: Throwable): String {
            val m = (e.message ?: "").replace("\n", " ").replace("java.lang.", "").take(70)
            return if (e is SecurityException) "SecurityException(권한) $m" else "${e.javaClass.simpleName} $m".trim()
        }

        fun seatLabel(s: SeatValue) = when (s) {
            SeatValue.ROW_1_LEFT -> "운전석"
            SeatValue.ROW_1_RIGHT -> "조수석"
            SeatValue.ROW_2_LEFT -> "2열좌"
            SeatValue.ROW_2_RIGHT -> "2열우"
            else -> s.name
        }
    }
}
