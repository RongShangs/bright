package top.rongshangs.brightnesslock.ui

import android.animation.ValueAnimator
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import java.time.Duration
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@LooperMode(LooperMode.Mode.PAUSED)
class PanelPageTransitionTest {
    private val context = RuntimeEnvironment.getApplication()
    private val root = FrameLayout(context)
    private val main = View(context).also(root::addView)
    private val settings = View(context).also(root::addView)
    private val transition = PanelPageTransition()

    @Test fun crossfadeCompletesAndRemovesTouchShield() {
        var completed = 0
        transition.start(main, settings, true) { completed++; main.visibility = View.GONE }
        assertTrue(transition.running)
        assertEquals(3, root.childCount)
        assertEquals(0f, settings.alpha, 0.001f)
        // Robolectric compresses animator duration; seek explicitly to verify a middle frame.
        ReflectionHelpers.getField<ValueAnimator>(transition, "animator").setCurrentFraction(0.5f)
        assertTrue(settings.alpha > 0f && settings.alpha < 1f)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300))
        assertFalse(transition.running)
        assertEquals(1, completed)
        assertEquals(2, root.childCount)
        assertEquals(View.GONE, main.visibility)
        assertEquals(1f, settings.alpha, 0f)
        assertEquals(0f, settings.translationX, 0f)
    }

    @Test fun teardownFinishesOnlyOnceWithoutLateCallback() {
        var completed = 0
        transition.start(main, settings, true) { completed++ }
        transition.finish()
        transition.finish()
        root.removeAllViews()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(350))
        assertEquals(1, completed)
        assertFalse(transition.running)
        assertEquals(0, root.childCount)
        assertEquals(1f, main.alpha, 0f)
        assertEquals(0f, settings.translationX, 0f)
    }

    @Test fun rapidReturnFinishesPreviousBeforeStartingNext() {
        var completed = 0
        transition.start(main, settings, true) { completed++ }
        transition.start(settings, main, false) { completed++; root.removeView(settings) }
        assertEquals(1, completed)
        assertEquals(3, root.childCount)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(350))
        assertEquals(2, completed)
        assertEquals(1, root.childCount)
        assertEquals(1f, main.alpha, 0f)
        assertEquals(0f, main.translationX, 0f)
    }

    @Test fun systemDisabledAnimationsSwitchImmediately() {
        ReflectionHelpers.callStaticMethod<Unit>(ValueAnimator::class.java, "setDurationScale",
            ReflectionHelpers.ClassParameter.from(Float::class.javaPrimitiveType, 0f))
        try {
            var completed = false
            transition.start(main, settings, true) { completed = true }
            assertTrue(completed)
            assertFalse(transition.running)
            assertEquals(2, root.childCount)
        } finally {
            ReflectionHelpers.callStaticMethod<Unit>(ValueAnimator::class.java, "setDurationScale",
                ReflectionHelpers.ClassParameter.from(Float::class.javaPrimitiveType, 1f))
        }
    }
}
