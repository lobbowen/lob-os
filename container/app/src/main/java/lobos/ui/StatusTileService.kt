package lobos.ui

import android.graphics.drawable.Icon
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import lobos.R
import lobos.os.OsInit

class StatusTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        refresh()
    }

    override fun onClick() {
        super.onClick()
        OsHostService.ensureRunning(this)
        refresh()
    }

    private fun refresh() {
        val tile = qsTile ?: return
        tile.state = Tile.STATE_ACTIVE
        tile.label = "Lob OS"
        tile.icon = Icon.createWithResource(this, R.drawable.ic_lobos_logo)
        tile.subtitle = OsInit.statusLine(this)
        tile.updateTile()
    }
}
