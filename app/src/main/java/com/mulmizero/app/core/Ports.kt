package com.mulmizero.app.core

/**
 * Ports = swappable adapters. Android standard implementations are used today;
 * Pleos TextToSpeech / SpeechToText / FusedLocation SDK implementations can be
 * dropped in behind the same interfaces once those SDKs are opened to third parties.
 */

enum class Audience(val label: String) { DRIVER("운전자"), PASSENGER("동승자"), ALL("모두") }

interface Speaker {
    val engineName: String
    fun speak(text: String, audience: Audience)
    fun shutdown() {}
}

/** Recognised voice intents. */
enum class VoiceIntent { YES, NO, SICK }

interface VoiceInput {
    val engineName: String
    val available: Boolean
    /** Start one listening session; [onIntent] is called on a recognised intent. */
    fun listenOnce(onIntent: (VoiceIntent, String) -> Unit, onError: (String) -> Unit)
    fun shutdown() {}
}

data class LocationSample(val speedMps: Double?, val headingDeg: Double?, val timeMs: Long)

interface LocationInput {
    val engineName: String
    fun start(onSample: (LocationSample) -> Unit): Boolean
    fun stop() {}
}

enum class ActuationStatus(val label: String) {
    APPLIED_SDK("SDK 적용"),
    PARTIAL_SDK("일부 SDK 적용"),
    NEEDS_APPROVAL("승인 필요 · 시뮬레이션"),
    UNSUPPORTED("미지원 · 시뮬레이션"),
    SIMULATED("시뮬레이션"),
}

data class ActuationResult(val status: ActuationStatus, val detail: String)

interface ClimateActuator {
    /** level 2 = care (fresh air, −2 ℃, fan +1), level 3 = max care (−2 ℃, fan +2, rear seat ventilation). */
    fun applyCare(level: Int, onResult: (ActuationResult) -> Unit)
    fun restore(onResult: (ActuationResult) -> Unit)
}

interface RouteActuator {
    fun addWaypoint(poi: Poi, onResult: (ActuationResult) -> Unit)
    fun requestComfortRoute(onResult: (ActuationResult) -> Unit)
}
