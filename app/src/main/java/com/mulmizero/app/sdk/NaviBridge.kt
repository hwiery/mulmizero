package com.mulmizero.app.sdk

import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.constants.RouteOption
import ai.pleos.playground.navi.constants.TurnType
import ai.pleos.playground.navi.constants.WaypointIndex
import ai.pleos.playground.navi.data.CautionPoint
import ai.pleos.playground.navi.data.CurrentLocationInfo
import ai.pleos.playground.navi.data.RequestWaypointInfo
import ai.pleos.playground.navi.data.RouteStateInfo
import ai.pleos.playground.navi.data.TBTInfo
import ai.pleos.playground.navi.data.WaypointChangedInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import android.content.Context
import android.os.Handler
import android.os.Looper
import com.mulmizero.app.core.ActuationResult
import com.mulmizero.app.core.ActuationStatus
import com.mulmizero.app.core.Poi
import com.mulmizero.app.core.TurnKind
import com.mulmizero.app.core.UpcomingTurn
import java.util.concurrent.ConcurrentHashMap

/**
 * Pleos NaviHelper SDK (ai.pleos.playground:NaviHelper:3.0.5) adapter:
 * TBT (next manoeuvres), current heading, caution points, route state,
 * addWaypoint / changeRouteOption(COMFORTABLE).
 */
class NaviBridge(context: Context, private val log: (String) -> Unit) {
    private val ctx = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private var navi: NaviHelper? = null

    @Volatile var connected = false; private set
    @Volatile var tbt: Sample<List<UpcomingTurn>>? = null; private set
    @Volatile var headingDeg: Sample<Double>? = null; private set
    @Volatile var routing: Sample<Boolean>? = null; private set
    @Volatile var routeOption: String? = null; private set
    @Volatile var cautionCount: Int = 0; private set
    val status = ConcurrentHashMap<String, String>()

    private var waypointCb: ((ActuationResult) -> Unit)? = null
    private var routeCb: ((ActuationResult) -> Unit)? = null

    private val listener = object : NaviHelperEventListener {
        override fun onTBTInfo(p0: List<TBTInfo>) {
            val turns = p0.mapIndexed { i, t ->
                UpcomingTurn("${t.type.name}|${t.description}|$i", TurnMapper.map(t.type), t.distance, t.description)
            }
            tbt = Sample(turns)
            status["TBT"] = "수신 ${turns.size}건" + (turns.firstOrNull()?.let { " · ${it.kind.label} ${it.distanceM.toInt()}m" } ?: "")
        }

        override fun onCurrentLocationInfo(p0: CurrentLocationInfo) {
            headingDeg = Sample(p0.heading)
            status["진행방향"] = "수신 %.0f°".format(p0.heading)
        }

        override fun onRouteStateInfo(p0: RouteStateInfo) {
            routing = Sample(p0.isRouting)
            routeOption = p0.routeOption.name
            status["경로상태"] = "수신 ${if (p0.isRouting) "안내 중" else "안내 없음"} · ${p0.routeOption.name}"
        }

        override fun onCautionPointInfo(p0: List<CautionPoint>) {
            cautionCount = p0.size
            status["주의지점"] = "수신 ${p0.size}건"
        }

        override fun onWaypointChanged(p0: WaypointChangedInfo) {
            log("NaviHelper onWaypointChanged: $p0")
            main.post { waypointCb?.invoke(ActuationResult(ActuationStatus.APPLIED_SDK, "NaviHelper.addWaypoint → onWaypointChanged")); waypointCb = null }
        }

        override fun onRouteOptionChanged(p0: RouteOption) {
            routeOption = p0.name
            main.post { routeCb?.invoke(ActuationResult(ActuationStatus.APPLIED_SDK, "changeRouteOption → onRouteOptionChanged(${p0.name})")); routeCb = null }
        }

        override fun onError(p0: NaviErrorCode) {
            status["오류"] = p0.name
            log("NaviHelper onError: ${p0.name}")
            main.post {
                val st = if (p0.name.contains("PERMISSION")) ActuationStatus.NEEDS_APPROVAL else ActuationStatus.UNSUPPORTED
                waypointCb?.invoke(ActuationResult(st, "NaviHelper onError(${p0.name})")); waypointCb = null
                routeCb?.invoke(ActuationResult(st, "NaviHelper onError(${p0.name})")); routeCb = null
            }
        }
    }

    private val poll = object : Runnable {
        override fun run() {
            navi?.let { n ->
                runCatching { n.getTBTInfo() }
                runCatching { n.getCurrentLocationInfo() }
                runCatching { n.getRouteStateInfo() }
            }
            main.postDelayed(this, 3000)
        }
    }

    fun start() {
        try {
            val n = NaviHelper(ctx)
            navi = n
            status["NaviHelper"] = "연결 중"
            n.initialize(
                {
                    connected = true
                    status["NaviHelper"] = "연결됨"
                    log("NaviHelper 연결됨")
                    runCatching { n.addListener(listener) }.onFailure { status["NaviHelper"] = "리스너 실패: ${it.javaClass.simpleName}" }
                    main.post(poll)
                },
                { t ->
                    status["NaviHelper"] = "연결 실패: ${t.javaClass.simpleName}"
                    log("NaviHelper 연결 실패: $t")
                },
            )
        } catch (t: Throwable) {
            status["NaviHelper"] = "초기화 실패: ${t.javaClass.simpleName}"
            log("NaviHelper 초기화 실패: $t")
        }
    }

    /**
     * Route-changing calls make the navigation app come to the foreground and try to re-route.
     * Without an active route guidance that only shows "경로를 얻지 못했습니다", so we skip the call.
     */
    private fun NOT_ROUTING(api: String) = ActuationResult(
        ActuationStatus.SIMULATED,
        "경로 안내 중 아님(isRouting=${routing?.value ?: "미수신"}) → NaviHelper.$api 호출 생략, 시나리오 경로에 반영",
    )

    fun stop() {
        main.removeCallbacksAndMessages(null)
        runCatching { navi?.removeListener(listener) }
        runCatching { navi?.release() }
        navi = null
        connected = false
    }

    fun addWaypoint(poi: Poi, onResult: (ActuationResult) -> Unit) {
        val n = navi
        if (n == null || !connected) return onResult(ActuationResult(ActuationStatus.UNSUPPORTED, "NaviHelper 미연결"))
        if (routing?.value != true) return onResult(NOT_ROUTING("addWaypoint"))
        try {
            waypointCb = onResult
            n.addWaypoint(RequestWaypointInfo(poi.lat, poi.lon, poi.name, WaypointIndex.FIRST, "", "", "", ""))
            main.postDelayed({
                waypointCb?.let {
                    it(ActuationResult(ActuationStatus.SIMULATED, "NaviHelper.addWaypoint 호출됨 · 응답 없음(경로 안내 중이 아님) → 시나리오 경로에 반영"))
                    waypointCb = null
                }
            }, 3000)
        } catch (t: Throwable) {
            waypointCb = null
            val st = if (t is SecurityException) ActuationStatus.NEEDS_APPROVAL else ActuationStatus.UNSUPPORTED
            onResult(ActuationResult(st, "addWaypoint 실패: ${VehicleBridge.shortError(t)}"))
        }
    }

    fun requestComfortRoute(onResult: (ActuationResult) -> Unit) {
        val n = navi
        if (n == null || !connected) return onResult(ActuationResult(ActuationStatus.UNSUPPORTED, "NaviHelper 미연결"))
        if (routing?.value != true) return onResult(NOT_ROUTING("changeRouteOption(COMFORTABLE)"))
        try {
            routeCb = onResult
            n.changeRouteOption(RouteOption.COMFORTABLE)
            main.postDelayed({
                routeCb?.let {
                    it(ActuationResult(ActuationStatus.SIMULATED, "changeRouteOption(COMFORTABLE) 호출됨 · 응답 없음(경로 안내 중이 아님) → 시나리오 경로에 반영"))
                    routeCb = null
                }
            }, 3000)
        } catch (t: Throwable) {
            routeCb = null
            val st = if (t is SecurityException) ActuationStatus.NEEDS_APPROVAL else ActuationStatus.UNSUPPORTED
            onResult(ActuationResult(st, "changeRouteOption 실패: ${VehicleBridge.shortError(t)}"))
        }
    }
}

object TurnMapper {
    fun map(t: TurnType): TurnKind = when (t) {
        TurnType.TURN_STRAIGHT -> TurnKind.STRAIGHT
        TurnType.TURN_LEFT -> TurnKind.LEFT
        TurnType.TURN_RIGHT -> TurnKind.RIGHT
        TurnType.TURN_KEEP_L -> TurnKind.KEEP_LEFT
        TurnType.TURN_KEEP_R -> TurnKind.KEEP_RIGHT
        TurnType.TURN_SHARP_L -> TurnKind.SHARP_LEFT
        TurnType.TURN_SHARP_R -> TurnKind.SHARP_RIGHT
        TurnType.TURN_UTURN -> TurnKind.UTURN
        TurnType.TURN_ENTER_IC_L, TurnType.TURN_EXIT_IC_L -> TurnKind.IC_LEFT
        TurnType.TURN_ENTER_IC_R, TurnType.TURN_EXIT_IC_R, TurnType.TURN_ENTER_IC_C, TurnType.TURN_EXIT_IC_C -> TurnKind.IC_RIGHT
        TurnType.TURN_ENTER_SERVICEAREA, TurnType.TURN_RESTAREA -> TurnKind.REST_AREA
        else -> when {
            t.name.startsWith("TURN_ENTER_ROUNDABOUT") || t == TurnType.TURN_EXIT_ROUNDABOUT -> TurnKind.ROUNDABOUT
            t.name.endsWith("_L") -> TurnKind.KEEP_LEFT
            t.name.endsWith("_R") -> TurnKind.KEEP_RIGHT
            else -> TurnKind.OTHER
        }
    }
}
