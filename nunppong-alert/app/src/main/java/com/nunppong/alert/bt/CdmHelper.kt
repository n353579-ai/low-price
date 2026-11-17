package com.nunppong.alert.bt

import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanResult
import android.companion.AssociationRequest
import android.companion.BluetoothDeviceFilter
import android.companion.CompanionDeviceManager
import android.content.Context
import android.content.IntentSender
import android.os.Build
import android.util.Log
import com.nunppong.alert.core.Prefs

data class PairedDevice(val name: String, val address: String)

object CdmHelper {
    /** 폰에 페어링된 블루투스 기기 목록 (BLUETOOTH_CONNECT 권한 필요) */
    fun bonded(ctx: Context): List<PairedDevice> = try {
        val bm = ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bm.adapter?.bondedDevices.orEmpty().map { PairedDevice(it.name ?: it.address, it.address) }
            .sortedBy { it.name }
    } catch (e: SecurityException) {
        emptyList()
    }

    /** 선택한 기기를 CompanionDeviceManager 에 등록. 시스템 확인창은 onChooser 로 넘어온다. */
    @Suppress("DEPRECATION")
    fun associate(ctx: Context, address: String, onChooser: (IntentSender) -> Unit, onError: (String) -> Unit) {
        val cdm = ctx.getSystemService(Context.COMPANION_DEVICE_SERVICE) as CompanionDeviceManager
        val filter = BluetoothDeviceFilter.Builder().setAddress(address).build()
        val request = AssociationRequest.Builder().addDeviceFilter(filter).setSingleDevice(true).build()
        cdm.associate(request, object : CompanionDeviceManager.Callback() {
            override fun onDeviceFound(chooserLauncher: IntentSender) = onChooser(chooserLauncher)
            override fun onFailure(error: CharSequence?) = onError(error?.toString() ?: "기기를 찾지 못했어요")
        }, null)
    }

    /** 등록 완료 후: 선택 저장 + 연결 감지 시작 (Android 12+) */
    @Suppress("DEPRECATION")
    fun finish(ctx: Context, device: PairedDevice) {
        val prefs = Prefs(ctx)
        val cdm = ctx.getSystemService(Context.COMPANION_DEVICE_SERVICE) as CompanionDeviceManager
        val old = prefs.btAddress
        if (old.isNotEmpty() && !old.equals(device.address, true)) {
            try {
                if (Build.VERSION.SDK_INT >= 31) cdm.stopObservingDevicePresence(old)
                cdm.disassociate(old)
            } catch (e: Exception) { Log.w("Cdm", "old cleanup", e) }
        }
        prefs.btAddress = device.address
        prefs.btName = device.name
        startObserving(ctx, device.address)
    }

    @Suppress("DEPRECATION")
    fun startObserving(ctx: Context, address: String) {
        if (Build.VERSION.SDK_INT < 31) return
        try {
            val cdm = ctx.getSystemService(Context.COMPANION_DEVICE_SERVICE) as CompanionDeviceManager
            cdm.startObservingDevicePresence(address)
        } catch (e: Exception) {
            Log.w("Cdm", "startObserving failed", e)
        }
    }
}
