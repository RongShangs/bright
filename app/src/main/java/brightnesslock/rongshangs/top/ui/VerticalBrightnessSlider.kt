package brightnesslock.rongshangs.top.ui

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import brightnesslock.rongshangs.top.R

class VerticalBrightnessSlider @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var progress = 0.5f // 0.0 to 1.0
    private var maxBrightness = 4095
    
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1A000000")
    }
    
    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
    }
    
    private val clipPath = Path()
    private val rectF = RectF()
    
    private var sunIcon: Bitmap? = null
    private var onProgressChanged: ((Int) -> Unit)? = null
    
    private var lastY = 0f

    init {
        val drawable = ContextCompat.getDrawable(context, R.drawable.ic_sun)
        drawable?.let {
            val bitmap = Bitmap.createBitmap(it.intrinsicWidth, it.intrinsicHeight, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            it.setBounds(0, 0, canvas.width, canvas.height)
            // Apply gray color filter to sun icon
            it.colorFilter = PorterDuffColorFilter(Color.parseColor("#999999"), PorterDuff.Mode.SRC_IN)
            it.draw(canvas)
            sunIcon = bitmap
        }
    }

    fun setMax(max: Int) {
        maxBrightness = max
        invalidate()
    }

    fun setProgress(value: Int) {
        progress = (value.toFloat() / maxBrightness).coerceIn(0f, 1f)
        invalidate()
    }

    fun setOnProgressChangedListener(listener: (Int) -> Unit) {
        onProgressChanged = listener
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val radius = 24 * resources.displayMetrics.density

        rectF.set(0f, 0f, w, h)
        
        // Draw background
        canvas.drawRoundRect(rectF, radius, radius, bgPaint)

        // Draw progress
        val progressHeight = h * progress
        if (progressHeight > 0) {
            canvas.save()
            clipPath.reset()
            clipPath.addRoundRect(rectF, radius, radius, Path.Direction.CW)
            canvas.clipPath(clipPath)
            canvas.drawRect(0f, h - progressHeight, w, h, progressPaint)
            canvas.restore()
        }

        // Draw icon
        sunIcon?.let {
            val iconX = (w - it.width) / 2
            val iconY = h - (h * 0.25f) - (it.height / 2)
            canvas.drawBitmap(it, iconX, iconY, null)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                lastY = event.y
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val deltaY = lastY - event.y
                val deltaProgress = deltaY / height
                progress = (progress + deltaProgress).coerceIn(0f, 1f)
                lastY = event.y
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP -> {
                val finalValue = (progress * maxBrightness).toInt()
                onProgressChanged?.invoke(finalValue)
                performClick()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        return super.performClick()
    }
}
