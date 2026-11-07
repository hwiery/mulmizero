package com.mulmizero.app.core

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Per-second features fed to [RiskModel]. */
data class Features(
    /** 0..1 — severity of the nearest major turn, weighted by proximity. */
    val nextTurnRisk: Double,
    val nextTurn: UpcomingTurn?,
    /** Number of non-straight manoeuvres within the next 1 km. */
    val turnsWithin1Km: Int,
    /** Mean |Δheading| over the last [FeatureEngine.SHORT_WINDOW] samples, deg/s. */
    val headingRateDegPerSec: Double,
    /** Brake pedal off→on transitions in the last 60 samples (per minute). */
    val brakeTransitionsPerMin: Double,
    /** Mean |Δsteering angle| over the short window, deg/s. */
    val steeringRateDegPerSec: Double,
    val drivingMinutes: Double,
    val cabinTempC: Double?,
    val passengerPresent: Boolean,
    /** Events detected on this sample. */
    val hardBrake: Boolean,
    val sharpSteer: Boolean,
)

/**
 * Turns raw [SensorFrame]s into [Features] using short sliding windows.
 * One frame = one second of vehicle dynamics.
 */
class FeatureEngine {
    companion object {
        const val SHORT_WINDOW = 10
        const val LONG_WINDOW = 60
        /** Proximity at which an upcoming turn counts fully. */
        const val TURN_NEAR_M = 500.0
        /** Turns further than this do not contribute to nextTurnRisk. */
        const val TURN_FAR_M = 1500.0
        /** Deceleration that counts as a hard brake (≈0.3 g). */
        const val HARD_BRAKE_MPS2 = 3.0
        /** Steering wheel speed that counts as an abrupt steer. */
        const val SHARP_STEER_DEG_PER_SEC = 90.0
    }

    private val history = ArrayDeque<SensorFrame>()

    fun reset() = history.clear()

    fun push(frame: SensorFrame): Features {
        val prev = history.lastOrNull()
        history.addLast(frame)
        while (history.size > LONG_WINDOW + 1) history.removeFirst()

        val shortWin = history.toList().takeLast(SHORT_WINDOW + 1)

        val headingRate = meanAbsDelta(shortWin.mapNotNull { it.headingDeg }, wrap = true)
        val steeringRate = meanAbsDelta(shortWin.mapNotNull { it.steeringDeg }, wrap = false)

        var transitions = 0
        var last: Boolean? = null
        for (f in history) {
            val b = f.brakePressed ?: continue
            if (last == false && b) transitions++
            last = b
        }
        val windowSec = max(1, history.size - 1)
        val perMin = transitions * 60.0 / max(windowSec, 30) // avoid inflating early in a trip

        val nearest = frame.turns
            .filter { it.kind != TurnKind.STRAIGHT && it.distanceM >= 0 }
            .minByOrNull { it.distanceM }
        val nextMajor = frame.turns
            .filter { it.kind.severity >= 0.3 && it.distanceM >= 0 }
            .minByOrNull { it.distanceM }
        val turnRisk = nextMajor?.let { it.kind.severity * proximity(it.distanceM) } ?: 0.0
        val within1Km = frame.turns.count { it.kind != TurnKind.STRAIGHT && it.distanceM in 0.0..1000.0 }

        val hardBrake = if (prev != null && frame.brakePressed == true &&
            prev.speedMps != null && frame.speedMps != null
        ) (prev.speedMps - frame.speedMps) >= HARD_BRAKE_MPS2 else false

        val sharpSteer = if (prev?.steeringDeg != null && frame.steeringDeg != null)
            abs(frame.steeringDeg - prev.steeringDeg) >= SHARP_STEER_DEG_PER_SEC else false

        return Features(
            nextTurnRisk = turnRisk,
            nextTurn = nextMajor ?: nearest,
            turnsWithin1Km = within1Km,
            headingRateDegPerSec = headingRate,
            brakeTransitionsPerMin = perMin,
            steeringRateDegPerSec = steeringRate,
            drivingMinutes = frame.drivingMinutes,
            cabinTempC = frame.cabinTempC,
            passengerPresent = frame.passengerPresent,
            hardBrake = hardBrake,
            sharpSteer = sharpSteer,
        )
    }

    private fun proximity(d: Double): Double = when {
        d <= TURN_NEAR_M -> 1.0
        d >= TURN_FAR_M -> 0.0
        else -> 1.0 - (d - TURN_NEAR_M) / (TURN_FAR_M - TURN_NEAR_M)
    }

    private fun meanAbsDelta(values: List<Double>, wrap: Boolean): Double {
        if (values.size < 2) return 0.0
        var sum = 0.0
        for (i in 1 until values.size) {
            var d = values[i] - values[i - 1]
            if (wrap) {
                d = ((d + 540.0) % 360.0) - 180.0
            }
            sum += abs(d)
        }
        return sum / (values.size - 1)
    }
}

internal fun clamp01(v: Double) = min(1.0, max(0.0, v))
