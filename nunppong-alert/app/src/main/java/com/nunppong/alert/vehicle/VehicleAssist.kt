package com.nunppong.alert.vehicle

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionRequest
import com.google.android.gms.location.ActivityTransitionResult
import com.google.android.gms.location.DetectedActivity
import com.nunppong.alert.bt.CarSession
import com.nunppong.alert.core.Prefs
import com.nunppong.alert.core.Reason

/** 보조 옵션(기본 꺼짐): 블루투스 없는 차용. 충전 연결 + IN_VEHICLE 감지 시 시작. */
object VehicleAssist {
    fun hasPermission(ctx: Context) = Build.VERSION.SDK_INT < 29 ||
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED

    private fun pending(ctx: Context): PendingIntent = PendingIntent.getBroadcast(
        ctx, 10, Intent(ctx, ActivityTransitionReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
    )

    /** 설정값에 맞춰 등록/해제 */
    fun sync(ctx: Context) {
        val client = ActivityRecognition.getClient(ctx)
        val pi = pending(ctx)
        try {
            if (Prefs(ctx).vehicleAssist && hasPermission(ctx)) {
                val req = ActivityTransitionRequest(
                    listOf(
                        ActivityTransition.Builder().setActivityType(DetectedActivity.IN_VEHICLE)
                            .setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_ENTER).build(),
                        ActivityTransition.Builder().setActivityType(DetectedActivity.IN_VEHICLE)
                            .setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_EXIT).build(),
                    )
                )
                client.requestActivityTransitionUpdates(req, pi)
            } else {
                client.removeActivityTransitionUpdates(pi)
            }
        } catch (e: Exception) {
            Log.w("VehicleAssist", "sync failed", e)
        }
    }

    fun isCharging(ctx: Context): Boolean {
        val i = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        return (i?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
    }
}

class ActivityTransitionReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (!ActivityTransitionResult.hasResult(intent)) return
        val result = ActivityTransitionResult.extractResult(intent) ?: return
        for (e in result.transitionEvents) {
            if (e.activityType != DetectedActivity.IN_VEHICLE) continue
            if (e.transitionType == ActivityTransition.ACTIVITY_TRANSITION_ENTER) {
                if (VehicleAssist.isCharging(ctx)) CarSession.onCarConnected(ctx, Reason.VEHICLE)
            } else {
                CarSession.onCarDisconnected(ctx, Reason.VEHICLE)
            }
        }
    }
}

/** 재부팅 후 차량 감지 재등록 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            VehicleAssist.sync(ctx)
            val addr = Prefs(ctx).btAddress
            if (addr.isNotEmpty()) com.nunppong.alert.bt.CdmHelper.startObserving(ctx, addr)
        }
    }
}
