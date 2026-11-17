package com.nunppong.alert.service

import android.content.Context
import android.content.Intent
import android.util.Log
import com.nunppong.alert.core.Mode
import com.nunppong.alert.core.Prefs
import com.nunppong.alert.core.StatusRepo

/** "지금은 승객(1시간 끄기)" — 알림 버튼 · 빠른 설정 타일 · 홈 버튼이 공통으로 쓴다. */
object Passenger {
    const val DURATION_MS = 60 * 60 * 1000L

    fun enable(ctx: Context) {
        Prefs(ctx).passengerUntil = System.currentTimeMillis() + DURATION_MS
        poke(ctx, DrivingService.ACTION_PASSENGER)
    }

    fun disable(ctx: Context) {
        Prefs(ctx).passengerUntil = 0L
        poke(ctx, DrivingService.ACTION_RESUME)
    }

    fun toggle(ctx: Context) {
        if (Prefs(ctx).isPassengerActive()) disable(ctx) else enable(ctx)
    }

    /** 서비스가 이미 돌고 있으면 알려준다 (안 돌고 있으면 플래그만 저장되어 있으면 충분) */
    private fun poke(ctx: Context, action: String) {
        if (StatusRepo.status.value.mode != Mode.REAL) return
        try {
            ctx.startService(Intent(ctx, DrivingService::class.java).setAction(action))
        } catch (e: Exception) {
            Log.w("Passenger", "poke failed", e)
        }
    }
}
