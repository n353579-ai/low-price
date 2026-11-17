package com.nunppong.alert.core

enum class Stage { HIDDEN, S1, S2, S3 }

enum class Mode { IDLE, SIM, REAL }

/** 서비스를 시작한 이유 */
enum class Reason { NONE, MANUAL, BLUETOOTH, VEHICLE }

data class Camera(
    val id: Int,
    val lat: Double,
    val lon: Double,
    /** 제한속도 km/h, 모르면 0 */
    val limit: Int,
    /** 단속 진행방향(0~359), 모르면 -1 */
    val bearing: Int = -1,
)

data class Fix(
    val lat: Double,
    val lon: Double,
    val speedMps: Float,
    val bearing: Float,
    val hasBearing: Boolean,
    val timeMs: Long,
)

data class EngineOutput(
    val stage: Stage,
    val overspeed: Boolean,
    val distanceM: Double?,
    val speedKmh: Double,
    /** 가장 가까운 카메라의 제한속도 (모르면 0) */
    val limit: Int = 0,
)
