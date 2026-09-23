package brightnesslock.rongshangs.top

import java.lang.ref.WeakReference

/** The Activity window used by the Quick Settings entry. */
internal object OverlayHost {
    private var reference: WeakReference<MainActivity>? = null

    fun attach(activity: MainActivity) {
        reference = WeakReference(activity)
    }

    fun current(): MainActivity? = reference?.get()?.takeUnless { it.isFinishing || it.isDestroyed }

    fun detach(activity: MainActivity) {
        if (reference?.get() === activity) reference = null
    }
}
