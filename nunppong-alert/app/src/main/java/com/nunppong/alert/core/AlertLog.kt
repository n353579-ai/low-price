package com.nunppong.alert.core

import android.content.Context

/** 알림이 시작된 순간의 기록 (틀린 알림 원인 확인·주행 후 검토용). 폰 안에만 저장. */
data class AlertEntry(
    val timeMs: Long,
    val camLat: Double,
    val camLon: Double,
    val limit: Int,
    val distM: Int,
    /** 어떤 규칙으로 알렸나: 도로선 / 정면(안전망) / 직선(도로 정보 없음) / 50m안 */
    val rule: String,
    val speedKmh: Int,
    /** 알림이 시작될 때 내 진행방향(0~359), 모르면 -1 */
    val heading: Int = -1,
    /** 카메라 300m·150m·50m 전의 내 속도(km/h) "62/55/41", 못 잰 곳은 "-" (지난 뒤에 채워진다) */
    val speeds: String = "",
    /** 주행 후 검토: 0 아직, 1 맞아요, 2 아니에요 */
    val review: Int = 0,
) {
    fun toLine(): String =
        listOf(timeMs, camLat, camLon, limit, distM, rule, speedKmh, heading, speeds.ifEmpty { "_" }, review).joinToString("|")

    companion object {
        fun parse(line: String): AlertEntry? {
            val p = line.split('|')
            if (p.size < 7) return null
            return try {
                AlertEntry(
                    p[0].toLong(), p[1].toDouble(), p[2].toDouble(), p[3].toInt(), p[4].toInt(), p[5], p[6].toInt(),
                    heading = p.getOrNull(7)?.toIntOrNull() ?: -1,
                    speeds = p.getOrNull(8)?.takeIf { it != "_" } ?: "",
                    review = p.getOrNull(9)?.toIntOrNull() ?: 0,
                )
            } catch (e: NumberFormatException) {
                null
            }
        }

        fun cameraKey(lat: Double, lon: Double): String = String.format(java.util.Locale.US, "%.5f,%.5f", lat, lon)
    }

    val key: String get() = cameraKey(camLat, camLon)
}

/** 최근 알림 기록 (최신이 앞). 저장 함수를 연결해 쓰고, 테스트에서는 메모리만 쓴다. */
class AlertLogList(initial: List<AlertEntry> = emptyList(), private val save: (List<AlertEntry>) -> Unit = {}) {
    private val items = ArrayList(initial)

    fun all(): List<AlertEntry> = items.toList()

    fun add(e: AlertEntry) {
        items.add(0, e)
        while (items.size > MAX) items.removeAt(items.size - 1)
        save(items.toList())
    }

    /** 카메라를 지난 뒤, 그 카메라의 가장 최근 기록에 속도 변화를 채운다 */
    fun markPass(camKey: String, speeds: String): Boolean {
        val i = items.indexOfFirst { it.key == camKey }
        if (i < 0) return false
        items[i] = items[i].copy(speeds = speeds)
        save(items.toList())
        return true
    }

    /** 주행 후 검토 결과 저장 (1 맞아요, 2 아니에요) */
    fun setReview(timeMs: Long, review: Int) {
        val i = items.indexOfFirst { it.timeMs == timeMs }
        if (i < 0) return
        items[i] = items[i].copy(review = review)
        save(items.toList())
    }

    fun clear() {
        items.clear()
        save(emptyList())
    }

    companion object {
        const val MAX = 60
    }
}

object AlertLog {
    @Volatile private var list: AlertLogList? = null

    fun get(context: Context): AlertLogList {
        list?.let { return it }
        synchronized(this) {
            list?.let { return it }
            val sp = context.applicationContext.getSharedPreferences("alertlog", Context.MODE_PRIVATE)
            val init = (sp.getString("lines", "") ?: "").split('\n').mapNotNull { if (it.isBlank()) null else AlertEntry.parse(it) }
            val l = AlertLogList(init) { items ->
                sp.edit().putString("lines", items.joinToString("\n") { it.toLine() }).apply()
            }
            list = l
            return l
        }
    }
}

/** 카메라 접근 중 내 속도를 기억했다가, 지난 뒤 300m·150m·50m 전 속도를 뽑아 낸다 */
class ApproachSpeeds(private val max: Int = 120) {
    private class S(val lat: Double, val lon: Double, val kmh: Double)

    private val buf = ArrayDeque<S>()

    fun add(lat: Double, lon: Double, kmh: Double) {
        buf.addLast(S(lat, lon, kmh))
        while (buf.size > max) buf.removeFirst()
    }

    /** "62/55/41": 각 거리(300,150,50m)에 가장 가까웠던 때의 속도, 없으면 "-" */
    fun profile(camLat: Double, camLon: Double): String =
        listOf(300.0, 150.0, 50.0).joinToString("/") { target ->
            val best = buf.minByOrNull { Math.abs(Geo.distanceM(it.lat, it.lon, camLat, camLon) - target) }
            if (best != null && Math.abs(Geo.distanceM(best.lat, best.lon, camLat, camLon) - target) <= 45.0) best.kmh.toInt().toString() else "-"
        }

    fun clear() = buf.clear()
}
