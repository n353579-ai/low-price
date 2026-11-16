package com.nunppong.alert.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat

object Perms {
    private fun granted(ctx: Context, p: String) =
        ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED

    fun overlay(ctx: Context) = Settings.canDrawOverlays(ctx)
    fun fineLocation(ctx: Context) = granted(ctx, Manifest.permission.ACCESS_FINE_LOCATION)
    fun backgroundLocation(ctx: Context) =
        if (Build.VERSION.SDK_INT >= 29) granted(ctx, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        else fineLocation(ctx)
    fun notifications(ctx: Context) =
        Build.VERSION.SDK_INT < 33 || granted(ctx, Manifest.permission.POST_NOTIFICATIONS)

    /** 시뮬레이션/주행 서비스를 시작하는 데 꼭 필요한 권한 */
    fun readyToRun(ctx: Context) = overlay(ctx) && fineLocation(ctx)
}
