package com.nunppong.alert.core

import android.content.Context

/** 사용자가 직접 고쳐 둔 카메라 제한속도 (공공데이터 값이 현장과 다를 때). 카메라 좌표 기준, 폰 안에만 저장. */
class LimitOverride(
    initial: Map<String, Int> = emptyMap(),
    private val save: (Map<String, Int>) -> Unit = {},
) {
    private val data = HashMap(initial)

    private fun key(lat: Double, lon: Double) = String.format(java.util.Locale.US, "%.5f,%.5f", lat, lon)

    /** 고쳐 둔 값, 없으면 0 */
    fun get(lat: Double, lon: Double): Int = data[key(lat, lon)] ?: 0

    /** v <= 0 이면 원래대로 */
    fun set(lat: Double, lon: Double, v: Int) {
        if (v > 0) data[key(lat, lon)] = v else data.remove(key(lat, lon))
        save(data.toMap())
    }

    companion object {
        @Volatile private var inst: LimitOverride? = null

        fun get(context: Context): LimitOverride {
            inst?.let { return it }
            synchronized(this) {
                inst?.let { return it }
                val sp = context.applicationContext.getSharedPreferences("limitfix", Context.MODE_PRIVATE)
                val init = HashMap<String, Int>()
                for (ln in (sp.getString("lines", "") ?: "").split('\n')) {
                    val i = ln.indexOf('=')
                    if (i > 0) ln.substring(i + 1).toIntOrNull()?.let { init[ln.substring(0, i)] = it }
                }
                val l = LimitOverride(init) { m -> sp.edit().putString("lines", m.entries.joinToString("\n") { (k, v) -> "$k=$v" }).apply() }
                inst = l
                return l
            }
        }
    }
}
