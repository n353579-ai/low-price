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
    /** 이 카메라에 다가가는 도로 선들 (없으면 도로 정보 없음 -> 예전 방식으로 판단) */
    val paths: List<CamPath> = emptyList(),
)

/**
 * 카메라 앞 도로 선 (접근 순서의 점들, 마지막 점이 카메라가 붙은 도로 위 위치).
 * 지도 대신 쓰는 최소한의 도로 정보: '내가 이 선 위를 카메라 쪽으로 달리고 있나' 만 판단한다.
 */
class CamPath(val lat: DoubleArray, val lon: DoubleArray) {
    /** 각 점에서 카메라(마지막 점)까지 도로를 따라 남은 거리(m) */
    private val remain = DoubleArray(lat.size)

    init {
        for (i in lat.size - 2 downTo 0) {
            remain[i] = remain[i + 1] + Geo.distanceM(lat[i], lon[i], lat[i + 1], lon[i + 1])
        }
    }

    val lengthM: Double get() = if (remain.isEmpty()) 0.0 else remain[0]

    /**
     * (lat, lon)에서 heading 방향으로 달리는 차가 이 선 위에 있으면 카메라까지 도로를 따라 남은 거리(m), 아니면 null.
     * - 선에서 옆으로 lateralTolM 이내
     * - 진행방향이 그 지점의 선 방향과 angleTolDeg 이내 (반대로 달리면 불일치)
     * - 이미 카메라 위치를 지나쳤으면 불일치
     */
    fun matchRemaining(pLat: Double, pLon: Double, heading: Double, lateralTolM: Double, angleTolDeg: Double): Double? {
        if (lat.size < 2) return null
        val mLat = 111194.9
        val mLon = mLat * Math.cos(Math.toRadians(pLat))
        var best: Double? = null
        var bestLateral = Double.MAX_VALUE
        for (i in 0 until lat.size - 1) {
            val ax = (lon[i] - pLon) * mLon
            val ay = (lat[i] - pLat) * mLat
            val bx = (lon[i + 1] - pLon) * mLon
            val by = (lat[i + 1] - pLat) * mLat
            val dx = bx - ax
            val dy = by - ay
            val ll = dx * dx + dy * dy
            if (ll < 1e-6) continue
            val tRaw = -(ax * dx + ay * dy) / ll
            // 마지막 구간의 끝(카메라 위치)을 넘어섰으면 이미 지나친 것
            if (i == lat.size - 2 && tRaw > 1.0) continue
            val t = tRaw.coerceIn(0.0, 1.0)
            val px = ax + t * dx
            val py = ay + t * dy
            val lateral = Math.sqrt(px * px + py * py)
            if (lateral > lateralTolM || lateral >= bestLateral) continue
            val tangent = (Math.toDegrees(Math.atan2(dx, dy)) + 360.0) % 360.0
            if (Geo.angleDiff(heading, tangent) > angleTolDeg) continue
            bestLateral = lateral
            best = remain[i + 1] + (1.0 - t) * Math.sqrt(ll)
        }
        return best
    }
}

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

/** 카메라를 방금 지나간 사건 (heading = 지나갈 때 진행방향) */
data class PassEvent(val camera: Camera, val heading: Double)
