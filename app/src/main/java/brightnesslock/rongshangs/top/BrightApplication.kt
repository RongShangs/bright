package brightnesslock.rongshangs.top

import android.app.Application
import brightnesslock.rongshangs.top.hook.HookBridge
import brightnesslock.rongshangs.top.util.IdleProcessExit

class BrightApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        HookBridge.initialize(this)
        IdleProcessExit.scheduleIfIdle(this)
    }
}
