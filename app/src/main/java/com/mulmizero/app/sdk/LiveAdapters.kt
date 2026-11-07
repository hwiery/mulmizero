package com.mulmizero.app.sdk

import ai.pleos.playground.vehicle.constant.area.SeatValue
import com.mulmizero.app.core.ActuationResult
import com.mulmizero.app.core.ActuationStatus
import com.mulmizero.app.core.ClimateActuator
import com.mulmizero.app.core.LocationSample
import com.mulmizero.app.core.Poi
import com.mulmizero.app.core.RouteActuator
import com.mulmizero.app.core.SensorFrame
import com.mulmizero.app.engine.FrameSource
import com.mulmizero.app.sim.FamilyTripScenario
import com.mulmizero.app.sim.SimCabin
import com.mulmizero.app.sim.SimClimateActuator

/**
 * LIVE mode: every signal comes from the Pleos SDK (or Android location) when a value has been
 * received; otherwise the scenario's simulated value is used for that signal only.
 * [sources] tells the UI which is which.
 */
class HybridFrameSource(
    private var scenarioFactory: () -> FamilyTripScenario,
    private val vehicle: VehicleBridge,
    private val navi: NaviBridge,
    private val location: () -> LocationSample?,
) : FrameSource {
    var scenario = scenarioFactory(); private set
    private val src = linkedMapOf<String, String>()

    override fun sources(): Map<String, String> = src

    override fun next(): SensorFrame {
        var sim = scenario.next()
        if (sim.gear == com.mulmizero.app.core.Gear.PARK) {   // loop the background simulation
            scenario = scenarioFactory(); sim = scenario.next()
        }
        src.clear()
        fun <T> pick(key: String, sdk: Sample<T>?, simV: T?): T? =
            if (sdk != null) { src[key] = "SDK"; sdk.value } else { src[key] = "SIM"; simV }

        val steering = pick("조향각", vehicle.steeringDeg, sim.steeringDeg)
        val brake = pick("브레이크", vehicle.brake, sim.brakePressed)
        val dtRaw = vehicle.drivingTimeRaw
        val drivingMin = if (dtRaw != null) { src["주행시간"] = "SDK"; dtRaw.value / 60.0 } else { src["주행시간"] = "SIM"; sim.drivingMinutes }
        val cabin = pick("실내온도", vehicle.cabinTempC, sim.cabinTempC)
        val target = pick("설정온도", vehicle.hvacTargetC, sim.hvacTargetC)
        val fan = pick("풍량", vehicle.fanSpeed, sim.fanSpeed)
        val recirc = pick("내기순환", vehicle.recirculation, sim.recirculation)
        val gear = pick("기어", vehicle.gear, sim.gear)

        val occ = vehicle.occupancy
        val rear: Boolean?
        val front: Boolean?
        val occSupported = vehicle.status["좌석착석"]?.let { it.startsWith("지원") || it.startsWith("수신") } == true
        if (occ.isNotEmpty() || occSupported) {
            // supported by the vehicle: never invent a passenger while waiting for the first event
            src["좌석착석"] = "SDK"
            rear = (occ[SeatValue.ROW_2_LEFT]?.value == true) || (occ[SeatValue.ROW_2_RIGHT]?.value == true)
            front = occ[SeatValue.ROW_1_RIGHT]?.value == true
        } else { src["좌석착석"] = "SIM"; rear = sim.rearOccupied; front = sim.frontPassengerOccupied }

        val tbt = navi.tbt?.takeIf { it.value.isNotEmpty() && navi.routing?.value != false }
        val turns = if (tbt != null) { src["TBT"] = "SDK"; tbt.value.sortedBy { it.distanceM } } else { src["TBT"] = "SIM"; sim.turns }

        val loc = location()
        val heading = when {
            navi.headingDeg != null -> { src["진행방향"] = "SDK"; navi.headingDeg!!.value }
            loc?.headingDeg != null -> { src["진행방향"] = "Android"; loc.headingDeg }
            else -> { src["진행방향"] = "SIM"; sim.headingDeg }
        }
        val speed = if (loc?.speedMps != null) { src["속도"] = "Android"; loc.speedMps } else { src["속도"] = "SIM"; sim.speedMps }

        return sim.copy(
            drivingMinutes = drivingMin,
            steeringDeg = steering, brakePressed = brake, speedMps = speed, headingDeg = heading,
            cabinTempC = cabin, hvacTargetC = target, fanSpeed = fan, recirculation = recirc, gear = gear,
            rearOccupied = rear, frontPassengerOccupied = front, turns = turns,
            roadLabel = if (tbt != null) "실제 경로 안내" else sim.roadLabel,
        )
    }
}

/**
 * Climate care: always tries the Pleos Vehicle SDK first. The simulated cabin is updated
 * too, so the demo stays consistent when the SDK call is rejected (no App Market approval).
 */
class PleosClimateActuator(private val vehicle: VehicleBridge, private val cabin: SimCabin) : ClimateActuator {
    override fun applyCare(level: Int, onResult: (ActuationResult) -> Unit) {
        cabin.applyCare(level)
        vehicle.applyCare(level) { r -> onResult(withSim(r)) }
    }

    override fun restore(onResult: (ActuationResult) -> Unit) {
        cabin.restore()
        vehicle.restore { r -> onResult(withSim(r)) }
    }

    private fun withSim(r: ActuationResult) =
        if (r.status == ActuationStatus.APPLIED_SDK) r
        else r.copy(detail = "${r.detail} ⇒ 시뮬레이션: ${SimClimateActuator.describe(cabin)}")
}

/** Route actions: NaviHelper first; the scenario route is updated either way (demo route is simulated). */
class PleosRouteActuator(private val navi: NaviBridge, private val scenario: () -> FamilyTripScenario) : RouteActuator {
    override fun addWaypoint(poi: Poi, onResult: (ActuationResult) -> Unit) {
        scenario().onWaypointAdded()
        navi.addWaypoint(poi, onResult)
    }

    override fun requestComfortRoute(onResult: (ActuationResult) -> Unit) {
        scenario().onComfortRoute()
        navi.requestComfortRoute(onResult)
    }
}
