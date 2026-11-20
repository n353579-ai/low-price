package com.nunppong.alert.core

import android.content.Context

/**
 * 손 안 대고 배우기: 카메라를 지날 때마다 "카메라가 내 오른쪽/왼쪽 몇 m였나"를 기억한다.
 * 우측통행에서 카메라는 단속하는 방향의 오른쪽 길가에 선다. 그래서 같은 카메라를 같은 방향으로 여러 번 지나며
 * 늘 왼쪽에 있었다면 그 방향은 '반대 차선'이다. (반대 차선 판정이 틀려도 끝까지 조용해지지는 않는다:
 * 엔진은 이 경우 약한 경로처럼 약 4초 거리 안에서는 그대로 알린다.)
 * 키 = 카메라 좌표 + 진행방향 8구역. 폰 안에만 저장한다.
 */
class SideLearner(
    initial: Map<String, List<Double>> = emptyMap(),
    private val save: (Map<String, List<Double>>) -> Unit = {},
) {
    private val data = HashMap<String, ArrayList<Double>>().also { m -> initial.forEach { (k, v) -> m[k] = ArrayList(v) } }

    fun record(lat: Double, lon: Double, heading: Double, sideM: Double) {
        val l = data.getOrPut(FeedbackBlocklist.key(lat, lon, heading)) { ArrayList() }
        l.add(sideM.coerceIn(-50.0, 50.0))
        while (l.size > MAX_SAMPLES) l.removeAt(0)
        save(data.mapValues { it.value.toList() })
    }

    /** 이 방향에서 이 카메라가 늘 내 왼쪽이었나 (표본 2개 이상, 평균 4m 이상 왼쪽, 대부분 왼쪽) */
    fun isOpposite(c: Camera, heading: Double): Boolean {
        val l = data[FeedbackBlocklist.key(c.lat, c.lon, heading)] ?: return false
        if (l.size < MIN_PASSES) return false
        val left = l.count { it <= -LEFT_EACH_M }
        return l.average() <= -LEFT_MEAN_M && left * 3 >= l.size * 2
    }

    fun clear() {
        data.clear()
        save(emptyMap())
    }

    companion object {
        const val MAX_SAMPLES = 8
        const val MIN_PASSES = 2
        const val LEFT_MEAN_M = 4.0
        const val LEFT_EACH_M = 2.0

        @Volatile private var inst: SideLearner? = null

        fun get(context: Context): SideLearner {
            inst?.let { return it }
            synchronized(this) {
                inst?.let { return it }
                val sp = context.applicationContext.getSharedPreferences("sidelearn", Context.MODE_PRIVATE)
                val init = HashMap<String, List<Double>>()
                for (ln in (sp.getString("lines", "") ?: "").split('\n')) {
                    val i = ln.indexOf('=')
                    if (i <= 0) continue
                    val v = ln.substring(i + 1).split(',').mapNotNull { it.toDoubleOrNull() }
                    if (v.isNotEmpty()) init[ln.substring(0, i)] = v
                }
                val l = SideLearner(init) { m ->
                    sp.edit().putString("lines", m.entries.joinToString("\n") { (k, v) -> k + "=" + v.joinToString(",") { x -> String.format(java.util.Locale.US, "%.1f", x) } }).apply()
                }
                inst = l
                return l
            }
        }
    }
}
