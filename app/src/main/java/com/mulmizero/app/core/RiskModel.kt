package com.mulmizero.app.core

import kotlin.math.roundToInt

/** One additive term of the risk score, for on-screen explanation. */
data class RiskComponent(val key: String, val label: String, val points: Double, val maxPoints: Double)

data class RiskResult(
    /** Final score 0..100 (after profile multiplier and occupancy factor, before smoothing). */
    val score: Double,
    val components: List<RiskComponent>,
    val rawSum: Double,
    val multiplier: Double,
) {
    val rounded: Int get() = score.roundToInt()
    fun top(n: Int) = components.sortedByDescending { it.points }.take(n).filter { it.points >= 1.0 }
}

/**
 * v1 rule-based weighted sum (planned v2: on-device logistic regression trained on "멀미나" labels).
 *
 * Each feature is normalised to 0..1 and multiplied by its weight; weights sum to 100.
 * The sum is then scaled by the passenger profile sensitivity, and damped when no
 * passenger is detected (driver-only trips rarely need care).
 */
object RiskModel {
    data class Weights(
        val nextTurn: Double = 20.0,
        val turnDensity: Double = 15.0,
        val heading: Double = 15.0,
        val brake: Double = 12.0,
        val steering: Double = 13.0,
        val drivingTime: Double = 15.0,
        val cabinTemp: Double = 10.0,
    ) {
        val total get() = nextTurn + turnDensity + heading + brake + steering + drivingTime + cabinTemp
    }

    val DEFAULT = Weights()

    /** Normalisation constants: value at which a feature saturates to 1.0. */
    const val TURNS_PER_KM_SAT = 4.0
    const val HEADING_RATE_SAT = 12.0      // deg/s
    const val BRAKE_PER_MIN_SAT = 10.0     // transitions/min
    const val STEERING_RATE_SAT = 60.0     // deg/s
    const val DRIVE_MIN_START = 30.0       // minutes before fatigue counts
    const val DRIVE_MIN_SAT = 120.0
    const val TEMP_COMFORT_C = 22.0
    const val TEMP_SAT_C = 27.0
    const val NO_PASSENGER_FACTOR = 0.5
    const val CHILD_BONUS = 1.05

    fun compute(f: Features, profile: Profile, w: Weights = DEFAULT): RiskResult {
        val comps = listOf(
            RiskComponent("turn", "다음 회전", w.nextTurn * clamp01(f.nextTurnRisk), w.nextTurn),
            RiskComponent("density", "1km 내 회전 수", w.turnDensity * clamp01(f.turnsWithin1Km / TURNS_PER_KM_SAT), w.turnDensity),
            RiskComponent("heading", "진행방향 변화", w.heading * clamp01(f.headingRateDegPerSec / HEADING_RATE_SAT), w.heading),
            RiskComponent("brake", "제동 전환 빈도", w.brake * clamp01(f.brakeTransitionsPerMin / BRAKE_PER_MIN_SAT), w.brake),
            RiskComponent("steering", "조향각 변화", w.steering * clamp01(f.steeringRateDegPerSec / STEERING_RATE_SAT), w.steering),
            RiskComponent(
                "time", "주행 시간",
                w.drivingTime * clamp01((f.drivingMinutes - DRIVE_MIN_START) / (DRIVE_MIN_SAT - DRIVE_MIN_START)),
                w.drivingTime,
            ),
            RiskComponent(
                "temp", "실내 온도",
                w.cabinTemp * clamp01(((f.cabinTempC ?: TEMP_COMFORT_C) - TEMP_COMFORT_C) / (TEMP_SAT_C - TEMP_COMFORT_C)),
                w.cabinTemp,
            ),
        )
        val raw = comps.sumOf { it.points }
        var mult = profile.sensitivity.multiplier
        if (profile.isChild) mult *= CHILD_BONUS
        if (!f.passengerPresent) mult *= NO_PASSENGER_FACTOR
        val score = (raw * mult).coerceIn(0.0, 100.0)
        return RiskResult(score, comps, raw, mult)
    }
}

/** Exponential smoothing so the gauge does not jitter second to second. */
class RiskSmoother(private val alpha: Double = 0.35) {
    private var value: Double? = null
    fun reset() { value = null }
    fun update(x: Double): Double {
        val v = value?.let { it + alpha * (x - it) } ?: x
        value = v
        return v
    }
    /** Jump the smoothed value (used when a passenger reports sickness: risk is known to be high). */
    fun force(x: Double) { value = x }
}

enum class RiskLevel(val label: String) { CALM("안정"), WATCH("주의"), CARE("케어 필요"), MAX("최고 단계") }

fun riskLevel(score: Double, threshold: Int, maxCare: Boolean): RiskLevel = when {
    maxCare -> RiskLevel.MAX
    score >= threshold -> RiskLevel.CARE
    score >= threshold - 15 -> RiskLevel.WATCH
    else -> RiskLevel.CALM
}
