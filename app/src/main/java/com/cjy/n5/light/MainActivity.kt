package com.cjy.n5.light

import android.content.Intent
import android.content.ComponentName
import android.content.ServiceConnection
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import android.graphics.Color
import android.view.WindowManager
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.core.view.WindowCompat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.cjy.n5.light.ui.theme.BlackTransparentTheme
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.focusable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

class MainActivity : ComponentActivity() {

    private var overlayService: OverlayService? = null
    private var isBound = false
    private var isServiceRunning = false
    private var isUIVisible by mutableStateOf(true)
    private var isKeepAliveMode by mutableStateOf(false)
    private var isTransparentKeepAlive by mutableStateOf(false)
    private var keepAliveJob: Job? = null
    private val keepAliveScope = CoroutineScope(Dispatchers.Main)
    private var hasPermission by mutableStateOf(false)

    companion object {
        private const val TAG = "MainActivity"
        private const val EXTRA_KEEP_ALIVE_MODE = "extra_keep_alive_mode"
        private const val EXTRA_UI_VISIBLE = "extra_ui_visible"
        private const val EXTRA_FROM_KEEP_ALIVE = "extra_from_keep_alive"
        private const val PREFS_NAME = "keep_alive_prefs"
        private const val PREF_KEEP_ALIVE_MODE = "pref_keep_alive_mode"
        private const val PREF_UI_VISIBLE = "pref_ui_visible"
    }

    private val sharedPrefs by lazy {
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private fun saveKeepAliveState() {
        with(sharedPrefs.edit()) {
            putBoolean(PREF_KEEP_ALIVE_MODE, isKeepAliveMode)
            putBoolean(PREF_UI_VISIBLE, isUIVisible)
            apply()
        }
    }

    private fun loadKeepAliveState() {
        isKeepAliveMode = sharedPrefs.getBoolean(PREF_KEEP_ALIVE_MODE, false)
        isUIVisible = sharedPrefs.getBoolean(PREF_UI_VISIBLE, true)
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            val binder = service as OverlayService.OverlayBinder
            overlayService = binder.getService()
            isBound = true
        }

        override fun onServiceDisconnected(arg0: ComponentName) {
            overlayService = null
            isBound = false
        }
    }

    private fun bindOverlayService() {
        val intent = Intent(this, OverlayService::class.java)
        try {
            bindService(intent, connection, Context.BIND_AUTO_CREATE)
        } catch (e: Exception) {
            Toast.makeText(this, "绑定服务失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun unbindOverlayService() {
        if (isBound) {
            unbindService(connection)
            isBound = false
            overlayService = null
        }
    }

    private fun isOverlayServiceRunning(): Boolean {
        // 检查服务是否正在运行
        val manager = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val runningServices = manager.getRunningServices(Integer.MAX_VALUE)
        return runningServices.any { it.service.className == OverlayService::class.java.name }
    }

    private fun stopOverlayService() {
        val intent = Intent(this, OverlayService::class.java)
        stopService(intent)
    }

    private fun updateOverlayAlpha(alpha: Float) {
        if (isBound && overlayService != null) {
            overlayService?.setAlpha(alpha)
        } else {
            Toast.makeText(this, "未连接到服务，无法设置透明度", Toast.LENGTH_SHORT).show()
        }
    }

    private fun getCurrentAlpha(): Float {
        return if (isBound && overlayService != null) {
            overlayService?.getAlpha() ?: 0.5f
        } else {
            0.5f // 默认值
        }
    }


    private fun setupTransparentBars() {
        // 设置透明状态栏和导航栏
        WindowCompat.setDecorFitsSystemWindows(window, false)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            window.statusBarColor = Color.TRANSPARENT
            window.navigationBarColor = Color.TRANSPARENT
            window.addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)

            // 对于Android 10+，使用更现代的方法
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                window.isNavigationBarContrastEnforced = false
                window.isStatusBarContrastEnforced = false
            }
        }

        // 设置状态栏和导航栏图标颜色（浅色图标）
        val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
        windowInsetsController.isAppearanceLightStatusBars = false
        windowInsetsController.isAppearanceLightNavigationBars = false
    }

    private fun updateWindowFlags() {
        if (isUIVisible && !isTransparentKeepAlive) {
            window.clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
            window.clearFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
            window.clearFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL)
            window.decorView.visibility = android.view.View.VISIBLE
        } else {
            window.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
            window.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
            window.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL)
            window.decorView.visibility = android.view.View.INVISIBLE
        }
    }

    private fun toggleUIVisibility() {
        val newVisibility = !isUIVisible
        isUIVisible = newVisibility
        Log.d(TAG, "toggleUIVisibility: isUIVisible=$newVisibility")

        updateWindowFlags()

        if (newVisibility) {
            Log.d(TAG, "toggleUIVisibility: UI 变为可见，停止保活")
            stopKeepAliveLoop()
            isKeepAliveMode = false
        } else {
            Log.d(TAG, "toggleUIVisibility: UI 变为隐藏，启动保活")
            isKeepAliveMode = true
            startKeepAliveLoop()
        }

        saveKeepAliveState()
    }

    private fun startKeepAliveLoop() {
        Log.d(TAG, "startKeepAliveLoop: 启动保活循环")
        if (keepAliveJob?.isActive == true) {
            keepAliveJob?.cancel()
        }

        keepAliveJob = keepAliveScope.launch {
            var loopCount = 0
            while (isKeepAliveMode && isActive) {
                loopCount++
                Log.d(TAG, "保活循环 #$loopCount: 切到后台")
                moveTaskToBack(true)
                delay(25000L)

                if (!isActive) break

                Log.d(TAG, "保活循环 #$loopCount: 切回前台")
                val intent = Intent(this@MainActivity, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    putExtra(EXTRA_KEEP_ALIVE_MODE, true)
                    putExtra(EXTRA_UI_VISIBLE, false)
                    putExtra(EXTRA_FROM_KEEP_ALIVE, true)
                }
                startActivity(intent)
            }
        }
    }

    private fun stopKeepAliveLoop() {
        Log.d(TAG, "stopKeepAliveLoop: 停止保活循环")
        isKeepAliveMode = false
        keepAliveJob?.cancel()
        keepAliveJob = null
        saveKeepAliveState()
    }

    private fun handleIntent(intent: Intent) {
        val keepAliveMode = intent.getBooleanExtra(EXTRA_KEEP_ALIVE_MODE, false)
        val uiVisible = intent.getBooleanExtra(EXTRA_UI_VISIBLE, true)
        val fromKeepAlive = intent.getBooleanExtra(EXTRA_FROM_KEEP_ALIVE, false)

        Log.d(TAG, "handleIntent: keepAliveMode=$keepAliveMode, uiVisible=$uiVisible, fromKeepAlive=$fromKeepAlive")

        if (keepAliveMode && fromKeepAlive) {
            Log.d(TAG, "handleIntent: 来自保活循环，保持保活模式（完全透明）")
            isKeepAliveMode = true
            isUIVisible = uiVisible
            isTransparentKeepAlive = true

            if (isKeepAliveMode && !isUIVisible && (keepAliveJob == null || !keepAliveJob!!.isActive)) {
                Log.d(TAG, "handleIntent: 保活循环未运行，重新启动")
                startKeepAliveLoop()
            }

            saveKeepAliveState()
            updateWindowFlags()
        } else {
            isTransparentKeepAlive = false
            if (isKeepAliveMode) {
                Log.d(TAG, "handleIntent: 用户手动打开，停止保活并显示UI")
                stopKeepAliveLoop()
                isUIVisible = true
                saveKeepAliveState()
            } else {
                Log.d(TAG, "handleIntent: 用户手动打开，当前不在保活模式")
            }
            updateWindowFlags()
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        intent?.let { handleIntent(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setupTransparentBars()
        loadKeepAliveState()
        handleIntent(intent)

        // 检查悬浮窗权限
        hasPermission = hasOverlayPermission()
        // 检查服务是否正在运行
        isServiceRunning = isOverlayServiceRunning()

        setContent {
            BlackTransparentTheme {
                if (isTransparentKeepAlive) {
                    // 保活自动切前台时完全透明，不渲染任何内容
                    return@BlackTransparentTheme
                }
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = ComposeColor.Transparent
                ) {
                    val context = LocalContext.current

                    if (!hasPermission) {
                        PermissionRequestScreen(
                            onRequestPermission = { requestOverlayPermission() }
                        )
                    } else {
                        try {
                            if (!isServiceRunning) {
                                startOverlayService()
                            }
                        } catch (e: Exception) {}

                        BrightnessControlScreen(
                            onReturn = { moveTaskToBack(true) },
                            onTogglePip = { enabled ->
                                if (enabled) startPipKeepAlive()
                                else PipKeepAliveActivity.instance?.finish()
                            },
                            onStopService = {
                                stopKeepAliveLoop()
                                unbindOverlayService()
                                stopOverlayService()
                                finish()
                            },
                            initialAlpha = getCurrentAlpha(),
                            onAlphaChange = { alpha ->
                                updateOverlayAlpha(alpha)
                            },
                            isUIVisible = isUIVisible,
                            onToggleUI = { toggleUIVisibility() }
                        )
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        setupTransparentBars()
        hasPermission = hasOverlayPermission()
        isServiceRunning = isOverlayServiceRunning()

        if (hasPermission && !isServiceRunning) {
            try {
                startOverlayService()
                isServiceRunning = true
            } catch (_: Exception) {}
        }

        updateWindowFlags()
    }

    override fun onStart() {
        super.onStart()
        // 尝试绑定服务，即使服务可能刚刚启动
        // 使用BIND_AUTO_CREATE标志，如果服务不存在会自动创建
        if (hasOverlayPermission()) {
            bindOverlayService()
        }
    }

    override fun onPause() {
        super.onPause()
    }

    override fun onStop() {
        super.onStop()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopKeepAliveLoop()
        unbindOverlayService()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            if (!hasOverlayPermission()) {
                startOverlayService()
                finish()
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun startPipKeepAlive() {
        Log.d(TAG, "startPipKeepAlive: 点击画中画保活按钮, SDK=${Build.VERSION.SDK_INT}")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Log.d(TAG, "startPipKeepAlive: 启动PipKeepAliveActivity")
            val intent = Intent(this, PipKeepAliveActivity::class.java)
            startActivity(intent)
        } else {
            Log.w(TAG, "startPipKeepAlive: 系统版本过低, 不支持画中画")
            Toast.makeText(this, "画中画需要 Android 8.0 及以上系统", Toast.LENGTH_SHORT).show()
        }
    }

    private fun hasOverlayPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(this)
        } else {
            // Android 6.0以下默认有权限
            true
        }
    }

    private fun startOverlayService() {
        val intent = Intent(this, OverlayService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                startForegroundService(intent)
            } catch (e: SecurityException) {
                Toast.makeText(this, "启动服务失败: ${e.message}", Toast.LENGTH_LONG).show()
                throw e
            } catch (e: Exception) {
                Toast.makeText(this, "启动服务异常: ${e.message}", Toast.LENGTH_LONG).show()
                throw e
            }
        } else {
            try {
                startService(intent)
            } catch (e: SecurityException) {
                Toast.makeText(this, "启动服务失败: ${e.message}", Toast.LENGTH_LONG).show()
                throw e
            } catch (e: Exception) {
                Toast.makeText(this, "启动服务异常: ${e.message}", Toast.LENGTH_LONG).show()
                throw e
            }
        }
    }

    private fun requestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivity(intent)
            } catch (e: Exception) {
                Toast.makeText(this, "启动权限设置失败: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
        // Android 6.0以下不需要请求权限
    }
}
