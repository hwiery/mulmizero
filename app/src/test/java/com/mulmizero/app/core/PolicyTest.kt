package com.mulmizero.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonalizationTest {
    @Test fun reportNearThresholdLowersItClearly() {
        // reported at 55 with threshold 55: 0.7·55 + 0.3·40 = 50.5 → 51 (< 55 − 2)
        assertEquals(51, Personalization.updatedThreshold(55, 55.0))
    }

    @Test fun reportFarAboveThresholdStillLowersByMinStep() {
        // 0.7·50 + 0.3·75 = 57.5 would raise it → the minimum step (−2) wins
        assertEquals(48, Personalization.updatedThreshold(50, 90.0))
    }

    @Test fun thresholdIsBounded() {
        assertEquals(Personalization.MIN_THRESHOLD, Personalization.updatedThreshold(36, 0.0))
    }

    @Test fun applyLabelCountsReports() {
        val p = Profile("서아", 7, Sensitivity.HIGH)
        val label = SicknessLabel(0.0, 40.0, 3, 80.0, 25.0, 20.0, 5.0, "커브")
        val q = Personalization.applyLabel(p, label)
        assertEquals(1, q.sicknessLabels)
        assertTrue(q.careThreshold < p.careThreshold)
    }
}

class TripRecorderTest {
    @Test fun familyScoreFormula() {
        assertEquals(100, TripRecorder.familyScore(0, 0, 0, 20.0))
        // 100 − 6 − 0 − 5 − 0.3·10 = 86
        assertEquals(86, TripRecorder.familyScore(2, 0, 1, 45.0))
        assertEquals(0, TripRecorder.familyScore(40, 0, 0, 20.0))
    }
}

class CareOrchestratorTest {
    private val profile = Profile("서아", 7, Sensitivity.HIGH) // threshold 50

    private fun frame(
        t: Double, speed: Double = 20.0, gear: Gear = Gear.DRIVE, rear: Boolean = true,
        turns: List<UpcomingTurn> = emptyList(), rest: Poi? = null,
    ) = SensorFrame(t, t / 60, 0.0, false, speed, 0.0, 24.0, 24.0, 2, true, gear, rear, false, turns, rest)

    private fun feat(
        passenger: Boolean = true, turns: Int = 0, hardBrake: Boolean = false,
    ) = Features(0.0, null, turns, 0.0, 0.0, 0.0, 60.0, 24.0, passenger, hardBrake, false)

    @Test fun asksToStartCareOnlyWhenPassengerPresent() {
        val o = CareOrchestrator()
        assertTrue(o.tick(frame(1.0, rear = false), feat(passenger = false), 30.0, profile).isEmpty())
        val a = o.tick(frame(2.0), feat(), 30.0, profile)
        assertTrue(a.any { it is Action.Ask && it.question == Question.CARE_START })
        assertEquals(Question.CARE_START, o.pending)
        o.command(Command.Yes)
        assertTrue(o.monitoring)
        assertNull(o.pending)
    }

    private fun monitoring(): CareOrchestrator {
        val o = CareOrchestrator()
        o.tick(frame(1.0), feat(), 10.0, profile)
        o.command(Command.Yes)
        return o
    }

    @Test fun climateNeedsDriverApproval() {
        val o = monitoring()
        val a = o.tick(frame(2.0), feat(), 60.0, profile)
        assertTrue(a.any { it is Action.Ask && it.question == Question.CLIMATE })
        assertFalse(a.any { it is Action.ApplyClimate })
        val b = o.command(Command.Yes)
        assertTrue(b.contains(Action.ApplyClimate(2)))
        assertEquals(2, o.climateLevel)
    }

    @Test fun declinedClimateIsNotReproposedImmediately() {
        val o = monitoring()
        o.tick(frame(2.0), feat(), 60.0, profile)
        o.command(Command.No)
        for (i in 3..50) {
            val a = o.tick(frame(i.toDouble()), feat(), 60.0, profile)
            assertFalse("re-asked at $i", a.any { it is Action.Ask && it.question == Question.CLIMATE })
        }
    }

    @Test fun announcesEachMajorTurnOnceInWindow() {
        val o = monitoring()
        var count = 0
        for (d in listOf(700.0, 600.0, 500.0, 400.0, 300.0, 200.0)) {
            val a = o.tick(frame(d), feat(), 10.0, profile.copy(careThreshold = 99), )
            count += a.count { it is Action.Speak && it.kind == Action.Speak.Kind.TURN }
            // feed the turn through the frame
            o.tick(frame(d, turns = listOf(UpcomingTurn("x", TurnKind.SHARP_LEFT, d))), feat(), 10.0, profile.copy(careThreshold = 99))
                .let { r -> count += r.count { it is Action.Speak && it.kind == Action.Speak.Kind.TURN } }
        }
        assertEquals(1, count)
    }

    @Test fun keepLaneIsNotAnnounced() {
        val o = monitoring()
        val a = o.tick(frame(1.0, turns = listOf(UpcomingTurn("k", TurnKind.KEEP_LEFT, 400.0))), feat(), 10.0, profile)
        assertFalse(a.any { it is Action.Speak && it.kind == Action.Speak.Kind.TURN })
    }

    @Test fun sicknessGoesToMaxCareSavesLabelAndOffersRestStop() {
        val o = monitoring()
        val poi = Poi("휴게소", 8.4, 0.0, 0.0)
        o.tick(frame(5.0, rest = poi), feat(turns = 3), 40.0, profile)
        val a = o.command(Command.PassengerSick)
        assertTrue(a.contains(Action.ApplyClimate(3)))
        assertTrue(a.any { it is Action.SaveLabel })
        assertEquals(Question.REST_STOP, o.pending)
        val b = o.command(Command.Yes)
        assertTrue(b.contains(Action.AddWaypoint(poi)))
        assertEquals("then offers a comfortable route", Question.COMFORT_ROUTE, o.pending)
        assertTrue(o.command(Command.Yes).contains(Action.RequestComfortRoute))
    }

    @Test fun coachingHasCooldown() {
        val o = monitoring()
        var coached = 0
        for (i in 2..20) {
            val a = o.tick(frame(i.toDouble()), feat(hardBrake = true), 10.0, profile.copy(careThreshold = 99))
            coached += a.count { it is Action.Speak && it.kind == Action.Speak.Kind.COACH }
        }
        assertEquals(2, coached) // frame 2 and frame 2+15
    }

    @Test fun climateAutoRestoresAfter20MinutesWhenCalm() {
        val o = monitoring()
        o.tick(frame(100.0), feat(), 60.0, profile)
        o.command(Command.Yes)
        assertTrue(o.tick(frame(100.0 + 600), feat(), 20.0, profile).none { it == Action.RestoreClimate })
        assertTrue("still risky → keep care", o.tick(frame(100.0 + 1300), feat(), 45.0, profile).none { it == Action.RestoreClimate })
        assertTrue(o.tick(frame(100.0 + 1310), feat(), 20.0, profile).contains(Action.RestoreClimate))
        assertEquals(0, o.climateLevel)
    }

    @Test fun parkingAfterDrivingEndsTripAndRestoresClimate() {
        val o = monitoring()
        o.tick(frame(1.0), feat(), 60.0, profile); o.command(Command.Yes)
        for (i in 2..15) o.tick(frame(i.toDouble()), feat(), 30.0, profile)
        val a = o.tick(frame(16.0, speed = 0.0, gear = Gear.PARK), feat(), 30.0, profile)
        assertTrue(a.contains(Action.EndTrip))
        assertTrue(a.contains(Action.RestoreClimate))
        assertTrue(o.tripEnded)
    }

    @Test fun parkedAtStartDoesNotEndTrip() {
        val o = CareOrchestrator()
        val a = o.tick(frame(1.0, speed = 0.0, gear = Gear.PARK), feat(), 0.0, profile)
        assertTrue(a.isEmpty())
        assertFalse(o.tripEnded)
    }

    @Test fun turnPhraseMatchesMockup() {
        assertEquals(
            "300m 앞에서 왼쪽으로 크게 돕니다. 창밖 먼 곳을 봐 주세요.",
            turnPhrase(UpcomingTurn("a", TurnKind.SHARP_LEFT, 320.0)),
        )
    }
}
