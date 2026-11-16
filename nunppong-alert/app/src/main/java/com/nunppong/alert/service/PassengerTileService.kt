package com.nunppong.alert.service

import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.nunppong.alert.core.Prefs
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 빠른 설정 타일: 지금은 승객 (1시간 끄기) 켜기/끄기 */
class PassengerTileService : TileService() {
    override fun onStartListening() = refresh()

    override fun onClick() {
        Passenger.toggle(this)
        refresh()
    }

    private fun refresh() {
        val tile = qsTile ?: return
        val prefs = Prefs(this)
        val active = prefs.isPassengerActive()
        tile.state = if (active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = "지금은 승객"
        if (Build.VERSION.SDK_INT >= 29) {
            tile.subtitle = if (active) {
                SimpleDateFormat("HH:mm", Locale.KOREA).format(Date(prefs.passengerUntil)) + "까지 꺼짐"
            } else "1시간 끄기"
        }
        tile.updateTile()
    }
}
