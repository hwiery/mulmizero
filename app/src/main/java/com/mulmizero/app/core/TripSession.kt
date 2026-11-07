package com.mulmizero.app.core

import kotlin.math.max
import kotlin.math.roundToInt

enum class EventType(val tag: String) {
    INFO("정보"), ASK("질문"), ANSWER("응답"), TURN("회전 예고"), CARE("케어"), CLIMATE("공조"),
    COACH("코칭"), SICK("멀미 신고"), ROUTE("경로"), SDK("SDK"), END("도착"),
}

data class LogEvent(val frame: Int, val tripTimeSec: Double, val type: EventType, val text: String)

data class TimelinePoint(val frame: Int, val score: Double, val threshold: Int, val marker: EventType?)

data class TripReport(
    val familyScore: Int,
    val durationMin: Int,
    val hardBrakes: Int,
    val sharpSteers: Int,
    val sicknessReports: Int,
    val sicknessRoads: List<String>,
    val avgScore: Double,
    val maxScore: Double,
    val smoothBrakingRatio: Double,
    val announceRatio: Double,
    val careMinutes: Int,
    val thresholdBefore: Int,
    val thresholdAfter: Int,
)

/** Accumulates trip statistics and computes the "패밀리 승차감 점수". */
class TripRecorder {
    var hardBrakes = 0; private set
    var sharpSteers = 0; private set
    var brakeEvents = 0; private set
    var sicknessReports = 0; private set
    val sicknessRoads = mutableListOf<String>()
    private val scores = mutableListOf<Double>()
    private val majorTurnIds = HashSet<String>()
    private var lastBrake: Boolean? = null
    private var careFramesTripSec = 0.0
    private var firstTrip: Double? = null
    private var lastTrip = 0.0

    fun record(frame: SensorFrame, f: Features, score: Double, inCare: Boolean) {
        if (firstTrip == null) firstTrip = frame.tripTimeSec
        val dt = frame.tripTimeSec - lastTrip
        if (inCare && lastTrip > 0 && dt > 0) careFramesTripSec += dt
        lastTrip = frame.tripTimeSec
        if (frame.gear == Gear.PARK) return
        scores += score
        if (f.hardBrake) hardBrakes++
        if (f.sharpSteer) sharpSteers++
        val b = frame.brakePressed
        if (b == true && lastBrake == false) brakeEvents++
        if (b != null) lastBrake = b
        frame.turns.filter { it.kind.isMajor && it.distanceM in 0.0..CareOrchestrator.ANNOUNCE_MAX_M }
            .forEach { majorTurnIds += it.id }
    }

    fun recordSickness(road: String) {
        sicknessReports++
        if (road.isNotBlank()) sicknessRoads += road
    }

    fun report(announced: Int, thresholdBefore: Int, thresholdAfter: Int, drivingMinutes: Double): TripReport {
        val avg = if (scores.isEmpty()) 0.0 else scores.average()
        val family = familyScore(hardBrakes, sharpSteers, sicknessReports, avg)
        return TripReport(
            familyScore = family,
            durationMin = drivingMinutes.roundToInt(),
            hardBrakes = hardBrakes,
            sharpSteers = sharpSteers,
            sicknessReports = sicknessReports,
            sicknessRoads = sicknessRoads.toList(),
            avgScore = avg,
            maxScore = scores.maxOrNull() ?: 0.0,
            smoothBrakingRatio = if (brakeEvents == 0) 1.0 else 1.0 - hardBrakes.toDouble() / brakeEvents,
            announceRatio = if (majorTurnIds.isEmpty()) 1.0 else (announced.toDouble() / majorTurnIds.size).coerceAtMost(1.0),
            careMinutes = (careFramesTripSec / 60).roundToInt(),
            thresholdBefore = thresholdBefore,
            thresholdAfter = thresholdAfter,
        )
    }

    companion object {
        /** 100 − 3·급제동 − 1.5·급조향 − 5·멀미신고 − 0.3·max(0, 평균지수 − 35), clamped to 0..100. */
        fun familyScore(hardBrakes: Int, sharpSteers: Int, sick: Int, avgScore: Double): Int {
            val s = 100.0 - 3.0 * hardBrakes - 1.5 * sharpSteers - 5.0 * sick - 0.3 * max(0.0, avgScore - 35.0)
            return s.coerceIn(0.0, 100.0).roundToInt()
        }
    }
}

data class StepResult(
    val frameIndex: Int,
    val frame: SensorFrame,
    val features: Features,
    val risk: RiskResult,
    val score: Double,
    val level: RiskLevel,
    val actions: List<Action>,
)

/**
 * Glue for one trip: features → risk → orchestrator → recorder/log/timeline.
 * Pure Kotlin; the Android layer only feeds frames, executes [Action]s and renders.
 */
class TripSession(initialProfile: Profile) {
    val featureEngine = FeatureEngine()
    private val smoother = RiskSmoother()
    val orchestrator = CareOrchestrator()
    val recorder = TripRecorder()
    val events = mutableListOf<LogEvent>()
    val timeline = mutableListOf<TimelinePoint>()
    val labels = mutableListOf<SicknessLabel>()

    val thresholdAtStart = initialProfile.careThreshold
    var profile: Profile = initialProfile; private set
    var frameIndex = 0; private set
    var last: StepResult? = null; private set
    var report: TripReport? = null; private set

    /** Optional demo autopilot: answer "응" N frames after a question (scenario playback only). */
    var autoAnswerAfter: Int? = null
    private var askedAt = -1

    private var pendingMarker: EventType? = null

    fun step(frame: SensorFrame): StepResult {
        frameIndex++
        val f = featureEngine.push(frame)
        val risk = RiskModel.compute(f, profile)
        val score = smoother.update(risk.score)
        val actions = orchestrator.tick(frame, f, score, profile).toMutableList()
        recorder.record(frame, f, score, orchestrator.climateLevel > 0)
        if (f.hardBrake) log(EventType.COACH, "급제동 감지 (감속 ≥ 3 m/s²)")
        if (f.sharpSteer) log(EventType.COACH, "급조향 감지 (조향 ≥ 90°/s)")

        absorb(actions)
        // demo autopilot (scenario playback only): the driver answers "응" a few seconds after a question
        val auto = autoAnswerAfter
        if (auto != null && orchestrator.pending != null && askedAt >= 0 && frameIndex - askedAt >= auto) {
            log(EventType.ANSWER, "운전자: \"응\" (시연 자동 응답)")
            val more = handleCommand(Command.Yes)
            absorb(more)
            actions += more
        }
        val res = StepResult(
            frameIndex, frame, f, risk, score,
            riskLevel(score, profile.careThreshold, orchestrator.maxCare), actions,
        )
        last = res
        timeline += TimelinePoint(frameIndex, score, profile.careThreshold, pendingMarker)
        pendingMarker = null
        return res
    }

    /** External command (big button, voice). Returns actions to execute. */
    fun command(cmd: Command, source: String): List<Action> {
        when (cmd) {
            Command.Yes -> log(EventType.ANSWER, "운전자: \"응\" ($source)")
            Command.No -> log(EventType.ANSWER, "운전자: \"아니\" ($source)")
            Command.PassengerSick -> log(EventType.SICK, "동승자: \"멀미나\" ($source)")
            is Command.SetCare -> log(EventType.CARE, if (cmd.on) "케어 모드 켬 ($source)" else "케어 모드 끔 ($source)")
        }
        val actions = handleCommand(cmd)
        absorb(actions)
        return actions
    }

    private fun handleCommand(cmd: Command): List<Action> {
        val actions = orchestrator.command(cmd)
        if (cmd == Command.PassengerSick) {
            smoother.force(maxOf(last?.score ?: 0.0, profile.careThreshold + 15.0).coerceAtMost(100.0))
            recorder.recordSickness(last?.frame?.roadLabel ?: "")
        }
        return actions
    }

    private fun absorb(actions: List<Action>) {
        for (a in actions) when (a) {
            is Action.Ask -> { askedAt = frameIndex; log(EventType.ASK, a.text) }
            is Action.Speak -> log(
                when (a.kind) {
                    Action.Speak.Kind.TURN -> EventType.TURN
                    Action.Speak.Kind.COACH -> EventType.COACH
                    Action.Speak.Kind.CARE -> EventType.CARE
                    else -> EventType.INFO
                },
                "[${a.audience.label}] ${a.text}",
            )
            is Action.ApplyClimate -> log(EventType.CLIMATE, "실내 케어 ${a.level}단계 요청")
            Action.RestoreClimate -> log(EventType.CLIMATE, "공조 원래 설정으로 복귀 요청")
            is Action.AddWaypoint -> log(EventType.ROUTE, "경유지 추가 요청: ${a.poi.name}")
            Action.RequestComfortRoute -> log(EventType.ROUTE, "'편안한 길' 재탐색 요청")
            is Action.SaveLabel -> {
                labels += a.label
                profile = Personalization.applyLabel(profile, a.label)
                log(EventType.SICK, "라벨 저장: 지수 ${a.label.score.roundToInt()}, 1km 내 회전 ${a.label.turnsWithin1Km}개, 주행 ${a.label.drivingMinutes.roundToInt()}분 → 개인 임계값 ${profile.careThreshold}")
            }
            Action.EndTrip -> {
                report = recorder.report(
                    orchestrator.announcedCount, thresholdAtStart, profile.careThreshold,
                    last?.frame?.drivingMinutes ?: 0.0,
                )
                log(EventType.END, "트립 종료 · 패밀리 승차감 점수 ${report!!.familyScore}점")
            }
        }
    }

    fun log(type: EventType, text: String) {
        events += LogEvent(frameIndex, last?.frame?.tripTimeSec ?: 0.0, type, text)
        if (type != EventType.SDK && type != EventType.INFO && type != EventType.ANSWER) {
            // keep the most salient marker for this timeline point
            if (pendingMarker == null || type == EventType.SICK) pendingMarker = type
        }
    }
}
