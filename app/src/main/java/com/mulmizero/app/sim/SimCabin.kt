package com.mulmizero.app.sim

/**
 * Simulated HVAC + cabin temperature. Used when the vehicle does not accept
 * control commands (e.g. CONTROL_CAR_CLIMATE not granted) so the demo can still
 * show the closed loop: care → cooler cabin → lower risk.
 */
class SimCabin(
    var targetC: Double = 24.0,
    var fanSpeed: Int = 2,
    var recirculation: Boolean = true,
    var cabinC: Double = 25.0,
    var rearVentilation: Int = 0,
) {
    /** Heat load from sun/passengers: cabin settles this much above the target. */
    var heatLoadC = 1.0

    private var saved: Snapshot? = null
    private data class Snapshot(val target: Double, val fan: Int, val recirc: Boolean, val vent: Int)

    fun applyCare(level: Int) {
        if (saved == null) saved = Snapshot(targetC, fanSpeed, recirculation, rearVentilation)
        val base = saved!!
        recirculation = false
        targetC = (base.target - 2.0).coerceAtLeast(18.0)
        fanSpeed = (base.fan + if (level >= 3) 2 else 1).coerceAtMost(8)
        rearVentilation = if (level >= 3) 1 else base.vent
    }

    fun restore() {
        val s = saved ?: return
        targetC = s.target; fanSpeed = s.fan; recirculation = s.recirc; rearVentilation = s.vent
        saved = null
    }

    val inCare get() = saved != null
    val savedTarget: Double? get() = saved?.target
    val savedFan: Int? get() = saved?.fan

    /** Advance one frame (≈ one compressed minute of HVAC response). */
    fun step() {
        val eq = targetC + heatLoadC - (if (!recirculation) 0.3 else 0.0)
        val k = 0.02 + 0.006 * fanSpeed
        cabinC += (eq - cabinC) * k
    }
}
