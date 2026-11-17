package com.nunppong.alert.bt

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.nunppong.alert.core.Prefs
import com.nunppong.alert.core.Reason

/** Android 8~11 용(및 12+ 보조) ACL 연결/해제 감지. 12+ 에서는 CompanionDeviceService 가 주 경로. */
class BtReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val dev: BluetoothDevice? = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            @Suppress("DEPRECATION") intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }
        val saved = Prefs(ctx).btAddress
        if (saved.isEmpty() || !saved.equals(dev?.address, ignoreCase = true)) return
        when (intent.action) {
            BluetoothDevice.ACTION_ACL_CONNECTED -> CarSession.onCarConnected(ctx, Reason.BLUETOOTH)
            BluetoothDevice.ACTION_ACL_DISCONNECTED -> CarSession.onCarDisconnected(ctx, Reason.BLUETOOTH)
        }
    }
}
