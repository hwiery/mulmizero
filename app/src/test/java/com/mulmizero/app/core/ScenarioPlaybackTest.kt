package com.mulmizero.app.core

import com.mulmizero.app.engine.Mode
import com.mulmizero.app.engine.MulmiEngine
import com.mulmizero.app.sim.FamilyTripScenario
import com.mulmizero.app.sim.RecordingSpeaker
import com.mulmizero.app.sim.ScenarioFrameSource
import com.mulmizero.app.sim.SimCabin
import com.mulmizero.app.sim.SimClimateActuator
import com.mulmizero.app.sim.SimRouteActuator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToInt

/** Replays the whole "주말 가족여행" demo headlessly and checks the story beats happen in order. */
class ScenarioPlaybackTest {

    private fun run(): Pair<MulmiEngine, RecordingSpeaker> {
        val cabin = SimCabin()
        val scenario = FamilyTripScenario(cabin)
        val speaker = RecordingSpeaker()
        val engine = MulmiEngine(
            Profile("서아", 7, Sensitivity.HIGH), Mode.SCENARIO, ScenarioFrameSource(scenario), speaker,
            SimClimateActuator(cabin), SimRouteActuator(scenario),
            scriptedSickFrame = FamilyTripScenario.SICK_FRAME,
        )
        var i = 0
        while (engine.session.report == null && i < 220) {
            val r = engine.tick(); i++
            if (System.getenv("MULMI_TRACE") != null) {
                println(
                    "%3d %-10s score=%5.1f turn=%s brake=%s steer=%6.1f spd=%4.1f temp=%4.1f %s".format(
                        r.frameIndex, r.frame.roadLabel, r.score,
                        r.features.nextTurn?.let { "${it.kind.name}@${it.distanceM.roundToInt()}" } ?: "-",
                        r.frame.brakePressed, r.frame.steeringDeg ?: 0.0, r.frame.speedMps ?: 0.0, r.frame.cabinTempC ?: 0.0,
                        r.actions.joinToString { it.javaClass.simpleName },
                    ),
                )
            }
        }
        if (System.getenv("MULMI_TRACE") != null) engine.session.events.forEach { println("  [${it.frame}] ${it.type.tag}: ${it.text}") }
        return engine to speaker
    }

    @Test fun storyBeatsHappenInOrder() {
        val (engine, speaker) = run()
        val ev = engine.session.events
        fun firstFrame(type: EventType, contains: String = "") =
            ev.firstOrNull { it.type == type && it.text.contains(contains) }?.frame ?: -1

        val careStart = firstFrame(EventType.ASK, "동승자 케어")
        val climateAsk = firstFrame(EventType.ASK, "멀미 위험 지수")
        val firstTurn = firstFrame(EventType.TURN)
        val coach = firstFrame(EventType.COACH, "부드럽게")
        val sick = firstFrame(EventType.SICK, "멀미나")
        val restAsk = firstFrame(EventType.ASK, "경유지")
        val comfort = firstFrame(EventType.ROUTE, "편안한 길")
        val restore = firstFrame(EventType.CARE, "원래 설정")
        val end = firstFrame(EventType.END)

        val beats = listOf(careStart, climateAsk, firstTurn, coach, sick, restAsk, comfort, restore, end)
        assertTrue("all beats present: $beats", beats.all { it > 0 })
        assertTrue("care start before climate", careStart < climateAsk)
        assertTrue("sick before rest stop", sick <= restAsk)
        assertTrue("restore after comfort route", comfort < restore)
        assertTrue("demo fits in 1–3 minutes of playback: ended at $end s", end in 60..180)

        val report = engine.session.report
        assertNotNull(report)
        report!!
        assertEquals(2, report.hardBrakes)
        assertEquals(1, report.sicknessReports)
        assertTrue("family score plausible: ${report.familyScore}", report.familyScore in 75..95)
        assertTrue("turns were announced", speaker.lines.any { it.second.contains("크게 돕니다") })
        assertEquals(1, engine.session.labels.size)
    }

    @Test fun riskPeaksInCurvesAndIsLowOnHighway() {
        val cabin = SimCabin()
        val scenario = FamilyTripScenario(cabin)
        val engine = MulmiEngine(
            Profile("서아", 7, Sensitivity.HIGH), Mode.SCENARIO, ScenarioFrameSource(scenario), RecordingSpeaker(),
            SimClimateActuator(cabin), SimRouteActuator(scenario), scriptedSickFrame = FamilyTripScenario.SICK_FRAME,
        )
        val scores = mutableListOf<Pair<String, Double>>()
        repeat(80) { val r = engine.tick(); scores += r.frame.roadLabel to r.score }
        val highway = scores.filter { it.first == "고속도로" }.maxOf { it.second }
        val curves = scores.filter { it.first == "국도 커브 구간" }.maxOf { it.second }
        assertTrue("highway $highway < 35", highway < 35)
        assertTrue("curves $curves ≥ 60", curves >= 60)
    }
}
