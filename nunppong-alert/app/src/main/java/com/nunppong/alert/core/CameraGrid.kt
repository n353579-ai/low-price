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
            for (raw in lines) {
                val line = raw.trim()
                if (line.isEmpty() || line[0] == '#') continue
                val p = line.split(' ')
                when (p[0]) {
                    "G" -> cur = cells.getOrPut(key(p[1].toInt(), p[2].toInt())) { ArrayList() }
                    "C" -> cur?.add(
                        Camera(id++, p[1].toDouble(), p[2].toDouble(), p[3].toInt(), p[4].toInt())
                    )
                }
            }
            return CameraGrid(cells, id)
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
