package com.mulmizero.app

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.ProgressBar
import android.widget.TextView
import com.mulmizero.app.core.Command
import com.mulmizero.app.core.EventType
import com.mulmizero.app.core.Gear
import com.mulmizero.app.core.StepResult
import com.mulmizero.app.core.TurnKind
import com.mulmizero.app.core.VoiceIntent
import com.mulmizero.app.engine.FrameSource
import com.mulmizero.app.engine.Mode
import com.mulmizero.app.engine.MulmiEngine
import com.mulmizero.app.io.AndroidLocationInput
import com.mulmizero.app.io.AndroidSpeechVoiceInput
import com.mulmizero.app.io.AndroidTtsSpeaker
import com.mulmizero.app.io.ProfileStore
import com.mulmizero.app.sdk.HybridFrameSource
import com.mulmizero.app.sdk.NaviBridge
import com.mulmizero.app.sdk.PleosClimateActuator
import com.mulmizero.app.sdk.PleosRouteActuator
import com.mulmizero.app.sdk.VehicleBridge
import com.mulmizero.app.sim.FamilyTripScenario
import com.mulmizero.app.sim.ScenarioFrameSource
import com.mulmizero.app.sim.SimCabin
import com.mulmizero.app.ui.FactorBarsView
import com.mulmizero.app.ui.GaugeView
import com.mulmizero.app.ui.Palette
import com.mulmizero.app.ui.TimelineView
import com.mulmizero.app.ui.TurnArrowView
import kotlin.math.roundToInt

class MainActivity : Activity() {
    companion object { const val TAG = "MulmiZero" }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var vehicle: VehicleBridge
    private lateinit var navi: NaviBridge
    private lateinit var speaker: AndroidTtsSpeaker
    private lateinit var voice: AndroidSpeechVoiceInput
    private lateinit var location: AndroidLocationInput
    private lateinit var store: ProfileStore

    private var engine: MulmiEngine? = null
    private var frames: FrameSource? = null
    private var cabin: SimCabin? = null
    private var mode = Mode.SCENARIO
    private var speed = 1
    private var running = false
    private var reportShown = false
    private var loggedEvents = 0
    private val sdkLog = mutableListOf<String>()

    // views
    private lateinit var gauge: GaugeView
    private lateinit var timeline: TimelineView
    private lateinit var factors: FactorBarsView
    private lateinit var turnArrow: TurnArrowView
    private val tv = HashMap<Int, TextView>()
    private fun t(id: Int) = tv.getOrPut(id) { findViewById(id) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)
        gauge = findViewById(R.id.gauge)
        timeline = findViewById(R.id.timeline)
        factors = findViewById(R.id.factors)
        turnArrow = findViewById(R.id.turnArrow)
        store = ProfileStore(this)

        vehicle = VehicleBridge(this) { s -> sdk(s) }
        navi = NaviBridge(this) { s -> sdk(s) }
        speaker = AndroidTtsSpeaker(this) { state -> sdk("TTS: $state") }
        voice = AndroidSpeechVoiceInput(this)
        location = AndroidLocationInput(this)
        vehicle.start()
        navi.start()
        location.start { }

        t(R.id.btnPrimary).setOnClickListener { onPrimary() }
        t(R.id.btnSecondary).setOnClickListener { onSecondary() }
        t(R.id.chipScenario).setOnClickListener { startTrip(Mode.SCENARIO) }
        t(R.id.chipLive).setOnClickListener {
            requestRuntimePermissions()
            startTrip(Mode.LIVE)
        }
        t(R.id.chipRestart).setOnClickListener { startTrip(mode) }
        t(R.id.chipSpeed).setOnClickListener {
            speed = if (speed == 1) 2 else 1
            t(R.id.chipSpeed).text = "${speed}x"
        }
        t(R.id.chipMic).setOnClickListener { listen() }
        t(R.id.chipSick).setOnClickListener { command(Command.PassengerSick, "동승자 버튼") }
        t(R.id.btnSaveProfile).setOnClickListener {
            engine?.let { store.save(it.session.profile) }
            t(R.id.btnSaveProfile).text = "저장됨 ✓ (기기 내 저장)"
        }
        t(R.id.btnReplay).setOnClickListener { startTrip(mode) }

        val startMode = if (intent?.getStringExtra("mode") == "live") Mode.LIVE else Mode.SCENARIO
        intent?.getIntExtra("speed", 1)?.let { speed = it.coerceIn(1, 4); t(R.id.chipSpeed).text = "${speed}x" }
        // give the Vehicle service a moment to bind before the first frame
        handler.postDelayed({ startTrip(startMode) }, if (intent?.getBooleanExtra("wait", true) == false) 0L else 2500L)
    }

    override fun onDestroy() {
        running = false
        handler.removeCallbacksAndMessages(null)
        vehicle.stop(); navi.stop(); location.stop(); speaker.shutdown(); voice.shutdown()
        super.onDestroy()
    }

    private fun requestRuntimePermissions() {
        val need = listOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.ACCESS_FINE_LOCATION)
            .filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (need.isNotEmpty()) requestPermissions(need.toTypedArray(), 1)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        location.start { }
    }

    private fun sdk(s: String) {
        Log.i(TAG, s)
        handler.post {
            sdkLog += s
            engine?.session?.log(EventType.SDK, s)
        }
    }

    // ------------------------------------------------------------------ trip lifecycle
    private fun startTrip(m: Mode) {
        running = false
        handler.removeCallbacks(loop)
        mode = m
        reportShown = false
        loggedEvents = 0
        findViewById<View>(R.id.reportOverlay).visibility = View.GONE
        t(R.id.btnSaveProfile).text = "가족 프로필에 저장"
        val c = SimCabin(); cabin = c
        val climate = PleosClimateActuator(vehicle, c)
        val e = when (m) {
            Mode.SCENARIO -> {
                val sc = FamilyTripScenario(c)
                val src = ScenarioFrameSource(sc); frames = src
                MulmiEngine(
                    store.demoProfile, m, src, speaker, climate, PleosRouteActuator(navi) { sc },
                    onLabel = { store.appendLabel(it) }, scriptedSickFrame = FamilyTripScenario.SICK_FRAME,
                )
            }
            Mode.LIVE -> {
                val hy = HybridFrameSource({ FamilyTripScenario(c) }, vehicle, navi, { location.last }); frames = hy
                MulmiEngine(
                    store.load(), m, hy, speaker, climate, PleosRouteActuator(navi) { hy.scenario },
                    onLabel = { store.appendLabel(it) },
                )
            }
        }
        engine = e
        e.session.log(EventType.SDK, "Vehicle SDK: ${vehicle.status["Vehicle"] ?: "-"} · NaviHelper: ${navi.status["NaviHelper"] ?: "-"} · ${speaker.engineName}")
        t(R.id.chipScenario).setBackgroundResource(if (m == Mode.SCENARIO) R.drawable.bg_chip_on else R.drawable.bg_chip)
        t(R.id.chipLive).setBackgroundResource(if (m == Mode.LIVE) R.drawable.bg_chip_on else R.drawable.bg_chip)
        running = true
        handler.post(loop)
    }

    private val loop = object : Runnable {
        override fun run() {
            if (!running) return
            val e = engine ?: return
            try {
                val r = e.tick()
                render(e, r)
                val evs = e.session.events
                while (loggedEvents < evs.size) { val ev = evs[loggedEvents++]; Log.i(TAG, "[${ev.frame}] ${ev.type.tag}: ${ev.text}") }
            } catch (t: Throwable) {
                Log.e(TAG, "tick failed", t)
            }
            handler.postDelayed(this, (1000L / speed))
        }
    }

    private fun command(cmd: Command, source: String) {
        val e = engine ?: return
        e.command(cmd, source)
        e.session.last?.let { render(e, it) }
    }

    private fun onPrimary() {
        val e = engine ?: return
        val o = e.session.orchestrator
        when {
            o.pending != null -> command(Command.Yes, "버튼")
            !o.monitoring -> command(Command.SetCare(true), "버튼")
            else -> command(Command.SetCare(false), "버튼")
        }
    }

    private fun onSecondary() {
        val e = engine ?: return
        val o = e.session.orchestrator
        when {
            o.pending != null -> command(Command.No, "버튼")
            o.monitoring -> command(Command.PassengerSick, "버튼")
            else -> Unit
        }
    }

    private fun listen() {
        val e = engine ?: return
        if (!voice.available) {
            requestRuntimePermissions()
            e.session.log(EventType.SDK, "음성 인식 사용 불가 (엔진/권한 없음) — 큰 버튼으로 응답하세요")
            return
        }
        t(R.id.chipMic).text = "🎤 듣는 중…"
        voice.listenOnce(
            { intent, text ->
                t(R.id.chipMic).text = "🎤 말하기"
                when (intent) {
                    VoiceIntent.YES -> command(Command.Yes, "음성 \"$text\"")
                    VoiceIntent.NO -> command(Command.No, "음성 \"$text\"")
                    VoiceIntent.SICK -> command(Command.PassengerSick, "음성 \"$text\"")
                }
            },
            { err ->
                t(R.id.chipMic).text = "🎤 말하기"
                e.session.log(EventType.SDK, err)
            },
        )
    }

    // ------------------------------------------------------------------ rendering
    private fun render(e: MulmiEngine, r: StepResult) {
        val s = e.session
        val o = s.orchestrator
        val f = r.frame
        val p = s.profile

        gauge.threshold = p.careThreshold
        gauge.level = r.level
        gauge.score = r.score

        val seat = when {
            f.rearOccupied == true && f.frontPassengerOccupied == true -> "조수석·뒷좌석 착석"
            f.rearOccupied == true -> "뒷좌석 착석"
            f.frontPassengerOccupied == true -> "조수석 착석"
            else -> "동승자 없음"
        }
        val gear = when (f.gear) { Gear.PARK -> "P"; Gear.REVERSE -> "R"; Gear.NEUTRAL -> "N"; Gear.DRIVE -> "D"; else -> "-" }
        t(R.id.tvStatus).text = "${e.mode.label} · 동승자 케어 ${if (o.monitoring) "ON" else "OFF"} · $seat · 주행 ${f.drivingMinutes.roundToInt()}분 · 기어 $gear · ${((f.speedMps ?: 0.0) * 3.6).roundToInt()}km/h"
        t(R.id.tvProfile).text = "프로필: ${p.name}(${p.ageYears}세) · 민감도 ${p.sensitivity.label}\n케어 기준 ${p.careThreshold}점 · 멀미 신고 누적 ${p.sicknessLabels}회"

        // next turn
        val nt = r.features.nextTurn
        turnArrow.kind = nt?.kind ?: TurnKind.STRAIGHT
        if (nt != null) {
            t(R.id.tvTurnTitle).text = "${fmtDist(nt.distanceM)} 앞 ${nt.kind.label}"
            t(R.id.tvTurnSub).text = "1km 내 회전 ${r.features.turnsWithin1Km}개 · ${f.roadLabel}" +
                (nt.description.takeIf { it.isNotBlank() }?.let { " · $it" } ?: "")
        } else {
            t(R.id.tvTurnTitle).text = "당분간 큰 회전 없음"
            t(R.id.tvTurnSub).text = f.roadLabel
        }
        t(R.id.tvTurnTag).text = "다음 회전 · NaviHelper TBT [${srcOf("TBT")}]"

        // prompt / speech
        if (o.pending != null) {
            t(R.id.tvPromptTag).text = "운전자에게 질문 · \"응\"/\"아니\" 또는 아래 큰 버튼"
            t(R.id.tvPromptTag).setTextColor(Palette.amber)
            t(R.id.tvPrompt).text = o.pendingText
        } else {
            val ls = e.lastSpoken
            t(R.id.tvPromptTag).setTextColor(Palette.sub)
            t(R.id.tvPromptTag).text = if (ls != null) "🔊 ${ls.first.label}용 음성 안내" else "음성 안내"
            t(R.id.tvPrompt).text = ls?.second?.let { highlight(it) } ?: "주행 중 화면 조작 없이 음성으로 안내합니다."
        }
        t(R.id.tvCaption).text = "음성 출력: ${speaker.engineName} · 음성 입력: ${if (voice.available) voice.engineName else "사용 불가(엔진 없음) → 버튼"}"

        // care
        val c = cabin
        t(R.id.tvCare).text = if (o.climateLevel > 0 && c != null) {
            val baseT = c.savedTarget ?: c.targetC
            val baseF = c.savedFan ?: c.fanSpeed
            buildString {
                append("케어 ${o.climateLevel}단계  ✔ 외기 유입  ✔ 설정 ${fmt1(baseT)}→${fmt1(c.targetC)}℃  ✔ 풍량 $baseF→${c.fanSpeed}")
                if (c.rearVentilation > 0) append("  ✔ 뒷좌석 통풍")
            }
        } else {
            "실내 케어 대기 · 실내 ${fmt1(f.cabinTempC ?: 0.0)}℃ · 설정 ${fmt1(f.hvacTargetC ?: 0.0)}℃ · 풍량 ${f.fanSpeed ?: "-"} · ${if (f.recirculation == true) "내기순환" else "외기"}"
        }
        val act = e.actuation.entries.lastOrNull()
        t(R.id.tvCareStatus).text = act?.let { "${it.key}: ${it.value.status.label} — ${it.value.detail}" }
            ?: "차량 제어는 운전자 승인 후에만 실행 · 자동 원복 20분"

        // factors
        factors.multiplierText = "× 민감도·연령 ${"%.2f".format(r.risk.multiplier)}  →  ${r.risk.rounded}점 (평활 ${r.score.roundToInt()})"
        factors.components = r.risk.components

        renderSources(e, r)

        timeline.points = s.timeline.takeLast(135)
        t(R.id.tvTimelineTitle).text = "타임라인 · ${s.frameIndex}초 경과 · 트립 시각 ${fmtClock(f.tripTimeSec)}"
        renderLog(e)

        // buttons
        val (pri, sec) = when {
            o.tripEnded -> "트립 종료" to "다시 재생"
            o.pending != null -> o.pending!!.primary to o.pending!!.secondary
            !o.monitoring -> "케어 모드 시작" to "괜찮아요"
            else -> "케어 모드 끄기" to "동승자 멀미나"
        }
        t(R.id.btnPrimary).text = pri
        t(R.id.btnSecondary).text = sec

        s.report?.let { if (!reportShown) showReport(e) }
    }

    private fun srcOf(key: String): String = frames?.sources()?.get(key) ?: "SIM"

    private fun renderSources(e: MulmiEngine, r: StepResult) {
        val f = r.frame
        val rows = listOf(
            Triple("조향각", "%.0f°".format(f.steeringDeg ?: 0.0), "조향각"),
            Triple("브레이크", if (f.brakePressed == true) "밟음" else "뗌", "브레이크"),
            Triple("주행시간", "${f.drivingMinutes.roundToInt()}분", "주행시간"),
            Triple("실내온도", "${fmt1(f.cabinTempC ?: 0.0)}℃", "실내온도"),
            Triple("설정/풍량", "${fmt1(f.hvacTargetC ?: 0.0)}℃/${f.fanSpeed ?: "-"}", "설정온도"),
            Triple("기어", f.gear?.name ?: "-", "기어"),
            Triple("좌석착석", if (f.rearOccupied == true) "뒷좌석●" else "뒷좌석○", "좌석착석"),
            Triple("TBT", "${f.turns.size}건", "TBT"),
            Triple("진행방향", "%.0f°".format(f.headingDeg ?: 0.0), "진행방향"),
        )
        val sb = SpannableStringBuilder()
        for ((name, value, key) in rows) {
            val src = srcOf(key)
            sb.append(name.padEnd(5, '　').take(5)).append(" ").append(value.padEnd(8))
            val tagStart = sb.length
            sb.append(" [").append(src).append("]")
            sb.setSpan(
                ForegroundColorSpan(if (src == "SIM") Palette.amber else Palette.green),
                tagStart, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
            val st = vehicle.status[key] ?: navi.status[key]
            val short = when {
                st == null -> ""
                st.startsWith("수신 ") -> "SDK " + st.removePrefix("수신 ").take(14)
                st.startsWith("지원") -> "SDK 지원·값 대기"
                st.contains("NOT_SUPPORT") -> "SDK 미지원"
                st.contains("PERMISSION") || st.contains("권한") -> "SDK 권한 없음"
                else -> "SDK " + st.take(12)
            }
            if (short.isNotBlank() && !(e.mode == Mode.LIVE && src == "SDK")) {
                val s0 = sb.length
                sb.append("  ").append(short)
                sb.setSpan(ForegroundColorSpan(if (short.startsWith("SDK 미") || short.contains("권한")) Palette.red else Palette.sub), s0, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            sb.append("\n")
        }
        t(R.id.tvSources).text = sb
        t(R.id.tvSourcesTitle).text = if (e.mode == Mode.SCENARIO)
            "데이터 출처 · 재생값 [SIM] · 실차 SDK 수신값"
        else "데이터 출처 · SDK 값 우선, 없으면 [SIM] 대체"
    }

    private fun renderLog(e: MulmiEngine) {
        val sb = SpannableStringBuilder()
        e.session.events.takeLast(9).forEach { ev ->
            val s0 = sb.length
            sb.append("${fmtClock(ev.tripTimeSec)} ")
            sb.setSpan(ForegroundColorSpan(Palette.sub), s0, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            val s1 = sb.length
            sb.append(ev.type.tag)
            sb.setSpan(ForegroundColorSpan(Palette.event(ev.type)), s1, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.setSpan(StyleSpan(Typeface.BOLD), s1, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.append(" ").append(ev.text.take(64)).append("\n")
        }
        t(R.id.tvLog).text = sb
    }

    private fun showReport(e: MulmiEngine) {
        val rep = e.session.report ?: return
        reportShown = true
        t(R.id.tvReportRoute).text = "서울 → 강릉 (대관령 휴게소 경유) · 주행 ${rep.durationMin / 60}시간 ${rep.durationMin % 60}분"
        t(R.id.tvReportScore).text = rep.familyScore.toString()
        t(R.id.tvBar1Label).text = "부드러운 제동  ${(rep.smoothBrakingRatio * 100).roundToInt()}%"
        findViewById<ProgressBar>(R.id.bar1).progress = (rep.smoothBrakingRatio * 100).roundToInt()
        t(R.id.tvBar2Label).text = "회전 예고 (큰 회전 중 사전 안내 비율)  ${(rep.announceRatio * 100).roundToInt()}%"
        findViewById<ProgressBar>(R.id.bar2).progress = (rep.announceRatio * 100).roundToInt()
        val roads = rep.sicknessRoads.distinct().joinToString(", ").ifBlank { "-" }
        t(R.id.tvReportSummary).text =
            "급제동 ${rep.hardBrakes}회 · 급조향 ${rep.sharpSteers}회 · 멀미 신고 ${rep.sicknessReports}회($roads) · 케어 ${rep.careMinutes}분\n" +
            "평균 지수 ${rep.avgScore.roundToInt()} / 최고 ${rep.maxScore.roundToInt()} · 개인 케어 기준 ${rep.thresholdBefore} → ${rep.thresholdAfter} (다음엔 더 일찍 케어)"
        findViewById<View>(R.id.reportOverlay).visibility = View.VISIBLE
        running = false
    }

    // ------------------------------------------------------------------ helpers
    private fun highlight(text: String): CharSequence {
        val sb = SpannableStringBuilder(text)
        for (kw in listOf("왼쪽으로 크게", "오른쪽으로 크게", "왼쪽으로", "오른쪽으로", "휴게소", "부드럽게", "최고 단계", "편안한 길")) {
            val i = text.indexOf(kw)
            if (i >= 0) {
                sb.setSpan(ForegroundColorSpan(Palette.amber), i, i + kw.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                sb.setSpan(StyleSpan(Typeface.BOLD), i, i + kw.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                break
            }
        }
        return sb
    }

    private fun fmt1(v: Double) = "%.1f".format(v).removeSuffix(".0")
    private fun fmtDist(m: Double) = if (m >= 1000) "%.1fkm".format(m / 1000) else "${((m / 10).roundToInt() * 10)}m"
    private fun fmtClock(sec: Double): String {
        val s = sec.roundToInt()
        return "%d:%02d:%02d".format(s / 3600, (s / 60) % 60, s % 60)
    }

}
