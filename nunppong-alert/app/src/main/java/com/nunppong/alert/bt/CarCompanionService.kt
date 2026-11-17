package com.nunppong.alert.bt

import android.companion.CompanionDeviceService
import androidx.annotation.RequiresApi
import com.nunppong.alert.core.Prefs
import com.nunppong.alert.core.Reason

/**
 * CompanionDeviceManager 에 등록된 '내 차' 블루투스가 연결되면 시스템이 이 서비스를 깨워 준다.
 * 이 경로는 백그라운드에서 포그라운드 서비스를 시작할 수 있는 예외 권한이 있다 (Android 12+).
 */
@RequiresApi(31)
class CarCompanionService : CompanionDeviceService() {
    @Suppress("OVERRIDE_DEPRECATION")
    override fun onDeviceAppeared(address: String) {
        if (address.equals(Prefs(this).btAddress, ignoreCase = true)) {
            CarSession.onCarConnected(this, Reason.BLUETOOTH)
        }
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onDeviceDisappeared(address: String) {
        if (address.equals(Prefs(this).btAddress, ignoreCase = true)) {
            CarSession.onCarDisconnected(this, Reason.BLUETOOTH)
        }
    }
}
