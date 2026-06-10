package brightnesslock.rongshangs.top

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import brightnesslock.rongshangs.top.util.BrightnessManager
import brightnesslock.rongshangs.top.util.ShellUtils

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Check root first
        if (!ShellUtils.isRootAvailable()) {
            Toast.makeText(this, "戎：未检测到 Root 权限", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        val currentState = BrightnessManager.getCurrentState()
        val success = if (currentState == BrightnessManager.BrightnessState.LOCKED) {
            BrightnessManager.restoreSystemControl()
        } else {
            BrightnessManager.lockBrightness()
        }

        if (success) {
            val newState = BrightnessManager.getCurrentState()
            val msg = if (newState == BrightnessManager.BrightnessState.LOCKED) "戎：已激发背屏亮度" else "戎：已恢复系统控制"
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "戎：执行失败", Toast.LENGTH_SHORT).show()
        }

        finish()
    }
}
