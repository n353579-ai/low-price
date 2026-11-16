package com.nunppong.alert.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import com.nunppong.alert.core.Stage

class OverlayController(context: Context) {
    private val ctx = context.applicationContext
    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var view: BorderOverlayView? = null

    fun update(
        stage: Stage, overspeed: Boolean, night: Boolean, brightness: Float, thickness: Float,
        sidesOnly: Boolean = false,
    ) {
        if (view == null) {
            if (stage == Stage.HIDDEN || !Settings.canDrawOverlays(ctx)) return
            val v = BorderOverlayView(ctx)
            try {
                wm.addView(v, params())
                view = v
            } catch (e: Exception) {
                Log.w("Overlay", "addView failed", e)
                return
            }
        }
        view?.setState(stage, overspeed, night, brightness, thickness, sidesOnly)
    }

    fun remove() {
        view?.let {
            try { wm.removeView(it) } catch (_: Exception) {}
        }
        view = null
    }

    private fun params(): WindowManager.LayoutParams {
        val flags = WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            flags,
            PixelFormat.TRANSLUCENT,
        ).apply {
            // Android 12+ : 터치 통과를 허용받으려면 창 알파가 0.8 이하여야 한다.
            alpha = 0.8f
            gravity = Gravity.TOP or Gravity.START
            title = "NunppongBorder"
            if (Build.VERSION.SDK_INT >= 30) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            } else if (Build.VERSION.SDK_INT >= 28) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
    }
}
