package com.mulmizero.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RiskModelTest {
    private val adult = Profile("성인", 35, Sensitivity.MEDIUM)
    private val child = Profile("서아", 7, Sensitivity.HIGH)

    private fun features(
        turnRisk: Double = 0.0, turns: Int = 0, heading: Double = 0.0, brake: Double = 0.0,
        steering: Double = 0.0, minutes: Double = 0.0, temp: Double? = 22.0, passenger: Boolean = true,
    ) = Features(turnRisk, null, turns, heading, brake, steering, minutes, temp, passenger, false, false)

    @Test fun calmHighwayIsZero() {
        val r = RiskModel.compute(features(), adult)
        assertEquals(0.0, r.score, 1e-9)
    }

    @Test fun weightsSumTo100() {
        assertEquals(100.0, RiskModel.DEFAULT.total, 1e-9)
    }

    @Test fun everythingSaturatedIsCappedAt100() {
        val r = RiskModel.compute(
            features(1.0, 10, 50.0, 30.0, 200.0, 300.0, 35.0), child,
        )
        assertEquals(100.0, r.score, 1e-9)
        assertEquals(100.0, r.rawSum, 1e-9)
    }

    @Test fun eachComponentRespectsItsWeight() {
        val r = RiskModel.compute(features(1.0, 10, 50.0, 30.0, 200.0, 300.0, 35.0), adult)
        r.components.forEach { assertEquals(it.maxPoints, it.points, 1e-9) }
    }

    @Test fun halfSaturatedFeaturesGiveHalfPoints() {
        val f = features(
            turnRisk = 0.5, turns = 2, heading = RiskModel.HEADING_RATE_SAT / 2,
            brake = RiskModel.BRAKE_PER_MIN_SAT / 2, steering = RiskModel.STEERING_RATE_SAT / 2,
            minutes = 75.0, temp = 24.5,
        )
        val r = RiskModel.compute(f, adult)
        assertEquals(50.0, r.score, 1e-6)
    }

    @Test fun drivingTimeBelow30MinutesDoesNotCount() {
        val r = RiskModel.compute(features(minutes = 29.0), adult)
        assertEquals(0.0, r.components.first { it.key == "time" }.points, 1e-9)
    }

    @Test fun coolCabinDoesNotReduceScore() {
        val r = RiskModel.compute(features(temp = 18.0), adult)
        assertEquals(0.0, r.components.first { it.key == "temp" }.points, 1e-9)
    }

    @Test fun missingTemperatureIsNeutral() {
        val r = RiskModel.compute(features(temp = null), adult)
        assertEquals(0.0, r.components.first { it.key == "temp" }.points, 1e-9)
    }

    @Test fun sensitiveChildScoresHigherThanAdult() {
        val f = features(turnRisk = 0.6, turns = 2, heading = 5.0, minutes = 80.0, temp = 25.0)
        val a = RiskModel.compute(f, adult).score
        val c = RiskModel.compute(f, child).score
        assertEquals(a * 1.15 * 1.05, c, 1e-6)
        assertTrue(c > a)
    }

    @Test fun noPassengerHalvesScore() {
        val f = features(turnRisk = 1.0, turns = 4, minutes = 120.0)
        val with = RiskModel.compute(f, adult).score
        val without = RiskModel.compute(f.copy(passengerPresent = false), adult).score
        assertEquals(with * 0.5, without, 1e-9)
    }

    @Test fun riskLevels() {
        assertEquals(RiskLevel.CALM, riskLevel(20.0, 55, false))
        assertEquals(RiskLevel.WATCH, riskLevel(41.0, 55, false))
        assertEquals(RiskLevel.CARE, riskLevel(55.0, 55, false))
        assertEquals(RiskLevel.MAX, riskLevel(10.0, 55, true))
    }

    @Test fun smootherConvergesAndCanBeForced() {
        val s = RiskSmoother(0.5)
        assertEquals(10.0, s.update(10.0), 1e-9)
        assertEquals(15.0, s.update(20.0), 1e-9)
        s.force(80.0)
        assertEquals(50.0, s.update(20.0), 1e-9)
    }
}
