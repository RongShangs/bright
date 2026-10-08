package brightnesslock.rongshangs.top.hook

import android.content.Context
import android.annotation.SuppressLint
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.util.Log
import brightnesslock.rongshangs.top.util.ControlQueue
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper

/** Only the configuration UI uses this bridge. It does not hold an idle-exit owner. */
object HookBridge {
    enum class Connection { UNAVAILABLE, UNSUPPORTED, MISSING_SCOPE, CONNECTED }
    data class State(val connection: Connection = Connection.UNAVAILABLE, val dark: Boolean = false, val cover: Boolean = false,
        val uncertain: Boolean = false) {
        val editable: Boolean get() = connection == Connection.CONNECTED
        val knownDisabled: Boolean get() = !dark && !cover && !uncertain
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val observers = linkedSetOf<(State) -> Unit>() // Main thread only.
    @Volatile var state = State()
        private set
    @Volatile private var service: XposedService? = null
    private var remote: SharedPreferences? = null // ControlQueue only.
    private lateinit var cache: SharedPreferences

    fun initialize(context: Context) {
        cache = context.getSharedPreferences("hook_ui_cache", Context.MODE_PRIVATE)
        state = State(dark = cache.getBoolean(RearScreenPolicy.KEY_DARK, false), cover = cache.getBoolean(RearScreenPolicy.KEY_COVER, false),
            uncertain = cache.getBoolean("uncertain", false))
        XposedServiceHelper.registerListener(object : XposedServiceHelper.OnServiceListener {
            override fun onServiceBind(bound: XposedService) {
                service = bound
                ControlQueue.execute {
                    if (service !== bound) return@execute
                    try {
                        val connection = when {
                            bound.apiVersion < 101 || bound.frameworkProperties and XposedService.PROP_CAP_REMOTE == 0L -> Connection.UNSUPPORTED
                            RearScreenPolicy.TARGET_PACKAGE !in bound.scope -> Connection.MISSING_SCOPE
                            else -> Connection.CONNECTED
                        }
                        remote = if (connection == Connection.CONNECTED) bound.getRemotePreferences(RearScreenPolicy.PREF_GROUP) else null
                        val prefs = remote
                        val next = State(connection, prefs?.getBoolean(RearScreenPolicy.KEY_DARK, false) ?: state.dark,
                            prefs?.getBoolean(RearScreenPolicy.KEY_COVER, false) ?: state.cover,
                            if (prefs != null) false else state.uncertain)
                        if (prefs != null) persistCache(next)
                        publish(next)
                    } catch (error: RuntimeException) {
                        Log.w("BrightHookBridge", "Cannot connect remote preferences", error)
                        remote = null
                        publish(state.copy(connection = Connection.UNAVAILABLE))
                    }
                }
            }

            override fun onServiceDied(dead: XposedService) {
                if (service !== dead) return
                service = null
                ControlQueue.execute {
                    if (service == null) {
                        remote = null
                        publish(state.copy(connection = Connection.UNAVAILABLE))
                    }
                }
            }
        })
    }

    fun observe(observer: (State) -> Unit) {
        observers.add(observer)
        observer(state)
    }

    fun removeObserver(observer: (State) -> Unit) { observers.remove(observer) }

    /** Call on ControlQueue, never on the UI thread. False means no confirmed save. */
    fun setPolicy(dark: Boolean, cover: Boolean): Boolean {
        val before = state
        val prefs = remote ?: return false
        if (!before.editable || service == null) return false
        return try {
            val success = prefs.edit().putBoolean(RearScreenPolicy.KEY_DARK, dark)
                .putBoolean(RearScreenPolicy.KEY_COVER, cover).commit()
            if (success) {
                // Synchronous small cache commit: the App may exit immediately after closing its UI.
                val next = before.copy(dark = dark, cover = cover, uncertain = false)
                persistCache(next)
                publish(next)
            } else {
                // RemotePreferences updates its local map before Binder persistence. Invalidate it
                // on failure instead of showing that unconfirmed map as a successfully saved state.
                remote = null
                val next = before.copy(connection = Connection.UNAVAILABLE, uncertain = true)
                persistCache(next)
                publish(next)
            }
            success
        } catch (error: RuntimeException) {
            Log.w("BrightHookBridge", "Cannot save policy", error)
            remote = null
            val next = before.copy(connection = Connection.UNAVAILABLE, uncertain = true)
            persistCache(next)
            publish(next)
            false
        }
    }

    fun resetPolicy(): Boolean = if (state.editable) setPolicy(false, false) else state.knownDisabled

    @SuppressLint("ApplySharedPref") // Runs on ControlQueue; must finish before intentional process exit.
    private fun persistCache(next: State) {
        cache.edit().putBoolean(RearScreenPolicy.KEY_DARK, next.dark).putBoolean(RearScreenPolicy.KEY_COVER, next.cover)
            .putBoolean("uncertain", next.uncertain).commit()
    }

    private fun publish(next: State) {
        state = next
        mainHandler.post { observers.toList().forEach { it(state) } }
    }
}
