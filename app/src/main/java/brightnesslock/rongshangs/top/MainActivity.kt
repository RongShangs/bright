package brightnesslock.rongshangs.top

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import brightnesslock.rongshangs.top.util.BrightnessManager
import brightnesslock.rongshangs.top.util.ShellUtils
import brightnesslock.rongshangs.top.ui.VerticalBrightnessSlider
import java.util.concurrent.Executors
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    private lateinit var takeoverStatus: TextView
    private lateinit var brightnessValue: TextView
    private lateinit var rootStatus: TextView
    private lateinit var deviceModel: TextView
    private lateinit var aodText: TextView
    private lateinit var brightnessSlider: VerticalBrightnessSlider
    
    private val prefs by lazy { getSharedPreferences("config", Context.MODE_PRIVATE) }
    private val singleThreadExecutor = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.dialog_main)

        takeoverStatus = findViewById(R.id.takeoverStatus)
        brightnessValue = findViewById(R.id.brightnessValue)
        rootStatus = findViewById(R.id.rootStatus)
        deviceModel = findViewById(R.id.deviceModel)
        aodText = findViewById(R.id.aodText)
        brightnessSlider = findViewById(R.id.brightnessSlider)
        
        val rootContainer = findViewById<View>(R.id.rootContainer)
        val restoreBtn = findViewById<FrameLayout>(R.id.restoreBtn)
        val aodToggleBtn = findViewById<FrameLayout>(R.id.aodToggleBtn)
        val developerLink = findViewById<TextView>(R.id.developerLink)
        val releasePage = findViewById<TextView>(R.id.releasePage)

        // Set device model
        deviceModel.text = "${Build.MANUFACTURER} ${Build.MODEL}"

        // Close on click outside
        rootContainer.setOnTouchListener { v, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                val dialogCard = findViewById<View>(R.id.dialogCard)
                val outRect = android.graphics.Rect()
                dialogCard.getGlobalVisibleRect(outRect)
                if (!outRect.contains(event.rawX.toInt(), event.rawY.toInt())) {
                    finish()
                }
            }
            v.performClick()
            true
        }

        developerLink.setOnClickListener {
            openUrl("https://www.coolapk.com/u/3261403")
        }

        releasePage.setOnClickListener {
            openUrl("http://bright.rongshangs.top/")
        }

        aodToggleBtn.setOnClickListener {
            thread {
                val currentAod = BrightnessManager.isRearAodEnabled()
                val success = BrightnessManager.setRearAodEnabled(!currentAod)
                runOnUiThread {
                    if (success) {
                        refreshUI()
                    } else {
                        Toast.makeText(this, "AOD设置失败", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        restoreBtn.setOnClickListener {
            thread {
                val success = BrightnessManager.restoreSystemControl()
                runOnUiThread {
                    if (success) {
                        Toast.makeText(this, "已恢复系统控制", Toast.LENGTH_SHORT).show()
                        refreshUI()
                    } else {
                        Toast.makeText(this, "执行失败", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        brightnessSlider.setOnProgressChangedListener { value ->
            // Update local value immediately in text view
            val max = BrightnessManager.getMaxBrightness()
            brightnessValue.text = "$value / $max"
            
            // Execute set brightness in background
            singleThreadExecutor.execute {
                val success = BrightnessManager.lockBrightness(value)
                if (success) {
                    prefs.edit().putInt("target_brightness", value).apply()
                }
                
                runOnUiThread {
                    updateStatusLabels()
                }
            }
        }

        refreshUI()
    }

    private fun openUrl(url: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "无法打开链接", Toast.LENGTH_SHORT).show()
        }
    }

    private fun refreshUI() {
        thread {
            val max = BrightnessManager.getMaxBrightness()
            val current = BrightnessManager.getCurrentBrightness()
            val aodEnabled = BrightnessManager.isRearAodEnabled()
            
            runOnUiThread {
                brightnessSlider.setMax(max)
                brightnessSlider.setProgress(current)
                brightnessValue.text = "$current / $max"
                aodText.text = if (aodEnabled) "AOD\n已开" else "AOD\n已关"
                aodText.setTextColor(if (aodEnabled) 0xFF4CAF50.toInt() else 0xFF333333.toInt())
                updateStatusLabels()
            }
        }
    }

    private fun updateStatusLabels() {
        thread {
            val isRoot = ShellUtils.isRootAvailable()
            val state = BrightnessManager.getCurrentState()
            
            runOnUiThread {
                if (!isRoot) {
                    rootStatus.text = "Root: 未授权"
                    rootStatus.setTextColor(0xFFFF0000.toInt())
                } else {
                    rootStatus.text = "Root: 已授权"
                    rootStatus.setTextColor(0xFF4CAF50.toInt())
                }

                if (state == BrightnessManager.BrightnessState.LOCKED) {
                    takeoverStatus.text = "当前为软件接管"
                    takeoverStatus.setTextColor(0xFF4CAF50.toInt())
                } else {
                    takeoverStatus.text = "当前为系统控制"
                    takeoverStatus.setTextColor(0xFF333333.toInt())
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        singleThreadExecutor.shutdown()
        ShellUtils.destroy()
    }
}
