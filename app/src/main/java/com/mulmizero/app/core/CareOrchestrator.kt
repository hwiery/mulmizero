package com.mulmizero.app.core

import kotlin.math.roundToInt

enum class Question(val primary: String, val secondary: String) {
    CARE_START("케어 모드 시작", "괜찮아요"),
    CLIMATE("실내 케어 실행", "괜찮아요"),
    REST_STOP("경유지 추가", "괜찮아요"),
    COMFORT_ROUTE("편안한 길로 재탐색", "이대로 갈게요"),
}

sealed interface Action {
    data class Speak(val audience: Audience, val text: String, val kind: Kind) : Action {
        enum class Kind { TURN, CARE, COACH, INFO, QUESTION }
    }
    data class Ask(val question: Question, val text: String) : Action
    data class ApplyClimate(val level: Int) : Action
    data object RestoreClimate : Action
    data class AddWaypoint(val poi: Poi) : Action
    data object RequestComfortRoute : Action
    data class SaveLabel(val label: SicknessLabel) : Action
    data object EndTrip : Action
}

sealed interface Command {
    data object Yes : Command
    data object No : Command
    data object PassengerSick : Command
    data class SetCare(val on: Boolean) : Command
}

/**
 * ActionOrchestrator from the plan: decides *when* to speak, ask, or actuate.
 * Deterministic and Android-free. Policies:
 *  - every vehicle actuation needs a driver "yes" (except "멀미나", which is an explicit request),
 *  - one question at a time, questions expire after [QUESTION_TIMEOUT] s,
 *  - declined climate care is not re-proposed for [CLIMATE_RETRY_FRAMES] s,
 *  - climate care is reverted automatically after [RESTORE_AFTER_TRIP_SEC] of trip time once risk is low,
 *  - turn announcements once per turn, 250–520 m before the turn,
 *  - driving coaching at most once per [COACH_COOLDOWN] s.
 */
class CareOrchestrator {
    companion object {
        const val QUESTION_TIMEOUT = 20
        const val CLIMATE_RETRY_FRAMES = 90
        const val RESTORE_AFTER_TRIP_SEC = 20 * 60.0
        const val COACH_COOLDOWN = 15
        const val ANNOUNCE_MIN_M = 250.0
        const val ANNOUNCE_MAX_M = 520.0
        const val COMFORT_SUSTAIN = 15
        const val MIN_MOVING_FRAMES = 10
    }

    var monitoring = false; private set
    var climateLevel = 0; private set
    var maxCare = false; private set
    var pending: Question? = null; private set
    var pendingText: String = ""; private set
    var tripEnded = false; private set
    var parked = false; private set

    private var frameIdx = 0
    private var pendingSince = 0
    private var careStartAsked = false
    private var climateRetryAt = 0
    private var careStartedTrip = 0.0
    private var lastCoach = -1000
    private var restStopAsked = false
    private var comfortAsked = false
    private var highSince = -1
    private var movingFrames = 0
    private val announced = HashSet<String>()

    private var lastFrame: SensorFrame? = null
    private var lastFeatures: Features? = null
    private var lastScore = 0.0

    val announcedCount get() = announced.size

    fun tick(frame: SensorFrame, f: Features, score: Double, profile: Profile): List<Action> {
        frameIdx++
        lastFrame = frame; lastFeatures = f; lastScore = score
        val out = mutableListOf<Action>()
        if (tripEnded) return out

        // --- trip state from gear -------------------------------------------------
        val moving = frame.gear != Gear.PARK && (frame.speedMps ?: 0.0) > 1.0
        if (moving) movingFrames++
        parked = frame.gear == Gear.PARK
        if (parked) {
            if (movingFrames >= MIN_MOVING_FRAMES) {
                tripEnded = true
                if (climateLevel > 0) { out += Action.RestoreClimate; climateLevel = 0; maxCare = false }
                out += Action.Speak(Audience.ALL, "도착했어요. 트립 리포트를 보여 드릴게요.", Action.Speak.Kind.INFO)
                out += Action.EndTrip
            }
            return out
        }

        // --- question expiry ---------------------------------------------------------
        if (pending != null && frameIdx - pendingSince > QUESTION_TIMEOUT) {
            if (pending == Question.CLIMATE) climateRetryAt = frameIdx + CLIMATE_RETRY_FRAMES
            pending = null
        }

        // --- propose care when a passenger is detected -------------------------------
        if (!monitoring) {
            if (!careStartAsked && f.passengerPresent && pending == null) {
                careStartAsked = true
                val who = if (frame.rearOccupied == true) "뒷좌석" else "조수석"
                out += ask(Question.CARE_START, "$who 동승자가 감지됐어요. 동승자 케어를 켤까요?")
            }
            return out
        }

        // --- F2: turn announcement for passengers -----------------------------------
        frame.turns
            .filter { it.kind.isMajor && it.distanceM in ANNOUNCE_MIN_M..ANNOUNCE_MAX_M && it.id !in announced }
            .minByOrNull { it.distanceM }
            ?.let { t ->
                announced += t.id
                out += Action.Speak(Audience.PASSENGER, turnPhrase(t), Action.Speak.Kind.TURN)
            }

        // --- F4: gentle driving coaching ------------------------------------------------
        if ((f.hardBrake || f.sharpSteer) && frameIdx - lastCoach >= COACH_COOLDOWN) {
            lastCoach = frameIdx
            val msg = if (f.hardBrake) "급제동이 감지됐어요. 조금만 더 부드럽게요."
            else "핸들을 조금만 더 천천히 돌려 주세요."
            out += Action.Speak(Audience.DRIVER, msg, Action.Speak.Kind.COACH)
        }

        val threshold = profile.careThreshold

        // --- F3: climate care proposal ------------------------------------------------
        if (climateLevel == 0 && pending == null && score >= threshold && frameIdx >= climateRetryAt) {
            out += ask(
                Question.CLIMATE,
                "멀미 위험 지수 ${score.roundToInt()}점이에요. 외기 유입, 설정온도 2도 낮춤, 풍량 한 단계 올릴까요?",
            )
        }

        // --- F5: comfortable route when risk stays high and curves remain --------------
        if (score >= threshold + 10 && f.turnsWithin1Km >= 3) {
            if (highSince < 0) highSince = frameIdx
        } else highSince = -1
        if (!comfortAsked && pending == null && highSince >= 0 && frameIdx - highSince >= COMFORT_SUSTAIN) {
            comfortAsked = true
            out += ask(Question.COMFORT_ROUTE, "남은 길에 커브가 많아요. 편안한 길로 다시 찾을까요?")
        }

        // --- automatic revert of climate care --------------------------------------------
        if (climateLevel > 0 && frame.tripTimeSec - careStartedTrip >= RESTORE_AFTER_TRIP_SEC &&
            score < threshold - 10
        ) {
            climateLevel = 0
            maxCare = false
            climateRetryAt = frameIdx + CLIMATE_RETRY_FRAMES
            out += Action.RestoreClimate
            out += Action.Speak(Audience.ALL, "멀미 위험이 낮아져 실내 공조를 원래 설정으로 돌렸어요.", Action.Speak.Kind.CARE)
        }
        return out
    }

    fun command(cmd: Command): List<Action> {
        val out = mutableListOf<Action>()
        if (tripEnded) return out
        when (cmd) {
            Command.Yes -> {
                val q = pending ?: return out
                pending = null
                when (q) {
                    Question.CARE_START -> {
                        monitoring = true
                        out += Action.Speak(Audience.ALL, "동승자 케어를 시작합니다. 회전 전에 미리 알려 드릴게요.", Action.Speak.Kind.CARE)
                    }
                    Question.CLIMATE -> {
                        startClimate(2, out)
                        out += Action.Speak(Audience.ALL, "외기를 들이고 실내를 조금 시원하게 할게요. 20분 뒤 원래대로 돌려요.", Action.Speak.Kind.CARE)
                    }
                    Question.REST_STOP -> {
                        lastFrame?.restAreaAhead?.let { poi ->
                            out += Action.AddWaypoint(poi)
                            out += Action.Speak(Audience.ALL, "${poi.name}를 경유지로 추가했어요.", Action.Speak.Kind.INFO)
                        }
                        maybeAskComfort(out)
                    }
                    Question.COMFORT_ROUTE -> {
                        out += Action.RequestComfortRoute
                        out += Action.Speak(Audience.ALL, "커브가 적은 편안한 길로 다시 안내할게요.", Action.Speak.Kind.INFO)
                    }
                }
            }
            Command.No -> {
                val q = pending ?: return out
                pending = null
                when (q) {
                    Question.CLIMATE -> climateRetryAt = frameIdx + CLIMATE_RETRY_FRAMES
                    Question.REST_STOP -> maybeAskComfort(out)
                    else -> Unit
                }
            }
            Command.PassengerSick -> {
                monitoring = true
                careStartAsked = true
                maxCare = true
                startClimate(3, out)
                val f = lastFeatures
                val fr = lastFrame
                if (f != null && fr != null) {
                    out += Action.SaveLabel(
                        SicknessLabel(
                            tripTimeSec = fr.tripTimeSec,
                            score = lastScore,
                            turnsWithin1Km = f.turnsWithin1Km,
                            drivingMinutes = f.drivingMinutes,
                            cabinTempC = f.cabinTempC,
                            steeringRateDegPerSec = f.steeringRateDegPerSec,
                            brakeTransitionsPerMin = f.brakeTransitionsPerMin,
                            roadLabel = fr.roadLabel,
                        ),
                    )
                }
                out += Action.Speak(
                    Audience.ALL,
                    "최고 단계 케어를 시작합니다. 창밖 먼 곳을 보며 천천히 숨 쉬어 보세요.",
                    Action.Speak.Kind.CARE,
                )
                val poi = fr?.restAreaAhead
                if (poi != null && !restStopAsked) {
                    restStopAsked = true
                    pending = null
                    out += ask(Question.REST_STOP, "${fmtKm(poi.distanceKm)} 앞 ${poi.name}를 경유지로 추가할까요?")
                }
            }
            is Command.SetCare -> {
                if (cmd.on) {
                    monitoring = true; careStartAsked = true
                    if (pending == Question.CARE_START) pending = null
                    out += Action.Speak(Audience.ALL, "동승자 케어를 시작합니다.", Action.Speak.Kind.CARE)
                } else {
                    monitoring = false
                    if (climateLevel > 0) { out += Action.RestoreClimate; climateLevel = 0; maxCare = false }
                    pending = null
                    out += Action.Speak(Audience.ALL, "동승자 케어를 껐어요.", Action.Speak.Kind.INFO)
                }
            }
        }
        return out
    }

    private fun startClimate(level: Int, out: MutableList<Action>) {
        if (level > climateLevel) {
            climateLevel = level
            out += Action.ApplyClimate(level)
        }
        careStartedTrip = lastFrame?.tripTimeSec ?: 0.0
    }

    private fun maybeAskComfort(out: MutableList<Action>) {
        val f = lastFeatures ?: return
        if (!comfortAsked && f.turnsWithin1Km >= 2) {
            comfortAsked = true
            out += ask(Question.COMFORT_ROUTE, "남은 길에 커브가 많아요. 편안한 길로 다시 찾을까요?")
        }
    }

    private fun ask(q: Question, text: String): Action.Ask {
        pending = q
        pendingText = text
        pendingSince = frameIdx
        return Action.Ask(q, text)
    }
}

fun fmtKm(km: Double): String = if (km >= 10) "${km.roundToInt()}km" else "${(km * 10).roundToInt() / 10.0}km"

/** "300m 앞에서 왼쪽으로 크게 돕니다. 창밖 먼 곳을 봐 주세요." */
fun turnPhrase(t: UpcomingTurn): String {
    val d = ((t.distanceM / 100.0).roundToInt() * 100).coerceAtLeast(100)
    val dir = when (t.kind.direction) {
        Direction.LEFT -> "왼쪽으로"
        Direction.RIGHT -> "오른쪽으로"
        Direction.NONE -> ""
    }
    val how = when (t.kind) {
        TurnKind.SHARP_LEFT, TurnKind.SHARP_RIGHT, TurnKind.UTURN -> "크게 돕니다"
        TurnKind.IC_LEFT, TurnKind.IC_RIGHT -> "빠져나갑니다"
        TurnKind.ROUNDABOUT -> "회전교차로를 돕니다"
        else -> "돕니다"
    }
    return "${d}m 앞에서 $dir $how. 창밖 먼 곳을 봐 주세요.".replace("  ", " ")
}
