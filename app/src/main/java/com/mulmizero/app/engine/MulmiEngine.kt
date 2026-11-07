package com.mulmizero.app.engine

import com.mulmizero.app.core.Action
import com.mulmizero.app.core.ActuationResult
import com.mulmizero.app.core.ActuationStatus
import com.mulmizero.app.core.Audience
import com.mulmizero.app.core.ClimateActuator
import com.mulmizero.app.core.Command
import com.mulmizero.app.core.EventType
import com.mulmizero.app.core.Profile
import com.mulmizero.app.core.RouteActuator
import com.mulmizero.app.core.SensorFrame
import com.mulmizero.app.core.SicknessLabel
import com.mulmizero.app.core.Speaker
import com.mulmizero.app.core.StepResult
import com.mulmizero.app.core.TripSession

/** Where frames come from: pure scenario, or scenario merged with live SDK values. */
interface FrameSource {
    fun next(): SensorFrame
    /** Per-signal source label for the UI ("SDK" / "SIM"). */
    fun sources(): Map<String, String> = emptyMap()
}

enum class Mode(val label: String) { SCENARIO("시나리오 재생"), LIVE("실시간 (SDK 우선)") }

/**
 * One running trip. Pure Kotlin so the full scenario can be replayed in unit tests.
 * The Android layer supplies the ports and calls [tick] once per second.
 */
class MulmiEngine(
    profile: Profile,
    val mode: Mode,
    private val frames: FrameSource,
    private val speaker: Speaker,
    private val climate: ClimateActuator,
    private val route: RouteActuator,
    private val onLabel: (SicknessLabel) -> Unit = {},
    /** Scenario playback: frame at which the scripted passenger says "멀미나". */
    private val scriptedSickFrame: Int? = null,
) {
    val session = TripSession(profile)
    var lastSpoken: Pair<Audience, String>? = null; private set
    val actuation = linkedMapOf<String, ActuationResult>()

    init {
        if (mode == Mode.SCENARIO) session.autoAnswerAfter = 3
        session.log(EventType.INFO, "트립 시작 · 모드: ${mode.label} · 프로필: ${profile.name}(${profile.ageYears}세, 민감도 ${profile.sensitivity.label})")
    }

    fun tick(): StepResult {
        val r = session.step(frames.next())
        execute(r.actions)
        if (scriptedSickFrame != null && r.frameIndex == scriptedSickFrame && session.report == null) {
            command(Command.PassengerSick, "시연 스크립트 음성")
        }
        return r
    }

    fun command(cmd: Command, source: String) = execute(session.command(cmd, source))

    private fun execute(actions: List<Action>) {
        for (a in actions) when (a) {
            is Action.Speak -> say(a.audience, a.text)
            is Action.Ask -> say(Audience.DRIVER, a.text)
            is Action.ApplyClimate -> climate.applyCare(a.level) { res -> report("공조", res) }
            Action.RestoreClimate -> climate.restore { res -> report("공조 복귀", res) }
            is Action.AddWaypoint -> route.addWaypoint(a.poi) { res -> report("경유지", res) }
            Action.RequestComfortRoute -> route.requestComfortRoute { res -> report("편안한 길", res) }
            is Action.SaveLabel -> onLabel(a.label)
            Action.EndTrip -> Unit
        }
    }

    private fun say(audience: Audience, text: String) {
        lastSpoken = audience to text
        speaker.speak(text, audience)
    }

    private fun report(what: String, res: ActuationResult) {
        actuation[what] = res
        val type = when {
            res.status == ActuationStatus.APPLIED_SDK || res.status == ActuationStatus.PARTIAL_SDK -> EventType.SDK
            what.startsWith("공조") -> EventType.CLIMATE
            else -> EventType.ROUTE
        }
        session.log(type, "$what: ${res.status.label} — ${res.detail}")
    }
}
