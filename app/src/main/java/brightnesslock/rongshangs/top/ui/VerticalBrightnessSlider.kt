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
    
    private var sunIcon: Bitmap? = null
    private var onProgressChanged: ((Int) -> Unit)? = null

    init {
        val drawable = ContextCompat.getDrawable(context, R.drawable.ic_sun)
        drawable?.let {
            val bitmap = Bitmap.createBitmap(it.intrinsicWidth, it.intrinsicHeight, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            it.setBounds(0, 0, canvas.width, canvas.height)
            it.draw(canvas)
            sunIcon = bitmap
        }
    }

    fun setMax(max: Int) {
        maxBrightness = max
        invalidate()
    }

    fun setProgress(value: Int) {
        progress = value.toFloat() / maxBrightness
        invalidate()
    }

    fun setOnProgressChangedListener(listener: (Int) -> Unit) {
        onProgressChanged = listener
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val radius = w / 2

        // Draw background
        canvas.drawRoundRect(0f, 0f, w, h, radius, radius, bgPaint)

        // Draw progress
        val progressHeight = h * progress
        canvas.drawRoundRect(0f, h - progressHeight, w, h, radius, radius, progressPaint)

        // Draw icon
        sunIcon?.let {
            val iconX = (w - it.width) / 2
            // Icon position logic: if progress is high enough, icon is on white background, otherwise on dark
            // But reference shows icon roughly in the middle of the progress bar or fixed position
            // Let's place it at 1/4 from the bottom of the widget
            val iconY = h - (h * 0.25f) - (it.height / 2)
            
            // To ensure icon is visible on white background, we might need a color filter or just use a contrasting color
            // Reference has an orange sun.
            canvas.drawBitmap(it, iconX, iconY, null)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                val y = event.y
                progress = 1.0f - (y / height).coerceIn(0f, 1f)
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP -> {
                val finalValue = (progress * maxBrightness).toInt()
                onProgressChanged?.invoke(finalValue)
                return true
            }
        }
        return super.onTouchEvent(event)
    }
}
