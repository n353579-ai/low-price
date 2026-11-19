package com.nunppong.alert.core

import android.content.Context

/** 알림이 시작된 순간의 기록 (틀린 알림 원인을 확인하는 용도). 폰 안에만 저장. */
data class AlertEntry(
    val timeMs: Long,
    val camLat: Double,
    val camLon: Double,
    val limit: Int,
    val distM: Int,
    /** 어떤 규칙으로 알렸나: 도로선 / 정면(300m 안전망) / 직선(도로 정보 없음) / 50m안 */
    val rule: String,
    val speedKmh: Int,
) {
    fun toLine(): String = listOf(timeMs, camLat, camLon, limit, distM, rule, speedKmh).joinToString("|")

    companion object {
        fun parse(line: String): AlertEntry? {
            val p = line.split('|')
            if (p.size < 7) return null
            return try {
                AlertEntry(p[0].toLong(), p[1].toDouble(), p[2].toDouble(), p[3].toInt(), p[4].toInt(), p[5], p[6].toInt())
            } catch (e: NumberFormatException) {
                null
            }
        }
    }
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

    fun clear() {
        items.clear()
        save(emptyList())
    }

    companion object {
        const val MAX = 30
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
