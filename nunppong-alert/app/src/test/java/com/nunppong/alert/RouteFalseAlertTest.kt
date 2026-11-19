package com.nunppong.alert

import com.nunppong.alert.core.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * 실제 도로 위를 가상으로 달려 '틀린 알림'을 센다 (개발용 측정 도구, 환경변수 ROUTES_FILE 이 있을 때만 실행).
 * routes.txt 는 tools/make_test_routes.py 로 만든다. GPS_NOISE_M(기본 4)으로 위치 흔들림을 넣는다.
 *
 * 분류:
 *  - 정답 알림: 알린 카메라가 내 경로 위(25m 이내)에 실제로 있음
 *  - 틀린 알림: 알린 카메라가 내 경로 위에 없음 (옆 도로·평행 도로·엉뚱한 카메라)
 *  - 놓침: 내 경로 위 카메라인데 도착 전에 3단계까지 알리지 못함
 *  - 꺼짐 지연: 틀린 알림에서, 내가 카메라의 도로 선을 30m 넘게 벗어난 뒤 알림이 꺼질 때까지 걸린 시간(초)
 *  - 끊김: 정답 알림이 카메라까지 80m 넘게 남았는데 꺼진 횟수
 */
class RouteFalseAlertTest {
    private class Route(val pts: List<DoubleArray>, val onRoute: Map<String, Int>)

    private fun key(la: Double, lo: Double) = String.format(java.util.Locale.US, "%.5f,%.5f", la, lo)

    /** 점 (la,lo)에서 카메라의 도로 선들까지 가장 가까운 옆거리(m) */
    private fun lateralToPaths(c: Camera, la: Double, lo: Double): Double {
        val mLat = 111195.0
        val mLon = mLat * Math.cos(Math.toRadians(la))
        var best = Double.MAX_VALUE
        for (p in c.paths) {
            for (i in 0 until p.lat.size - 1) {
                val ax = (p.lon[i] - lo) * mLon
                val ay = (p.lat[i] - la) * mLat
                val bx = (p.lon[i + 1] - lo) * mLon
                val by = (p.lat[i + 1] - la) * mLat
                val dx = bx - ax
                val dy = by - ay
                val ll = dx * dx + dy * dy
                val t = if (ll < 1e-9) 0.0 else (-(ax * dx + ay * dy) / ll).coerceIn(0.0, 1.0)
                val d = Math.hypot(ax + t * dx, ay + t * dy)
                if (d < best) best = d
            }
        }
        return best
    }

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
        val noiseM = (System.getenv("GPS_NOISE_M") ?: "4").toDouble()
        val rnd = java.util.Random(11)

        val speeds = floatArrayOf(14f, 20f)                       // 50, 72 km/h
        var episodes = 0
        var good = 0
        var bad = 0
        var onRouteCams = 0
        var missed = 0
        var dropouts = 0
        var goodEnded = 0
        val badByRule = HashMap<String, Int>()
        val goodByRule = HashMap<String, Int>()
        val lagHist = IntArray(5)
        var km = 0.0
        val dropEx = ArrayList<String>()

        for ((ri, r) in routes.withIndex()) {
            val mps = speeds[ri % speeds.size]
            val e = DetectionEngine(grid)
            var t = 0L
            var prevKey: String? = null
            var epStart = -1
            var epBad = false
            var epKey = ""
            var lastRule = ""
            val alertedOn = HashSet<String>()                     // S3까지 간 정답 카메라
            val seenBad = HashSet<String>()

            fun endEpisode(i: Int, toHidden: Boolean) {
                if (epStart < 0) return
                if (epBad) {
                    val la = epKey.substringBefore(',').toDouble()
                    val lo = epKey.substringAfter(',').toDouble()
                    val cam = grid.near(la, lo).firstOrNull { key(it.lat, it.lon) == epKey }
                    if (cam != null && cam.paths.isNotEmpty()) {
                        var dev = -1
                        for (j in epStart until i) {
                            if (lateralToPaths(cam, r.pts[j][0], r.pts[j][1]) > 30.0) { dev = j; break }
                        }
                        if (dev >= 0) {
                            val lag = i - dev
                            lagHist[when { lag <= 1 -> 0; lag == 2 -> 1; lag <= 4 -> 2; lag <= 8 -> 3; else -> 4 }]++
                        }
                    }
                } else {
                    goodEnded++
                    val idxEnd = r.onRoute[epKey]
                    if (toHidden && idxEnd != null && (idxEnd - i) * 20 > 80) {
                        dropouts++
                        if (dropEx.size < 30) dropEx += "route=$ri end=$i camIdx=$idxEnd remain=${(idxEnd - i) * 20}m cam=$epKey rule=${lastRule}"
                    }
                }
                epStart = -1
            }

            for (i in 1 until r.pts.size) {
                val a = r.pts[i - 1]
                val b = r.pts[i]
                val hd = Geo.bearingDeg(a[0], a[1], b[0], b[1])
                t += 1000L
                // 점 사이 20m 를 mps 로 가면 20/mps 초지만, 엔진은 1초 단위 입력이라 점마다 1초로 취급(속도값만 mps)
                val nla = b[0] + rnd.nextGaussian() * noiseM / 111195.0
                val nlo = b[1] + rnd.nextGaussian() * noiseM / (111195.0 * Math.cos(Math.toRadians(b[0])))
                val o = e.update(Fix(nla, nlo, mps, hd.toFloat(), true, t))
                if (o.stage == Stage.HIDDEN || o.camLat == null) { endEpisode(i, true); prevKey = null; continue }
                lastRule = o.rule
                val k = key(o.camLat!!, o.camLon!!)
                if (k != prevKey) {
                    endEpisode(i, false)
                    prevKey = k
                    epStart = i
                    epKey = k
                    episodes++
                    val idx = r.onRoute[k]
                    epBad = !(idx != null && idx >= i - 1)
                    if (!epBad) {
                        good++; goodByRule.merge(o.rule, 1, Int::plus)
                    } else if (seenBad.add(k)) {
                        bad++; badByRule.merge(o.rule, 1, Int::plus)
                    }
                }
                if (o.stage == Stage.S3) {
                    val k2 = key(o.camLat!!, o.camLon!!)
                    if (r.onRoute.containsKey(k2)) alertedOn.add(k2)
                }
            }
            endEpisode(r.pts.size - 1, false)
            km += r.pts.size * 0.02
            for ((ck, ix) in r.onRoute) {
                if (ix < 60) continue                             // 경로 시작 직후 카메라는 제외
                onRouteCams++
                if (ck !in alertedOn) missed++
            }
        }
        println("RESULT 경로 ${routes.size}개 ${km.toInt()}km GPS흔들림 ${noiseM}m / 알림 $episodes 건: 정답 $good, 틀림 $bad (${100 * bad / maxOf(1, episodes)}%)")
        println("RESULT 규칙별 정답 $goodByRule")
        println("RESULT 규칙별 틀림 $badByRule")
        println("RESULT 길 벗어난 뒤 꺼질 때까지(<=1초,2초,3~4초,5~8초,9초+) ${lagHist.toList()}")
        println("RESULT 정답 알림 끊김 $dropouts / 끝난 정답 알림 $goodEnded")
        dropEx.forEach { println("RESULT 끊김) $it") }
        println("RESULT 경로 위 카메라 $onRouteCams 개 중 3단계 못 울림 $missed 개")
    }
}
