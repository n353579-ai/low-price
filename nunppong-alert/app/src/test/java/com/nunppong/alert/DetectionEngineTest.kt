package com.nunppong.alert

import com.nunppong.alert.core.*
import org.junit.Assert.*
import org.junit.Test

class DetectionEngineTest {
    private val mPerDeg = 111194.9
    private val camLat = 37.5
    private val camLon = 127.0

    private fun grid(vararg cams: Camera): CameraGrid {
        val lines = ArrayList<String>()
        cams.groupBy { Pair(Math.floor(it.lat * 100).toInt(), Math.floor(it.lon * 100).toInt()) }.forEach { (k, v) ->
            lines += "G ${k.first} ${k.second}"
            v.forEach { lines += "C ${it.lat} ${it.lon} ${it.limit} ${it.bearing}" }
        }
        return CameraGrid.parse(lines.asSequence())
    }

    private fun fix(lat: Double, lon: Double, i: Int, mps: Float = 20f, bearing: Float = 0f) =
        Fix(lat, lon, mps, bearing, true, i * 1000L)

    /** 북쪽으로 달리며 카메라에 접근. i번째 fix 시점의 남은 거리 = 1500 - 20*i */
    private fun drive(engine: DetectionEngine, mps: Float = 20f, upTo: Int): List<EngineOutput> {
        val start = camLat - 1500 / mPerDeg
        return (0..upTo).map { i -> engine.update(fix(start + i * 20 / mPerDeg, camLon, i, mps)) }
    }

    @Test fun approachingStagesAndPass() {
        val e = DetectionEngine(grid(Camera(0, camLat, camLon, 100)))
        val out = drive(e, upTo = 95)
        assertEquals(Stage.HIDDEN, out[10].stage)        // 1300m
        assertEquals(Stage.S1, out[35].stage)            // 800m
        assertEquals(Stage.S2, out[55].stage)            // 400m
        assertEquals(Stage.S3, out[65].stage)            // 200m
        assertFalse(out[65].overspeed)                   // 72km/h <= 80
        assertEquals(Stage.HIDDEN, out[95].stage)        // 통과 후
    }

    @Test fun overspeedAtStage3() {
        val e = DetectionEngine(grid(Camera(0, camLat, camLon, 80)))
        val out = drive(e, mps = 25f, upTo = 65)          // 90km/h
        assertEquals(Stage.S3, out[65].stage)
        assertTrue(out[65].overspeed)
    }

    @Test fun cameraBehindIsIgnored() {
        val e = DetectionEngine(grid(Camera(0, camLat, camLon, 80)))
        val start = camLat + 100 / mPerDeg
        for (i in 0..60) {
            val o = e.update(fix(start + i * 20 / mPerDeg, camLon, i))
            assertEquals(Stage.HIDDEN, o.stage)
        }
    }

    @Test fun oppositeDirectionCameraIgnoredWhenBearingKnown() {
        val e = DetectionEngine(grid(Camera(0, camLat, camLon, 80, bearing = 180)))
        val out = drive(e, upTo = 65)
        assertTrue(out.all { it.stage == Stage.HIDDEN })
    }

    @Test fun sideCameraIgnored() {
        // 진행방향 북쪽, 카메라는 동쪽 600m 옆 -> ±30° 밖
        val e = DetectionEngine(grid(Camera(0, camLat, camLon + 600 / (mPerDeg * Math.cos(Math.toRadians(camLat))), 80)))
        val out = drive(e, upTo = 75)
        assertTrue(out.all { it.stage == Stage.HIDDEN })
    }

    @Test fun lowSpeedKeepsStage() {
        val e = DetectionEngine(grid(Camera(0, camLat, camLon, 80)))
        val out = drive(e, upTo = 55)
        assertEquals(Stage.S2, out[55].stage)
        val start = camLat - 1500 / mPerDeg
        var o = out[55]
        for (j in 1..8) o = e.update(fix(start + 55 * 20 / mPerDeg, camLon, 55 + j, mps = 0f))
        assertEquals(Stage.S2, o.stage)
    }

    @Test fun stageNeedsTwoConsecutiveResults() {
        val e = DetectionEngine(grid(Camera(0, camLat, camLon, 80)))
        val out = drive(e, upTo = 60)
        // 거리 500m 이하(i>=50)가 처음 나온 직후에는 아직 S1이어야 한다
        assertEquals(Stage.S1, out[50].stage)
        assertEquals(Stage.S2, out[52].stage)
    }

    @Test fun gridParseAndNear() {
        val g = CameraGrid.parse(sequenceOf("# h", "G 3750 12700", "C 37.5 127.0 80 -1", "G 3900 12900", "C 39.0 129.0 0 90"))
        assertEquals(2, g.count)
        assertEquals(1, g.near(37.505, 127.005).size)
        assertEquals(1, g.near(37.495, 126.995).size)   // 이웃 칸
        assertEquals(0, g.near(36.0, 127.0).size)
    }

    @Test fun passEventEmittedOnce() {
        val e = DetectionEngine(grid(Camera(0, camLat, camLon, 80)))
        var count = 0
        val start = camLat - 1500 / mPerDeg
        for (i in 0..95) {
            e.update(fix(start + i * 20 / mPerDeg, camLon, i))
            if (e.takePassed() != null) count++
        }
        assertEquals(1, count)
    }

    @Test fun blockedCameraIsIgnoredOnlyInThatHeading() {
        val cam = Camera(0, camLat, camLon, 80)
        val bl = FeedbackBlocklist()
        bl.block(camLat, camLon, 0.0)                      // 북쪽 진행에서 끔
        val north = DetectionEngine(grid(cam)) { c, h -> bl.isBlocked(c, h) }
        assertTrue(drive(north, upTo = 65).all { it.stage == Stage.HIDDEN })
        // 남쪽으로 달릴 때는 여전히 경고
        val south = DetectionEngine(grid(cam)) { c, h -> bl.isBlocked(c, h) }
        val start = camLat + 1500 / mPerDeg
        var o = EngineOutput(Stage.HIDDEN, false, null, 0.0)
        for (i in 0..65) o = south.update(fix(start - i * 20 / mPerDeg, camLon, i, bearing = 180f))
        assertEquals(Stage.S3, o.stage)
    }

    @Test fun feedbackKeyUsesHeadingSector() {
        assertEquals(FeedbackBlocklist.sector(0.0), FeedbackBlocklist.sector(20.0))
        assertEquals(FeedbackBlocklist.sector(0.0), FeedbackBlocklist.sector(350.0))
        assertNotEquals(FeedbackBlocklist.sector(0.0), FeedbackBlocklist.sector(180.0))
        var saved = emptySet<String>()
        val bl = FeedbackBlocklist(emptyList()) { saved = it }
        bl.block(37.5, 127.0, 90.0)
        assertEquals(1, saved.size)
        assertEquals(1, FeedbackBlocklist(saved).size)
        bl.clear()
        assertEquals(0, saved.size)
    }

    @Test fun characterShowsOnlyOnStageChange() {
        assertEquals(1, CharacterStyle.fingers(Stage.S1))
        assertEquals(5, CharacterStyle.fingers(Stage.S2))
        assertEquals(3, CharacterStyle.fingers(Stage.S3))
        assertTrue(CharacterStyle.shouldShow(Stage.HIDDEN, Stage.S1))
        assertTrue(CharacterStyle.shouldShow(Stage.S1, Stage.S2))
        assertFalse(CharacterStyle.shouldShow(Stage.S2, Stage.S2))
        assertFalse(CharacterStyle.shouldShow(Stage.S3, Stage.HIDDEN))
    }

    @Test fun characterFadesInAndOutWithoutBlinking() {
        assertEquals(0f, CharacterStyle.alpha(0), 0.001f)
        assertEquals(1f, CharacterStyle.alpha(1000), 0.001f)
        assertEquals(0f, CharacterStyle.alpha(CharacterStyle.SHOW_MS), 0.001f)
        // 한 번 올라갔다가 내려오기만 한다 (깜빡임 없음)
        var peaked = false
        var prev = 0f
        for (t in 0L until CharacterStyle.SHOW_MS step 20) {
            val a = CharacterStyle.alpha(t)
            if (a < prev) peaked = true
            if (peaked) assertTrue(a <= prev + 1e-6f)
            prev = a
        }
    }

    @Test fun characterPopsOutAndTucksBackIn() {
        assertEquals(0f, CharacterStyle.scale(0), 0.001f)
        assertTrue(CharacterStyle.scale(CharacterStyle.POP_IN_MS) > 1f)       // 살짝 튀어나왔다가
        assertEquals(1f, CharacterStyle.scale(1000), 0.001f)                  // 자리 잡고
        assertTrue(CharacterStyle.scale(CharacterStyle.SHOW_MS - 50) < 0.3f)  // 쏙 들어간다
        assertEquals(0f, CharacterStyle.scale(CharacterStyle.SHOW_MS), 0.001f)
    }

    @Test fun gpsJitterNear30mDoesNotEndAlertEarly() {
        val e = DetectionEngine(grid(Camera(0, camLat, camLon, 80)))
        val start = camLat - 1500 / mPerDeg
        // 남은 거리 40m 지점까지 접근 후, 한 번 3m 물러난 것처럼 흔들린 fix, 이어서 계속 접근
        var o = EngineOutput(Stage.HIDDEN, false, null, 0.0)
        for (i in 0..73) o = e.update(fix(start + i * 20 / mPerDeg, camLon, i))
        o = e.update(fix(start + (73 * 20 - 3) / mPerDeg, camLon, 74))   // 약 43m, 3m 되돌아감(흔들림)
        o = e.update(fix(start + 75 * 20 / mPerDeg, camLon, 75))
        assertEquals(Stage.S3, o.stage)
        assertNull(e.takePassed())
    }

    @Test fun spreadGrowsWithProximityAndStaysBounded() {
        assertEquals(0f, AlertStyle.proximity(null), 0.001f)
        assertEquals(0f, AlertStyle.proximity(1200.0), 0.001f)
        assertTrue(AlertStyle.proximity(300.0) > AlertStyle.proximity(800.0))
        assertEquals(1f, AlertStyle.proximity(0.0), 0.001f)
        val screen = 1080f
        val band = 30f
        var prev = 0f
        for (d in 1000 downTo 0 step 50) {
            val g = AlertStyle.glowPx(band, screen, AlertStyle.proximity(d.toDouble()), 1.5f)
            assertTrue(g >= prev - 0.001f)                       // 가까울수록 넓어지기만 한다
            assertTrue(g <= screen * AlertStyle.MAX_GLOW_FRACTION + 0.001f)  // 가운데 지도 보호
            prev = g
        }
        // 번짐 끔(spread=0)이면 예전과 같은 고정 폭
        assertEquals(band * 1.6f, AlertStyle.glowPx(band, screen, 1f, 0f), 0.001f)
        assertTrue(AlertStyle.glowPeakAlpha(1f, 1.5f) <= 0.85f)
    }

    @Test fun cameraOffToTheSideOfMyPathIsIgnored() {
        // 북쪽으로 달리는데 카메라는 진행선에서 동쪽으로 150m 떨어진 곳 (800m 앞) -> 예전엔 각도(약 11°)만 봐서 울렸다
        val e = DetectionEngine(grid(Camera(0, camLat, camLon + 150 / (mPerDeg * Math.cos(Math.toRadians(camLat))), 80)))
        val out = drive(e, upTo = 75)
        assertTrue(out.all { it.stage == Stage.HIDDEN })
    }

    @Test fun cameraNearMyPathStillDetected() {
        // 진행선에서 10m 옆(같은 도로의 다른 차선 정도)은 그대로 감지
        val e = DetectionEngine(grid(Camera(0, camLat, camLon + 10 / (mPerDeg * Math.cos(Math.toRadians(camLat))), 80)))
        val out = drive(e, upTo = 65)
        assertEquals(Stage.S3, out[65].stage)
    }

    @Test fun calmLimitOnlyWhenKnownAndObeying() {
        assertEquals(80, AlertStyle.calmLimit(true, 80, 70.0))
        assertEquals(80, AlertStyle.calmLimit(true, 80, 80.0))
        assertEquals(0, AlertStyle.calmLimit(true, 80, 81.0))   // 넘으면 깜빡임으로
        assertEquals(0, AlertStyle.calmLimit(true, 0, 50.0))    // 제한속도 모르면 항상 경고
        assertEquals(0, AlertStyle.calmLimit(false, 80, 50.0))  // 옵션 끄면 예전처럼
    }

    @Test fun cameraBearingBlocksOppositeLaneButAllowsCurves() {
        val mPerDeg = 111320.0
        val camLat = 37.5; val camLon = 127.0
        fun run(camBearing: Int, myHeading: Float): Stage {
            val e = DetectionEngine(grid(Camera(0, camLat, camLon, 80, bearing = camBearing)))
            val start = camLat + 900 / mPerDeg
            var o = e.update(fix(start, camLon, 0, bearing = myHeading))
            for (i in 1..30) o = e.update(fix(start - i * 20 / mPerDeg, camLon, i, bearing = myHeading))
            return o.stage
        }
        assertEquals(Stage.HIDDEN, run(0, 180f))       // 반대 방향 단속 카메라: 울리지 않는다
        assertTrue(run(180, 180f) != Stage.HIDDEN)      // 같은 방향: 울린다
        assertTrue(run(180 + 40, 180f) != Stage.HIDDEN) // 커브로 40° 어긋나도 울린다
        assertTrue(run(-1, 180f) != Stage.HIDDEN)       // 방향 모르면 예전처럼 울린다
    }

    // ---------- 도로 선(접근 경로) 맞추기 ----------

    private val lonPerM get() = 1.0 / (mPerDeg * Math.cos(Math.toRadians(camLat)))

    /** (북쪽 m, 동쪽 m) 로 적은 점들을 카메라 기준 좌표로 바꿔 P 줄을 만든다. 마지막 점이 카메라 위치. */
    private fun pathLine(vararg ne: Pair<Double, Double>): String {
        val q = ne.map { (n, e) ->
            Pair(Math.round((camLat + n / mPerDeg) * 1e5), Math.round((camLon + e * lonPerM) * 1e5))
        }
        val sb = StringBuilder("P ${q[0].first} ${q[0].second}")
        for (k in 1 until q.size) sb.append(" ${q[k].first - q[k - 1].first} ${q[k].second - q[k - 1].second}")
        return sb.toString()
    }

    private fun gridWithPaths(bearing: Int, vararg paths: String): CameraGrid {
        val lines = arrayListOf(
            "G ${Math.floor(camLat * 100).toInt()} ${Math.floor(camLon * 100).toInt()}",
            "C $camLat $camLon 100 $bearing",
        )
        lines += paths
        return CameraGrid.parse(lines.asSequence())
    }

    private fun at(n: Double, e: Double, i: Int, bearing: Float) =
        fix(camLat + n / mPerDeg, camLon + e * lonPerM, i, bearing = bearing)

    @Test fun pathLineIsParsed() {
        val g = gridWithPaths(-1, pathLine(-400.0 to -600.0, -400.0 to 0.0, 0.0 to 0.0))
        val c = g.near(camLat, camLon).single()
        assertEquals(1, c.paths.size)
        assertEquals(1000.0, c.paths[0].lengthM, 5.0)
    }

    @Test fun curvedRoadIsFollowedAndDistanceIsAlongRoad() {
        // 도로: 동쪽으로 600m 달린 뒤 좌회전해 북쪽으로 400m 가면 카메라 (ㄴ자). 직선 방향으로는 카메라가 정면이 아니다.
        val e = DetectionEngine(gridWithPaths(-1, pathLine(-400.0 to -600.0, -400.0 to 0.0, 0.0 to 0.0)))
        val out = ArrayList<EngineOutput>()
        for (i in 0..30) out += e.update(at(-400.0, -600.0 + 20.0 * i, i, 90f))       // 동쪽으로
        for (i in 31..47) out += e.update(at(-400.0 + 20.0 * (i - 30), 0.0, i, 0f))     // 북쪽으로
        assertEquals(Stage.S1, out[10].stage)                 // 도로 따라 800m 남음 (직선 방향은 39° 어긋남)
        assertEquals(800.0, out[10].distanceM!!, 10.0)        // 남은 거리는 직선(566m)이 아니라 도로 거리
        assertEquals(Stage.S2, out[28].stage)                 // 440m
        assertEquals(Stage.S3, out[45].stage)                 // 100m
    }

    @Test fun parallelRoadDoesNotAlert() {
        // 카메라 도로는 남->북 직선. 나는 60m 옆의 나란한 다른 도로를 같은 방향으로 달린다.
        val road = pathLine(-1100.0 to 0.0, 0.0 to 0.0)
        val e = DetectionEngine(gridWithPaths(-1, road))
        for (i in 0..80) {
            val o = e.update(at(-1500.0 + 20.0 * i, 60.0, i, 0f))
            assertEquals("i=$i", Stage.HIDDEN, o.stage)
        }
        // 같은 주행을 도로 선 없는 카메라(예전 방식)로 하면 울린다 = 이번 변경으로 없어지는 오탐
        val old = DetectionEngine(grid(Camera(0, camLat, camLon, 80)))
        val outs = (0..60).map { i -> old.update(at(-1500.0 + 20.0 * i, 60.0, i, 0f)) }
        assertTrue(outs.any { it.stage != Stage.HIDDEN })
    }

    @Test fun safetyNetAlertsWhenRoadDataIsWrong() {
        // 도로 선이 엉뚱한 도로(동서 방향)로 잡혀 있어도, 카메라가 바로 정면 300m 안이면 알린다.
        val wrong = pathLine(0.0 to 1000.0, 0.0 to 0.0)
        val e = DetectionEngine(gridWithPaths(-1, wrong))
        val out = drive(e, upTo = 70)
        assertEquals(Stage.HIDDEN, out[45].stage)             // 600m: 도로 선과 안 맞아 조용
        assertEquals(Stage.S3, out[66].stage)                 // 180m: 안전망
    }

    @Test fun oppositeDirectionOnOneWayPath() {
        // 도로 선은 남->북 접근만 있다. 나는 북쪽에서 남쪽으로(반대 차선) 달려온다.
        val road = pathLine(-1100.0 to 0.0, 0.0 to 0.0)
        fun run(bearing: Int): List<EngineOutput> {
            val e = DetectionEngine(gridWithPaths(bearing, road))
            return (0..70).map { i -> e.update(at(1500.0 - 20.0 * i, 0.0, i, 180f)) }
        }
        val unknown = run(-1)                                  // 방향 모름: 멀리서는 조용, 300m 안전망만
        assertEquals(Stage.HIDDEN, unknown[45].stage)
        assertEquals(Stage.S3, unknown[66].stage)
        assertTrue(run(0).all { it.stage == Stage.HIDDEN })    // 방향 확정(북행 단속): 반대 차선은 끝까지 조용
    }

    @Test fun sameRoadStillAlertsFromOneKm() {
        val road = pathLine(-1100.0 to 0.0, 0.0 to 0.0)
        val e = DetectionEngine(gridWithPaths(-1, road))
        val out = drive(e, upTo = 95)
        assertEquals(Stage.HIDDEN, out[10].stage)
        assertEquals(Stage.S1, out[35].stage)
        assertEquals(Stage.S2, out[55].stage)
        assertEquals(Stage.S3, out[65].stage)
        assertEquals(Stage.HIDDEN, out[95].stage)             // 통과 후
    }

    @Test fun weakPathAlertsOnlyWithin200m() {
        // 반대쪽 접근이 약한 경로(Q 줄): 내 도로 위지만 200m 밖에서는 조용, 200m 안에서는 알린다.
        val weak = pathLine(-1100.0 to 0.0, 0.0 to 0.0).replaceFirst("P ", "Q ")
        val e = DetectionEngine(gridWithPaths(-1, weak))
        val out = drive(e, upTo = 70)
        assertEquals(Stage.HIDDEN, out[35].stage)             // 700m
        assertEquals(Stage.HIDDEN, out[55].stage)             // 300m: 정면이어도 약한 경로면 조용
        assertEquals(Stage.S3, out[66].stage)                 // 180m
    }

    @Test fun strongAndWeakPathsTogether() {
        // 같은 도로의 두 접근: 남->북(강), 북->남(약). 남쪽에서 오면 1km 앞부터, 북쪽에서 오면 200m 안에서만.
        val strong = pathLine(-1100.0 to 0.0, 0.0 to 0.0)
        val weak = pathLine(1100.0 to 0.0, 0.0 to 0.0).replaceFirst("P ", "Q ")
        val g = gridWithPaths(-1, strong, weak)
        val fromSouth = drive(DetectionEngine(g), upTo = 40)
        assertEquals(Stage.S1, fromSouth[35].stage)            // 800m
        val e2 = DetectionEngine(g)
        val fromNorth = (0..70).map { i -> e2.update(at(1500.0 - 20.0 * i, 0.0, i, 180f)) }
        assertEquals(Stage.HIDDEN, fromNorth[45].stage)        // 600m
        assertEquals(Stage.S3, fromNorth[66].stage)            // 180m
    }

    @Test fun alertRangeDependsOnSpeedLimit() {
        assertEquals(1000.0, AlertStyle.rangeM(110), 0.1)   // 고속도로
        assertEquals(800.0, AlertStyle.rangeM(80), 0.1)
        assertEquals(600.0, AlertStyle.rangeM(60), 0.1)     // 국도·시내: 제한속도 km/h x 10m
        assertEquals(500.0, AlertStyle.rangeM(50), 0.1)
        assertEquals(300.0, AlertStyle.rangeM(30), 0.1)     // 어린이보호구역
        assertEquals(300.0, AlertStyle.rangeM(20), 0.1)     // 하한 300m
        assertEquals(700.0, AlertStyle.rangeM(0), 0.1)      // 제한속도 모름
        // 500m 범위: 500m 밖은 숨김, 400m까지 1단계, 250m까지 2단계, 그 안은 3단계
        assertEquals(Stage.HIDDEN, AlertStyle.stageForDistance(520.0, 500.0))
        assertEquals(Stage.S1, AlertStyle.stageForDistance(450.0, 500.0))
        assertEquals(Stage.S2, AlertStyle.stageForDistance(300.0, 500.0))
        assertEquals(Stage.S3, AlertStyle.stageForDistance(200.0, 500.0))
        // 1km 범위는 예전과 같다
        assertEquals(Stage.S1, AlertStyle.stageForDistance(800.0))
        assertEquals(Stage.S2, AlertStyle.stageForDistance(400.0))
        assertEquals(Stage.S3, AlertStyle.stageForDistance(250.0))
    }

    @Test fun nationalRoadCameraStartsAt500m() {
        val e = DetectionEngine(grid(Camera(0, camLat, camLon, 50)))
        val out = drive(e, upTo = 80)                       // i번째 남은 거리 = 1500 - 20*i
        assertEquals(Stage.HIDDEN, out[40].stage)           // 700m: 제한속도 50 도로에서는 아직 조용
        assertEquals(Stage.S1, out[54].stage)               // 420m
        assertEquals(Stage.S3, out[66].stage)               // 180m
    }

    @Test fun longStopHidesAlertButShortStopKeepsIt() {
        val e = DetectionEngine(grid(Camera(0, camLat, camLon, 100)))
        val out = drive(e, upTo = 66)                          // 180m 앞, 3단계
        assertEquals(Stage.S3, out[66].stage)
        val lat = camLat - 180 / mPerDeg
        var o = out[66]
        for (k in 1..60) o = e.update(Fix(lat, camLon, 0f, 0f, true, (66 + k) * 1000L))   // 60초 정차(신호 대기)
        assertEquals(Stage.S3, o.stage)                        // 짧은 정차는 유지
        for (k in 61..100) o = e.update(Fix(lat, camLon, 0f, 0f, true, (66 + k) * 1000L)) // 100초째
        assertEquals(Stage.HIDDEN, o.stage)                    // 오래 멈추면 끈다
    }

    @Test fun engineReportsWhichCameraAndRule() {
        val e = DetectionEngine(grid(Camera(0, camLat, camLon, 100)))
        val out = drive(e, upTo = 40)
        val o = out[40]                                          // 700m 앞
        assertEquals(camLat, o.camLat!!, 1e-6)
        assertEquals(camLon, o.camLon!!, 1e-6)
        assertEquals("직선(도로 정보 없음)", o.rule)
        val e2 = DetectionEngine(gridWithPaths(-1, pathLine(-1100.0 to 0.0, 0.0 to 0.0)))
        assertEquals("도로선", drive(e2, upTo = 40)[40].rule)
    }

    @Test fun alertLogKeepsLatest30AndRoundTrips() {
        var saved: List<AlertEntry> = emptyList()
        val log = AlertLogList(emptyList()) { saved = it }
        for (i in 1..35) log.add(AlertEntry(i * 1000L, 37.5, 127.0, 60, 300, "도로선", 50))
        assertEquals(30, log.all().size)
        assertEquals(35_000L, log.all().first().timeMs)          // 최신이 앞
        val back = AlertEntry.parse(saved.first().toLine())!!
        assertEquals("도로선", back.rule)
        assertEquals(37.5, back.camLat, 1e-9)
        log.clear()
        assertTrue(saved.isEmpty())
    }
}
