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
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import com.nunppong.alert.MainActivity
import com.nunppong.alert.R
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.nunppong.alert.core.AlertStyle
import com.nunppong.alert.core.CameraFeedback
import com.nunppong.alert.core.PassEvent
import com.nunppong.alert.core.CameraRepository
import com.nunppong.alert.core.DetectionEngine
import com.nunppong.alert.core.Fix
import com.nunppong.alert.core.Mode
import com.nunppong.alert.core.Prefs
import com.nunppong.alert.core.Reason
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
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class DrivingService : Service() {
    companion object {
        const val ACTION_START = "com.nunppong.alert.START"
        const val ACTION_PASSENGER = "com.nunppong.alert.PASSENGER"
        const val ACTION_RESUME = "com.nunppong.alert.RESUME"
        const val EXTRA_REASON = "reason"
        const val ACTION_SIM = "com.nunppong.alert.SIM"
        const val ACTION_STOP = "com.nunppong.alert.STOP"
        private const val CHANNEL = "driving"
        private const val NOTI_ID = 1
        const val FEEDBACK_NOTI_ID = 2
        private const val FEEDBACK_CHANNEL = "feedback"

        fun startReal(ctx: Context, reason: Reason = Reason.MANUAL) {
            ctx.startForegroundService(
                Intent(ctx, DrivingService::class.java).setAction(ACTION_START).putExtra(EXTRA_REASON, reason.name)
            )
        }

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
    private val fused by lazy { LocationServices.getFusedLocationProviderClient(this) }
    private var locationCallback: LocationCallback? = null
    private var lastSavedPos = 0L
    private var currentReason = Reason.MANUAL
    private var isSim = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        overlay = OverlayController(this)
        prefs = Prefs(this)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "단속 알림 동작 상태", NotificationManager.IMPORTANCE_LOW)
        )
        nm.createNotificationChannel(
            NotificationChannel(FEEDBACK_CHANNEL, "단속 맞았나요? 질문", NotificationManager.IMPORTANCE_LOW).apply {
                setSound(null, null)
                enableVibration(false)
            }
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
            ACTION_START -> startReal(
                runCatching { Reason.valueOf(intent?.getStringExtra(EXTRA_REASON) ?: "") }.getOrDefault(Reason.MANUAL)
            )
            ACTION_PASSENGER -> if (!isSim) enterPassenger() else Unit
            ACTION_RESUME -> if (!isSim) startReal(currentReason) else Unit
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
            .apply {
                if (!isSim) {
                    val passenger = prefs.isPassengerActive()
                    val pi = PendingIntent.getService(
                        this@DrivingService, 2,
                        Intent(this@DrivingService, DrivingService::class.java)
                            .setAction(if (passenger) ACTION_RESUME else ACTION_PASSENGER),
                        PendingIntent.FLAG_IMMUTABLE
                    )
                    addAction(Notification.Action.Builder(null, if (passenger) "승객 모드 해제" else "지금은 승객(1시간 끄기)", pi).build())
                }
            }
            .addAction(Notification.Action.Builder(null, "종료", stop).build())
            .build()
    }

    private fun updateNotification(text: String) {
        if (text == lastNotiText) return
        lastNotiText = text
        getSystemService(NotificationManager::class.java).notify(NOTI_ID, buildNotification(text))
    }

    // ---- 실제 주행: GPS 1초 간격 -> 판단 엔진 -> 오버레이 ----
    private fun startReal(reason: Reason) {
        isSim = false
        if (reason == Reason.MANUAL) {
            prefs.passengerUntil = 0L                     // 직접 시작하면 승객 모드 해제
        } else if (prefs.isPassengerActive()) {
            stopSelf()                                    // 자동 시작인데 승객 모드면 시작하지 않는다
            return
        }
        currentReason = reason
        stopWork()
        lastNotiText = ""
        StatusRepo.status.value = Status(Mode.REAL, reason = reason)
        updateNotification("주행 중 · 앞쪽 단속 없음")
        job = scope.launch {
            val grid = withContext(Dispatchers.Default) { CameraRepository.load(this@DrivingService) }
            StatusRepo.cameraCount.value = grid.count
            val feedback = CameraFeedback.get(this@DrivingService)
            val engine = DetectionEngine(grid) { c, h -> feedback.isBlocked(c, h) }
            val cb = object : LocationCallback() {
                override fun onLocationResult(result: LocationResult) {
                    for (loc in result.locations) {
                        val fix = Fix(
                            loc.latitude, loc.longitude,
                            if (loc.hasSpeed()) loc.speed else 0f,
                            loc.bearing, loc.hasBearing(),
                            SystemClock.elapsedRealtime(),
                        )
                        val o = engine.update(fix)
                        wakeIfNeeded(o.stage)
                        engine.takePassed()?.let { askFeedback(it) }
                        val show = !prefs.overspeedOnly || o.limit <= 0 || o.speedKmh > o.limit + 5
                        overlay.update(if (show) o.stage else Stage.HIDDEN, o.overspeed, prefs.isNightNow(), prefs.brightness, prefs.thickness, prefs.sidesOnly, prefs.yellowColor, prefs.redColor, prefs.character)
                        val st = Status(Mode.REAL, o.stage, o.distanceM?.toInt(), o.speedKmh.toInt(), o.overspeed, currentReason)
                        StatusRepo.status.value = st
                        updateNotification("주행 중 · " + stageText(st))
                        if (fix.timeMs - lastSavedPos > 60_000) { // 낮/밤 계산용으로 마지막 위치 저장
                            lastSavedPos = fix.timeMs
                            prefs.lastLat = loc.latitude
                            prefs.lastLon = loc.longitude
                        }
                    }
                }
            }
            locationCallback = cb
            val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L)
                .setMinUpdateIntervalMillis(1000L)
                .build()
            try {
                fused.requestLocationUpdates(req, cb, Looper.getMainLooper())
            } catch (e: SecurityException) {
                Log.e("DrivingService", "no location permission", e)
                stopSelf()
            }
        }
    }

    private var wakeStage = Stage.HIDDEN
    private var lastWakeAt = 0L

    /** 화면이 꺼진 채로 단속 구간에 새로 가까워지면(숨김→1km 등 단계 상승) 화면을 5초 켠다. */
    @Suppress("DEPRECATION")
    private fun wakeIfNeeded(stage: Stage) {
        val rose = stage.ordinal > wakeStage.ordinal
        wakeStage = stage
        if (!rose || !prefs.wakeScreen) return
        val pm = getSystemService(PowerManager::class.java)
        if (pm.isInteractive) return
        val nowMs = SystemClock.elapsedRealtime()
        if (nowMs - lastWakeAt < 60_000L) return   // 카메라가 연달아 있어도 1분에 한 번만 (배터리)
        lastWakeAt = nowMs
        try {
            pm.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "nunppong:wake"
            ).acquire(5_000L)
        } catch (e: Exception) {
            Log.w("DrivingService", "wake failed", e)
        }
    }

    /** 카메라를 지난 뒤 "방금 단속 맞았나요?" 조용한 알림 (60초 뒤 저절로 사라짐) */
    private fun askFeedback(e: PassEvent) {
        fun pi(down: Boolean) = PendingIntent.getBroadcast(
            this, if (down) 11 else 10,
            Intent(this, FeedbackReceiver::class.java)
                .putExtra(FeedbackReceiver.EXTRA_LAT, e.camera.lat)
                .putExtra(FeedbackReceiver.EXTRA_LON, e.camera.lon)
                .putExtra(FeedbackReceiver.EXTRA_HEADING, e.heading)
                .putExtra(FeedbackReceiver.EXTRA_DOWN, down),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = Notification.Builder(this, FEEDBACK_CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("방금 단속 맞았나요?")
            .setContentText("아니면 👎 — 이 방향에서는 이 카메라를 끕니다")
            .setTimeoutAfter(60_000L)
            .setAutoCancel(true)
            .addAction(Notification.Action.Builder(null, "👍 맞아요", pi(false)).build())
            .addAction(Notification.Action.Builder(null, "👎 아니에요", pi(true)).build())
            .build()
        getSystemService(NotificationManager::class.java).notify(FEEDBACK_NOTI_ID, n)
    }

    /** 승객 모드: 위치 수집과 오버레이를 멈추고 1시간 뒤 자동 재개 */
    private fun enterPassenger() {
        if (!prefs.isPassengerActive()) prefs.passengerUntil = System.currentTimeMillis() + Passenger.DURATION_MS
        stopWork()
        overlay.update(Stage.HIDDEN, false, false, 1f, 1f)
        val until = prefs.passengerUntil
        StatusRepo.status.value = Status(Mode.REAL, reason = currentReason, passengerUntil = until)
        lastNotiText = ""
        updateNotification("승객 모드 · " + SimpleDateFormat("HH:mm", Locale.KOREA).format(Date(until)) + "까지 꺼짐")
        job = scope.launch {
            delay((until - System.currentTimeMillis()).coerceAtLeast(0L))
            prefs.passengerUntil = 0L
            startReal(currentReason)
        }
    }

    private fun stopWork() {
        job?.cancel()
        locationCallback?.let { fused.removeLocationUpdates(it) }
        locationCallback = null
    }

    // ---- 시뮬레이션: 1.5km 앞에서 출발해 카메라를 지나간다 ----
    private fun startSim() {
        isSim = true
        stopWork()
        job = scope.launch {
            var dist = 1500.0
            while (isActive) {
                val speed = StatusRepo.simSpeedKmh.value
                val limit = StatusRepo.simLimitKmh.value
                val scale = if (StatusRepo.simFast.value) 3.0 else 1.0
                dist -= speed / 3.6 * 0.1 * scale

                val stage = AlertStyle.stageForDistance(dist)
                val over = stage == Stage.S3 && speed > limit
                val show = !prefs.overspeedOnly || speed > limit + 5
                overlay.update(if (show) stage else Stage.HIDDEN, over, prefs.isNightNow(), prefs.brightness, prefs.thickness, prefs.sidesOnly, prefs.yellowColor, prefs.redColor, prefs.character)

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
        stopWork()
        scope.cancel()
        overlay.remove()
        StatusRepo.status.value = Status()
        super.onDestroy()
    }
}

fun stageText(s: Status): String {
    if (s.passengerUntil > 0) {
        return "승객 모드 (" + SimpleDateFormat("HH:mm", Locale.KOREA).format(Date(s.passengerUntil)) + "까지)"
    }
    val d = s.distanceM?.let { " · ${it}m" } ?: ""
    return when (s.stage) {
        Stage.HIDDEN -> "앞쪽 단속 없음"
        Stage.S1 -> "1km 앞 단속$d"
        Stage.S2 -> "500m 앞 단속$d"
        Stage.S3 -> (if (s.overspeed) "300m 앞 단속 · 과속!" else "300m 앞 단속") + d
    }
}
