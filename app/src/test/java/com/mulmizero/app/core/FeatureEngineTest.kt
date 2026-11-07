package com.mulmizero.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FeatureEngineTest {
    private fun frame(
        t: Double = 0.0, steering: Double? = 0.0, brake: Boolean? = false, speed: Double? = 20.0,
        heading: Double? = 0.0, turns: List<UpcomingTurn> = emptyList(), rear: Boolean? = true,
    ) = SensorFrame(t, t / 60, steering, brake, speed, heading, 23.0, 23.0, 2, true, Gear.DRIVE, rear, false, turns)

    @Test fun nearTurnCountsFullyFarTurnNotAtAll() {
        val e = FeatureEngine()
        val near = e.push(frame(turns = listOf(UpcomingTurn("a", TurnKind.SHARP_LEFT, 400.0))))
        assertEquals(1.0, near.nextTurnRisk, 1e-9)
        val far = FeatureEngine().push(frame(turns = listOf(UpcomingTurn("a", TurnKind.SHARP_LEFT, 2000.0))))
        assertEquals(0.0, far.nextTurnRisk, 1e-9)
        val mid = FeatureEngine().push(frame(turns = listOf(UpcomingTurn("a", TurnKind.LEFT, 1000.0))))
        assertEquals(0.6 * 0.5, mid.nextTurnRisk, 1e-9)
    }

    @Test fun countsTurnsWithin1KmIgnoringStraight() {
        val turns = listOf(
            UpcomingTurn("a", TurnKind.LEFT, 100.0), UpcomingTurn("b", TurnKind.STRAIGHT, 300.0),
            UpcomingTurn("c", TurnKind.RIGHT, 900.0), UpcomingTurn("d", TurnKind.SHARP_LEFT, 1200.0),
        )
        assertEquals(2, FeatureEngine().push(frame(turns = turns)).turnsWithin1Km)
    }

    @Test fun headingRateHandlesWrapAround() {
        val e = FeatureEngine()
        e.push(frame(heading = 355.0))
        val f = e.push(frame(heading = 5.0))
        assertEquals(10.0, f.headingRateDegPerSec, 1e-9)
    }

    @Test fun steeringRateIsMeanAbsoluteDelta() {
        val e = FeatureEngine()
        e.push(frame(steering = 0.0))
        e.push(frame(steering = 30.0))
        val f = e.push(frame(steering = 0.0))
        assertEquals(30.0, f.steeringRateDegPerSec, 1e-9)
    }

    @Test fun brakeTransitionsCountedPerMinute() {
        val e = FeatureEngine()
        var f: Features? = null
        // 61 frames (= 60 s window) with a press every 10 frames → 6 transitions/min
        for (i in 0..60) f = e.push(frame(brake = i % 10 == 5))
        assertEquals(6.0, f!!.brakeTransitionsPerMin, 1e-9)
    }

    @Test fun hardBrakeNeedsPedalAndDeceleration() {
        val e = FeatureEngine()
        e.push(frame(speed = 20.0))
        assertTrue(e.push(frame(speed = 16.5, brake = true)).hardBrake)
        val g = FeatureEngine()
        g.push(frame(speed = 20.0))
        assertFalse(g.push(frame(speed = 18.5, brake = true)).hardBrake)
        val h = FeatureEngine()
        h.push(frame(speed = 20.0))
        assertFalse("deceleration without pedal (e.g. uphill) is not a hard brake", h.push(frame(speed = 15.0, brake = false)).hardBrake)
    }

    @Test fun sharpSteerDetected() {
        val e = FeatureEngine()
        e.push(frame(steering = 0.0))
        assertTrue(e.push(frame(steering = 95.0)).sharpSteer)
        assertFalse(e.push(frame(steering = 120.0)).sharpSteer)
    }

    @Test fun missingSignalsAreTolerated() {
        val e = FeatureEngine()
        val f = e.push(SensorFrame(0.0, 0.0))
        assertEquals(0.0, f.steeringRateDegPerSec, 1e-9)
        assertEquals(0.0, f.brakeTransitionsPerMin, 1e-9)
        assertFalse(f.passengerPresent)
    }
}
