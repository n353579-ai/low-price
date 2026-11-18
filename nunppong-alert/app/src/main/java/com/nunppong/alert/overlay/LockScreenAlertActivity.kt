package com.nunppong.alert.overlay

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import com.nunppong.alert.core.AlertStyle
import com.nunppong.alert.core.Prefs
import com.nunppong.alert.core.Stage
import com.nunppong.alert.core.StatusRepo

/**
 * 잠금 화면 위에 테두리·캐릭터를 보여주는 투명 화면.
 * - 화면을 켜고(잠금 화면 위에 표시) 단속 구간 동안만 떠 있는다.
 * - 터치는 통과(NOT_TOUCHABLE)해서 아래 잠금 화면을 밀어 올려 잠금 해제할 수 있다.
 * - 잠금이 풀리거나 단속 구간이 끝나면 곧바로 스스로 닫혀서, 내비 터치를 절대 가로막지 않는다.
 */
class LockScreenAlertActivity : Activity() {
    private lateinit var view: BorderOverlayView
    private lateinit var prefs: Prefs
    private lateinit var km: KeyguardManager
    private val handler = Handler(Looper.getMainLooper())
    private var openedAt = 0L

    private val tick = object : Runnable {
        override fun run() {
            val st = StatusRepo.status.value
            val locked = km.isKeyguardLocked
            // 잠금이 풀렸거나(켜진 직후 잠깐은 기다림) 단속 구간이 끝났으면 닫는다.
            val settled = System.currentTimeMillis() - openedAt > 1500
            if ((settled && !locked) || st.stage == Stage.HIDDEN && settled) {
                finish()
                return
            }
            view.setState(
                st.stage, st.overspeed, prefs.isNightNow(), prefs.brightness, prefs.thickness,
                prefs.sidesOnly, prefs.yellowColor, prefs.redColor, prefs.character,
                AlertStyle.proximity(st.distanceM?.toDouble()), prefs.spread,
                AlertStyle.calmLimit(prefs.calmMode, st.limit, st.speedKmh.toDouble()), st.distanceM ?: -1,
            )
            handler.postDelayed(this, 300)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        km = getSystemService(KeyguardManager::class.java)
        openedAt = System.currentTimeMillis()
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        )
        window.setBackgroundDrawable(null)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= 28) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
        }
        view = BorderOverlayView(this)
        setContentView(view)
    }

    override fun onResume() {
        super.onResume()
        handler.removeCallbacks(tick)
        handler.post(tick)
    }

    override fun onPause() {
        handler.removeCallbacks(tick)
        super.onPause()
    }

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        super.onDestroy()
    }

    companion object {
        fun start(ctx: Context) {
            try {
                ctx.startActivity(
                    Intent(ctx, LockScreenAlertActivity::class.java).addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION or
                            Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS or Intent.FLAG_ACTIVITY_NO_HISTORY
                    )
                )
            } catch (e: Exception) {
                android.util.Log.w("LockScreenAlert", "start failed", e)
            }
        }
    }
}
