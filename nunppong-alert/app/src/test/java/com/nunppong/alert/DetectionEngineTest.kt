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
}
