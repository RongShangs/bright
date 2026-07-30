package brightnesslock.rongshangs.top

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import brightnesslock.rongshangs.top.util.BrightnessManager

class BrightnessTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        updateTileState()
    }

    override fun onClick() {
        super.onClick()
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pendingIntent = PendingIntent.getActivity(
                this, 0, intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            startActivityAndCollapse(pendingIntent)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    private fun updateTileState() {
        val tile = qsTile ?: return
        val state = BrightnessManager.getCurrentState()
        
        when (state) {
            BrightnessManager.BrightnessState.LOCKED -> {
                tile.state = Tile.STATE_ACTIVE
                tile.label = "背屏接管中"
            }
            BrightnessManager.BrightnessState.SYSTEM -> {
                tile.state = Tile.STATE_INACTIVE
                tile.label = "系统背屏"
            }
            else -> {
                tile.state = Tile.STATE_UNAVAILABLE
                tile.label = "未知状态"
            }
        }
        tile.updateTile()
    }
}
