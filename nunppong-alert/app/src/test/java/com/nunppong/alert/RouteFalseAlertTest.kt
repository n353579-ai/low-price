package com.nunppong.alert

import com.nunppong.alert.core.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * 실제 도로 위를 가상으로 달려 '틀린 알림'을 센다 (개발용 측정 도구, 환경변수 ROUTES_FILE 이 있을 때만 실행).
 * routes.txt 는 tools/make_test_routes.py 로 만든다.
 *
 * 분류:
 *  - 정답 알림: 알린 카메라가 내 경로 위(25m 이내)에 실제로 있음
 *  - 틀린 알림: 알린 카메라가 내 경로 위에 없음 (옆 도로·평행 도로·엉뚱한 카메라)
 *  - 놓침: 내 경로 위 카메라인데 도착 전에 3단계까지 알리지 못함
 */
class RouteFalseAlertTest {
    private class Route(val pts: List<DoubleArray>, val onRoute: Map<String, Int>)

    private fun key(la: Double, lo: Double) = String.format(java.util.Locale.US, "%.5f,%.5f", la, lo)

    private fun load(f: File): List<Route> {
        val out = ArrayList<Route>()
        var pts = ArrayList<DoubleArray>()
        var on = HashMap<String, Int>()
        fun flush() { if (pts.isNotEmpty()) out += Route(pts, on); pts = ArrayList(); on = HashMap() }
        f.forEachLine { ln ->
            val p = ln.split(' ')
            when (p[0]) {
                "R" -> flush()
                "p" -> pts.add(doubleArrayOf(p[1].toDouble(), p[2].toDouble()))
                "c" -> on[key(p[1].toDouble(), p[2].toDouble())] = p[3].toInt()
            }
        }
        flush()
        return out
    }

    @Test fun measureFalseAlertsOnRealRoads() {
        val env = System.getenv("ROUTES_FILE")
        assumeTrue(env != null && File(env).exists())
        val grid = File("src/main/assets/cameras.grid.txt").bufferedReader().useLines { CameraGrid.parse(it) }
        val routes = load(File(env!!))

        val speeds = floatArrayOf(14f, 20f)                       // 50, 72 km/h
        var episodes = 0
        var good = 0
        var bad = 0
        var onRouteCams = 0
        var missed = 0
        val badByRule = HashMap<String, Int>()
        val goodDist = IntArray(6)
        val badNear = IntArray(4)
        val badNearRule = HashMap<String, Int>()
        val badDist = IntArray(6)
        fun bucket(d: Int) = when { d < 150 -> 0; d < 300 -> 1; d < 500 -> 2; d < 700 -> 3; d < 1000 -> 4; else -> 5 }
        val goodByRule = HashMap<String, Int>()
        val examples = ArrayList<String>()
        var km = 0.0
        val missEx = ArrayList<String>()

        for ((ri, r) in routes.withIndex()) {
            val mps = speeds[ri % speeds.size]
            val e = DetectionEngine(grid)
            var t = 0L
            var prevKey: String? = null
            val alertedOn = HashSet<String>()                     // S3까지 간 정답 카메라
            val seenBad = HashSet<String>()
            for (i in 1 until r.pts.size) {
                val a = r.pts[i - 1]
                val b = r.pts[i]
                val hd = Geo.bearingDeg(a[0], a[1], b[0], b[1])
                t += 1000L
                // 점 사이 20m 를 mps 로 가면 20/mps 초지만, 엔진은 1초 단위 입력이라 점마다 1초로 취급(속도값만 mps)
                val o = e.update(Fix(b[0], b[1], mps, hd.toFloat(), true, t))
                if (o.stage == Stage.HIDDEN || o.camLat == null) { prevKey = null; continue }
                val k = key(o.camLat!!, o.camLon!!)
                if (k != prevKey) {
                    prevKey = k
                    episodes++
                    val idx = r.onRoute[k]
                    if (idx != null && idx >= i - 1) {
                        good++; goodByRule.merge(o.rule, 1, Int::plus); goodDist[bucket((o.distanceM ?: 0.0).toInt())]++
                    } else if (seenBad.add(k)) {
                        bad++; badByRule.merge(o.rule, 1, Int::plus); badDist[bucket((o.distanceM ?: 0.0).toInt())]++
                        // 이 카메라에 내 경로가 앞으로 가장 가까이 가는 거리(m): 꺾어서 멀어졌나, 스치듯 지나가나
                        var minAhead = 1e9
                        for (j in i until r.pts.size) {
                            val dd = Geo.distanceM(r.pts[j][0], r.pts[j][1], o.camLat!!, o.camLon!!)
                            if (dd < minAhead) minAhead = dd
                        }
                        val bi = when { minAhead < 40 -> 0; minAhead < 80 -> 1; minAhead < 150 -> 2; else -> 3 }
                        badNear[bi]++; badNearRule.merge("${o.rule}/${listOf("<40","40-80","80-150",">150")[bi]}", 1, Int::plus)
                        if (examples.size < 25) examples += "${o.rule} cam=$k dist=${(o.distanceM ?: -1.0).toInt()} limit=${o.limit} route=$ri idx=$i"
                    }
                }
                if (o.stage == Stage.S3) {
                    val k2 = key(o.camLat!!, o.camLon!!)
                    if (r.onRoute.containsKey(k2)) alertedOn.add(k2)
                }
            }
            km += r.pts.size * 0.02
            for ((ck, ix) in r.onRoute) {
                if (ix < 60) continue                             // 경로 시작 직후 카메라는 제외
                onRouteCams++
                if (ck !in alertedOn) {
                    missed++
                    println("MISSKEY route=$ri idx=$ix cam=$ck")
                    if (missEx.size < 40) {
                        val la = ck.substringBefore(',').toDouble(); val lo = ck.substringAfter(',').toDouble()
                        val cam = grid.near(la, lo).firstOrNull { key(it.lat, it.lon) == ck }
                        val i0 = maxOf(1, ix - 1)
                        val hd = Geo.bearingDeg(r.pts[i0 - 1][0], r.pts[i0 - 1][1], r.pts[i0][0], r.pts[i0][1])
                        missEx += "route=$ri idx=$ix cam=$ck limit=${cam?.limit} bearing=${cam?.bearing} paths=${cam?.paths?.size} weak=${cam?.paths?.count { it.rangeM < 1e9 }} myHeading=${hd.toInt()}"
                    }
                }
            }
        }
        println("RESULT 경로 ${routes.size}개 ${km.toInt()}km / 알림 $episodes 건: 정답 $good, 틀림 $bad (${100 * bad / maxOf(1, episodes)}%)")
        println("RESULT 규칙별 정답 $goodByRule")
        println("RESULT 규칙별 틀림 $badByRule")
        println("RESULT 틀린 알림이 경로에 가장 가까이 가는 거리(<40,40-80,80-150,>150m) ${badNear.toList()}")
        println("RESULT   규칙별 ${badNearRule.toSortedMap()}")
        println("RESULT 알림 시작 거리별(0-150,150-300,300-500,500-700,700-1000,1000+) 정답 ${goodDist.toList()} 틀림 ${badDist.toList()}")
        println("RESULT 경로 위 카메라 $onRouteCams 개 중 3단계 못 울림 $missed 개")
        examples.take(0).forEach { println("RESULT 예) $it") }
        missEx.forEach { println("RESULT 놓침) $it") }
    }
}
