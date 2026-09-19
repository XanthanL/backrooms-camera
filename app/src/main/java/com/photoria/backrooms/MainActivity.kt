package com.photoria.backrooms

import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.lifecycle.Lifecycle
import com.photoria.backrooms.ui.screen.CameraScreen
import com.photoria.backrooms.ui.theme.PhotoriaTheme
import com.photoria.backrooms.ui.viewmodel.CameraViewModel
import com.photoria.backrooms.util.KeyAction

class MainActivity : ComponentActivity() {

    /**
     * 与 [CameraScreen] 里 `viewModel()` 拿到的是同一个实例：
     * CameraScreen 由本 Activity 直接 setContent，其 CompositionLocal 里的
     * ViewModelStoreOwner 就是本 Activity，故二者共享同一个 ViewModel。
     */
    private val viewModel: CameraViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PhotoriaTheme {
                CameraScreen()
            }
        }
    }

    // ── 音量键快门 ────────────────────────────────────────────────
    //
    // 走 Activity 而非 Compose 的 Modifier.onKeyEvent：后者要求焦点落在可聚焦节点上，
    // 本屏只有那个 AndroidView，抽屉/弹窗任一重组都可能把焦点挤掉 —— 静默失效且没真机发现不了。
    // 消费掉 DOWN 之后系统就不会再弹音量条。

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (isVolume(keyCode)) return handleVolumeKey(keyCode, event, down = true)
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (isVolume(keyCode)) return handleVolumeKey(keyCode, event, down = false)
        return super.onKeyUp(keyCode, event)
    }

    private fun handleVolumeKey(keyCode: Int, event: KeyEvent, down: Boolean): Boolean {
        // 离开前台（切后台、锁屏、系统弹窗）时一律交还系统，绝不出片
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return superOnKey(keyCode, event, down)
        return when (viewModel.onVolumeKey(keyCode, event.repeatCount, down)) {
            KeyAction.PASS -> superOnKey(keyCode, event, down)
            else -> true
        }
    }

    private fun superOnKey(keyCode: Int, event: KeyEvent, down: Boolean): Boolean =
        if (down) super.onKeyDown(keyCode, event) else super.onKeyUp(keyCode, event)

    private fun isVolume(keyCode: Int): Boolean =
        keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN
}
