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

    /** "sides" = 화면 좌우만, "all" = 사방 테두리 */
    var edgeMode: String
        get() = sp.getString("edgeMode", "sides") ?: "sides"
        set(v) = sp.edit().putString("edgeMode", v).apply()

    val sidesOnly: Boolean get() = edgeMode == "sides"

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

    /** 내 차로 등록한 블루투스 기기 */
    var btAddress: String
        get() = sp.getString("btAddress", "") ?: ""
        set(v) = sp.edit().putString("btAddress", v).apply()

    var btName: String
        get() = sp.getString("btName", "") ?: ""
        set(v) = sp.edit().putString("btName", v).apply()

    /** 보조 옵션(기본 꺼짐): 충전 연결 + 차량 이동 감지로 시작 */
    var vehicleAssist: Boolean
        get() = sp.getBoolean("vehicleAssist", false)
        set(v) = sp.edit().putBoolean("vehicleAssist", v).apply()

    /** 승객 모드 해제 시각 (epoch ms, 0 = 꺼짐) */
    var passengerUntil: Long
        get() = sp.getLong("passengerUntil", 0L)
        set(v) = sp.edit().putLong("passengerUntil", v).apply()

    fun isPassengerActive(now: Long = System.currentTimeMillis()) = passengerUntil > now
}
