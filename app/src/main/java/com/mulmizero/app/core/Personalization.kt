package com.mulmizero.app.core

import kotlin.math.roundToInt

/** Snapshot saved when a passenger says "멀미나". Stored on the device only. */
data class SicknessLabel(
    val tripTimeSec: Double,
    val score: Double,
    val turnsWithin1Km: Int,
    val drivingMinutes: Double,
    val cabinTempC: Double?,
    val steeringRateDegPerSec: Double,
    val brakeTransitionsPerMin: Double,
    val roadLabel: String,
)

/**
 * v1 personalisation. A "멀미나" report means care should have started earlier for this passenger:
 *  - the personal care threshold drops by at least [MIN_STEP] points per report,
 *  - and is pulled towards (score at report − [LEAD]) when the report came at a relatively low score.
 * It never rises because of a report and stays within [[MIN_THRESHOLD], [MAX_THRESHOLD]].
 */
object Personalization {
    const val LEAD = 15.0
    const val LEARNING_RATE = 0.3
    const val MIN_STEP = 2
    const val MIN_THRESHOLD = 35
    const val MAX_THRESHOLD = 75

    fun updatedThreshold(current: Int, scoreAtReport: Double): Int {
        val pulled = (1 - LEARNING_RATE) * current + LEARNING_RATE * (scoreAtReport - LEAD)
        val next = minOf((current - MIN_STEP).toDouble(), pulled).roundToInt()
        return next.coerceIn(MIN_THRESHOLD, MAX_THRESHOLD)
    }

    fun applyLabel(profile: Profile, label: SicknessLabel): Profile =
        profile.copy(
            careThreshold = updatedThreshold(profile.careThreshold, label.score),
            sicknessLabels = profile.sicknessLabels + 1,
        )
}
