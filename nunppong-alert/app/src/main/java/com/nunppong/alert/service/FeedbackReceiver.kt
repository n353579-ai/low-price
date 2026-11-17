package com.nunppong.alert.service

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.nunppong.alert.core.CameraFeedback

/** 알림의 👍/👎 버튼 처리. 👎면 그 카메라를 그 진행방향에서 끈다. */
class FeedbackReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.getBooleanExtra(EXTRA_DOWN, false)) {
            CameraFeedback.get(context).block(
                intent.getDoubleExtra(EXTRA_LAT, 0.0),
                intent.getDoubleExtra(EXTRA_LON, 0.0),
                intent.getDoubleExtra(EXTRA_HEADING, 0.0),
            )
        }
        context.getSystemService(NotificationManager::class.java).cancel(DrivingService.FEEDBACK_NOTI_ID)
    }

    companion object {
        const val EXTRA_LAT = "lat"
        const val EXTRA_LON = "lon"
        const val EXTRA_HEADING = "heading"
        const val EXTRA_DOWN = "down"
    }
}
