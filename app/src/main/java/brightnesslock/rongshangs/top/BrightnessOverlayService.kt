package brightnesslock.rongshangs.top

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import brightnesslock.rongshangs.top.ui.VerticalBrightnessSlider
import brightnesslock.rongshangs.top.util.BrightnessManager
import brightnesslock.rongshangs.top.util.ControlStateStore
import brightnesslock.rongshangs.top.util.ShellUtils
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min

class BrightnessOverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private var overlayView: View? = null
    private var activityHost: MainActivity? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private val ioExecutor = Executors.newCachedThreadPool()
    private val refreshInFlight = AtomicBoolean(false)
    private val isSyncing = AtomicBoolean(false)

    private var syncThread: Thread? = null
    private var isUserSliding = false
    private var lastTargetValue = -1

    private lateinit var takeoverStatus: TextView
    private lateinit var targetVal: TextView
    private lateinit var currentVal: TextView
    private lateinit var maxVal: TextView
    private lateinit var rootStatus: TextView
    private lateinit var aodText: TextView
    private lateinit var brightnessSlider: VerticalBrightnessSlider

    private val refreshRunnable = object : Runnable {
        override fun run() {
            updateCurrentBrightness()
            mainHandler.postDelayed(this, REFRESH_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i("BrightnessPanel", "Panel service started; activity host=${OverlayHost.current() != null}")
        if (intent?.action == ACTION_DISMISS) {
            closeOverlay()
            return START_NOT_STICKY
        }
        if (OverlayHost.current() == null && !Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "请先授予悬浮窗权限", Toast.LENGTH_SHORT).show()
            stopSelf()
            return START_NOT_STICKY
        }
        // Entry points always mean "show". Treating the tile as a toggle can close an
        // existing panel hidden behind the notification shade and look like a failed open.
        // Reattach on every explicit open request. Some apps temporarily hide
        // TYPE_APPLICATION_OVERLAY windows; keeping the old attached view would make
        // later tile taps look ignored even after the foreground app has changed.
        overlayView?.let { existing ->
            mainHandler.removeCallbacks(refreshRunnable)
            removePanelView(existing)
            overlayView = null
        }
        showOverlay()
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun showOverlay() {
        val card = LayoutInflater.from(this).inflate(
            R.layout.dialog_main,
            FrameLayout(this),
            false
        )
        val horizontalMargin = dp(16)
        val panelWidth = min(resources.displayMetrics.widthPixels - horizontalMargin * 2, dp(380))
        val view = FrameLayout(this).apply {
            isClickable = true
            addView(
                card,
                FrameLayout.LayoutParams(
                    panelWidth,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER
                )
            )
        }
        overlayView = view
        bindViews(view)
        bindActions(view)

        var windowFlags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S &&
            windowManager.isCrossWindowBlurEnabled
        ) {
            windowFlags = windowFlags or WindowManager.LayoutParams.FLAG_BLUR_BEHIND
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            windowFlags,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
            windowAnimations = android.R.style.Animation_Dialog
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S &&
                windowManager.isCrossWindowBlurEnabled
            ) {
                setBlurBehindRadius(dp(28))
            }
        }

        view.setOnTouchListener { _, event ->
            if (event.action != MotionEvent.ACTION_DOWN) return@setOnTouchListener false
            if (event.x < card.left || event.x >= card.right ||
                event.y < card.top || event.y >= card.bottom) {
                view.performClick()
                closeOverlay()
                true
            } else false
        }

        val host = OverlayHost.current()
        if (host != null) {
            activityHost = host
            host.setContentView(view)
            Log.i("BrightnessPanel", "Panel attached to Activity")
        } else {
            activityHost = null
            windowManager.addView(view, params)
            Log.i("BrightnessPanel", "Panel attached as overlay")
        }
        refreshFullUi()
        mainHandler.post(refreshRunnable)
    }

    private fun bindViews(view: View) {
        takeoverStatus = view.findViewById(R.id.takeoverStatus)
        targetVal = view.findViewById(R.id.targetVal)
        currentVal = view.findViewById(R.id.currentVal)
        maxVal = view.findViewById(R.id.maxVal)
        rootStatus = view.findViewById(R.id.rootStatus)
        aodText = view.findViewById(R.id.aodText)
        brightnessSlider = view.findViewById(R.id.brightnessSlider)
    }

    private fun bindActions(view: View) {
        view.findViewById<FrameLayout>(R.id.aodToggleBtn).setOnClickListener { toggleAod() }
        view.findViewById<FrameLayout>(R.id.restoreBtn).setOnClickListener { restoreSystemControl() }
        view.findViewById<TextView>(R.id.developerLink).setOnClickListener {
            openUrl("https://www.coolapk.com/u/3261403")
        }
        view.findViewById<TextView>(R.id.releasePage).setOnClickListener {
            openUrl("https://bright.rongshangs.top/")
        }

        brightnessSlider.setOnSlidingListener { value ->
            isUserSliding = true
            targetVal.text = value.toString()
        }
        brightnessSlider.setOnProgressChangedListener { value ->
            isUserSliding = false
            lastTargetValue = value
            startSyncLoop(value)
        }
    }

    private fun toggleAod() {
        executeIo {
            val currentAod = BrightnessManager.isRearAodEnabled()
            val success = BrightnessManager.setRearAodEnabled(!currentAod)
            mainHandler.post {
                if (overlayView != null) {
                    if (success) refreshFullUi() else showToast("AOD 设置失败")
                }
            }
        }
    }

    private fun restoreSystemControl() {
        stopSync()
        executeIo {
            val success = BrightnessManager.restoreSystemControl()
            mainHandler.post {
                if (overlayView != null) {
                    if (success) {
                        lastTargetValue = -1
                        ControlStateStore.setTakeoverActive(this, false)
                        showToast("已恢复系统控制")
                        closeOverlay()
                    } else {
                        showToast("恢复失败，请检查 Root 授权")
                        updateStatusLabels()
                    }
                }
            }
        }
    }

    private fun startSyncLoop(target: Int) {
        stopSync()
        isSyncing.set(true)
        updateStatusLabels()

        syncThread = Thread({
            val startTime = System.currentTimeMillis()
            var attempts = 0
            var finalSuccess = false

            try {
                while (isSyncing.get() && attempts < MAX_SYNC_ATTEMPTS &&
                    System.currentTimeMillis() - startTime <= SYNC_TIMEOUT_MS
                ) {
                    attempts++
                    val writeSucceeded = BrightnessManager.lockBrightnessOnce(target)
                    if (writeSucceeded && BrightnessManager.getCurrentBrightness() == target) {
                        Thread.sleep(50)
                        if (BrightnessManager.getCurrentBrightness() == target) {
                            finalSuccess = BrightnessManager.startWatchdog(applicationContext, target)
                            break
                        }
                    }
                    Thread.sleep(
                        when {
                            attempts <= 10 -> 100L
                            attempts <= 20 -> 200L
                            else -> 300L
                        }
                    )
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } finally {
                isSyncing.set(false)
            }

            if (finalSuccess) {
                lastTargetValue = target
                ControlStateStore.setTakeoverActive(this, true)
            }
            mainHandler.post {
                if (overlayView != null) {
                    if (!finalSuccess) {
                        val message = if (ShellUtils.isRootAvailable()) {
                            "亮度被系统持续覆盖，请重试"
                        } else {
                            "Root 未授权"
                        }
                        showToast(message)
                    }
                    updateStatusLabels()
                }
            }
        }, "brightness-sync").also { it.start() }
    }

    private fun stopSync() {
        isSyncing.set(false)
        syncThread?.interrupt()
        syncThread = null
    }

    private fun refreshFullUi() {
        executeIo {
            val max = BrightnessManager.getMaxBrightness()
            val current = BrightnessManager.getCurrentBrightness()
            val state = BrightnessManager.getCurrentState()
            val aodEnabled = BrightnessManager.isRearAodEnabled()
            val rootAvailable = ShellUtils.isRootAvailable()

            mainHandler.post {
                if (overlayView == null) return@post
                brightnessSlider.setMax(max)
                maxVal.text = max.toString()
                currentVal.text = current.toString()
                rootStatus.visibility = if (rootAvailable) View.GONE else View.VISIBLE

                if (state == BrightnessManager.BrightnessState.SYSTEM) {
                    ControlStateStore.setTakeoverActive(this, false)
                    brightnessSlider.setProgress(0)
                    targetVal.text = "—"
                    lastTargetValue = -1
                } else {
                    if (state == BrightnessManager.BrightnessState.LOCKED) {
                        ControlStateStore.setTakeoverActive(this, true)
                    }
                    brightnessSlider.setProgress(current)
                    targetVal.text = current.toString()
                    lastTargetValue = current
                }

                aodText.text = if (aodEnabled) "AOD\n已开启" else "AOD\n已关闭"
                aodText.setTextColor(color(if (aodEnabled) R.color.success else R.color.text_primary))
                updateStatusLabels(rootAvailable, state)
            }
        }
    }

    private fun updateCurrentBrightness() {
        if (!refreshInFlight.compareAndSet(false, true)) return
        executeIo {
            val current = BrightnessManager.getCurrentBrightness()
            mainHandler.post {
                if (overlayView != null) currentVal.text = current.toString()
                refreshInFlight.set(false)
            }
        }
    }

    private fun updateStatusLabels(
        knownRoot: Boolean? = null,
        knownState: BrightnessManager.BrightnessState? = null
    ) {
        if (knownRoot != null && knownState != null) {
            renderStatus(knownRoot, knownState)
            return
        }
        executeIo {
            val rootAvailable = ShellUtils.isRootAvailable()
            val state = BrightnessManager.getCurrentState()
            mainHandler.post { renderStatus(rootAvailable, state) }
        }
    }

    private fun renderStatus(rootAvailable: Boolean, state: BrightnessManager.BrightnessState) {
        if (overlayView == null) return
        rootStatus.visibility = if (rootAvailable) View.GONE else View.VISIBLE

        when {
            isSyncing.get() -> setStatus("正在同步", color(R.color.accent))
            !rootAvailable -> setStatus("等待 Root 授权", color(R.color.error))
            lastTargetValue == -1 || state == BrightnessManager.BrightnessState.SYSTEM -> {
                setStatus("系统控制中", color(R.color.text_secondary))
                if (!isUserSliding) {
                    targetVal.text = "—"
                    brightnessSlider.setProgress(0)
                    lastTargetValue = -1
                }
            }
            else -> setStatus("亮度已接管", color(R.color.success))
        }
    }

    private fun setStatus(text: String, color: Int) {
        takeoverStatus.text = text
        takeoverStatus.setTextColor(color)
    }

    private fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
            closeOverlay()
        } catch (_: Exception) {
            showToast("无法打开链接")
        }
    }

    private fun closeOverlay() {
        mainHandler.removeCallbacks(refreshRunnable)
        stopSync()
        overlayView?.let { removePanelView(it) }
        overlayView = null
        sendBroadcast(Intent(ACTION_OVERLAY_CLOSED).setPackage(packageName))
        stopSelf()
    }

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun executeIo(block: () -> Unit) {
        if (ioExecutor.isShutdown) return
        runCatching { ioExecutor.execute(block) }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun color(resourceId: Int): Int = ContextCompat.getColor(this, resourceId)

    private fun removePanelView(view: View) {
        if (activityHost != null) {
            (view.parent as? ViewGroup)?.removeView(view)
            activityHost = null
        } else {
            runCatching { windowManager.removeViewImmediate(view) }
        }
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        stopSync()
        overlayView?.let { removePanelView(it) }
        overlayView = null
        ioExecutor.shutdownNow()
        Thread({ ShellUtils.destroy() }, "root-shell-cleanup").apply {
            start()
        }
        super.onDestroy()
    }

    companion object {
        private const val REFRESH_INTERVAL_MS = 500L
        private const val SYNC_TIMEOUT_MS = 5_000L
        private const val MAX_SYNC_ATTEMPTS = 30
        const val ACTION_OVERLAY_CLOSED = "brightnesslock.rongshangs.top.OVERLAY_CLOSED"
        private const val ACTION_DISMISS = "brightnesslock.rongshangs.top.DISMISS_OVERLAY"

        fun createShowIntent(context: Context): Intent =
            Intent(context, BrightnessOverlayService::class.java)

        fun createDismissIntent(context: Context): Intent =
            Intent(context, BrightnessOverlayService::class.java).setAction(ACTION_DISMISS)
    }
}
