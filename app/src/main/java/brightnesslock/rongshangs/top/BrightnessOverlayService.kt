package brightnesslock.rongshangs.top

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
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
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import brightnesslock.rongshangs.top.ui.VerticalBrightnessSlider
import brightnesslock.rongshangs.top.util.BrightnessManager
import brightnesslock.rongshangs.top.util.ControlStateStore
import brightnesslock.rongshangs.top.util.ShellUtils
import brightnesslock.rongshangs.top.util.ControlQueue
import brightnesslock.rongshangs.top.util.OperationGeneration
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min

class BrightnessOverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private var overlayView: View? = null
    private var activityHost: MainActivity? = null
    private var overlayWindowParams: WindowManager.LayoutParams? = null
    private var panelAnimator: ValueAnimator? = null
    private var isClosing = false

    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var destroyed = false
    private val refreshInFlight = AtomicBoolean(false)
    private val isSyncing = AtomicBoolean(false)

    private val syncGeneration = OperationGeneration()
    private var nextHealthRead = 0L
    private var lastWatchdogState: BrightnessManager.WatchdogState? = null
    private var isUserSliding = false
    private var lastTargetValue = -1
    private var activeModeChangeInFlight = false
    private var restoreInFlight = false
    private var activeModeVersion = 0
    private var activeModeEnabled: Boolean? = null
    private var hintBesideCard = false

    private lateinit var takeoverStatus: TextView
    private lateinit var targetVal: TextView
    private lateinit var currentVal: TextView
    private lateinit var maxVal: TextView
    private lateinit var rootStatus: TextView
    private lateinit var aodText: TextView
    private lateinit var activeModeButton: FrameLayout
    private lateinit var activeModeText: TextView
    private lateinit var activeModeHint: LinearLayout
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
            cancelPanelAnimation()
            removePanelView(existing)
            overlayView = null
        }
        isClosing = false
        try {
            showOverlay()
        } catch (error: RuntimeException) {
            Log.e("BrightnessPanel", "Failed to open control panel", error)
            showToast("面板打开失败：${error.javaClass.simpleName}")
            closeOverlay()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (activityHost == null && overlayView != null && !isClosing) {
            mainHandler.removeCallbacks(refreshRunnable)
            cancelPanelAnimation()
            overlayView?.let(::removePanelView)
            overlayView = null
            runCatching { showOverlay() }.onFailure { closeOverlay() }
        }
    }

    private fun showOverlay() {
        val card = LayoutInflater.from(this).inflate(
            R.layout.dialog_main,
            FrameLayout(this),
            false
        )
        val horizontalMargin = dp(16)
        val screenWidth = resources.displayMetrics.widthPixels
        val sideBySide = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE &&
            screenWidth >= dp(620)
        hintBesideCard = sideBySide
        val landscapeLeftMargin = maxOf(dp(72), (screenWidth * 0.09f).toInt())
        val landscapeSpace = screenWidth - landscapeLeftMargin - horizontalMargin - dp(16)
        val panelWidth = if (sideBySide) {
            min(dp(380), (landscapeSpace * 0.65f).toInt())
        } else {
            min(screenWidth - horizontalMargin * 2, dp(380))
        }
        val cardParams = FrameLayout.LayoutParams(
            panelWidth,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            if (sideBySide) Gravity.START or Gravity.CENTER_VERTICAL else Gravity.CENTER
        ).apply {
            if (sideBySide) leftMargin = landscapeLeftMargin
        }
        val hintWidth = if (sideBySide) {
            landscapeSpace - panelWidth
        } else panelWidth
        val hintParams = FrameLayout.LayoutParams(
            hintWidth,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            if (sideBySide) Gravity.END or Gravity.CENTER_VERTICAL else Gravity.CENTER
        ).apply {
            if (sideBySide) rightMargin = horizontalMargin
        }
        val hint = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            addView(TextView(this@BrightnessOverlayService).apply {
                text = "背屏将保持活跃不会主动休眠或者进入AOD\n耗电与烧屏风险增加"
                setTextColor(color(R.color.hint_warning))
                textSize = 12f
                gravity = Gravity.CENTER
                includeFontPadding = false
            })
            visibility = View.GONE
            if (!sideBySide) translationY = dp(160).toFloat()
        }
        activeModeHint = hint
        val view = FrameLayout(this).apply {
            isClickable = true
            alpha = 0f
            addView(card, cardParams)
            addView(hint, hintParams)
        }
        if (sideBySide) {
            var cutoutLeft = 0
            var cutoutRight = 0
            fun updateLandscapePosition(width: Int) {
                if (width <= 0) return
                val left = maxOf(dp(72), (width * 0.09f).toInt(), cutoutLeft + dp(16))
                val right = maxOf(dp(16), cutoutRight + dp(16))
                val space = width - left - right - dp(16)
                if (space <= 0) return
                val cardWidth = min(dp(380), (space * 0.65f).toInt())
                val textWidth = space - cardWidth
                if (cardParams.leftMargin != left || cardParams.width != cardWidth ||
                    hintParams.rightMargin != right || hintParams.width != textWidth
                ) {
                    cardParams.leftMargin = left
                    cardParams.width = cardWidth
                    hintParams.rightMargin = right
                    hintParams.width = textWidth
                    card.layoutParams = cardParams
                    hint.layoutParams = hintParams
                }
            }
            view.addOnLayoutChangeListener { _, left, _, right, _, _, _, _, _ ->
                updateLandscapePosition(right - left)
            }
            view.setOnApplyWindowInsetsListener { _, insets ->
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                    cutoutLeft = insets.displayCutout?.safeInsetLeft ?: 0
                    cutoutRight = insets.displayCutout?.safeInsetRight ?: 0
                    updateLandscapePosition(view.width)
                }
                insets
            }
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
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S &&
                windowManager.isCrossWindowBlurEnabled
            ) {
                setBlurBehindRadius(0)
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
            overlayWindowParams = null
            host.setContentView(view)
            Log.i("BrightnessPanel", "Panel attached to Activity")
        } else {
            activityHost = null
            windowManager.addView(view, params)
            overlayWindowParams = params
            Log.i("BrightnessPanel", "Panel attached as overlay")
        }
        animatePanel(view, 1f)
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
        activeModeButton = view.findViewById(R.id.activeModeBtn)
        activeModeText = view.findViewById(R.id.activeModeText)
        activeModeEnabled = null
        activeModeButton.isEnabled = false
        brightnessSlider = view.findViewById(R.id.brightnessSlider)
    }

    private fun bindActions(view: View) {
        view.findViewById<FrameLayout>(R.id.aodToggleBtn).setOnClickListener { toggleAod() }
        view.findViewById<FrameLayout>(R.id.restoreBtn).setOnClickListener { restoreSystemControl() }
        activeModeButton.setOnClickListener {
            activeModeEnabled?.let { setActiveMode(!it) }
        }
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
            val success = currentAod != null && BrightnessManager.setRearAodEnabled(!currentAod)
            mainHandler.post {
                if (overlayView != null) {
                    if (success) refreshFullUi() else showToast("AOD 设置失败")
                }
            }
        }
    }

    private fun setActiveMode(enabled: Boolean) {
        if (activeModeChangeInFlight || restoreInFlight) return
        activeModeChangeInFlight = true
        activeModeVersion++
        activeModeButton.isEnabled = false
        activeModeText.text = "永不息屏\n设置中"
        val panel = overlayView
        executeIo {
            val success = BrightnessManager.setActiveMode(applicationContext, enabled)
              val actual = BrightnessManager.getActiveModeState()
            mainHandler.post {
                activeModeVersion++
                activeModeChangeInFlight = false
                if (actual != null) ControlStateStore.setActiveModeEnabled(this, actual)
                if (overlayView !== panel) {
                    if (overlayView != null) refreshFullUi()
                    return@post
                }
                renderActiveMode(actual)
                if (success && !enabled) {
                    showToast("已恢复正常息屏时间")
                } else if (!success) {
                    showToast(if (enabled) "背屏守护启动失败，请检查 Root 与设备支持" else "息屏时间设置失败")
                }
            }
        }
    }

    private fun restoreSystemControl() {
        if (restoreInFlight) return
        if (activeModeChangeInFlight) {
            showToast("请等待息屏时间设置完成")
            return
        }
        restoreInFlight = true
        brightnessSlider.isEnabled = false
        stopSync()
        executeIo {
            val success = BrightnessManager.restoreSystemControl(applicationContext)
            mainHandler.post {
                restoreInFlight = false
                if (overlayView != null) brightnessSlider.isEnabled = true
                if (success) {
                    ControlStateStore.setTakeoverActive(this, false)
                    ControlStateStore.setActiveModeEnabled(this, false)
                    ControlStateStore.setTargetBrightness(this, -1)
                }
                if (overlayView != null) {
                    if (success) {
                        lastTargetValue = -1
                        showToast("已恢复系统控制")
                        closeOverlay()
                    } else {
                        showToast("恢复失败，请检查 Root 授权")
                        refreshFullUi()
                    }
                }
            }
        }
    }

    private fun startSyncLoop(target: Int) {
        if (restoreInFlight) return
        stopSync()
        val ticket = syncGeneration.next()
        val panel = overlayView
        isSyncing.set(true)
        updateStatusLabels()

        executeIo {
            if (!syncGeneration.isCurrent(ticket)) return@executeIo
            val startTime = android.os.SystemClock.elapsedRealtime()
            var attempts = 0
            var finalSuccess = false
            var activeGuardFailed = false

            try {
                while (syncGeneration.isCurrent(ticket) && attempts < MAX_SYNC_ATTEMPTS &&
                    android.os.SystemClock.elapsedRealtime() - startTime <= SYNC_TIMEOUT_MS
                ) {
                    attempts++
                    val keepActive = BrightnessManager.getActiveModeState()
                    if (!syncGeneration.isCurrent(ticket) || keepActive == null) break
                    finalSuccess = BrightnessManager.startWatchdog(applicationContext, target, keepActive)
                    if (!syncGeneration.isCurrent(ticket)) break
                    if (!finalSuccess && keepActive) {
                        activeGuardFailed = true
                        finalSuccess = BrightnessManager.startWatchdog(applicationContext, target, false)
                    }
                    if (finalSuccess || !syncGeneration.isCurrent(ticket)) break
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
                if (syncGeneration.isCurrent(ticket)) isSyncing.set(false)
            }

            if (!syncGeneration.isCurrent(ticket)) return@executeIo
            if (finalSuccess) {
                ControlStateStore.setTakeoverActive(this, true)
                ControlStateStore.setTargetBrightness(this, target)
            }
            val rootAvailable = if (finalSuccess) true else ShellUtils.isRootAvailable()
            mainHandler.post {
                if (overlayView === panel && panel != null && syncGeneration.isCurrent(ticket)) {
                    if (finalSuccess) lastTargetValue = target
                    if (activeGuardFailed) showToast("亮度守护已恢复，但背屏唤醒守护未启动")
                    if (!finalSuccess) {
                        val message = if (rootAvailable) {
                            "亮度被系统持续覆盖，请重试"
                        } else {
                            "Root 未授权"
                        }
                        showToast(message)
                    }
                    updateStatusLabels()
                }
            }
        }
    }

    private fun stopSync() {
        syncGeneration.cancel()
        isSyncing.set(false)
    }

    private fun refreshFullUi() {
        val activeModeVersionAtStart = activeModeVersion
        val panel = overlayView
        val ticket = syncGeneration.current()
        executeIo {
            if (overlayView !== panel || destroyed) return@executeIo
            val max = BrightnessManager.getMaxBrightness()
            val current = BrightnessManager.getCurrentBrightness()
            val state = BrightnessManager.getCurrentState()
            val aodEnabled = BrightnessManager.isRearAodEnabled()
            val activeModeEnabled = BrightnessManager.getActiveModeState()
            val activeModeWatchdogReady = BrightnessManager.ensureActiveModeWatchdog(applicationContext)
            val watchdog = BrightnessManager.watchdogState(applicationContext)
            val rootAvailable = ShellUtils.isRootAvailable()

            mainHandler.post {
                if (overlayView !== panel || panel == null || destroyed) return@post
                lastWatchdogState = watchdog
                if (max > 0) brightnessSlider.setMax(max)
                maxVal.text = if (max > 0) max.toString() else "—"
                currentVal.text = if (current >= 0) current.toString() else "—"
                rootStatus.visibility = if (rootAvailable) View.GONE else View.VISIBLE

                if (syncGeneration.isCurrent(ticket) && !isUserSliding && !isSyncing.get()) {
                if (state == BrightnessManager.BrightnessState.SYSTEM) {
                    ControlStateStore.setTakeoverActive(this, false)
                    brightnessSlider.setProgress(0)
                    targetVal.text = "—"
                    lastTargetValue = -1
                } else {
                    if (state == BrightnessManager.BrightnessState.LOCKED) {
                        ControlStateStore.setTakeoverActive(this, watchdog?.let { it.running && it.target >= 10 && it.brightnessHealthy } == true)
                    }
                    val target = watchdog?.target?.takeIf { it >= 10 }
                        ?: ControlStateStore.getTargetBrightness(this).takeIf { it >= 10 }
                        ?: current.takeIf { it >= 10 }
                    if (target != null) brightnessSlider.setProgress(target)
                    targetVal.text = target?.toString() ?: "—"
                    lastTargetValue = target ?: -1
                }
                }

                aodText.text = when (aodEnabled) { true -> "AOD\n已开启"; false -> "AOD\n已关闭"; null -> "AOD\n读取失败" }
                aodText.setTextColor(color(R.color.text_primary))
                if (activeModeVersionAtStart == activeModeVersion && !activeModeChangeInFlight) {
                    renderActiveMode(activeModeEnabled)
                    if (activeModeEnabled == true && !activeModeWatchdogReady) {
                        activeModeText.text = "永不息屏\n守护异常"
                        ControlStateStore.setActiveModeEnabled(this, false)
                        showToast("背屏唤醒守护未启动，请检查 Root 与设备支持")
                    }
                }
                updateStatusLabels(rootAvailable, state)
            }
        }
    }

    private fun updateCurrentBrightness() {
        if (!refreshInFlight.compareAndSet(false, true)) return
        val panel = overlayView
        executeIo {
            if (destroyed || overlayView !== panel) { refreshInFlight.set(false); return@executeIo }
            val current = BrightnessManager.getCurrentBrightness()
            val now = android.os.SystemClock.elapsedRealtime()
            val checkedHealth = now >= nextHealthRead
            val health = if (checkedHealth) {
                nextHealthRead = now + 5000
                BrightnessManager.watchdogState(applicationContext)
            } else null
            mainHandler.post {
                refreshInFlight.set(false)
                if (overlayView !== panel || panel == null || destroyed) return@post
                currentVal.text = if (current >= 0) current.toString() else "—"
                if (checkedHealth && !isSyncing.get() && !activeModeChangeInFlight) {
                    lastWatchdogState = health
                    val brightnessHealthy = health?.let { it.running && it.target >= 10 && it.brightnessHealthy } == true
                    val activeHealthy = health?.let { it.running && it.active && it.activeHealthy } == true
                    ControlStateStore.setTakeoverActive(this, brightnessHealthy)
                    ControlStateStore.setActiveModeEnabled(this, activeHealthy)
                    if (lastTargetValue >= 10) setStatus(if (brightnessHealthy) "亮度已接管" else "亮度守护异常", color(if (brightnessHealthy) R.color.success else R.color.error))
                    if (activeModeEnabled == true) activeModeText.text = if (activeHealthy) "永不息屏\n已开启" else "永不息屏\n守护异常"
                }
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
            val health = BrightnessManager.watchdogState(applicationContext)
            mainHandler.post { lastWatchdogState = health; renderStatus(rootAvailable, state) }
        }
    }

    private fun renderStatus(rootAvailable: Boolean, state: BrightnessManager.BrightnessState) {
        if (overlayView == null) return
        rootStatus.visibility = if (rootAvailable) View.GONE else View.VISIBLE

        when {
            isSyncing.get() -> setStatus("正在同步", color(R.color.accent))
            !rootAvailable -> setStatus("等待 Root 授权", color(R.color.error))
            state == BrightnessManager.BrightnessState.UNKNOWN -> setStatus("状态读取失败", color(R.color.error))
            lastTargetValue == -1 || state == BrightnessManager.BrightnessState.SYSTEM -> {
                setStatus("系统控制中", color(R.color.text_secondary))
                if (!isUserSliding) {
                    targetVal.text = "—"
                    brightnessSlider.setProgress(0)
                    lastTargetValue = -1
                }
            }
            lastWatchdogState?.let { it.running && it.target >= 10 && it.brightnessHealthy } != true ->
                setStatus("亮度守护异常", color(R.color.error))
            else -> setStatus("亮度已接管", color(R.color.success))
        }
    }

    private fun setStatus(text: String, color: Int) {
        takeoverStatus.text = text
        takeoverStatus.setTextColor(color)
    }

    private fun renderActiveMode(enabled: Boolean?) {
        activeModeEnabled = enabled
        if (enabled != null) ControlStateStore.setActiveModeEnabled(this, enabled)
        activeModeButton.isEnabled = enabled != null && !activeModeChangeInFlight
        activeModeText.text = when (enabled) {
            true -> "永不息屏\n已开启"
            false -> "永不息屏\n已关闭"
            null -> "永不息屏\n状态未知"
        }
        activeModeText.setTextColor(color(R.color.warning_text))
        activeModeButton.contentDescription = activeModeText.text.toString().replace('\n', ' ')
        activeModeHint.visibility = if (enabled == true) View.VISIBLE else View.GONE
        if (enabled == true && !hintBesideCard) {
            activeModeHint.post {
                val card = overlayView?.findViewById<View>(R.id.dialogCard) ?: return@post
                activeModeHint.translationY = (card.height + activeModeHint.height) / 2f + dp(12)
            }
        }
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
        if (isClosing) return
        isClosing = true
        mainHandler.removeCallbacks(refreshRunnable)
        stopSync()
        val view = overlayView
        if (view != null && view.parent != null) {
            animatePanel(view, 0f) {
                if (overlayView === view && isClosing) finishCloseOverlay()
            }
        } else {
            finishCloseOverlay()
        }
    }

    private fun animatePanel(view: View, targetAlpha: Float, onEnd: (() -> Unit)? = null) {
        cancelPanelAnimation()
        val animator = ValueAnimator.ofFloat(view.alpha, targetAlpha).apply {
            duration = PANEL_FADE_MS
            addUpdateListener { frame ->
                val opacity = frame.animatedValue as Float
                view.alpha = opacity
                setPanelBlur(view, opacity)
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (panelAnimator === animation) {
                        panelAnimator = null
                        onEnd?.invoke()
                    }
                }
            })
        }
        panelAnimator = animator
        animator.start()
    }

    private fun setPanelBlur(view: View, opacity: Float) {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S) return
        activityHost?.let {
            it.setPanelBlurFraction(opacity)
            return
        }
        val params = overlayWindowParams ?: return
        if (view.parent == null || !windowManager.isCrossWindowBlurEnabled) return
        params.setBlurBehindRadius((dp(28) * opacity).toInt())
        runCatching { windowManager.updateViewLayout(view, params) }
    }

    private fun cancelPanelAnimation() {
        val animator = panelAnimator
        panelAnimator = null
        animator?.cancel()
    }

    private fun finishCloseOverlay() {
        cancelPanelAnimation()
        overlayView?.let {
            removePanelView(it)
        }
        overlayView = null
        isClosing = false
        sendBroadcast(Intent(ACTION_OVERLAY_CLOSED).setPackage(packageName))
        stopSelf()
    }

    private fun showToast(message: String, duration: Int = Toast.LENGTH_SHORT) {
        Toast.makeText(this, message, duration).show()
    }

    private fun executeIo(block: () -> Unit) {
        if (!destroyed) ControlQueue.execute(block)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun color(resourceId: Int): Int = ContextCompat.getColor(this, resourceId)

    private fun removePanelView(view: View) {
        if (activityHost != null) {
            (view.parent as? ViewGroup)?.removeView(view)
            activityHost = null
        } else {
            runCatching { windowManager.removeViewImmediate(view) }
            overlayWindowParams = null
        }
    }

    override fun onDestroy() {
        destroyed = true
        mainHandler.removeCallbacksAndMessages(null)
        stopSync()
        cancelPanelAnimation()
        overlayView?.let {
            removePanelView(it)
        }
        overlayView = null
        ControlQueue.execute { ShellUtils.destroy() }
        super.onDestroy()
    }

    companion object {
        private const val PANEL_FADE_MS = 180L
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
