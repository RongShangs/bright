package brightnesslock.rongshangs.top

import android.view.LayoutInflater
import android.view.ContextThemeWrapper
import android.widget.FrameLayout
import androidx.appcompat.widget.SwitchCompat
import brightnesslock.rongshangs.top.hook.HookBridge
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@LooperMode(LooperMode.Mode.PAUSED)
class PanelStartupTest {
    @Test fun applicationStartsWithoutAnXposedFramework() {
        assertTrue(RuntimeEnvironment.getApplication() is BrightApplication)
        assertFalse(HookBridge.state.editable)
    }

    @Test fun settingsInflateFromServiceContextAndBindBothSwitches() {
        val controller = Robolectric.buildService(BrightnessOverlayService::class.java).create()
        try {
            val service = controller.get()
            val context = ContextThemeWrapper(service, service.applicationInfo.theme)
            val card = LayoutInflater.from(context).inflate(R.layout.dialog_hooks, FrameLayout(context), false)
            for (id in listOf(R.id.activeModeSwitch, R.id.blockDarkSwitch, R.id.blockCoverSwitch)) {
                val switch = card.findViewById<SwitchCompat>(id)
                assertNotNull(switch)
                assertFalse(switch.showText)
                assertNotNull(switch.thumbDrawable)
                assertNotNull(switch.trackDrawable)
            }
            card.measure(android.view.View.MeasureSpec.makeMeasureSpec(380, android.view.View.MeasureSpec.EXACTLY),
                android.view.View.MeasureSpec.makeMeasureSpec(328, android.view.View.MeasureSpec.EXACTLY))
            card.layout(0, 0, card.measuredWidth, card.measuredHeight)
        } finally {
            controller.destroy()
        }
    }

    @Test fun primaryPanelKeepsOriginalShortProportions() {
        val context = ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.Theme_背屏激发)
        val card = LayoutInflater.from(context).inflate(R.layout.dialog_main, FrameLayout(context), false)
        card.measure(android.view.View.MeasureSpec.makeMeasureSpec(380, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(1200, android.view.View.MeasureSpec.AT_MOST))
        assertEquals((220 * context.resources.displayMetrics.density).toInt(), card.measuredHeight)
        assertNotNull(card.findViewById<android.view.View>(R.id.policyEntryBtn))
        assertEquals("背屏\n策略", card.findViewById<android.widget.TextView>(R.id.policyEntryText).text.toString())
        assertNull(card.findViewById<android.view.View>(R.id.activeModeSwitch))
        assertNull(card.findViewById<android.view.View>(R.id.blockDarkSwitch))
        assertNull(card.findViewById<android.view.View>(R.id.blockCoverSwitch))
    }

    @Test fun settingsPageReturnsToTheSamePrimaryPanel() {
        val controller = Robolectric.buildService(BrightnessOverlayService::class.java).create()
        try {
            val service = controller.get()
            val context = ContextThemeWrapper(service, service.applicationInfo.theme)
            val root = FrameLayout(context)
            val main = LayoutInflater.from(context).inflate(R.layout.dialog_main, root, false)
            root.addView(main, FrameLayout.LayoutParams(380, FrameLayout.LayoutParams.WRAP_CONTENT))
            root.layout(0, 0, 380, 800)
            ReflectionHelpers.setField(service, "overlayView", root)
            ReflectionHelpers.setField(service, "primaryCard", main)
            ReflectionHelpers.setField(service, "activeModeHint", android.widget.LinearLayout(context))
            ReflectionHelpers.callInstanceMethod<Unit>(service, "bindViews",
                ReflectionHelpers.ClassParameter.from(android.view.View::class.java, root))
            ReflectionHelpers.callInstanceMethod<Unit>(service, "showHookSettings")
            assertEquals(android.view.View.GONE, main.visibility)
            val settings = root.findViewById<android.view.View>(R.id.hookSettingsCard)
            assertNotNull(settings)
            val active = settings.findViewById<SwitchCompat>(R.id.activeModeSwitch)
            assertNotNull(active)
            // Root-only control must remain usable even with no LSP installed.
            ReflectionHelpers.callInstanceMethod<Unit>(service, "renderActiveMode",
                ReflectionHelpers.ClassParameter.from(Boolean::class.javaObjectType, true))
            assertTrue(active.isChecked)
            assertTrue(active.isEnabled)
            assertFalse(ReflectionHelpers.getField<Boolean>(service, "activeModeChangeInFlight"))
            assertFalse(settings.findViewById<SwitchCompat>(R.id.blockDarkSwitch).isEnabled)
            assertFalse(settings.findViewById<SwitchCompat>(R.id.blockCoverSwitch).isEnabled)
            ReflectionHelpers.setField(service, "activeGuardUnhealthy", true)
            ReflectionHelpers.callInstanceMethod<Unit>(service, "renderActiveMode",
                ReflectionHelpers.ClassParameter.from(Boolean::class.javaObjectType, true))
            assertTrue(active.isChecked) // The device setting is still enabled; allow turning it off.
            assertFalse(brightnesslock.rongshangs.top.util.ControlStateStore.isActiveModeEnabled(context))
            assertTrue(settings.findViewById<android.widget.TextView>(R.id.activeModeStatus).text.contains("守护异常"))
            // Avoid starting hardware reads in this view-only regression test.
            ReflectionHelpers.setField(service, "destroyed", true)
            settings.findViewById<android.view.View>(R.id.hookSettingsBack).performClick()
            assertEquals(android.view.View.VISIBLE, main.visibility)
            assertNull(root.findViewById<android.view.View>(R.id.hookSettingsCard))
            assertSame(main, root.findViewById<android.view.View>(R.id.dialogCard))
        } finally {
            controller.destroy()
        }
    }

    @Test @Config(qualifiers = "land-night")
    fun policyPageCanScrollOnShortDarkLandscapeScreens() {
        val context = ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.Theme_背屏激发)
        RuntimeEnvironment.setFontScale(1.5f)
        val page = LayoutInflater.from(context).inflate(R.layout.dialog_hooks, FrameLayout(context), false) as android.widget.ScrollView
        val density = context.resources.displayMetrics.density
        val width = (380 * density).toInt()
        val height = (240 * density).toInt()
        page.measure(android.view.View.MeasureSpec.makeMeasureSpec(width, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(height, android.view.View.MeasureSpec.EXACTLY))
        page.layout(0, 0, width, height)
        assertTrue(page.getChildAt(0).height > page.height)
        page.scrollTo(0, page.getChildAt(0).height)
        assertTrue(page.scrollY > 0)
        assertNotNull(page.findViewById<SwitchCompat>(R.id.activeModeSwitch))
    }
}
