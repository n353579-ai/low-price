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

    /** 약한 단계(1km, 500m 첫 색)에 쓰는 색 — 기본 노랑 */
    var yellowColor: Int
        get() = sp.getInt("yellowColor", AlertStyle.YELLOW_DAY)
        set(v) = sp.edit().putInt("yellowColor", v).apply()

    /** 임박 단계(300m 안, 500m 둘째 색)에 쓰는 색 — 기본 빨강 */
    var redColor: Int
        get() = sp.getInt("redColor", AlertStyle.RED_DAY)
        set(v) = sp.edit().putInt("redColor", v).apply()

    /** true = 제한속도 +5km/h 를 넘을 때만 경고 (제한속도를 모르는 카메라는 항상 경고) */
    var overspeedOnly: Boolean
        get() = sp.getBoolean("overspeedOnly", false)
        set(v) = sp.edit().putBoolean("overspeedOnly", v).apply()

    /** 단계가 바뀔 때 작은 캐릭터를 잠깐 보여준다 (기본 켜짐) */
    var character: Boolean
        get() = sp.getBoolean("character", true)
        set(v) = sp.edit().putBoolean("character", v).apply()

    /** 화면이 꺼져 있을 때 단속 구간에 가까워지면 화면을 잠깐 켠다 (기본 켜짐) */
    var wakeScreen: Boolean
        get() = sp.getBoolean("wakeScreen", true)
        set(v) = sp.edit().putBoolean("wakeScreen", v).apply()

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
