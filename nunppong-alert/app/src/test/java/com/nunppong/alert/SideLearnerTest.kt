package com.nunppong.alert

import com.nunppong.alert.core.*
import org.junit.Assert.*
import org.junit.Test
import java.util.Random

class SideLearnerTest {
    private val mLat = 111194.9
    private val camLat = 37.5
    private val camLon = 127.0
    private val mLon = mLat * Math.cos(Math.toRadians(camLat))

    private fun grid(c: Camera): CameraGrid =
        CameraGrid.parse(sequenceOf("G ${Math.floor(c.lat * 100).toInt()} ${Math.floor(c.lon * 100).toInt()}", "C ${c.lat} ${c.lon} ${c.limit} ${c.bearing}"))

    /** 북쪽으로 달려 카메라를 지난다. s = 카메라가 내 오른쪽(+) 몇 m. GPS 잡음 sigma m, 방위 잡음 5도 */
    private fun passOnce(s: Double, sigma: Double, rnd: Random, mps: Float = 15f): Double? {
        val e = DetectionEngine(grid(Camera(0, camLat, camLon, 50)))
        val x0 = -s                                   // 내 위치 동쪽 성분(카메라 기준): 카메라가 오른쪽(동)에 s만큼
        for (i in 0..24) {
            val y = (i - 14) * mps.toDouble()           // 북쪽 성분(카메라 기준 m)
            val lat = camLat + (y + rnd.nextGaussian() * sigma) / mLat
            val lon = camLon + (x0 + rnd.nextGaussian() * sigma) / mLon
            val f = Fix(lat, lon, mps, (rnd.nextGaussian() * 5.0).toFloat().let { if (it < 0) it + 360f else it }, true, i * 1000L, 5f)
            e.update(f)
            e.takePassed()?.let { return it.sideM }
        }
        return null
    }

    @Test fun cleanTrackSign() {
        val rnd = Random(1)
        val right = passOnce(6.0, 0.0, rnd)!!
        val left = passOnce(-6.0, 0.0, rnd)!!
        assertTrue("right=$right", right > 3.0)
        assertTrue("left=$left", left < -3.0)
    }

    @Test fun noisyAccuracy() {
        for ((lane, side) in listOf(3.2 to "5m도로(같은방향)", 6.7 to "")) { /* 아래에서 자세히 */ }
        val rnd = Random(7)
        val sb = StringBuilder()
        // 카메라가 도로 중심에서 g m 오른쪽 길가 -> 같은 방향 차량은 g-1.75 오른쪽, 반대 방향 차량은 g+1.75 왼쪽
        for (g in listOf(5.0, 8.0, 12.0)) {
            for (sigma in listOf(2.0, 4.0)) {
                var sameOk = 0; var oppOk = 0; val n = 300
                var learnedSame = 0; var learnedOpp = 0; val m = 100
                for (t in 0 until n) {
                    val a = passOnce(g - 1.75, sigma, rnd)
                    val b = passOnce(-(g + 1.75), sigma, rnd)
                    if (a != null && a > 0) sameOk++
                    if (b != null && b < 0) oppOk++
                }
                for (t in 0 until m) {
                    val l1 = SideLearner(); val l2 = SideLearner()
                    val cam = Camera(0, camLat, camLon, 50)
                    for (k in 0 until 3) {
                        passOnce(g - 1.75, sigma, rnd)?.let { l1.record(camLat, camLon, 0.0, it) }
                        passOnce(-(g + 1.75), sigma, rnd)?.let { l2.record(camLat, camLon, 0.0, it) }
                    }
                    if (l1.isOpposite(cam, 0.0)) learnedSame++      // 같은 방향인데 반대로 잘못 판정(중요: 낮아야 함)
                    if (l2.isOpposite(cam, 0.0)) learnedOpp++
                }
                sb.append("도로옆 ${g}m GPS잡음 ${sigma}m: 1회 부호 정확도 같은방향 ${sameOk * 100 / n}% / 반대방향 ${oppOk * 100 / n}%;" +
                    " 3회 학습 후 반대로 판정: 같은방향(오판) ${learnedSame}% / 반대방향(적중) ${learnedOpp}%\n")
                // 안전: 같은 방향 카메라를 반대로 잘못 판정하는 비율은 낮아야 한다
                assertTrue(sb.toString(), learnedSame <= 15)
            }
        }
        println(sb)
    }

    @Test fun learnerNeedsRepeatedLeft() {
        val l = SideLearner()
        val cam = Camera(0, camLat, camLon, 50)
        assertFalse(l.isOpposite(cam, 0.0))
        l.record(camLat, camLon, 0.0, -8.0)
        assertFalse(l.isOpposite(cam, 0.0))          // 한 번으로는 판정하지 않는다
        l.record(camLat, camLon, 0.0, -7.0)
        assertTrue(l.isOpposite(cam, 0.0))
        assertFalse(l.isOpposite(cam, 180.0))        // 다른 진행방향은 따로
        l.record(camLat, camLon, 0.0, 6.0)
        l.record(camLat, camLon, 0.0, 6.0)
        l.record(camLat, camLon, 0.0, 6.0)
        assertFalse(l.isOpposite(cam, 0.0))          // 오른쪽에 있었던 적이 많으면 풀린다
    }
}
