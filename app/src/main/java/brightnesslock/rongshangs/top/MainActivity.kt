package brightnesslock.rongshangs.top

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.util.function.Consumer

class MainActivity : AppCompatActivity() {
    private var keepOverlayHost = false
    private var receiverRegistered = false
    private var blurListener: Consumer<Boolean>? = null
    private var blurAvailable = false
    private var panelBlurFraction = 0f
    private val overlayClosedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == BrightnessOverlayService.ACTION_OVERLAY_CLOSED) finish()
        }
    }

    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (Settings.canDrawOverlays(this)) showOverlay()
        else Toast.makeText(this, "需要悬浮窗权限才能显示控制面板", Toast.LENGTH_LONG).show()
        if (!keepOverlayHost || !Settings.canDrawOverlays(this)) finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        keepOverlayHost = intent.getBooleanExtra(EXTRA_KEEP_OVERLAY_HOST, false)
        Log.i("BrightnessPanel", "Activity created; QS host=$keepOverlayHost")
        if (keepOverlayHost) {
            configureBackgroundBlur()
            OverlayHost.attach(this)
            ContextCompat.registerReceiver(this, overlayClosedReceiver,
                IntentFilter(BrightnessOverlayService.ACTION_OVERLAY_CLOSED),
                ContextCompat.RECEIVER_NOT_EXPORTED)
            receiverRegistered = true
            onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    startService(BrightnessOverlayService.createDismissIntent(this@MainActivity))
                }
            })
        }
        if (keepOverlayHost || Settings.canDrawOverlays(this)) {
            showOverlay()
            if (!keepOverlayHost) finish()
        } else {
            overlayPermissionLauncher.launch(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")))
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (!keepOverlayHost && intent.getBooleanExtra(EXTRA_KEEP_OVERLAY_HOST, false)) {
            recreate()
            return
        }
        Log.i("BrightnessPanel", "Activity reused from QS tile")
        if (intent.getBooleanExtra(EXTRA_KEEP_OVERLAY_HOST, false)) showOverlay()
    }

    private fun showOverlay() = startService(BrightnessOverlayService.createShowIntent(this)).let { }

    private fun configureBackgroundBlur() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val manager = getSystemService(WINDOW_SERVICE) as WindowManager
        window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
        val listener = Consumer<Boolean> { enabled ->
            blurAvailable = enabled
            updatePanelBlur()
        }
        blurListener = listener
        manager.addCrossWindowBlurEnabledListener(mainExecutor, listener)
        listener.accept(manager.isCrossWindowBlurEnabled)
    }

    fun setPanelBlurFraction(fraction: Float) {
        panelBlurFraction = fraction.coerceIn(0f, 1f)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) updatePanelBlur()
    }

    private fun updatePanelBlur() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        window.attributes = window.attributes.apply {
            setBlurBehindRadius(
                if (blurAvailable) ((28 * resources.displayMetrics.density) * panelBlurFraction).toInt()
                else 0
            )
        }
    }

    override fun onStop() {
        super.onStop()
        if (keepOverlayHost && !isChangingConfigurations && OverlayHost.current() === this) {
            stopService(Intent(this, BrightnessOverlayService::class.java))
            finish()
        }
    }

    override fun onDestroy() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            blurListener?.let { listener ->
                (getSystemService(WINDOW_SERVICE) as WindowManager)
                    .removeCrossWindowBlurEnabledListener(listener)
            }
        }
        OverlayHost.detach(this)
        if (receiverRegistered) unregisterReceiver(overlayClosedReceiver)
        super.onDestroy()
    }

    companion object { const val EXTRA_KEEP_OVERLAY_HOST = "keep_overlay_host" }
}
