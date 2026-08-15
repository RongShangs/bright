package brightnesslock.rongshangs.top

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import brightnesslock.rongshangs.top.util.BrightnessManager
import brightnesslock.rongshangs.top.util.ShellUtils
import brightnesslock.rongshangs.top.ui.VerticalBrightnessSlider
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    private lateinit var takeoverStatus: TextView
    private lateinit var targetVal: TextView
    private lateinit var currentVal: TextView
    private lateinit var maxVal: TextView
    private lateinit var rootStatus: TextView
    private lateinit var aodText: TextView
    private lateinit var brightnessSlider: VerticalBrightnessSlider
    
    private val prefs by lazy { getSharedPreferences("config", Context.MODE_PRIVATE) }
    private val handler = Handler(Looper.getMainLooper())
    
    private var isUserSliding = false
    private val isSyncing = AtomicBoolean(false)
    private var lastTargetValue = -1
    private var syncThread: Thread? = null

    private val refreshRunnable = object : Runnable {
        override fun run() {
            updateCurrentBrightness()
            handler.postDelayed(this, 300)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.dialog_main)

        takeoverStatus = findViewById(R.id.takeoverStatus)
        targetVal = findViewById(R.id.targetVal)
        currentVal = findViewById(R.id.currentVal)
        maxVal = findViewById(R.id.maxVal)
        rootStatus = findViewById(R.id.rootStatus)
        aodText = findViewById(R.id.aodText)
        brightnessSlider = findViewById(R.id.brightnessSlider)
        
        val rootContainer = findViewById<View>(R.id.rootContainer)
        val restoreBtn = findViewById<FrameLayout>(R.id.restoreBtn)
        val aodToggleBtn = findViewById<FrameLayout>(R.id.aodToggleBtn)
        val developerLink = findViewById<TextView>(R.id.developerLink)
        val releasePage = findViewById<TextView>(R.id.releasePage)

        releaseWatchdogBinary()

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
                        refreshFullUI()
                    } else {
                        Toast.makeText(this, "AOD设置失败", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        restoreBtn.setOnClickListener {
            stopSync()
            thread {
                val success = BrightnessManager.restoreSystemControl()
                runOnUiThread {
                    if (success) {
                        lastTargetValue = -1
                        prefs.edit()
                            .putBoolean("is_takeover", false)
                            .putInt("target_brightness", -1)
                            .apply()
                        
                        Toast.makeText(this, "已恢复系统控制", Toast.LENGTH_SHORT).show()
                        refreshFullUI()
                    } else {
                        Toast.makeText(this, "执行失败", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        brightnessSlider.setOnSlidingListener { value ->
            isUserSliding = true
            targetVal.text = value.toString()
        }

        brightnessSlider.setOnProgressChangedListener { value ->
            isUserSliding = false
            lastTargetValue = value
            prefs.edit().putBoolean("is_takeover", true).apply()
            startSyncLoop(value)
        }

        refreshFullUI()
        handler.post(refreshRunnable)
    }

    private fun releaseWatchdogBinary() {
        thread {
            try {
                val input = assets.open("watchdog_c")
                val bytes = input.readBytes()
                input.close()
                val f = File(filesDir, "watchdog_c")
                f.writeBytes(bytes)
                f.setExecutable(true)
                // Copy to executable location
                ShellUtils.execRoot("cp ${f.absolutePath} ${BrightnessManager.WATCHDOG_BIN}")
                ShellUtils.execRoot("chmod 777 ${BrightnessManager.WATCHDOG_BIN}")
            } catch (e: Exception) {
                Log.e("Watchdog", "释放二进制失败", e)
            }
        }
    }

    private fun startSyncLoop(target: Int) {
        stopSync()
        isSyncing.set(true)
        
        runOnUiThread {
            takeoverStatus.text = "修改中..."
            takeoverStatus.setTextColor(0xFFFBC02D.toInt())
        }

        syncThread = thread(start = true) {
            val startTime = System.currentTimeMillis()
            var attempts = 0
            val maxAttempts = 30
            var finalSuccess = false
            
            try {
                while (isSyncing.get() && attempts < maxAttempts) {
                    if (System.currentTimeMillis() - startTime > 5000) {
                        break
                    }

                    attempts++
                    BrightnessManager.lockBrightnessOnce(target)
                    val current = BrightnessManager.getCurrentBrightness()
                    
                    if (current == target) {
                        Thread.sleep(50)
                        if (BrightnessManager.getCurrentBrightness() == target) {
                            finalSuccess = true
                            isSyncing.set(false)
                            prefs.edit().putInt("target_brightness", target).apply()
                            // Start the C Watchdog
                            BrightnessManager.startWatchdog(target)
                        }
                    }

                    if (isSyncing.get()) {
                        val delay = when {
                            attempts <= 10 -> 100L
                            attempts <= 20 -> 200L
                            else -> 300L
                        }
                        Thread.sleep(delay)
                    }
                }
            } catch (e: InterruptedException) {
            }

            runOnUiThread {
                if (!finalSuccess && !isSyncing.get() && attempts >= maxAttempts) {
                    if (!ShellUtils.isRootAvailable()) {
                        Toast.makeText(this@MainActivity, "Root未授权", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this@MainActivity, "系统持续覆盖，修改失败", Toast.LENGTH_SHORT).show()
                    }
                }
                updateStatusLabels()
            }
        }
    }

    private fun stopSync() {
        isSyncing.set(false)
        syncThread?.interrupt()
        syncThread = null
    }

    private fun openUrl(url: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "无法打开链接", Toast.LENGTH_SHORT).show()
        }
    }

    private fun refreshFullUI() {
        thread {
            val max = BrightnessManager.getMaxBrightness()
            val current = BrightnessManager.getCurrentBrightness()
            val state = BrightnessManager.getCurrentState()
            val aodEnabled = BrightnessManager.isRearAodEnabled()
            
            runOnUiThread {
                brightnessSlider.setMax(max)
                maxVal.text = max.toString()
                currentVal.text = current.toString()
                
                if (state == BrightnessManager.BrightnessState.SYSTEM) {
                    brightnessSlider.setProgress(0)
                    targetVal.text = "——"
                    lastTargetValue = -1
                } else {
                    brightnessSlider.setProgress(current)
                    targetVal.text = current.toString()
                    lastTargetValue = current
                }
                
                aodText.text = if (aodEnabled) "AOD\n已开" else "AOD\n已关"
                aodText.setTextColor(if (aodEnabled) 0xFF4CAF50.toInt() else 0xFF333333.toInt())
                updateStatusLabels()
            }
        }
    }

    private fun updateCurrentBrightness() {
        thread {
            val current = BrightnessManager.getCurrentBrightness()
            runOnUiThread {
                currentVal.text = current.toString()
            }
        }
    }

    private fun updateStatusLabels() {
        thread {
            val isRoot = ShellUtils.isRootAvailable()
            val state = BrightnessManager.getCurrentState()
            val isSyncActive = isSyncing.get()
            
            runOnUiThread {
                if (!isRoot) {
                    rootStatus.text = "Root: 未授权"
                    rootStatus.visibility = View.VISIBLE
                } else {
                    rootStatus.visibility = View.GONE
                }

                when {
                    isSyncActive -> {
                        takeoverStatus.text = "修改中..."
                        takeoverStatus.setTextColor(0xFFFBC02D.toInt())
                    }
                    lastTargetValue == -1 -> {
                        takeoverStatus.text = "未修改"
                        takeoverStatus.setTextColor(0xFF333333.toInt())
                        if (!isUserSliding) {
                            targetVal.text = "——"
                            brightnessSlider.setProgress(0)
                        }
                    }
                    state == BrightnessManager.BrightnessState.SYSTEM -> {
                        takeoverStatus.text = "未修改"
                        takeoverStatus.setTextColor(0xFF333333.toInt())
                        if (!isUserSliding) {
                            targetVal.text = "——"
                            brightnessSlider.setProgress(0)
                            lastTargetValue = -1
                        }
                    }
                    else -> {
                        takeoverStatus.text = "已修改"
                        takeoverStatus.setTextColor(0xFF4CAF50.toInt())
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopSync()
        handler.removeCallbacks(refreshRunnable)
        ShellUtils.destroy()
    }
}
