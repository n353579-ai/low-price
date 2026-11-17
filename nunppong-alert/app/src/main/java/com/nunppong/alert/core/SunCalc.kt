package com.nunppong.alert.core

import kotlin.math.*

/** 위치·시각 기준 일몰~일출(태양 고도 -0.833° 미만)이면 밤. */
object SunCalc {
    private fun mod(a: Double, m: Double) = ((a % m) + m) % m

    fun solarElevationDeg(lat: Double, lon: Double, epochMs: Long): Double {
        val jd = epochMs / 86400000.0 + 2440587.5
        val n = jd - 2451545.0
        val l = mod(280.460 + 0.9856474 * n, 360.0)
        val g = Math.toRadians(mod(357.528 + 0.9856003 * n, 360.0))
        val lambda = Math.toRadians(l + 1.915 * sin(g) + 0.020 * sin(2 * g))
        val eps = Math.toRadians(23.439 - 0.0000004 * n)
        val decl = asin(sin(eps) * sin(lambda))
        val ra = atan2(cos(eps) * sin(lambda), cos(lambda))
        val gmstHours = mod(18.697374558 + 24.06570982441908 * n, 24.0)
        val lst = Math.toRadians(mod(gmstHours * 15.0 + lon, 360.0))
        val ha = lst - ra
        val latR = Math.toRadians(lat)
        val sinEl = sin(latR) * sin(decl) + cos(latR) * cos(decl) * cos(ha)
        return Math.toDegrees(asin(sinEl.coerceIn(-1.0, 1.0)))
    }

    fun isNight(lat: Double, lon: Double, epochMs: Long): Boolean =
        solarElevationDeg(lat, lon, epochMs) < -0.833
}
