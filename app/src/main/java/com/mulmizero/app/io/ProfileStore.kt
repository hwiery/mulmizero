package com.mulmizero.app.io

import android.content.Context
import com.mulmizero.app.core.Profile
import com.mulmizero.app.core.Sensitivity
import com.mulmizero.app.core.SicknessLabel
import org.json.JSONObject
import java.io.File

/** Family profile + "멀미나" labels. Stored in app-private storage only; never sent anywhere. */
class ProfileStore(context: Context) {
    private val prefs = context.getSharedPreferences("profile", Context.MODE_PRIVATE)
    private val labelsFile = File(context.filesDir, "sickness_labels.jsonl")

    /** Fixed profile used by the scenario playback so the demo is reproducible. */
    val demoProfile = Profile("서아", 7, Sensitivity.HIGH)

    fun load(): Profile = Profile(
        name = prefs.getString("name", demoProfile.name)!!,
        ageYears = prefs.getInt("age", demoProfile.ageYears),
        sensitivity = Sensitivity.valueOf(prefs.getString("sensitivity", demoProfile.sensitivity.name)!!),
        careThreshold = prefs.getInt("threshold", demoProfile.careThreshold),
        sicknessLabels = prefs.getInt("labels", 0),
    )

    fun save(p: Profile) {
        prefs.edit()
            .putString("name", p.name).putInt("age", p.ageYears)
            .putString("sensitivity", p.sensitivity.name)
            .putInt("threshold", p.careThreshold).putInt("labels", p.sicknessLabels)
            .apply()
    }

    fun appendLabel(l: SicknessLabel) {
        val o = JSONObject()
            .put("tripTimeSec", l.tripTimeSec).put("score", l.score)
            .put("turnsWithin1Km", l.turnsWithin1Km).put("drivingMinutes", l.drivingMinutes)
            .put("cabinTempC", l.cabinTempC ?: JSONObject.NULL)
            .put("steeringRate", l.steeringRateDegPerSec).put("brakePerMin", l.brakeTransitionsPerMin)
            .put("road", l.roadLabel).put("savedAt", System.currentTimeMillis())
        runCatching { labelsFile.appendText(o.toString() + "\n") }
    }

    fun labelCount(): Int = runCatching { labelsFile.readLines().count { it.isNotBlank() } }.getOrDefault(0)
}
