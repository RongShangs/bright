package brightnesslock.rongshangs.top

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import brightnesslock.rongshangs.top.util.ControlStateStore
import brightnesslock.rongshangs.top.util.ShellUtils
import kotlin.concurrent.thread

class BrightnessTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        updateTileState()
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    @Suppress("DEPRECATION")
    override fun onClick() {
        super.onClick()
        if (Settings.canDrawOverlays(this)) {
            val started = runCatching {
                startService(BrightnessOverlayService.createShowIntent(this))
            }.isSuccess

            if (started) {
                collapseControlCenter()
                return
            }
        }

        // Permission setup and the rare service-start failure use the official Activity path.
        launchActivityAndCollapse()
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    @Suppress("DEPRECATION")
    private fun launchActivityAndCollapse() {
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
            startActivityAndCollapse(intent)
        }
    }

    private fun updateTileState() {
        val tile = qsTile ?: return
        if (ControlStateStore.isTakeoverActive(this)) {
            tile.state = Tile.STATE_ACTIVE
            tile.label = "背屏接管中"
        } else {
            // Never use STATE_UNAVAILABLE for an uncertain hardware state: Android disables
            // clicks on an unavailable tile, which can lock the user out of the panel.
            tile.state = Tile.STATE_INACTIVE
            tile.label = "背屏控制"
        }
        tile.updateTile()
    }

    private fun collapseControlCenter() {
        thread(name = "collapse-control-center") {
            ShellUtils.execRoot(
                "cmd statusbar collapse 2>/dev/null || " +
                    "service call statusbar 2 >/dev/null 2>&1"
            )
        }
    }
}
