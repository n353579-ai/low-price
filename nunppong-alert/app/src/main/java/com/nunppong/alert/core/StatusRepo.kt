package com.nunppong.alert.core

import kotlinx.coroutines.flow.MutableStateFlow

data class Status(
    val mode: Mode = Mode.IDLE,
    val stage: Stage = Stage.HIDDEN,
    val distanceM: Int? = null,
    val speedKmh: Int = 0,
    val overspeed: Boolean = false,
    val reason: Reason = Reason.NONE,
    /** 0이 아니면 승객 모드 중 (해제 시각, epoch ms) */
    val passengerUntil: Long = 0L,
)

/** 서비스 ↔ UI 사이의 상태 공유 (같은 프로세스). */
object StatusRepo {
    val status = MutableStateFlow(Status())

    /** 로드된 단속카메라 수 (null = 아직 안 읽음) */
    val cameraCount = MutableStateFlow<Int?>(null)

    // 시뮬레이션 조작값 (UI가 쓰고 서비스가 읽는다)
    val simSpeedKmh = MutableStateFlow(80)
    val simLimitKmh = MutableStateFlow(80)
    val simFast = MutableStateFlow(false)
}
