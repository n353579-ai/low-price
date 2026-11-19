package com.nunppong.alert

import com.nunppong.alert.core.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * 실제 도로 위를 시나리오별로 가상 주행해 알림 품질을 잰다 (개발용 측정 도구).
 * 환경변수 ROUTES_FILES="이름=경로파일,이름=경로파일" 이 있을 때만 실행. 경로 파일은 tools/make_test_routes.py 로 만든다.
 * GPS_NOISE_M(기본 4)으로 위치 흔들림을 넣는다. 1초마다 한 번 위치를 넣는다(실제 폰과 같음).
 *
 * 시나리오: 속도 50/72/100/110km/h 일정, 과속(130km/h), 서다 가다(막히는 시내), 가속·감속이 섞인 주행.
 *
 * 지표
 *  - 틀린 알림: 알린 카메라가 내 경로 위(25m 이내)에 없음
 *  - 놓침: 내 경로 위 카메라인데 3단계까지 못 울림
 *  - 끊김: 맞는 알림이 카메라 80m 넘게 남았는데 꺼짐(카메라가 바뀐 경우 제외)
 *  - 짧은 경고: 3단계(빨강)가 카메라 도착 전 5초 미만으로만 켜진 맞는 알림
 *  - 정차 잔존: 멈춰 있는 동안(5초 이상) 켜져 있던 알림 시간 합(초)
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

    /** 속도 곡선: 매초 다음 속도(m/s)를 돌려준다 */
    private abstract class Driver { abstract fun next(rnd: java.util.Random): Double }

    private class Cruise(val v: Double) : Driver() {
        var cur = 0.0
        override fun next(rnd: java.util.Random): Double { cur = minOf(v, cur + 2.0); return cur }
    }

    /** 막히는 시내: 서다(5~40초) 가다(10~40초) 반복, 최고 12m/s(43km/h) */
    private class StopGo : Driver() {
        var cur = 0.0
        var phase = 0       // 0 정차, 1 주행
        var left = 10
        override fun next(rnd: java.util.Random): Double {
            if (left <= 0) {
                phase = 1 - phase
                left = if (phase == 0) 5 + rnd.nextInt(36) else 10 + rnd.nextInt(31)
            }
            left--
            cur = if (phase == 0) maxOf(0.0, cur - 2.5) else minOf(12.0, cur + 1.5)
            return cur
        }
    }

    /** 가속·감속이 섞인 주행: 15초마다 목표 속도를 바꿈 (8~25m/s) */
    private class Variable : Driver() {
        var cur = 8.0
        var target = 15.0
        var left = 0
        override fun next(rnd: java.util.Random): Double {
            if (left <= 0) { target = 8.0 + rnd.nextDouble() * 17.0; left = 15 }
            left--
            cur += (target - cur).coerceIn(-2.0, 1.5)
            return cur
        }
    }

    private class Stat {
        var episodes = 0; var good = 0; var bad = 0
        var onRouteCams = 0; var missed = 0
        var dropouts = 0; var goodEnded = 0
        var shortWarn = 0; var warnN = 0
        var stoppedAlertSec = 0; var km = 0.0
        val warn = ArrayList<Double>()
        val missReason = HashMap<String, Int>()
        val missEx = ArrayList<String>()
        val dropEx = ArrayList<String>()
    }

    /** 놓친 카메라의 원인 분류 */
    private fun diagnose(grid: CameraGrid, r: Route, ck: String, ix: Int): String {
        val la = ck.substringBefore(',').toDouble()
        val lo = ck.substringAfter(',').toDouble()
        val cam = grid.near(la, lo).firstOrNull { key(it.lat, it.lon) == ck } ?: return "카메라 못찾음"
        if (cam.paths.isEmpty()) return "도로 정보 없음(630개)"
        val i1 = maxOf(1, ix - 1)
        val i0 = maxOf(0, i1 - 6)
        val hd = Geo.bearingDeg(r.pts[i0][0], r.pts[i0][1], r.pts[i1][0], r.pts[i1][1])
        if (cam.bearing >= 0 && Geo.angleDiff(hd, cam.bearing.toDouble()) > 45.0) return "반대 방향 확정(일부러 조용)"
        var onPath = false
        for (j in maxOf(1, ix - 45)..minOf(ix, r.pts.size - 1)) {
            val h = Geo.bearingDeg(r.pts[j - 1][0], r.pts[j - 1][1], r.pts[j][0], r.pts[j][1])
            for (p in cam.paths) {
                if (p.matchRemaining(r.pts[j][0], r.pts[j][1], h, 30.0, 45.0) != null) onPath = true
            }
        }
        return if (onPath) "도로선 위에 있었는데 안 울림" else "내 길이 카메라 도로 선과 안 맞음(옆길·교차)"
    }

    private fun simulate(grid: CameraGrid, routes: List<Route>, mkDriver: () -> Driver, noiseM: Double, seed: Long): Stat {
        val st = Stat()
        val headNoise = (System.getenv("HEAD_NOISE_DEG") ?: "0").toDouble()
        val headLag = (System.getenv("HEAD_LAG") ?: "0").toInt()
        val noBearingPct = (System.getenv("NO_BEARING_PCT") ?: "0").toInt()
        val rnd = java.util.Random(seed)
        val mLat = 111195.0
        for (r in routes) {
            // 경로를 이어 붙인 길이(m) 계산
            val n = r.pts.size
            val cum = DoubleArray(n)
            for (i in 1 until n) cum[i] = cum[i - 1] + Geo.distanceM(r.pts[i - 1][0], r.pts[i - 1][1], r.pts[i][0], r.pts[i][1])
            val total = cum[n - 1]
            val e = DetectionEngine(grid)
            val driver = mkDriver()
            var s = 0.0
            var t = 0L
            var seg = 0
            var heading = 0.0
            val headHist = ArrayDeque<Double>()
            val ring = ArrayDeque<String>()
            var prevKey: String? = null
            var epBad = false
            var epKey = ""
            var epStartT = 0L
            var s3Time = -1L
            val alertedOn = HashSet<String>()
            val s3Pts = ArrayList<DoubleArray>()       // 3단계까지 간 카메라 좌표들
            val seenBad = HashSet<String>()
            val passT = HashMap<String, Long>()
            val s3T = HashMap<String, Long>()
            var stoppedRun = 0
            var alertWhileStopped = 0

            fun endEpisode(curIdx: Int, toHidden: Boolean) {
                if (prevKey == null) return
                if (!epBad) {
                    st.goodEnded++
                    val ix = r.onRoute[epKey]
                    if (toHidden && ix != null && (ix - curIdx) * 20 > 80) {
                        st.dropouts++
                        if (st.dropEx.size < 10) st.dropEx += "route=${routes.indexOf(r)} cam=$epKey remain=${(ix - curIdx) * 20}m 최근상태: ${ring.joinToString(" > ")}"
                    }
                }
            }

            while (s < total) {
                val v = driver.next(rnd)
                s += v
                if (s >= total) break
                while (seg < n - 2 && cum[seg + 1] < s) seg++
                val f = if (cum[seg + 1] > cum[seg]) (s - cum[seg]) / (cum[seg + 1] - cum[seg]) else 0.0
                val la = r.pts[seg][0] + f * (r.pts[seg + 1][0] - r.pts[seg][0])
                val lo = r.pts[seg][1] + f * (r.pts[seg + 1][1] - r.pts[seg][1])
                if (v > 0.5) heading = Geo.bearingDeg(r.pts[seg][0], r.pts[seg][1], r.pts[seg + 1][0], r.pts[seg + 1][1])
                val curIdx = (s / 20.0).toInt()
                t += 1000L
                // 카메라 통과 시각 기록 (경로 위 카메라 중 지나간 것)
                for ((ck, ix) in r.onRoute) if (ix <= curIdx && !passT.containsKey(ck)) passT[ck] = t
                val nla = la + rnd.nextGaussian() * noiseM / mLat
                val nlo = lo + rnd.nextGaussian() * noiseM / (mLat * Math.cos(Math.toRadians(la)))
                headHist.addLast(heading)
                while (headHist.size > headLag + 1) headHist.removeFirst()
                val usedHeading = (headHist.first() + rnd.nextGaussian() * headNoise + 360.0) % 360.0
                val hasB = rnd.nextInt(100) >= noBearingPct
                val o = e.update(Fix(nla, nlo, v.toFloat(), usedHeading.toFloat(), hasB, t, (noiseM * 1.3).toFloat()))

                if (v < 0.5) stoppedRun++ else stoppedRun = 0
                if (stoppedRun >= 5 && o.stage != Stage.HIDDEN) alertWhileStopped++

                ring.addLast("${o.stage}/${o.rule}/${o.distanceM?.toInt()}")
                while (ring.size > 6) ring.removeFirst()
                if (o.stage == Stage.HIDDEN || o.camLat == null) {
                    endEpisode(curIdx, true); prevKey = null; continue
                }
                val k = key(o.camLat!!, o.camLon!!)
                if (k != prevKey) {
                    endEpisode(curIdx, false)
                    prevKey = k
                    epKey = k
                    epStartT = t
                    st.episodes++
                    val ix = r.onRoute[k]
                    epBad = !(ix != null && ix >= curIdx - 1)
                    if (!epBad) st.good++ else if (seenBad.add(k)) st.bad++
                }
                if (o.stage == Stage.S3) {
                    val k2 = key(o.camLat!!, o.camLon!!)
                    if (r.onRoute.containsKey(k2)) { alertedOn.add(k2); s3T.putIfAbsent(k2, t) }
                    s3Pts.add(doubleArrayOf(o.camLat!!, o.camLon!!))
                }
            }
            endEpisode((s / 20.0).toInt(), false)
            st.km += total / 1000.0
            st.stoppedAlertSec += alertWhileStopped
            for ((ck, ix) in r.onRoute) {
                if (ix < 60) continue
                st.onRouteCams++
                val ckLa = ck.substringBefore(',').toDouble()
                val ckLo = ck.substringAfter(',').toDouble()
                // 50m 안에 3단계까지 간 다른 카메라가 있으면 같은 지점(중복 등록)으로 보고 울린 것으로 센다
                val covered = ck in alertedOn || s3Pts.any { Geo.distanceM(it[0], it[1], ckLa, ckLo) <= 50.0 }
                if (!covered) {
                    st.missed++
                    val why = diagnose(grid, r, ck, ix)
                    st.missReason.merge(why, 1, Int::plus)
                    if (st.missEx.size < 12 && why.startsWith("도로선")) st.missEx += "route=${routes.indexOf(r)} idx=$ix cam=$ck"
                } else {
                    val p = passT[ck]
                    val a = s3T[ck]
                    if (p != null && a != null && p >= a) {
                        val w = (p - a) / 1000.0
                        st.warn.add(w); st.warnN++
                        if (w < 5.0) st.shortWarn++
                    }
                }
            }
        }
        return st
    }

    @Test fun measureScenarios() {
        val env = System.getenv("ROUTES_FILES")
        assumeTrue(env != null)
        val grid = File("src/main/assets/cameras.grid.txt").bufferedReader().useLines { CameraGrid.parse(it) }
        val noiseM = (System.getenv("GPS_NOISE_M") ?: "4").toDouble()
        val sets = env!!.split(',').map { it.substringBefore('=') to load(File(it.substringAfter('='))) }
        val scenarios = listOf<Pair<String, () -> Driver>>(
            "50km/h" to { Cruise(13.9) },
            "72km/h" to { Cruise(20.0) },
            "100km/h" to { Cruise(27.8) },
            "130km/h(과속)" to { Cruise(36.1) },
            "서다가다" to { StopGo() },
            "가감속" to { Variable() },
        )
        println("RESULT 도로종류 | 시나리오 | km | 알림 | 틀림% | 놓침/경로위카메라 | 끊김 | 짧은경고(<5초)% | 경고시간중앙(초) | 정차중 켜진 초")
        for ((setName, routes) in sets) {
            for ((scName, mk) in scenarios) {
                val st = simulate(grid, routes, mk, noiseM, 5)
                if (System.getenv("DROPS") != null && scName == System.getenv("DROPS")) st.dropEx.forEach { println("RESULT   끊김 예) $it") }
                if (System.getenv("DIAG") != null && scName == System.getenv("DIAG")) { println("RESULT   놓친 원인($setName $scName): ${st.missReason}"); st.missEx.forEach { println("RESULT     예) $it") } }
                val med = if (st.warn.isEmpty()) 0.0 else st.warn.sorted()[st.warn.size / 2]
                println(
                    "RESULT $setName | $scName | ${st.km.toInt()} | ${st.episodes} | ${100 * st.bad / maxOf(1, st.episodes)}% | " +
                        "${st.missed}/${st.onRouteCams} | ${st.dropouts} | ${100 * st.shortWarn / maxOf(1, st.warnN)}% | ${"%.1f".format(med)} | ${st.stoppedAlertSec}"
                )
            }
        }
    }

    /** TRACE="경로파일|경로번호|카메라키" 로 그 카메라 앞 1초 단위 동작을 출력한다 (50km/h, GPS 흔들림 없음) */
    @Test fun traceOneMiss() {
        val env = System.getenv("TRACE")
        assumeTrue(env != null)
        val (file, ri, ck) = env!!.split('|')
        val grid = File("src/main/assets/cameras.grid.txt").bufferedReader().useLines { CameraGrid.parse(it) }
        val r = load(File(file))[ri.toInt()]
        val ix = r.onRoute[ck]!!
        val la = ck.substringBefore(',').toDouble()
        val lo = ck.substringAfter(',').toDouble()
        val cam = grid.near(la, lo).first { key(it.lat, it.lon) == ck }
        println("TR camera limit=${cam.limit} bearing=${cam.bearing} paths=${cam.paths.size} weak=${cam.paths.count { it.rangeM < 1e9 }} junctions=${cam.paths.map { it.junctions.toList() }}")
        val e = DetectionEngine(grid)
        val v = (System.getenv("TRACE_V") ?: "13.9").toDouble()
        var s = 0.0
        val n = r.pts.size
        val cum = DoubleArray(n)
        for (i in 1 until n) cum[i] = cum[i - 1] + Geo.distanceM(r.pts[i - 1][0], r.pts[i - 1][1], r.pts[i][0], r.pts[i][1])
        var seg = 0
        var t = 0L
        var hd = 0.0
        while (s < cum[n - 1]) {
            s += v
            while (seg < n - 2 && cum[seg + 1] < s) seg++
            val f = if (cum[seg + 1] > cum[seg]) (s - cum[seg]) / (cum[seg + 1] - cum[seg]) else 0.0
            val pla = r.pts[seg][0] + f * (r.pts[seg + 1][0] - r.pts[seg][0])
            val plo = r.pts[seg][1] + f * (r.pts[seg + 1][1] - r.pts[seg][1])
            hd = Geo.bearingDeg(r.pts[seg][0], r.pts[seg][1], r.pts[seg + 1][0], r.pts[seg + 1][1])
            t += 1000L
            val o = e.update(Fix(pla, plo, v.toFloat(), hd.toFloat(), true, t))
            val camRemain = ix * 20.0 - s
            if (camRemain in -60.0..500.0) {
                val d = Geo.distanceM(pla, plo, la, lo).toInt()
                val matches = cam.paths.map { it.matchRemaining(pla, plo, hd, 30.0, 45.0)?.toInt() }
                println("TR remain=${camRemain.toInt()} straight=$d hd=${hd.toInt()} stage=${o.stage} alertCam=${o.camLat?.let { key(it, o.camLon!!) }} rule=${o.rule} pathMatches=$matches")
            }
        }
    }
}
