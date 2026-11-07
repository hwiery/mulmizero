package com.mulmizero.app.sim

import com.mulmizero.app.core.ActuationResult
import com.mulmizero.app.core.ActuationStatus
import com.mulmizero.app.core.Audience
import com.mulmizero.app.core.ClimateActuator
import com.mulmizero.app.core.Poi
import com.mulmizero.app.core.RouteActuator
import com.mulmizero.app.core.SensorFrame
import com.mulmizero.app.core.Speaker
import com.mulmizero.app.engine.FrameSource

class SimClimateActuator(private val cabin: SimCabin) : ClimateActuator {
    override fun applyCare(level: Int, onResult: (ActuationResult) -> Unit) {
        cabin.applyCare(level)
        onResult(ActuationResult(ActuationStatus.SIMULATED, describe(cabin)))
    }

    override fun restore(onResult: (ActuationResult) -> Unit) {
        cabin.restore()
        onResult(ActuationResult(ActuationStatus.SIMULATED, describe(cabin)))
    }

    companion object {
        fun describe(c: SimCabin) =
            "외기 ${if (c.recirculation) "내기순환" else "유입"}, 설정 ${"%.1f".format(c.targetC)}℃, 풍량 ${c.fanSpeed}단" +
                if (c.rearVentilation > 0) ", 뒷좌석 통풍 ${c.rearVentilation}단" else ""
    }
}

class SimRouteActuator(private val scenario: FamilyTripScenario) : RouteActuator {
    override fun addWaypoint(poi: Poi, onResult: (ActuationResult) -> Unit) {
        scenario.onWaypointAdded()
        onResult(ActuationResult(ActuationStatus.SIMULATED, "${poi.name} 경유지 추가(시나리오 경로)"))
    }

    override fun requestComfortRoute(onResult: (ActuationResult) -> Unit) {
        scenario.onComfortRoute()
        onResult(ActuationResult(ActuationStatus.SIMULATED, "RouteOption.COMFORTABLE 재탐색(시나리오 경로)"))
    }
}

class ScenarioFrameSource(private val scenario: FamilyTripScenario) : FrameSource {
    override fun next(): SensorFrame = scenario.next()
    override fun sources(): Map<String, String> = emptyMap()
}

/** Speaker that only records (tests, or when no TTS engine is available). */
class RecordingSpeaker : Speaker {
    override val engineName = "자막 전용"
    val lines = mutableListOf<Pair<Audience, String>>()
    override fun speak(text: String, audience: Audience) { lines += audience to text }
}
