package com.nunppong.alert.core

import android.content.Context

class Prefs(context: Context) {
    private val sp = context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var brightness: Float
        get() = sp.getFloat("brightness", 1f)
        set(v) = sp.edit().putFloat("brightness", v).apply()

    /** 두께 배율 (기본 1.0 = 12dp / 과속 20dp) */
    var thickness: Float
        get() = sp.getFloat("thickness", 1f)
        set(v) = sp.edit().putFloat("thickness", v).apply()

    /** "auto" | "on" | "off" */
    var nightMode: String
        get() = sp.getString("nightMode", "auto") ?: "auto"
        set(v) = sp.edit().putString("nightMode", v).apply()

    var lastLat: Double
        get() = java.lang.Double.longBitsToDouble(sp.getLong("lastLat", java.lang.Double.doubleToRawLongBits(37.5665)))
        set(v) = sp.edit().putLong("lastLat", java.lang.Double.doubleToRawLongBits(v)).apply()

    var lastLon: Double
        get() = java.lang.Double.longBitsToDouble(sp.getLong("lastLon", java.lang.Double.doubleToRawLongBits(126.9780)))
        set(v) = sp.edit().putLong("lastLon", java.lang.Double.doubleToRawLongBits(v)).apply()

    fun isNightNow(nowMs: Long = System.currentTimeMillis()): Boolean = when (nightMode) {
        "on" -> true
        "off" -> false
        else -> SunCalc.isNight(lastLat, lastLon, nowMs)
    }
}
