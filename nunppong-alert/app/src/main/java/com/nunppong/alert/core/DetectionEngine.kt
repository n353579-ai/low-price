package com.nunppong.alert.core

/**
 * 단속카메라 판단 로직 (순수 Kotlin, 1초마다 GPS fix 입력).
 *
 * 1. 주변 격자 9칸 후보 조회
 * 2. 내 진행방향(bearing) ±30° 이내의 앞쪽 카메라만 남김 (카메라 방향각이 있으면 그것도 ±30°)
 * 3. 직전보다 거리가 줄어든 카메라만 인정, 가장 가까운 하나로 단계 결정
 * 4. 속도 10km/h 미만이면 직전 결과 유지
 * 5. 단계 변경은 2회 연속 같은 결과일 때만
 * 6. 50m 안에 들어왔다가 거리가 다시 늘면 통과 처리, 30초 무시
 * 7. 속도 = GPS speed × 3.6, 3초 이동평균
 */
class DetectionEngine(
    private val source: CameraSource,
    /** 사용자가 👎로 끈 (카메라, 진행방향) 조합이면 true */
    private val blocked: (Camera, Double) -> Boolean = { _, _ -> false },
) {
    companion object {
        const val MIN_SPEED_KMH = 10.0
        const val MAX_ANGLE = 30.0
        /** 카메라 방향각(도로에서 계산)과 내 진행방향이 이 이내여야 한다. 급커브를 위해 조금 넓게. */
        const val CAMERA_BEARING_TOL = 45.0
        /** 내 진행선에서 옆으로 허용하는 거리: 25m + 거리의 7% (1km 앞이면 약 95m). 도로 정보가 없어서 진행선으로 대신한다. */
        const val LATERAL_BASE_M = 25.0
        const val LATERAL_PER_M = 0.07
        const val PASS_RADIUS_M = 50.0
        /** 통과 판정 여유: GPS 흔들림(몇 m)으로 거리가 잠깐 늘어도 통과로 보지 않는다 */
        const val PASS_MARGIN_M = 5.0
        const val IGNORE_MS = 30_000L
        const val SPEED_WINDOW_MS = 3_000L
    }

    private val samples = ArrayDeque<Pair<Long, Double>>()
    private var lastDist = HashMap<Int, Double>()
    private val inside50 = HashSet<Int>()
    private val ignoreUntil = HashMap<Int, Long>()

    private var confirmed = Stage.HIDDEN
    private var pending = Stage.HIDDEN
    private var pendingCount = 0
    private var heading = -1.0
    private var last = EngineOutput(Stage.HIDDEN, false, null, 0.0)
    private var passed: PassEvent? = null

    /** 방금 통과한 카메라 (한 번 꺼내면 비워진다). 피드백 질문용. */
    fun takePassed(): PassEvent? = passed.also { passed = null }

    fun update(fix: Fix): EngineOutput {
        val now = fix.timeMs

        // 7) 3초 이동평균 속도
        samples.addLast(now to fix.speedMps * 3.6)
        while (samples.isNotEmpty() && samples.first().first < now - SPEED_WINDOW_MS) samples.removeFirst()
        val avg = samples.map { it.second }.average()

        // 4) 저속이면 직전 단계 유지
        // (평균뿐 아니라 현재 속도도 낮으면 정차로 본다: 신호 대기 중 단계가 꺼지지 않게)
        if (avg < MIN_SPEED_KMH || fix.speedMps * 3.6 < MIN_SPEED_KMH) {
            last = last.copy(speedKmh = avg)
            return last
        }
        if (fix.hasBearing) heading = fix.bearing.toDouble()
        if (heading < 0) {
            last = last.copy(speedKmh = avg)
            return last
        }

        ignoreUntil.entries.removeAll { it.value <= now }

        val newDist = HashMap<Int, Double>()
        var best: Camera? = null
        var bestD = Double.MAX_VALUE

        for (c in source.near(fix.lat, fix.lon)) {
            if (ignoreUntil.containsKey(c.id)) continue
            if (blocked(c, heading)) continue
            val d = Geo.distanceM(fix.lat, fix.lon, c.lat, c.lon)
            val prev = lastDist[c.id]
            newDist[c.id] = d
            val wasInside = inside50.contains(c.id)

            // 6) 통과 처리: 50m 안에 들어온 뒤 거리가 다시 늘면 30초 무시
            if (inside50.contains(c.id) && prev != null && d > prev + PASS_MARGIN_M) {
                inside50.remove(c.id)
                ignoreUntil[c.id] = now + IGNORE_MS
                passed = PassEvent(c, heading)
                continue
            }
            if (d < PASS_RADIUS_M) inside50.add(c.id)
            if (d > 1000.0) continue

            // 3) 직전보다 가까워진 카메라만
            if (prev == null || d >= prev) continue

            // 2) 진행방향 필터 (50m 안에서는 방위가 흔들리므로, 이미 접근해 들어온 카메라만 생략)
            if (!wasInside) {
                val toCam = Geo.bearingDeg(fix.lat, fix.lon, c.lat, c.lon)
                val diff = Geo.angleDiff(heading, toCam)
                if (diff > MAX_ANGLE) continue
                if (d * Math.sin(Math.toRadians(diff)) > LATERAL_BASE_M + LATERAL_PER_M * d) continue
                if (c.bearing >= 0 && Geo.angleDiff(heading, c.bearing.toDouble()) > CAMERA_BEARING_TOL) continue
            }
            if (d < bestD) {
                bestD = d
                best = c
            }
        }
        lastDist = newDist
        inside50.retainAll(newDist.keys)   // 격자를 벗어난 카메라는 목록에서 정리

        val raw = if (best != null) AlertStyle.stageForDistance(bestD) else Stage.HIDDEN

        // 5) 2회 연속 같은 결과일 때만 단계 변경
        if (raw == confirmed) {
            pending = raw
            pendingCount = 0
        } else if (raw == pending) {
            pendingCount++
            if (pendingCount >= 2) {
                confirmed = raw
                pendingCount = 0
            }
        } else {
            pending = raw
            pendingCount = 1
        }

        val over = confirmed == Stage.S3 && best != null && best.limit > 0 && avg > best.limit
        last = EngineOutput(confirmed, over, if (best != null) bestD else null, avg, best?.limit ?: 0)
        return last
    }
}
