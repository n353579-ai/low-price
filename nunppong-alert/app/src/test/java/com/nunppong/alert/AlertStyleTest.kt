package com.nunppong.alert

import com.nunppong.alert.core.AlertStyle
import com.nunppong.alert.core.AlertStyle.Phase
import com.nunppong.alert.core.Stage
import com.nunppong.alert.core.SunCalc
import org.junit.Assert.*
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class AlertStyleTest {
    @Test fun neverBlinksFasterThanTwoHz() {
        Stage.values().forEach { assertTrue(AlertStyle.hz(it) <= 2.0) }
    }

    @Test fun stageThresholds() {
        assertEquals(Stage.HIDDEN, AlertStyle.stageForDistance(1001.0))
        assertEquals(Stage.S1, AlertStyle.stageForDistance(1000.0))
        assertEquals(Stage.S1, AlertStyle.stageForDistance(501.0))
        assertEquals(Stage.S2, AlertStyle.stageForDistance(500.0))
        assertEquals(Stage.S2, AlertStyle.stageForDistance(301.0))
        assertEquals(Stage.S3, AlertStyle.stageForDistance(300.0))
        assertEquals(Stage.S3, AlertStyle.stageForDistance(0.0))
        assertEquals(Stage.HIDDEN, AlertStyle.stageForDistance(-1.0))
    }

    @Test fun phases() {
        assertEquals(Phase.YELLOW, AlertStyle.phase(Stage.S1, 0))
        assertEquals(Phase.OFF, AlertStyle.phase(Stage.S1, 600))
        assertEquals(Phase.YELLOW, AlertStyle.phase(Stage.S2, 0))
        assertEquals(Phase.RED, AlertStyle.phase(Stage.S2, 400))
        assertEquals(Phase.RED, AlertStyle.phase(Stage.S3, 0))
        assertEquals(Phase.OFF, AlertStyle.phase(Stage.S3, 300))
        assertEquals(Phase.OFF, AlertStyle.phase(Stage.HIDDEN, 0))
    }

    @Test fun widths() {
        assertEquals(12f, AlertStyle.widthDp(Stage.S3, false), 0f)
        assertEquals(20f, AlertStyle.widthDp(Stage.S3, true), 0f)
        assertEquals(12f, AlertStyle.widthDp(Stage.S2, true), 0f)
    }

    @Test fun sunSeoul() {
        val z = ZoneId.of("Asia/Seoul")
        val noon = ZonedDateTime.of(2026, 6, 21, 12, 0, 0, 0, z).toInstant().toEpochMilli()
        val midnight = ZonedDateTime.of(2026, 6, 21, 0, 0, 0, 0, z).toInstant().toEpochMilli()
        assertFalse(SunCalc.isNight(37.5665, 126.978, noon))
        assertTrue(SunCalc.isNight(37.5665, 126.978, midnight))
        assertTrue(SunCalc.solarElevationDeg(37.5665, 126.978, noon) > 60)
    }
}
