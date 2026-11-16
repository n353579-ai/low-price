package com.nunppong.alert.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.nunppong.alert.MainActivity
import com.nunppong.alert.R
import com.nunppong.alert.core.AlertStyle
import com.nunppong.alert.core.Mode
import com.nunppong.alert.core.Prefs
import com.nunppong.alert.core.Stage
import com.nunppong.alert.core.Status
import com.nunppong.alert.core.StatusRepo
import com.nunppong.alert.overlay.OverlayController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class DrivingService : Service() {
    companion object {
        const val ACTION_SIM = "com.nunppong.alert.SIM"
        const val ACTION_STOP = "com.nunppong.alert.STOP"
        private const val CHANNEL = "driving"
        private const val NOTI_ID = 1

        fun startSim(ctx: Context) {
            ctx.startForegroundService(Intent(ctx, DrivingService::class.java).setAction(ACTION_SIM))
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, DrivingService::class.java))
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null
    private lateinit var overlay: OverlayController
    private lateinit var prefs: Prefs
    private var lastNotiText = ""

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        overlay = OverlayController(this)
        prefs = Prefs(this)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "단속 알림 동작 상태", NotificationManager.IMPORTANCE_LOW)
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!goForeground("준비 중")) {
            stopSelf()
            return START_NOT_STICKY
        }
        when (intent?.action) {
            ACTION_SIM -> startSim()
            else -> stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun goForeground(text: String): Boolean = try {
        val n = buildNotification(text)
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTI_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTI_ID, n)
        }
        true
    } catch (e: Exception) {
        Log.e("DrivingService", "startForeground failed", e)
        false
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, DrivingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("눈뽕 단속알림")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "종료", stop).build())
            .build()
    }

    private fun updateNotification(text: String) {
        if (text == lastNotiText) return
        lastNotiText = text
        getSystemService(NotificationManager::class.java).notify(NOTI_ID, buildNotification(text))
    }

    // ---- 시뮬레이션: 1.5km 앞에서 출발해 카메라를 지나간다 ----
    private fun startSim() {
        job?.cancel()
        job = scope.launch {
            var dist = 1500.0
            while (isActive) {
                val speed = StatusRepo.simSpeedKmh.value
                val limit = StatusRepo.simLimitKmh.value
                val scale = if (StatusRepo.simFast.value) 3.0 else 1.0
                dist -= speed / 3.6 * 0.1 * scale

                val stage = AlertStyle.stageForDistance(dist)
                val over = stage == Stage.S3 && speed > limit
                overlay.update(stage, over, prefs.isNightNow(), prefs.brightness, prefs.thickness)

                val st = Status(Mode.SIM, stage, if (dist >= 0) dist.toInt() else null, speed, over)
                StatusRepo.status.value = st
                updateNotification("시뮬레이션 · " + stageText(st))

                if (dist < -100) { // 통과 후 잠깐 쉬고 처음부터 반복
                    overlay.update(Stage.HIDDEN, false, false, 1f, 1f)
                    delay(2000)
                    dist = 1500.0
                }
                delay(100)
            }
        }
    }

    override fun onDestroy() {
        job?.cancel()
        scope.cancel()
        overlay.remove()
        StatusRepo.status.value = Status()
        super.onDestroy()
    }
}

fun stageText(s: Status): String {
    val d = s.distanceM?.let { " · ${it}m" } ?: ""
    return when (s.stage) {
        Stage.HIDDEN -> "앞쪽 단속 없음"
        Stage.S1 -> "1km 앞 단속$d"
        Stage.S2 -> "500m 앞 단속$d"
        Stage.S3 -> (if (s.overspeed) "300m 앞 단속 · 과속!" else "300m 앞 단속") + d
    }
}
