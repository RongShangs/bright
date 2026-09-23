package brightnesslock.rongshangs.top

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import brightnesslock.rongshangs.top.util.ControlStateStore

class BrightnessTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        updateTileState()
    }

    override fun onTileAdded() {
        super.onTileAdded()
        updateTileState()
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    @Suppress("DEPRECATION")
    override fun onClick() {
        super.onClick()
        Log.i(TAG, "Tile onClick received; launching Activity")
        // The QS tile is allowed to start an Activity through this system API.
        // Avoid the shared Root shell here: brightness reads can hold it for up to 30 seconds.
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startActivityAndCollapse(createPanelPendingIntent())
            } else {
                startActivityAndCollapse(panelIntent())
            }
        } catch (error: Exception) {
            Log.e(TAG, "Tile activity launch failed", error)
        }
    }

    private fun updateTileState() {
        val tile = qsTile ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // SystemUI launches this PendingIntent itself. It also works when the
            // vendor's QS panel drops or delays TileService.onClick callbacks.
            try {
                tile.activityLaunchForClick = createPanelPendingIntent()
            } catch (error: RuntimeException) {
                Log.w(TAG, "Direct tile launch unavailable; using onClick fallback", error)
            }
        }
        if (ControlStateStore.isTakeoverActive(this)) {
            tile.state = Tile.STATE_ACTIVE
            tile.label = "背屏接管中"
        } else {
            tile.state = Tile.STATE_INACTIVE
            tile.label = "背屏控制"
        }
        tile.updateTile()
    }

    private fun createPanelPendingIntent(): PendingIntent = PendingIntent.getActivity(
        this,
        0,
        panelIntent(),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun panelIntent(): Intent = Intent(this, MainActivity::class.java).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or
            Intent.FLAG_ACTIVITY_SINGLE_TOP)
        putExtra(MainActivity.EXTRA_KEEP_OVERLAY_HOST, true)
    }

    companion object { private const val TAG = "BrightnessTileService" }
}
