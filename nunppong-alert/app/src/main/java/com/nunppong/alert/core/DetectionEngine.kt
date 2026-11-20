package com.nunppong.alert.core

/**
 * 단속카메라 판단 로직 (순수 Kotlin, 1초마다 GPS fix 입력).
 *
 * 1. 주변 격자 9칸 후보 조회
 * 2. 도로 선이 있는 카메라: 내가 그 선 위를 카메라 쪽으로 달릴 때 인정 (남은 거리는 도로를 따라 잼).
 *    선과 안 맞으면 100m 안 바로 정면일 때만 인정 (안전망).
 *    도로 선이 없는 카메라: 내 진행방향 ±30° 이내의 앞쪽 카메라만 (예전 방식)
 * 3. 직전보다 거리가 줄어든 카메라만 인정, 가장 가까운 하나로 단계 결정
 * 4. 속도 10km/h 미만이면 직전 결과 유지
 * 5. 단계 변경은 2회 연속 같은 결과일 때만
 * 6. 50m 안에 들어왔다가 거리가 다시 늘면 통과 처리, 30초 무시
 * 7. 속도 = GPS speed × 3.6, 3초 이동평균
 */
class DetectionEngine(
    private val source: CameraSource,
    /** 내가 지나며 배운 '이 카메라는 이 방향에선 반대 차선' 이면 true -> 약한 경로처럼 아주 가까울 때만 알린다 */
    private val learnedOpposite: (Camera, Double) -> Boolean = { _, _ -> false },
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
        /** 도로 선 맞추기: 선에서 옆으로 이 이내(넓은 도로 바깥 차선 + GPS 오차), 진행방향은 선 방향과 이 각도 이내 */
        const val PATH_LATERAL_M = 30.0
        /** 이미 알리는 중인 카메라의 범위 경계 여유 배율 */
        const val STICKY_RANGE = 1.15
        /** 방향이 확실하지 않은 쪽 접근 경로의 알림 거리: 속도 x 4초 (50~150m) */
        const val WEAK_SEC = 4.0
        /** 카메라 옆 위치를 재는 구간(카메라까지 직선거리) */
        const val SIDE_FROM_M = 70.0
        const val SIDE_TO_M = 12.0
        const val SIDE_MIN_SAMPLES = 3
        const val WEAK_MIN_M = 50.0
        const val WEAK_MAX_M = 150.0
        /** GPS 정확도가 나쁠 때 늘려 주는 옆 허용 오차의 상한 */
        const val PATH_LATERAL_MAX_M = 60.0
        /** 카메라 전에 내가 꺾을 수 있는 갈림길이 이보다 많이 남았으면 아직 알리지 않는다 (중간에 꺾으면 틀린 알림이 되므로).
         * 실제 도로 1,560km 가상 주행으로 고른 값: 0~99 중 1이 틀린 알림 -20%, 정답 알림 -2%. */
        const val MAX_JUNCTIONS = 1
        /** 카메라까지 이 거리(또는 약 8초 거리) 안이면 갈림길이 남아 있어도 알린다 */
        const val JUNCTION_FREE_M = 150.0
        const val JUNCTION_FREE_SEC = 8.0
        const val PATH_ANGLE = 45.0
        /** 안전망: 도로 선과 안 맞아도 이 거리 안에서 바로 정면(각도·옆거리 이내)이면 알린다.
         * 실제 도로 1,560km 가상 주행: 300m->100m 로 줄이면 틀린 알림 -59건, 못 울리는 카메라 +5개. */
        const val NET_M = 100.0
        const val NET_ANGLE = 20.0
        const val NET_LATERAL_M = 20.0
        const val PASS_RADIUS_M = 50.0
        /** 통과 판정 여유: GPS 흔들림(몇 m)으로 거리가 잠깐 늘어도 통과로 보지 않는다 */
        const val PASS_MARGIN_M = 5.0
        const val IGNORE_MS = 30_000L
        const val SPEED_WINDOW_MS = 3_000L
        /** 켜져 있던 알림을 끌 때 연속으로 필요한 '없음' 판정 횟수 (위치·방향이 순간 흔들려 깜빡이는 것 방지) */
        const val HIDE_FIXES = 2
        /** 이만큼 계속 멈춰 있으면 알림을 끈다 (신호 대기는 버티고, 주차·도착 후에는 안 남게) */
        const val STILL_HIDE_MS = 90_000L
        /** 멈춘 지 이 시간이 지났고 카메라가 이 거리보다 멀면 알림을 끈다 (막히는 길에서 멀리서부터 계속 울리지 않게). 카메라 바로 앞 신호 대기는 유지. */
        const val STILL_FAR_HIDE_MS = 8_000L
        const val STILL_FAR_M = 200.0
    }

    private val samples = ArrayDeque<Pair<Long, Double>>()
    private var lastDist = HashMap<Int, Double>()
    private val inside50 = HashSet<Int>()
    private val ignoreUntil = HashMap<Int, Long>()

    private var confirmed = Stage.HIDDEN
    private var pending = Stage.HIDDEN
    private var pendingCount = 0
    private var heading = -1.0
    private var bestRange = 1000.0
    private var bestRule = ""
    /** 직전 판단에서 알림 후보였던 카메라 */
    private var prevAccepted = HashSet<Int>()
    private var last = EngineOutput(Stage.HIDDEN, false, null, 0.0)
    private var passed: PassEvent? = null
    private var stillSince = -1L
    /** 카메라별로 접근하며 잰 '카메라가 내 오른쪽(+)/왼쪽(-) 몇 m' 표본 */
    private val sideSamples = HashMap<Int, ArrayList<Double>>()
    private var weakAll = false

    /** matchPath 직전 호출에서 '내가 선 위에 있지만 그 선의 알림 범위 밖'이었는지 */
    private var onPathButFar = false
    /** 마지막 matchPath 에서 알림 범위·교차로 조건과 상관없이 내가 선 위에 있을 때의 카메라까지 남은 거리 */
    private var lastAnyRemain: Double? = null

    /**
     * 카메라의 도로 선들 중 내가 올라타 있고 알림 범위 안인 선의 남은 거리(가장 짧은 것), 없으면 null.
     * 선 위에는 있지만 범위 밖이면(예: 아직 1km 이상 남음, 반대 차선 약한 경로) onPathButFar 를 켠다.
     */
    private fun matchPath(c: Camera, lat: Double, lon: Double, range: Double, speedMps: Double, accuracyM: Double, sticky: Boolean): Double? {
        // 이미 알림이 켜진 카메라는 경계값(범위·약한 경로 거리·교차로 개수)에서 위치가 흔들려도 꺼지지 않게 여유를 준다
        val stick = if (sticky) STICKY_RANGE else 1.0
        // GPS가 거칠면(고층 건물 사이 등) 도로 선 맞추기 허용 오차를 넓혀 알림이 끊기지 않게 한다
        val lateralTol = maxOf(PATH_LATERAL_M, 2.0 * accuracyM).coerceAtMost(PATH_LATERAL_MAX_M)
        val angleTol = PATH_ANGLE + minOf(20.0, accuracyM)
        val junctionFree = maxOf(JUNCTION_FREE_M, speedMps * JUNCTION_FREE_SEC)
        onPathButFar = false
        lastAnyRemain = null
        var best: Double? = null
        for (p in c.paths) {
            val r = p.matchRemaining(lat, lon, heading, lateralTol, angleTol) ?: continue
            if (lastAnyRemain == null || r < lastAnyRemain!!) lastAnyRemain = r
            // 방향이 확실하지 않은 쪽(약한 경로)은 약 4초 거리 안에서만 알린다 (안전장치: 추정이 틀려도 끝까지 조용하진 않다)
            val pRange = if (weakAll || p.rangeM < Double.MAX_VALUE) (speedMps * WEAK_SEC).coerceIn(WEAK_MIN_M, WEAK_MAX_M) else p.rangeM
            if (r > pRange * stick || r > range * stick) { onPathButFar = true; continue }
            if (!sticky && r > junctionFree && p.junctionsAhead(r) > MAX_JUNCTIONS) { onPathButFar = true; continue }
            if (best == null || r < best) best = r
        }
        return best
    }

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
            if (stillSince < 0) stillSince = now
            val stillMs = now - stillSince
            val farAlert = last.distanceM == null || last.distanceM!! > STILL_FAR_M
            if (last.stage != Stage.HIDDEN && (stillMs >= STILL_HIDE_MS || (stillMs >= STILL_FAR_HIDE_MS && farAlert))) {
                // 오래 멈춤: 단계·거리 기록을 모두 비운다. 다시 달리면 처음부터 판단한다.
                confirmed = Stage.HIDDEN
                pending = Stage.HIDDEN
                pendingCount = 0
                lastDist = HashMap()
                inside50.clear()
                last = EngineOutput(Stage.HIDDEN, false, null, avg)
            } else {
                last = last.copy(speedKmh = avg)
            }
            return last
        }
        stillSince = -1L
        if (fix.hasBearing) heading = fix.bearing.toDouble()
        if (heading < 0) {
            last = last.copy(speedKmh = avg)
            return last
        }

        ignoreUntil.entries.removeAll { it.value <= now }

        val newDist = HashMap<Int, Double>()
        val accepted = HashSet<Int>()
        val range = AlertStyle.rangeFor(avg / 3.6)
        var best: Camera? = null
        var bestD = Double.MAX_VALUE

        for (c in source.near(fix.lat, fix.lon)) {
            if (ignoreUntil.containsKey(c.id)) continue
            if (blocked(c, heading)) continue
            val d = Geo.distanceM(fix.lat, fix.lon, c.lat, c.lon)
            val prev = lastDist[c.id]
            newDist[c.id] = d
            if (d in SIDE_TO_M..SIDE_FROM_M && prev != null && d < prev) {
                var dif = Geo.bearingDeg(fix.lat, fix.lon, c.lat, c.lon) - heading
                while (dif > 180) dif -= 360
                while (dif < -180) dif += 360
                val l = sideSamples.getOrPut(c.id) { ArrayList() }
                if (l.size < 20) l.add(d * Math.sin(Math.toRadians(dif)))
            }
            weakAll = c.bearing < 0 && learnedOpposite(c, heading)
            // 도로 선 위에서 카메라까지 아직 한참(100m 초과) 남았으면, 직선거리로 50m 안을 스쳐도
            // '통과'로 보지 않는다 (회전 램프·굽은 길이 카메라 옆을 지나갔다가 돌아오는 경우).
            val roadRemain = if (c.paths.isNotEmpty()) matchPath(c, fix.lat, fix.lon, range, avg / 3.6, fix.accuracyM.toDouble(), prevAccepted.contains(c.id)) else null
            val anyRemain = if (c.paths.isNotEmpty()) lastAnyRemain else null
            val stillApproaching = anyRemain != null && anyRemain > 2 * PASS_RADIUS_M
            if (stillApproaching) inside50.remove(c.id)
            val wasInside = inside50.contains(c.id)

            // 6) 통과 처리: 50m 안에 들어온 뒤 거리가 다시 늘면 30초 무시
            if (wasInside && prev != null && d > prev + PASS_MARGIN_M) {
                inside50.remove(c.id)
                ignoreUntil[c.id] = now + IGNORE_MS
                val ss = sideSamples.remove(c.id)
                passed = PassEvent(c, heading, if (ss != null && ss.size >= SIDE_MIN_SAMPLES) ss.average() else null)
                continue
            }
            if (d > range * (if (prevAccepted.contains(c.id)) STICKY_RANGE else 1.0)) continue

            // 2a) 도로 선이 있는 카메라: 내가 그 선 위를 카메라 쪽으로 달리고 있으면 인정.
            //     남은 거리는 도로를 따라 잰다 (커브에서도 정확).
            var eff = d
            var rule = if (wasInside) "50m안" else "직선(도로 정보 없음)"
            val onRoad = if (!wasInside) roadRemain else null
            if (onRoad == null && !wasInside && onPathButFar) continue   // 내 도로 위지만 아직 멀거나 반대 차선 약한 경로
            if (onRoad != null) {
                eff = onRoad
                rule = "도로선"
            } else {
                // 3) 직전보다 가까워진 카메라만
                if (prev == null || d >= prev) continue

                // 2) 진행방향 필터 (50m 안에서는 방위가 흔들리므로, 이미 접근해 들어온 카메라만 생략)
                if (!wasInside) {
                    val toCam = Geo.bearingDeg(fix.lat, fix.lon, c.lat, c.lon)
                    val diff = Geo.angleDiff(heading, toCam)
                    val lateral = d * Math.sin(Math.toRadians(diff))
                    if (c.paths.isNotEmpty()) {
                        // 2b) 안전망: 도로 선과 안 맞아도(도로 정보가 틀렸을 수 있음) 바로 정면 가까이에 있으면 알린다.
                        if (d > NET_M || diff > NET_ANGLE || lateral > NET_LATERAL_M) continue
                        rule = "정면(안전망)"
                    } else {
                        // 2c) 도로 정보가 없는 카메라: 예전 방식 (진행선 기준)
                        if (diff > MAX_ANGLE) continue
                        if (lateral > LATERAL_BASE_M + LATERAL_PER_M * d) continue
                    }
                    if (c.bearing >= 0 && Geo.angleDiff(heading, c.bearing.toDouble()) > CAMERA_BEARING_TOL) continue
                }
            }
            accepted.add(c.id)
            // 필터를 통과한(=알림 후보가 된) 카메라만 '50m 안'으로 등록한다.
            // 걸러진 옆 도로 카메라가 다음 초에 필터 없이 통과하던 버그를 막는다.
            if (d < PASS_RADIUS_M && !stillApproaching) inside50.add(c.id)
            if (eff < bestD) {
                bestD = eff
                best = c
                bestRange = range
                bestRule = rule
            }
        }
        lastDist = newDist
        prevAccepted = accepted
        inside50.retainAll(newDist.keys)   // 격자를 벗어난 카메라는 목록에서 정리

        val raw = if (best != null) AlertStyle.stageForDistance(bestD, bestRange) else Stage.HIDDEN

        // 5) 2회 연속 같은 결과일 때만 단계 변경
        if (raw == confirmed) {
            pending = raw
            pendingCount = 0
        } else if (raw == pending) {
            pendingCount++
            if (pendingCount >= (if (raw == Stage.HIDDEN) HIDE_FIXES else 2)) {
                confirmed = raw
                pendingCount = 0
            }
        } else {
            pending = raw
            pendingCount = 1
        }

        val over = confirmed == Stage.S3 && best != null && best.limit > 0 && avg > best.limit
        last = EngineOutput(confirmed, over, if (best != null) bestD else null, avg, best?.limit ?: 0, best?.lat, best?.lon, if (best != null) bestRule else "")
        return last
    }
}
