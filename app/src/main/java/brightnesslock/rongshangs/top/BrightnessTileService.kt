package brightnesslock.rongshangs.top

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast
import brightnesslock.rongshangs.top.util.BrightnessManager
import brightnesslock.rongshangs.top.util.ShellUtils

class BrightnessTileService : TileService() {

    // Called when the tile is added to the Quick Settings
    override fun onTileAdded() {
        super.onTileAdded()
        updateTileState()
    }

    // Called when the tile becomes visible
    override fun onStartListening() {
        super.onStartListening()
        updateTileState()
    }

    // Called when the user clicks the tile
    override fun onClick() {
        super.onClick()
        
        if (!ShellUtils.isRootAvailable()) {
            Toast.makeText(this, "戎：未检测到 Root 权限", Toast.LENGTH_SHORT).show()
            return
        }

        val currentState = BrightnessManager.getCurrentState()
        val success = if (currentState == BrightnessManager.BrightnessState.LOCKED) {
            BrightnessManager.restoreSystemControl()
        } else {
            BrightnessManager.lockBrightness()
        }

        if (success) {
            val newState = BrightnessManager.getCurrentState()
            val msg = if (newState == BrightnessManager.BrightnessState.LOCKED) "戎：已激发背屏亮度" else "戎：已恢复系统控制"
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "戎：执行失败", Toast.LENGTH_SHORT).show()
        }

        updateTileState()
    }

    private fun updateTileState() {
        val tile = qsTile ?: return
        val state = BrightnessManager.getCurrentState()
        
        when (state) {
            BrightnessManager.BrightnessState.LOCKED -> {
                tile.state = Tile.STATE_ACTIVE
                tile.label = "戎：背屏激发中"
            }
            BrightnessManager.BrightnessState.SYSTEM -> {
                tile.state = Tile.STATE_INACTIVE
                tile.label = "戎：背屏系统"
            }
            else -> {
                tile.state = Tile.STATE_UNAVAILABLE
                tile.label = "戎：未知状态"
            }
        }
        tile.updateTile()
    }
}
