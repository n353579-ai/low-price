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

    /**
     * 이 카메라를 몇 m 앞에서부터 알릴지. 빠른 도로일수록 멀리서.
     * 제한속도 100 이상(고속도로) 1km, 80~99 700m, 그 아래(국도·시내) 500m, 모르면 700m.
     */
    fun rangeM(limit: Int): Double = when {
        limit >= 100 -> 1000.0
        limit >= 80 -> 700.0
        limit > 0 -> 500.0
        else -> 700.0
    }

    /** 범위 안에서 거리별 단계: 가까울수록 3단계(빨강). 1km 범위는 예전과 같은 500m/300m 경계. */
    fun stageForDistance(meters: Double, range: Double = 1000.0): Stage {
        val s3 = minOf(300.0, 0.5 * range)
        val s2 = minOf(500.0, 0.8 * range)
        return when {
            meters < 0 || meters > range -> Stage.HIDDEN
            meters > s2 -> Stage.S1
            meters > s3 -> Stage.S2
            else -> Stage.S3
        }
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

    /** 단속 지점에 얼마나 가까운지 0(1km 밖)..1(바로 앞). 번짐 폭·진하기를 키우는 데 쓴다. */
    fun proximity(distanceM: Double?): Float {
        if (distanceM == null || distanceM > 1000.0) return 0f
        return ((1000.0 - distanceM) / 1000.0).toFloat().coerceIn(0f, 1f)
    }

    const val MAX_GLOW_FRACTION = 0.30f   // 번짐이 화면 폭의 30%를 넘지 않게 (가운데 지도 보호)

    /** 안쪽으로 번지는 폭(px). spread=0 이면 예전과 같은 고정 폭(띠 두께의 1.6배). */
    fun glowPx(bandPx: Float, screenWidthPx: Float, proximity: Float, spread: Float): Float {
        val base = bandPx * 1.6f
        val target = screenWidthPx * 0.25f
        val extra = (target - base).coerceAtLeast(0f) * proximity * spread
        return (base + extra).coerceAtMost(screenWidthPx * MAX_GLOW_FRACTION)
    }

    /** 번짐의 가장 진한 부분의 투명도. 가까울수록 진해진다 (최대 0.85). */
    fun glowPeakAlpha(proximity: Float, spread: Float): Float =
        (0.55f + 0.30f * proximity * spread).coerceIn(0.55f, 0.85f)

    /** 조용한 표시: 제한속도를 알고 지키는 중이면 그 제한속도를 반환(깜빡임 없이 큰 숫자만), 아니면 0. */
    fun calmLimit(calm: Boolean, limit: Int, speedKmh: Double): Int =
        if (calm && limit > 0 && speedKmh <= limit) limit else 0
}
