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
        val e = DetectionEngine(grid(Camera(0, camLat, camLon, 80)))
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
}
