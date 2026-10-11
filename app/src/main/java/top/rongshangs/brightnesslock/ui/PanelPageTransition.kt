package top.rongshangs.brightnesslock.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator

/** UI-only, bounded animation. Finishing/cancelling always leaves a complete page. */
internal class PanelPageTransition {
    private var animator: ValueAnimator? = null
    private var completion: (() -> Unit)? = null
    val running: Boolean get() = animator != null

    fun start(from: View, to: View, forward: Boolean, onEnd: () -> Unit) {
        finish()
        if (!ValueAnimator.areAnimatorsEnabled()) {
            onEnd()
            return
        }
        val root = to.parent as ViewGroup
        // Prevent controls on either page being operated halfway through a transition.
        val shield = View(to.context).apply {
            isClickable = true
            elevation = maxOf(from.elevation, to.elevation) + 1f
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        root.addView(shield, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val distance = 12f * to.resources.displayMetrics.density * if (forward) 1 else -1
        to.alpha = 0f
        to.translationX = distance
        completion = {
            root.removeView(shield)
            from.alpha = 1f
            from.translationX = 0f
            to.alpha = 1f
            to.translationX = 0f
            onEnd()
        }
        val next = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 220L
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                val fraction = it.animatedValue as Float
                from.alpha = 1f - fraction
                from.translationX = -distance * fraction
                to.alpha = fraction
                to.translationX = distance * (1f - fraction)
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (animator === animation) finish()
                }
            })
        }
        animator = next
        next.start()
    }

    fun finish() {
        val previous = animator
        animator = null
        previous?.removeAllListeners()
        previous?.removeAllUpdateListeners()
        previous?.cancel()
        val action = completion
        completion = null
        action?.invoke()
    }
}
