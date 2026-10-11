package top.rongshangs.brightnesslock

import android.app.Application
import top.rongshangs.brightnesslock.hook.HookBridge
import top.rongshangs.brightnesslock.util.IdleProcessExit

class BrightApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        HookBridge.initialize(this)
        IdleProcessExit.scheduleIfIdle(this)
    }
}
