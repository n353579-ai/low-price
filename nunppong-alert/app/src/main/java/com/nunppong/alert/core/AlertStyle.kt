package com.nunppong.alert.core

/** 표시 규칙(색·주기·두께·단계)을 한곳에 모은 순수 로직. */
object AlertStyle {
    /** 광과민성 안전선: 어떤 경우에도 초당 2회를 넘기지 않는다. */
    const val MAX_HZ = 2.0

    const val NIGHT_FACTOR = 0.65f

    const val YELLOW_DAY = 0xFFFFC400.toInt()
    const val RED_DAY = 0xFFFF2A1A.toInt()
    const val YELLOW_NIGHT = 0xFFB37800.toInt()
    const val RED_NIGHT = 0xFFB3140A.toInt()

    enum class Phase { OFF, YELLOW, RED }

    fun stageForDistance(meters: Double): Stage = when {
        meters < 0 || meters > 1000 -> Stage.HIDDEN
        meters > 500 -> Stage.S1
        meters > 300 -> Stage.S2
        else -> Stage.S3
    }

    fun hz(stage: Stage): Double = when (stage) {
        Stage.S1 -> 1.0
        Stage.S2 -> 1.5
        Stage.S3 -> 2.0
        Stage.HIDDEN -> 0.0
    }.coerceAtMost(MAX_HZ)

    fun phase(stage: Stage, tMs: Long): Phase {
        val h = hz(stage)
        if (h <= 0.0) return Phase.OFF
        val period = 1000.0 / h
        val first = (tMs.toDouble() % period) / period < 0.5
        return when (stage) {
            Stage.S1 -> if (first) Phase.YELLOW else Phase.OFF
            Stage.S2 -> if (first) Phase.YELLOW else Phase.RED
            Stage.S3 -> if (first) Phase.RED else Phase.OFF
            Stage.HIDDEN -> Phase.OFF
        }
    }

    fun widthDp(stage: Stage, overspeed: Boolean): Float =
        if (stage == Stage.S3 && overspeed) 30f else 10f

    /** 밤에는 어둡게. 기본 색은 규칙의 밤 색을 그대로, 사용자가 고른 색은 70%로 어둡게. */
    private fun nightOf(c: Int, defDay: Int, defNight: Int): Int {
        if (c == defDay) return defNight
        val r = ((c shr 16) and 0xFF) * 7 / 10
        val g = ((c shr 8) and 0xFF) * 7 / 10
        val b = (c and 0xFF) * 7 / 10
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    /** yellow/red 는 사용자가 고른 낮 색 (기본: 규칙의 노랑/빨강) */
    fun color(phase: Phase, night: Boolean, yellow: Int = YELLOW_DAY, red: Int = RED_DAY): Int = when (phase) {
        Phase.YELLOW -> if (night) nightOf(yellow, YELLOW_DAY, YELLOW_NIGHT) else yellow
        Phase.RED -> if (night) nightOf(red, RED_DAY, RED_NIGHT) else red
        Phase.OFF -> 0
    }
}
