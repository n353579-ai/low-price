package com.nunppong.alert.core

import android.content.Context

/**
 * 👎를 받은 카메라를 "그 진행방향"에서 끈다. 서버 없이 폰 안에만 저장.
 * 키 = 카메라 좌표(소수 5자리) + 진행방향 8구역(45°). 데이터가 갱신돼도 좌표가 같으면 유지된다.
 */
class FeedbackBlocklist(initial: Collection<String> = emptyList(), private val save: (Set<String>) -> Unit = {}) {
    private val keys = HashSet<String>(initial)

    val size: Int get() = keys.size

    fun isBlocked(c: Camera, heading: Double): Boolean = keys.contains(key(c.lat, c.lon, heading))

    fun block(lat: Double, lon: Double, heading: Double) {
        if (keys.add(key(lat, lon, heading))) save(keys.toSet())
    }

    fun clear() {
        keys.clear()
        save(emptySet())
    }

    companion object {
        fun sector(heading: Double): Int = (((heading % 360 + 360) % 360 + 22.5) / 45.0).toInt() % 8

        fun key(lat: Double, lon: Double, heading: Double): String =
            String.format(java.util.Locale.US, "%.5f,%.5f,%d", lat, lon, sector(heading))
    }
}

/** 앱 전체에서 하나만 쓰는 저장소 (SharedPreferences 연결). */
object CameraFeedback {
    @Volatile private var list: FeedbackBlocklist? = null

    fun get(context: Context): FeedbackBlocklist {
        list?.let { return it }
        synchronized(this) {
            list?.let { return it }
            val sp = context.applicationContext.getSharedPreferences("feedback", Context.MODE_PRIVATE)
            val l = FeedbackBlocklist(sp.getStringSet("blocked", emptySet()) ?: emptySet()) {
                sp.edit().putStringSet("blocked", it).apply()
            }
            list = l
            return l
        }
    }
}
