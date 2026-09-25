package brightnesslock.rongshangs.top.util

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.Process

/** Only kills this APK process, never force-stops the package or touches the native guardian. */
object IdleProcessExit {
    private val handler = Handler(Looper.getMainLooper())
    private val gate = IdleExitGate()

    fun acquire(owner: Any) {
        check(Looper.myLooper() == Looper.getMainLooper())
        gate.acquire(owner)
        handler.removeCallbacksAndMessages(null)
    }

    fun release(context: Context, owner: Any) {
        check(Looper.myLooper() == Looper.getMainLooper())
        gate.release(owner)
        schedule(context.applicationContext)
    }

    private fun schedule(context: Context) {
        val ticket = gate.ticket() ?: return
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({
            if (!gate.accepts(ticket)) return@postDelayed
            // Drain accepted mutations, then let their UI callbacks finish before flushing prefs.
            ControlQueue.execute {
                handler.post {
                    if (!gate.accepts(ticket)) return@post
                    ControlQueue.execute {
                        ShellUtils.destroy()
                        val persisted = context.getSharedPreferences("config", Context.MODE_PRIVATE)
                            .edit().commit()
                        handler.postDelayed({
                            if (gate.accepts(ticket)) {
                                if (persisted && ControlQueue.isIdle()) Process.killProcess(Process.myPid())
                                else schedule(context)
                            }
                        }, 250)
                    }
                }
            }
        }, 750)
    }
}
