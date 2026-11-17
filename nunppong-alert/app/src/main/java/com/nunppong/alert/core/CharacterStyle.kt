package com.nunppong.alert.core

/**
 * 캐릭터 안내 (순수 로직). 단계가 바뀔 때만 한 번 작게 나왔다가 사라진다.
 * 깜빡이지 않고 부드럽게 나타났다 사라지므로 초당 2회 규칙과 충돌하지 않는다.
 */
object CharacterStyle {
    const val SHOW_MS = 2000L
    const val FADE_IN_MS = 200L
    const val FADE_OUT_MS = 500L
    const val POP_IN_MS = 220L
    const val SETTLE_MS = 120L
    const val POP_OUT_MS = 260L

    /** 손가락 개수: 1km=1, 500m=5, 300m=3 (숨김은 0 = 안 보임) */
    fun fingers(stage: Stage): Int = when (stage) {
        Stage.HIDDEN -> 0
        Stage.S1 -> 1
        Stage.S2 -> 5
        Stage.S3 -> 3
    }

    fun label(stage: Stage): String = when (stage) {
        Stage.HIDDEN -> ""
        Stage.S1 -> "1km"
        Stage.S2 -> "500m"
        Stage.S3 -> "300m"
    }

    /** 단계가 (숨김이 아닌 값으로) 바뀐 순간에만 보여준다. */
    fun shouldShow(prev: Stage, now: Stage): Boolean = now != Stage.HIDDEN && now != prev

    /** 톡 튀어나왔다가 쏙 들어가는 크기 배율 0..1.15 (가운데에서 커졌다 작아진다). */
    fun scale(elapsedMs: Long): Float = when {
        elapsedMs < 0 || elapsedMs >= SHOW_MS -> 0f
        elapsedMs < POP_IN_MS -> 1.15f * (elapsedMs / POP_IN_MS.toFloat())
        elapsedMs < POP_IN_MS + SETTLE_MS -> 1.15f - 0.15f * ((elapsedMs - POP_IN_MS) / SETTLE_MS.toFloat())
        elapsedMs > SHOW_MS - POP_OUT_MS -> (SHOW_MS - elapsedMs) / POP_OUT_MS.toFloat()
        else -> 1f
    }

    /** 시작 후 elapsedMs 시점의 투명도 0..1 (끝나면 0). */
    fun alpha(elapsedMs: Long): Float = when {
        elapsedMs < 0 || elapsedMs >= SHOW_MS -> 0f
        elapsedMs < FADE_IN_MS -> elapsedMs / FADE_IN_MS.toFloat()
        elapsedMs > SHOW_MS - FADE_OUT_MS -> (SHOW_MS - elapsedMs) / FADE_OUT_MS.toFloat()
        else -> 1f
    }
}
