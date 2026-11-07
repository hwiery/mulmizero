package com.mulmizero.app.sim

import com.mulmizero.app.core.Direction
import com.mulmizero.app.core.Gear
import com.mulmizero.app.core.Poi
import com.mulmizero.app.core.SensorFrame
import com.mulmizero.app.core.TurnKind
import com.mulmizero.app.core.UpcomingTurn
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * "주말 가족여행" — 서울 → 강릉, 7세 아이가 뒷좌석. Compressed to ≈ 2 min of playback.
 *
 * Positions are kept in route metres (s) so TBT distances, steering and heading
 * stay mutually consistent. The scenario reacts to app actions:
 * [onWaypointAdded] and [onComfortRoute] change the remaining route.
 *
 * Timeline at 1 frame/s (approx.):
 *   0–8 s    고속도로 순항, 뒷좌석 착석 감지 → 케어 제안
 *   8–31 s   IC 진출 접근 (1.7 km → 0), 연속 커브 구간 예고, 지수 상승 → 공조 케어 제안
 *   31–80 s  국도 연속 커브: 회전 예고 음성, 급제동 2회(코칭), 74 s "멀미나"
 *   80–120 s 휴게소 경유 + 편안한 길, 지수 하강, 공조 자동 복귀
 *   ~122 s   휴게소 도착 → 기어 P → 트립 리포트
 */
class FamilyTripScenario(private val cabin: SimCabin) {

    private data class RoutePoint(val id: String, val kind: TurnKind, val atM: Double, val desc: String)

    enum class Phase(val label: String) {
        HIGHWAY("고속도로"), IC_APPROACH("IC 진출 접근"), CURVES("국도 커브 구간"),
        COMFORT("편안한 길"), ARRIVING("휴게소 진입"), PARKED("주차")
    }

    companion object {
        const val START_TRIP_SEC = 70 * 60.0
        const val IC_AT = 2180.0
        const val CURVE_START = IC_AT + 250.0
        const val CURVE_GAP = 420.0
        const val REST_AREA_AT = 12325.0
        val REST_AREA = Poi("대관령 휴게소", 8.4, 37.6867, 128.7553)
        /** Frames (seconds) at which the scripted events happen in playback. */
        const val SICK_FRAME = 74
        val HARD_BRAKE_FRAMES = setOf(46, 66)
        private val CURVE_PATTERN = listOf(
            TurnKind.SHARP_LEFT, TurnKind.RIGHT, TurnKind.SHARP_RIGHT, TurnKind.LEFT, TurnKind.SHARP_LEFT,
            TurnKind.SHARP_RIGHT, TurnKind.LEFT, TurnKind.RIGHT, TurnKind.SHARP_LEFT, TurnKind.RIGHT,
        )
    }

    var frame = 0; private set
    var s = 0.0; private set
    var tripSec = START_TRIP_SEC; private set
    var speed = 27.0; private set
    var heading = 78.0; private set
    var steering = 0.0; private set
    var phase = Phase.HIGHWAY; private set
    var waypointAdded = false; private set
    var comfortRoute = false; private set
    private var stoppedFrames = 0

    private var route: MutableList<RoutePoint> = buildOriginalRoute()

    private fun buildOriginalRoute(): MutableList<RoutePoint> {
        val list = mutableListOf(RoutePoint("ic", TurnKind.IC_RIGHT, IC_AT, "강릉·대관령 방면 IC 진출"))
        CURVE_PATTERN.forEachIndexed { k, kind ->
            list += RoutePoint("c$k", kind, CURVE_START + CURVE_GAP * k, "연속 커브 ${k + 1}")
        }
        list += RoutePoint("ra", TurnKind.REST_AREA, REST_AREA_AT, "목적지 방면")
        return list
    }

    fun onWaypointAdded() {
        waypointAdded = true
        route.removeAll { it.id == "ra" }
        route += RoutePoint("ra", TurnKind.REST_AREA, REST_AREA_AT, "경유지: ${REST_AREA.name}")
    }

    fun onComfortRoute() {
        comfortRoute = true
        // keep only manoeuvres that cannot be avoided any more (< 300 m), re-plan the rest
        route.removeAll { it.id.startsWith("c") && it.atM - s > 300 }
        route += RoutePoint("k1", TurnKind.KEEP_RIGHT, s + 1500, "편안한 길: 우측 방향 유지")
        route += RoutePoint("k2", TurnKind.KEEP_LEFT, s + 4200, "편안한 길: 좌측 방향 유지")
    }

    private val curvesEnd get() = route.filter { it.id.startsWith("c") }.maxOfOrNull { it.atM } ?: 0.0

    fun next(): SensorFrame {
        val t = frame
        // ---------------- progress & phase ----------------
        val distToEnd = REST_AREA_AT - s
        phase = when {
            stoppedFrames > 0 || distToEnd <= 0 -> Phase.PARKED
            distToEnd < 600 -> Phase.ARRIVING
            s < IC_AT - 1800 -> Phase.HIGHWAY
            s < IC_AT + 30 -> Phase.IC_APPROACH
            comfortRoute -> Phase.COMFORT
            s < curvesEnd + 200 -> Phase.CURVES
            else -> Phase.COMFORT
        }
        val progress = when (phase) {
            Phase.HIGHWAY -> 60.0
            Phase.IC_APPROACH -> 75.0
            Phase.CURVES -> 40.0
            Phase.COMFORT -> if (comfortRoute) 200.0 else 120.0
            Phase.ARRIVING -> 120.0
            Phase.PARKED -> 0.0
        }
        tripSec += when (phase) {
            Phase.COMFORT, Phase.ARRIVING -> 40.0
            Phase.PARKED -> 5.0
            else -> 15.0
        }

        // ---------------- braking ----------------
        val nextCurve = route.filter { it.kind.severity >= 0.6 && it.atM - s >= 0 }.minByOrNull { it.atM - s }
        val dNext = nextCurve?.let { it.atM - s } ?: Double.MAX_VALUE
        var brake = when (phase) {
            Phase.CURVES -> dNext in 60.0..150.0
            Phase.IC_APPROACH -> t == 12 || t == 13 || t == 19 || dNext in 120.0..300.0
            Phase.ARRIVING, Phase.PARKED -> true
            else -> false
        }
        val hard = t in HARD_BRAKE_FRAMES && phase == Phase.CURVES
        if (hard) brake = true

        // ---------------- speed ----------------
        val target = when (phase) {
            Phase.HIGHWAY -> 27.0
            Phase.IC_APPROACH -> if (dNext < 600) 18.0 else 25.0
            Phase.CURVES -> 16.0
            Phase.COMFORT -> 22.0
            Phase.ARRIVING -> 6.0
            Phase.PARKED -> 0.0
        }
        speed = when {
            hard -> speed - 4.5
            brake -> speed - 1.5
            speed < target -> min(target, speed + 1.2)
            else -> max(target, speed - 1.0)
        }.coerceAtLeast(0.0)
        if (phase == Phase.PARKED) {
            speed = max(0.0, (speed + 1.5) - 2.5) // gentle stop: −2.5 m/s per frame in total
            if (speed <= 0.5) stoppedFrames++
        }

        s += progress

        // ---------------- steering & heading ----------------
        var steer = 0.0
        for (p in route) {
            val amp = when (p.kind) {
                TurnKind.SHARP_LEFT, TurnKind.SHARP_RIGHT -> 170.0
                TurnKind.LEFT, TurnKind.RIGHT -> 100.0
                TurnKind.IC_LEFT, TurnKind.IC_RIGHT -> 70.0
                TurnKind.KEEP_LEFT, TurnKind.KEEP_RIGHT -> 20.0
                TurnKind.REST_AREA -> 40.0
                else -> 0.0
            }
            val sign = if (p.kind.direction == Direction.LEFT) -1.0 else 1.0
            val x = (s - p.atM) / 150.0
            steer += sign * amp * exp(-x * x)
        }
        steer += 3.0 * sin(t * 1.7) // lane keeping noise
        // a real steering wheel cannot jump: rate-limit (also hides route re-planning in compressed playback)
        steering += (steer - steering).coerceIn(-70.0, 70.0)
        if (phase != Phase.PARKED) heading = (heading + steering * 0.075 + 360.0) % 360.0

        cabin.step()
        frame++

        val turns = route
            .map { UpcomingTurn(it.id, it.kind, it.atM - s, it.desc) }
            .filter { it.distanceM >= 0 && it.distanceM <= 3000 }
            .sortedBy { it.distanceM }
            .take(6)

        val restKm = (REST_AREA_AT - s) / 1000.0
        val rest = if (phase == Phase.CURVES || phase == Phase.COMFORT || phase == Phase.ARRIVING)
            REST_AREA.copy(distanceKm = max(0.0, restKm)) else null

        return SensorFrame(
            tripTimeSec = tripSec,
            drivingMinutes = tripSec / 60.0,
            steeringDeg = steering,
            brakePressed = brake,
            speedMps = speed,
            headingDeg = heading,
            cabinTempC = cabin.cabinC,
            hvacTargetC = cabin.targetC,
            fanSpeed = cabin.fanSpeed,
            recirculation = cabin.recirculation,
            gear = if (stoppedFrames >= 2) Gear.PARK else Gear.DRIVE,
            rearOccupied = true,
            frontPassengerOccupied = false,
            turns = turns,
            restAreaAhead = rest,
            roadLabel = phase.label,
        )
    }
}
