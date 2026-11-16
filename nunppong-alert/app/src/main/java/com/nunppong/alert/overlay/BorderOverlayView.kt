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
    private var sidesOnly = false
    private var lastPhase: AlertStyle.Phase? = null

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

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

    fun setState(
        stage: Stage, overspeed: Boolean, night: Boolean, brightness: Float, thickness: Float,
        sidesOnly: Boolean = false,
    ) {
        this.sidesOnly = sidesOnly
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

        // 밝은 배경(흰 지도 등)에서도 노랑이 묻히지 않도록 띠 안쪽에 검은 가는 선을 둔다.
        // 전체 두께(w)는 그대로: 색 띠 (w - lw) + 검은 선 lw
        val lw = (2f * resources.displayMetrics.density).coerceAtMost(w / 3)
        val cw = w - lw
        val darkAlpha = (255 * 0.75f * brightness).toInt().coerceIn(0, 255)

        if (sidesOnly) {
            drawSides(canvas, base, alphaScale, w, cw, darkAlpha, wf, hf)
            return
        }

        // 1) 단색 테두리 띠 + 안쪽 검은 선
        paint.color = base
        paint.alpha = (255 * alphaScale).toInt().coerceIn(0, 255)
        paint.strokeWidth = cw
        canvas.drawRect(cw / 2, cw / 2, wf - cw / 2, hf - cw / 2, paint)
        paint.color = 0xFF000000.toInt()
        paint.alpha = darkAlpha
        paint.strokeWidth = lw
        canvas.drawRect(cw + lw / 2, cw + lw / 2, wf - cw - lw / 2, hf - cw - lw / 2, paint)

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

    /** 화면 좌우 가장자리만: 단색 띠 + 안쪽으로 번지는 글로우 */
    private fun drawSides(
        canvas: Canvas, base: Int, alphaScale: Float, w: Float, cw: Float, darkAlpha: Int, wf: Float, hf: Float,
    ) {
        fill.color = base
        fill.alpha = (255 * alphaScale).toInt().coerceIn(0, 255)
        canvas.drawRect(0f, 0f, cw, hf, fill)
        canvas.drawRect(wf - cw, 0f, wf, hf, fill)
        fill.color = 0xFF000000.toInt()
        fill.alpha = darkAlpha
        canvas.drawRect(cw, 0f, w, hf, fill)
        canvas.drawRect(wf - w, 0f, wf - cw, hf, fill)
        fill.color = base
        val glow = w * 1.6f
        val n = 8
        val step = glow / n
        for (i in 0 until n) {
            val a = 0.55f * (1f - i.toFloat() / n).pow(2)
            fill.alpha = (255 * alphaScale * a).toInt().coerceIn(0, 255)
            val x = w + step * i
            canvas.drawRect(x, 0f, x + step + 0.5f, hf, fill)
            canvas.drawRect(wf - x - step - 0.5f, 0f, wf - x, hf, fill)
        }
    }
}
