package com.nunppong.alert.core

import android.content.Context
import kotlin.math.floor

interface CameraSource {
    /** 현재 위치 주변 격자 9칸의 카메라 후보 */
    fun near(lat: Double, lon: Double): List<Camera>
}

/** 0.01도 격자 인덱스. 파일 형식은 tools/convert_cameras.py 참고. */
class CameraGrid(private val cells: Map<Long, List<Camera>>, val count: Int) : CameraSource {
    override fun near(lat: Double, lon: Double): List<Camera> {
        val la = floor(lat * 100.0).toInt()
        val lo = floor(lon * 100.0).toInt()
        val out = ArrayList<Camera>()
        for (dy in -1..1) for (dx in -1..1) {
            cells[key(la + dy, lo + dx)]?.let { out.addAll(it) }
        }
        return out
    }

    companion object {
        fun key(latIdx: Int, lonIdx: Int): Long = latIdx.toLong() * 100000L + lonIdx

        fun parse(lines: Sequence<String>): CameraGrid {
            val cells = HashMap<Long, MutableList<Camera>>()
            var cur: MutableList<Camera>? = null
            var id = 0
            var paths: MutableList<CamPath>? = null
            for (raw in lines) {
                val line = raw.trim()
                if (line.isEmpty() || line[0] == '#') continue
                val p = line.split(' ')
                when (p[0]) {
                    "G" -> cur = cells.getOrPut(key(p[1].toInt(), p[2].toInt())) { ArrayList() }
                    "C" -> {
                        val list = ArrayList<CamPath>(0)
                        paths = list
                        cur?.add(Camera(id++, p[1].toDouble(), p[2].toDouble(), p[3].toInt(), p[4].toInt(), list))
                    }
                    // 도로 선: 첫 점은 (위도, 경도)×1e5 절대값, 이후는 앞 점과의 차
                    "P" -> parsePath(p)?.let { paths?.add(it) }
                }
            }
            return CameraGrid(cells, id)
        }

        private fun parsePath(p: List<String>): CamPath? {
            val n = (p.size - 1) / 2
            if (n < 2) return null
            return try {
                val lat = DoubleArray(n)
                val lon = DoubleArray(n)
                var la = 0L
                var lo = 0L
                for (i in 0 until n) {
                    la += p[1 + i * 2].toLong()
                    lo += p[2 + i * 2].toLong()
                    lat[i] = la / 1e5
                    lon[i] = lo / 1e5
                }
                CamPath(lat, lon)
            } catch (e: NumberFormatException) {
                null   // 깨진 줄은 무시 (그 카메라는 예전 방식으로 판단)
            }
        }
    }
}

/** assets/cameras.grid.txt 를 한 번만 읽어 보관한다. */
object CameraRepository {
    @Volatile private var grid: CameraGrid? = null

    fun load(context: Context): CameraGrid {
        grid?.let { return it }
        synchronized(this) {
            grid?.let { return it }
            val g = try {
                context.applicationContext.assets.open("cameras.grid.txt").bufferedReader().useLines { CameraGrid.parse(it) }
            } catch (e: Exception) {
                CameraGrid(emptyMap(), 0)
            }
            grid = g
            return g
        }
    }
}
