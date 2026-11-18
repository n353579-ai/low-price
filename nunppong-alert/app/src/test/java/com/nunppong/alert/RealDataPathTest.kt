package com.nunppong.alert

import com.nunppong.alert.core.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * 실제 카메라 파일(assets/cameras.grid.txt)로 하는 '놓침' 검사.
 * 카메라의 도로 선을 따라 가상으로 달리면, 카메라 앞에서 반드시 3단계(300m 이내) 경고가 떠야 한다.
 */
class RealDataPathTest {
    private val file = File("src/main/assets/cameras.grid.txt")

    private class Sample(val lat: Double, val lon: Double, val heading: Double, val remain: Double)

    /** 선을 따라 stepM 간격으로 찍은 주행 점들 */
    private fun samples(p: CamPath, stepM: Double): List<Sample> {
        val out = ArrayList<Sample>()
        val segLen = DoubleArray(p.lat.size - 1) { Geo.distanceM(p.lat[it], p.lon[it], p.lat[it + 1], p.lon[it + 1]) }
        val total = segLen.sum()
        var s = 0.0
        while (s < total) {
            var acc = 0.0
            var i = 0
            while (i < segLen.size - 1 && acc + segLen[i] < s) { acc += segLen[i]; i++ }
            val t = if (segLen[i] > 0) ((s - acc) / segLen[i]).coerceIn(0.0, 1.0) else 0.0
            out += Sample(
                p.lat[i] + t * (p.lat[i + 1] - p.lat[i]),
                p.lon[i] + t * (p.lon[i + 1] - p.lon[i]),
                Geo.bearingDeg(p.lat[i], p.lon[i], p.lat[i + 1], p.lon[i + 1]),
                total - s,
            )
            s += stepM
        }
        return out
    }

    @Test fun drivingEveryRoadLineReachesStage3() {
        assumeTrue(file.exists())
        val grid = file.bufferedReader().useLines { CameraGrid.parse(it) }
        assertTrue("카메라가 너무 적음: ${grid.count}", grid.count > 20000)

        // 격자 전체를 훑어 카메라 목록을 모은다 (한국 범위)
        val cams = HashMap<Int, Camera>()
        var la = 33.0
        while (la < 39.0) {
            var lo = 124.5
            while (lo < 132.0) {
                for (c in grid.near(la, lo)) cams[c.id] = c
                lo += 0.03
            }
            la += 0.03
        }
        assertEquals(grid.count, cams.size)

        var withPath = 0
        var tested = 0
        var missed = 0
        val examples = ArrayList<String>()
        for (c in cams.values) {
            if (c.paths.isEmpty()) continue
            withPath++
            if (c.id % 5 != 0) continue          // 5개 중 1개 표본 (시간 절약)
            for (p in c.paths) {
                if (p.lengthM < 150.0) continue  // 너무 짧은 선은 경고가 확정되기 전에 끝난다
                val e = DetectionEngine(grid)
                var ok = false
                var i = 0
                for (s in samples(p, 20.0)) {
                    val o = e.update(Fix(s.lat, s.lon, 20f, s.heading.toFloat(), true, i * 1000L))
                    i++
                    // 카메라 150m 앞~20m 앞 구간에서 3단계가 떠 있어야 한다
                    if (s.remain <= 150.0 && s.remain >= 20.0 && o.stage == Stage.S3) ok = true
                }
                tested++
                if (!ok) {
                    missed++
                    if (examples.size < 10) examples += "${c.lat},${c.lon} len=${p.lengthM.toInt()}"
                }
            }
        }
        println("도로 선 있는 카메라 $withPath / 검사한 선 $tested / 놓침 $missed  예: $examples")
        assertTrue("도로 선이 붙은 카메라가 너무 적음: $withPath", withPath > grid.count * 0.9)
        assertEquals("놓친 선: $examples", 0, missed)
    }
}
