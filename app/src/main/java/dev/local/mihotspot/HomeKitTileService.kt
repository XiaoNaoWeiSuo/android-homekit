package dev.local.mihotspot

import android.graphics.drawable.Icon
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** Quick Settings switch for the HomeKit driver service. */
class HomeKitTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        updateTile()
    }

    override fun onClick() {
        super.onClick()
        val enabled = !ServiceControl.isEnabled(this)
        if (enabled) ServiceControl.start(this) else ServiceControl.stop(this)
        qsTile?.state = if (enabled) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        qsTile?.updateTile()
    }

    private fun updateTile() {
        qsTile?.label = "HomeKit驱动"
        qsTile?.contentDescription = "HomeKit后台驱动常驻开关"
        qsTile?.icon = Icon.createWithResource(this, R.drawable.ic_driver_resident)
        qsTile?.state = if (ServiceControl.isEnabled(this)) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        qsTile?.updateTile()
    }
}
