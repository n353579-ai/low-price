package com.nunppong.alert.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.os.SystemClock
import android.view.View
import com.nunppong.alert.core.AlertStyle
import com.nunppong.alert.core.Stage
import kotlin.math.pow

/** 화면 테두리만 그리는 오버레이 View. 테두리 바깥(가운데)은 아무것도 그리지 않는다. */
class BorderOverlayView(context: Context) : View(context) {
    private var stage = Stage.HIDDEN
    private var overspeed = false
    private var night = false
    private var brightness = 1f
    private var thickness = 1f
    private var lastPhase: AlertStyle.Phase? = null

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    private val ticker = object : Runnable {
        override fun run() {
            val p = AlertStyle.phase(stage, SystemClock.uptimeMillis())
            if (p != lastPhase) {
                lastPhase = p
                invalidate()
            }
            if (stage != Stage.HIDDEN && isAttachedToWindow) postOnAnimation(this)
        }
    }

    fun setState(stage: Stage, overspeed: Boolean, night: Boolean, brightness: Float, thickness: Float) {
        this.stage = stage
        this.overspeed = overspeed
        this.night = night
        this.brightness = brightness
        this.thickness = thickness
        removeCallbacks(ticker)
        lastPhase = null
        visibility = if (stage == Stage.HIDDEN) INVISIBLE else VISIBLE
        invalidate()
        if (stage != Stage.HIDDEN) postOnAnimation(ticker)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(ticker)
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        if (stage == Stage.HIDDEN) return
        val phase = AlertStyle.phase(stage, SystemClock.uptimeMillis())
        if (phase == AlertStyle.Phase.OFF) return

        val base = AlertStyle.color(phase, night)
        val alphaScale = brightness * (if (night) AlertStyle.NIGHT_FACTOR else 1f)
        val w = AlertStyle.widthDp(stage, overspeed) * thickness * resources.displayMetrics.density
        val wf = width.toFloat()
        val hf = height.toFloat()

        // 1) 단색 테두리 띠
        paint.color = base
        paint.alpha = (255 * alphaScale).toInt().coerceIn(0, 255)
        paint.strokeWidth = w
        canvas.drawRect(w / 2, w / 2, wf - w / 2, hf - w / 2, paint)

        // 2) 안쪽으로 번지는 글로우 (점점 옅어지는 동심 사각형)
        val glow = w * 1.6f
        val n = 8
        val step = glow / n
        for (i in 0 until n) {
            val inset = w + step * i + step / 2
            val a = 0.55f * (1f - i.toFloat() / n).pow(2)
            paint.color = base
            paint.alpha = (255 * alphaScale * a).toInt().coerceIn(0, 255)
            paint.strokeWidth = step + 0.5f
            canvas.drawRect(inset, inset, wf - inset, hf - inset, paint)
        }
    }
}
