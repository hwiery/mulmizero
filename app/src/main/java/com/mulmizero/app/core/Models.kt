package com.mulmizero.app.core

/**
 * Pure-Kotlin domain model. Nothing in core/ depends on Android or the Pleos SDK,
 * so the risk logic can be unit tested on the JVM.
 */

enum class Direction { LEFT, RIGHT, NONE }

/** Turn categories derived from NaviHelper TBT TurnType (see sdk/TurnMapper.kt). */
enum class TurnKind(val severity: Double, val direction: Direction, val label: String) {
    STRAIGHT(0.0, Direction.NONE, "직진"),
    KEEP_LEFT(0.3, Direction.LEFT, "왼쪽 방향 유지"),
    KEEP_RIGHT(0.3, Direction.RIGHT, "오른쪽 방향 유지"),
    LEFT(0.6, Direction.LEFT, "좌회전"),
    RIGHT(0.6, Direction.RIGHT, "우회전"),
    SHARP_LEFT(1.0, Direction.LEFT, "왼쪽 급커브"),
    SHARP_RIGHT(1.0, Direction.RIGHT, "오른쪽 급커브"),
    UTURN(1.0, Direction.LEFT, "유턴"),
    IC_LEFT(0.7, Direction.LEFT, "왼쪽 IC 진출입"),
    IC_RIGHT(0.7, Direction.RIGHT, "오른쪽 IC 진출입"),
    ROUNDABOUT(0.8, Direction.RIGHT, "회전교차로"),
    REST_AREA(0.2, Direction.RIGHT, "휴게소 진입"),
    OTHER(0.1, Direction.NONE, "안내 지점");

    val isMajor: Boolean get() = severity >= 0.6
}

data class UpcomingTurn(
    /** Stable id so a turn is announced only once. */
    val id: String,
    val kind: TurnKind,
    val distanceM: Double,
    val description: String = "",
)

enum class Gear { PARK, REVERSE, NEUTRAL, DRIVE, UNKNOWN }

data class Poi(val name: String, val distanceKm: Double, val lat: Double, val lon: Double)

/** One sample per second. Null = signal unavailable. */
data class SensorFrame(
    /** Trip clock in seconds (scenario playback compresses it). */
    val tripTimeSec: Double,
    /** Continuous driving time, minutes. */
    val drivingMinutes: Double,
    val steeringDeg: Double? = null,
    val brakePressed: Boolean? = null,
    val speedMps: Double? = null,
    val headingDeg: Double? = null,
    val cabinTempC: Double? = null,
    val hvacTargetC: Double? = null,
    val fanSpeed: Int? = null,
    val recirculation: Boolean? = null,
    val gear: Gear? = null,
    val rearOccupied: Boolean? = null,
    val frontPassengerOccupied: Boolean? = null,
    /** Upcoming manoeuvres ordered by distance (nearest first). */
    val turns: List<UpcomingTurn> = emptyList(),
    val restAreaAhead: Poi? = null,
    val roadLabel: String = "",
) {
    val passengerPresent: Boolean
        get() = (rearOccupied == true) || (frontPassengerOccupied == true)
}

enum class Sensitivity(val multiplier: Double, val defaultThreshold: Int, val label: String) {
    LOW(0.85, 62, "낮음"),
    MEDIUM(1.0, 55, "보통"),
    HIGH(1.15, 50, "높음"),
}

data class Profile(
    val name: String,
    val ageYears: Int,
    val sensitivity: Sensitivity,
    /** Personal care threshold (0..100). Learned from "멀미나" reports. */
    val careThreshold: Int = sensitivity.defaultThreshold,
    val sicknessLabels: Int = 0,
) {
    val isChild: Boolean get() = ageYears in 1..12
}
