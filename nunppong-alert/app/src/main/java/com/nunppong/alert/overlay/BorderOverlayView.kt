package com.nunppong.alert.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import androidx.core.content.res.ResourcesCompat
import com.nunppong.alert.core.CharacterStyle
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
    private var yellow = AlertStyle.YELLOW_DAY
    private var red = AlertStyle.RED_DAY
    private var lastPhase: AlertStyle.Phase? = null
    private var charOn = false
    private var calmLimit = 0
    private var signLimit = 0
    private var distM = -1
    private var proximity = 0f
    private var spread = 1f
    private var charStage = Stage.HIDDEN
    private var charStart = -100000L
    private var prevStage = Stage.HIDDEN
    private val rect = RectF()
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
        color = 0xFFFFFFFF.toInt()
    }
    private val charTicker = object : Runnable {
        override fun run() {
            invalidate()
            if (SystemClock.uptimeMillis() - charStart < CharacterStyle.SHOW_MS && isAttachedToWindow) postOnAnimation(this)
        }
    }

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
        sidesOnly: Boolean = false, yellow: Int = AlertStyle.YELLOW_DAY, red: Int = AlertStyle.RED_DAY,
        character: Boolean = false, proximity: Float = 0f, spread: Float = 1f,
        calmLimit: Int = 0, distanceM: Int = -1, signLimit: Int = 0,
    ) {
        this.calmLimit = calmLimit
        this.signLimit = signLimit
        this.distM = distanceM
        this.proximity = proximity
        this.spread = spread
        charOn = false   // 캐릭터는 뺐다 (제한속도 표지판·거리 숫자로 대체)
        if (false && character && CharacterStyle.shouldShow(prevStage, stage)) {
            charStage = stage
            charStart = SystemClock.uptimeMillis()
            removeCallbacks(charTicker)
            postOnAnimation(charTicker)
        }
        prevStage = stage
        this.sidesOnly = sidesOnly
        this.yellow = yellow
        this.red = red
        this.stage = stage
        this.overspeed = overspeed
        this.night = night
        this.brightness = brightness
        this.thickness = thickness
        removeCallbacks(ticker)
        lastPhase = null
        visibility = if (stage == Stage.HIDDEN && !charShowing()) INVISIBLE else VISIBLE
        invalidate()
        if (stage != Stage.HIDDEN) postOnAnimation(ticker)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(ticker)
        removeCallbacks(charTicker)
        super.onDetachedFromWindow()
    }

    private fun charShowing() = charOn && SystemClock.uptimeMillis() - charStart < CharacterStyle.SHOW_MS

    override fun onDraw(canvas: Canvas) {
        if (calmLimit <= 0) drawBorder(canvas)   // 제한속도를 안 지키는 중(또는 모름)이면 테두리도 깜빡인다
        drawSign(canvas)                          // 표지판과 거리는 알림 중에는 늘 크게
    }

    private val signText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }

    /**
     * 알림 중에는 늘: 화면 위쪽에 아주 큰 제한속도 표지판(빨간 테두리 원)과 그 아래 아주 큰 남은 거리.
     * 운전 중 잠깐 흘끗 봐도 읽히게 화면 폭의 약 60% 크기로 그린다. 거리 상자는 단계 색(노랑/빨강).
     */
    private fun drawSign(canvas: Canvas) {
        if (stage == Stage.HIDDEN) return
        val dim = brightness * (if (night) AlertStyle.NIGHT_FACTOR else 1f)
        val a = (255 * dim).toInt().coerceIn(0, 255)
        val base = minOf(width, height).toFloat()
        val r = base * 0.30f
        val cx = width / 2f
        val cy = height * 0.04f + r + base * 0.04f
        var bottom = cy - r
        if (signLimit > 0) {
            fill.color = 0xFFFFFFFF.toInt(); fill.alpha = a
            canvas.drawCircle(cx, cy, r, fill)
            val ring = r * 0.17f
            paint.color = 0xFFE53935.toInt(); paint.alpha = a
            paint.strokeWidth = ring
            canvas.drawCircle(cx, cy, r - ring / 2f, paint)
            signText.color = 0xFF111111.toInt(); signText.alpha = a
            signText.textSize = if (signLimit >= 100) r * 0.78f else r * 0.95f
            canvas.drawText(signLimit.toString(), cx, cy + signText.textSize * 0.35f, signText)
            bottom = cy + r
        }
        if (distM >= 0) {
            val label = if (distM >= 1000) String.format(java.util.Locale.US, "%.1fkm", distM / 1000f) else "${(distM / 10) * 10}m"
            text.textSize = base * 0.26f
            val tw = text.measureText(label)
            val top = bottom + base * 0.03f
            rect.set(cx - tw / 2 - base * 0.05f, top, cx + tw / 2 + base * 0.05f, top + base * 0.30f)
            val isYellow = stage == Stage.S1
            fill.color = if (isYellow) yellow else red
            fill.alpha = a
            canvas.drawRoundRect(rect, base * 0.06f, base * 0.06f, fill)
            text.color = if (isYellow) 0xFF111111.toInt() else 0xFFFFFFFF.toInt()
            text.alpha = a
            canvas.drawText(label, cx, rect.centerY() + text.textSize * 0.35f, text)
        }
    }

    /** 화면에 꽉 차게(폭의 90%) 2초 동안 톡 튀어나왔다 쏙 들어간다. 터치는 통과. */
    private fun drawCharacter(canvas: Canvas) {
        if (!charOn || charStage == Stage.HIDDEN) return
        val a = CharacterStyle.alpha(SystemClock.uptimeMillis() - charStart)
        if (a <= 0f) return
        val d = resources.displayMetrics.density
        val size = minOf(width, height) * 0.9f
        val k = size / (80f * d)
        val cx = width / 2f
        val cy = height * 0.5f
        val sc = CharacterStyle.scale(SystemClock.uptimeMillis() - charStart)
        val alpha = (255 * a).toInt().coerceIn(0, 255)
        canvas.save()
        canvas.scale(sc, sc, cx, cy)
        // res/drawable 에 char_s1 / char_s2 / char_s3 (png 등)를 넣으면 그 그림을 쓴다.
        val resId = resources.getIdentifier("char_" + charStage.name.lowercase(), "drawable", context.packageName)
        if (resId != 0) {
            ResourcesCompat.getDrawable(resources, resId, null)?.let { dr ->
                dr.setBounds((cx - size / 2).toInt(), (cy - size / 2).toInt(), (cx + size / 2).toInt(), (cy + size / 2).toInt())
                dr.alpha = alpha
                dr.draw(canvas)
                canvas.restore()
                return
            }
        }

        // 기본 그림: 손가락 N개 + 거리 글자
        val n = CharacterStyle.fingers(charStage)
        val skin = 0xFFFFD9A8.toInt()
        val line = 0xFF3A2A1A.toInt()
        val fw = size * 0.13f
        val gap = size * 0.03f
        val total = n * fw + (n - 1) * gap
        val palmTop = cy - size * 0.05f
        // 손바닥
        rect.set(cx - size * 0.32f, palmTop, cx + size * 0.32f, palmTop + size * 0.40f)
        fill.color = skin
        fill.alpha = alpha
        canvas.drawRoundRect(rect, size * 0.16f, size * 0.16f, fill)
        paint.strokeWidth = 2f * d * k
        paint.color = line
        paint.alpha = alpha
        canvas.drawRoundRect(rect, size * 0.16f, size * 0.16f, paint)
        // 손가락
        for (i in 0 until n) {
            val left = cx - total / 2 + i * (fw + gap)
            val h = size * (if (n == 1) 0.45f else if (i == n / 2) 0.38f else 0.30f)
            rect.set(left, palmTop - h, left + fw, palmTop + size * 0.08f)
            fill.color = skin
            fill.alpha = alpha
            canvas.drawRoundRect(rect, fw / 2, fw / 2, fill)
            paint.color = line
            paint.alpha = alpha
            canvas.drawRoundRect(rect, fw / 2, fw / 2, paint)
        }
        // 거리 글자 (어두운 알약 위에 흰 글씨 - 어떤 지도 위에서도 보이게)
        val label = CharacterStyle.label(charStage)
        text.textSize = 20f * d * k
        val tw = text.measureText(label)
        rect.set(cx - tw / 2 - 10 * d * k, palmTop + size * 0.46f, cx + tw / 2 + 10 * d * k, palmTop + size * 0.46f + 28 * d * k)
        fill.color = 0xFF000000.toInt()
        fill.alpha = (alpha * 0.75f).toInt()
        canvas.drawRoundRect(rect, 14 * d * k, 14 * d * k, fill)
        text.alpha = alpha
        canvas.drawText(label, cx, rect.bottom - 6 * d * k, text)
        canvas.restore()
    }

    private fun drawBorder(canvas: Canvas) {
        if (stage == Stage.HIDDEN) return
        val phase = AlertStyle.phase(stage, SystemClock.uptimeMillis())
        if (phase == AlertStyle.Phase.OFF) return

        val base = AlertStyle.color(phase, night, yellow, red)
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
        val glow = AlertStyle.glowPx(w, wf, proximity, spread)
        val n = 14
        val step = glow / n
        val peak = AlertStyle.glowPeakAlpha(proximity, spread)
        for (i in 0 until n) {
            val inset = w + step * i + step / 2
            val a = peak * (1f - i.toFloat() / n).pow(2)
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
        val glow = AlertStyle.glowPx(w, wf, proximity, spread)
        val n = 14
        val step = glow / n
        val peak = AlertStyle.glowPeakAlpha(proximity, spread)
        for (i in 0 until n) {
            val a = peak * (1f - i.toFloat() / n).pow(2)
            fill.alpha = (255 * alphaScale * a).toInt().coerceIn(0, 255)
            val x = w + step * i
            canvas.drawRect(x, 0f, x + step + 0.5f, hf, fill)
            canvas.drawRect(wf - x - step - 0.5f, 0f, wf - x, hf, fill)
        }
    }
}
