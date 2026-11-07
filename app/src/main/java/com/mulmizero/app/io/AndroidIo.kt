package com.mulmizero.app.io

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import com.mulmizero.app.core.Audience
import com.mulmizero.app.core.LocationInput
import com.mulmizero.app.core.LocationSample
import com.mulmizero.app.core.Speaker
import com.mulmizero.app.core.VoiceInput
import com.mulmizero.app.core.VoiceIntent
import java.util.Locale

/**
 * Android standard TextToSpeech. Pleos TextToSpeech SDK is "준비 중" for third parties;
 * a PleosTtsSpeaker can implement the same [Speaker] port later.
 * If no Korean engine exists (e.g. the Pleos emulator image), lines are shown as captions only.
 */
class AndroidTtsSpeaker(context: Context, private val onState: (String) -> Unit) : Speaker {
    @Volatile private var ready = false
    override var engineName: String = "Android TTS 초기화 중"; private set
    private var tts: TextToSpeech? = null

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val r = tts?.setLanguage(Locale.KOREAN) ?: TextToSpeech.LANG_NOT_SUPPORTED
                ready = r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED
                engineName = if (ready) "Android TTS(${tts?.defaultEngine})" else "TTS 한국어 미지원 → 자막"
            } else {
                engineName = "TTS 엔진 없음 → 자막"
            }
            onState(engineName)
        }
    }

    override fun speak(text: String, audience: Audience) {
        if (!ready) return
        tts?.setSpeechRate(if (audience == Audience.PASSENGER) 0.95f else 1.0f)
        tts?.speak(text, TextToSpeech.QUEUE_ADD, null, "mz-${SystemClock.elapsedRealtime()}")
    }

    override fun shutdown() { runCatching { tts?.shutdown() } }
}

/**
 * Android SpeechRecognizer for "응 / 아니 / 멀미나". Replaceable by Pleos SpeechToText SDK.
 */
class AndroidSpeechVoiceInput(private val context: Context) : VoiceInput {
    override val engineName = "Android SpeechRecognizer"
    override val available: Boolean
        get() = SpeechRecognizer.isRecognitionAvailable(context) &&
            context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    private var recognizer: SpeechRecognizer? = null

    override fun listenOnce(onIntent: (VoiceIntent, String) -> Unit, onError: (String) -> Unit) {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) return onError("음성 인식 엔진 없음")
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return onError("마이크 권한 없음")
        recognizer?.destroy()
        val r = SpeechRecognizer.createSpeechRecognizer(context)
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle) {
                val texts = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
                val hit = texts.firstNotNullOfOrNull { t -> parseIntent(t)?.let { it to t } }
                if (hit != null) onIntent(hit.first, hit.second) else onError("인식: ${texts.firstOrNull() ?: "(없음)"}")
            }
            override fun onError(error: Int) = onError("음성 인식 오류 $error")
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR")
            .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        r.startListening(intent)
    }

    override fun shutdown() { runCatching { recognizer?.destroy() } }

    companion object {
        /** Keyword spotting kept deliberately small: drivers answer with one word. */
        fun parseIntent(text: String): VoiceIntent? {
            val t = text.replace(" ", "")
            return when {
                t.contains("멀미") || t.contains("속이안좋") || t.contains("토할") -> VoiceIntent.SICK
                listOf("아니", "싫어", "괜찮아", "됐어", "취소").any { t.contains(it) } -> VoiceIntent.NO
                listOf("응", "네", "예", "그래", "좋아", "해줘", "켜").any { t.contains(it) } -> VoiceIntent.YES
                else -> null
            }
        }
    }
}

/** Android LocationManager (GPS) for speed/heading. Replaceable by Pleos FusedLocation SDK. */
class AndroidLocationInput(private val context: Context) : LocationInput {
    override val engineName = "Android LocationManager"
    @Volatile var last: LocationSample? = null; private set
    private var lm: LocationManager? = null
    private val listener = LocationListener { loc: Location ->
        last = LocationSample(
            if (loc.hasSpeed()) loc.speed.toDouble() else null,
            if (loc.hasBearing()) loc.bearing.toDouble() else null,
            System.currentTimeMillis(),
        )
    }

    @SuppressLint("MissingPermission")
    override fun start(onSample: (LocationSample) -> Unit): Boolean {
        if (context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return false
        return try {
            val m = context.getSystemService(LocationManager::class.java) ?: return false
            lm = m
            val provider = when {
                m.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
                m.isProviderEnabled(LocationManager.FUSED_PROVIDER) -> LocationManager.FUSED_PROVIDER
                else -> return false
            }
            m.requestLocationUpdates(provider, 1000L, 0f, listener, Looper.getMainLooper())
            true
        } catch (t: Throwable) { false }
    }

    override fun stop() { runCatching { lm?.removeUpdates(listener) } }
}
