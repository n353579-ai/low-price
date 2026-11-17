package com.nunppong.alert.bt

import android.content.Context
import android.util.Log
import com.nunppong.alert.core.Mode
import com.nunppong.alert.core.Prefs
import com.nunppong.alert.core.Reason
import com.nunppong.alert.core.StatusRepo
import com.nunppong.alert.service.DrivingService
import com.nunppong.alert.ui.Perms

/** 내 차 연결/해제 이벤트(블루투스·차량 감지)를 받아 서비스를 켜고 끈다. */
object CarSession {
    fun onCarConnected(ctx: Context, reason: Reason) {
        if (Prefs(ctx).isPassengerActive()) return          // 승객 모드: 시작하지 않는다
        if (!Perms.readyToRun(ctx)) return                  // 권한이 없으면 조용히 무시
        if (StatusRepo.status.value.mode == Mode.REAL) return // 이미 동작 중
        try {
            DrivingService.startReal(ctx, reason)
        } catch (e: Exception) {                            // 백그라운드 시작 제한에 걸린 경우
            Log.w("CarSession", "start refused: ${e.javaClass.simpleName}")
        }
    }

    fun onCarDisconnected(ctx: Context, reason: Reason) {
        val s = StatusRepo.status.value
        if (s.mode == Mode.REAL && s.reason == reason) DrivingService.stop(ctx)
    }
}
