package com.cjy.blacktransparent

import android.content.Intent
import android.content.ComponentName
import android.content.ServiceConnection
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
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
import com.cjy.blacktransparent.ui.theme.BlackTransparentTheme
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.focusable
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type

class MainActivity : ComponentActivity() {

    private var overlayService: OverlayService? = null
    private var isBound = false
    private var isServiceRunning = false
    private var isUIVisible by mutableStateOf(true)
    private var isKeepAliveMode by mutableStateOf(false)
    private var keepAliveJob: Job? = null
    private val keepAliveScope = CoroutineScope(Dispatchers.Main)

    companion object {
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

    private fun toggleUIVisibility() {
        val newVisibility = !isUIVisible
        isUIVisible = newVisibility

        if (newVisibility) {
            // UI变为可见，停止保活循环
            stopKeepAliveLoop()
            isKeepAliveMode = false
        } else {
            // UI变为隐藏，启动保活循环
            isKeepAliveMode = true
            startKeepAliveLoop()
        }

        // 保存状态
        saveKeepAliveState()
    }

    private fun startKeepAliveLoop() {
        // 如果已有活跃的循环任务，先取消
        if (keepAliveJob?.isActive == true) {
            keepAliveJob?.cancel()
        }

        keepAliveJob = keepAliveScope.launch {
            while (isKeepAliveMode && isActive) {
                // 切到后台
                moveTaskToBack(true)
                delay(5000L) // 5秒

                // 切回前台（通过启动Activity自身）
                val intent = Intent(this@MainActivity, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    putExtra(EXTRA_KEEP_ALIVE_MODE, true)
                    putExtra(EXTRA_UI_VISIBLE, false)
                    putExtra(EXTRA_FROM_KEEP_ALIVE, true)
                }
                startActivity(intent)

                delay(500L) // 500毫秒

                // 再次切到后台，继续循环
                moveTaskToBack(true)
            }
        }
    }

    private fun stopKeepAliveLoop() {
        isKeepAliveMode = false
        keepAliveJob?.cancel()
        keepAliveJob = null
        saveKeepAliveState()
    }

    private fun handleIntent(intent: Intent) {
        val keepAliveMode = intent.getBooleanExtra(EXTRA_KEEP_ALIVE_MODE, false)
        val uiVisible = intent.getBooleanExtra(EXTRA_UI_VISIBLE, true)
        val fromKeepAlive = intent.getBooleanExtra(EXTRA_FROM_KEEP_ALIVE, false)

        if (keepAliveMode && fromKeepAlive) {
            // 来自保活循环的启动
            isKeepAliveMode = true
            isUIVisible = uiVisible

            // 如果处于保活模式且UI不可见，确保启动保活循环（如果循环未在运行）
            if (isKeepAliveMode && !isUIVisible && (keepAliveJob == null || !keepAliveJob!!.isActive)) {
                startKeepAliveLoop()
            }

            // 保存从Intent恢复的状态
            saveKeepAliveState()
        } else {
            // 用户手动启动或其他情况
            // 检查当前状态（已在onCreate中加载），如果处于保活模式，则停止循环并显示UI
            if (isKeepAliveMode) {
                // 之前处于保活模式，用户手动打开了应用，停止保活循环并显示UI
                stopKeepAliveLoop()
                isUIVisible = true
                saveKeepAliveState() // 保存新的状态
            }
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        intent?.let { handleIntent(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 设置透明状态栏和导航栏
        setupTransparentBars()

        // 加载保存的保活状态
        loadKeepAliveState()

        // 处理Intent传递的保活状态
        handleIntent(intent)

        // 检查悬浮窗权限
        val hasPermission = hasOverlayPermission()
        // 检查服务是否正在运行
        isServiceRunning = isOverlayServiceRunning()

        setContent {
            BlackTransparentTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = ComposeColor.Transparent
                ) {
                    val context = LocalContext.current

                    if (!hasPermission) {
                        // 没有权限，显示权限请求界面
                        PermissionRequestScreen(
                            onRequestPermission = { requestOverlayPermission() }
                        )
                    } else {
                        // 有权限，自动启动服务并显示亮度控制界面
                        try {
                            if (!isServiceRunning) {
                                startOverlayService()
                            }
                        } catch (e: Exception) {
                            // 启动服务失败，但仍然显示亮度控制界面
                            // 用户可以在界面中看到错误提示
                        }

                        BrightnessControlScreen(
                            onBack = {
                                // 返回按钮，将Activity移到后台，保持服务运行
                                moveTaskToBack(true)
                            },
                            onStopService = {
                                // 停止服务按钮：先解绑再停止服务
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
        // 重新设置透明状态栏和导航栏
        setupTransparentBars()
        // 用户可能从设置页面返回，检查权限和服务状态
        val hasPermission = hasOverlayPermission()
        isServiceRunning = isOverlayServiceRunning()

        // 刷新界面以反映新的权限和服务状态
        setContent {
            BlackTransparentTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = if (isUIVisible) {
                        ComposeColor.Black
                    } else {
                        ComposeColor.Transparent
                    }
                ) {
                    val context = LocalContext.current

                    if (!hasPermission) {
                        // 没有权限，显示权限请求界面
                        PermissionRequestScreen(
                            onRequestPermission = { requestOverlayPermission() }
                        )
                    } else {
                        // 有权限，自动启动服务并显示亮度控制界面
                        try {
                            if (!isServiceRunning) {
                                startOverlayService()
                            }
                        } catch (e: Exception) {
                            // 启动服务失败，但仍然显示亮度控制界面
                            // 用户可以在界面中看到错误提示
                        }

                        BrightnessControlScreen(
                            onBack = {
                                // 返回按钮，将Activity移到后台，保持服务运行
                                moveTaskToBack(true)
                            },
                            onStopService = {
                                // 停止服务按钮：先解绑再停止服务
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
        // 注意：不在onStop中解绑服务，避免切后台时遮罩消失
        // 服务绑定会在onDestroy时解除
        // unbindOverlayService()
    }

    override fun onDestroy() {
        super.onDestroy()
        // 停止保活循环
        stopKeepAliveLoop()
        // 确保解绑
        unbindOverlayService()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        // 检查是否按下OK键（DPAD_CENTER或ENTER）
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            // 如果有权限
            if (hasOverlayPermission()) {
                // 如果UI不可见，显示UI并停止保活循环
                if (!isUIVisible) {
                    toggleUIVisibility()  // 这会显示UI并停止保活循环
                    return true
                }
                // 如果UI可见，保持原有行为：启动服务并关闭Activity（仅在需要时）
                // 注意：这里可能不再需要，因为服务已经在运行
                // 保留原有逻辑，但用户可以按OK键隐藏UI后再次按OK键显示UI
                // 这里不处理，让事件传递
            } else {
                // 没有权限，按OK键启动服务并关闭Activity（原有逻辑）
                startOverlayService()
                finish()
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
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


@Composable
fun PermissionRequestScreen(onRequestPermission: () -> Unit) {
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "需要悬浮窗权限",
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.padding(16.dp))

        Text(
            text = "BlackTransparent 需要悬浮窗权限来显示亮度调节覆盖层。\n\n" +
                    "这不会影响其他应用的正常运行，视频播放不会自动暂停。\n\n" +
                    "请点击下方按钮开启权限，然后在系统设置中允许「显示在其他应用上层」权限。",
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.padding(32.dp))

        Button(
            onClick = onRequestPermission,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(text = "开启悬浮窗权限")
        }

        Spacer(modifier = Modifier.padding(8.dp))

        Text(
            text = "开启权限后需要确认启动服务",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        )
    }
}

@Composable
fun BrightnessControlScreen(
    onBack: () -> Unit,
    onStopService: () -> Unit,
    initialAlpha: Float = 0.5f,
    onAlphaChange: (Float) -> Unit,
    isUIVisible: Boolean = true,
    onToggleUI: () -> Unit = {}
) {
    val context = LocalContext.current

    // 如果UI不可见，返回空内容
    if (!isUIVisible) {
        return
    }

    // 转换：initialAlpha是透明度（0透明最亮，1不透明最暗），UI使用亮度值（0最暗，1最亮）
    val initialBrightness = 1f - initialAlpha
    var currentBrightness by remember { mutableStateOf(initialBrightness) }
    var isConnected by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }

    // 当initialAlpha变化时更新currentBrightness
    LaunchedEffect(initialAlpha) {
        currentBrightness = 1f - initialAlpha
    }

    // 请求焦点，使Slider能够接收按键事件
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    Box(
        modifier = Modifier.fillMaxSize()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "亮度调节控制",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.padding(5.dp))

            // 当前亮度显示（0%最暗，100%最亮）
            Text(
                text = "当前亮度: ${(currentBrightness * 100).toInt()}%",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.padding(5.dp))

            // 亮度滑块
            Text(
                text = "调整亮度",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                modifier = Modifier.fillMaxWidth()
            )

            Slider(
                value = currentBrightness,
                onValueChange = { newBrightness ->
                    currentBrightness = newBrightness
                    onAlphaChange(1f - newBrightness) // 转换为透明度传递给服务
                },
                valueRange = 0f..1f,
                steps = 19, // 20个档位 (0%, 5%, 10%, ..., 95%, 100%)
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester)
                    .focusable()
                    .onKeyEvent { keyEvent ->
                        if (keyEvent.type == KeyEventType.KeyDown) {
                            when (keyEvent.key) {
                                Key.DirectionRight -> {
                                    // 右键增加亮度
                                    val newBrightness = (currentBrightness + 0.05f).coerceIn(0f, 1f)
                                    currentBrightness = newBrightness
                                    onAlphaChange(1f - newBrightness) // 转换为透明度传递给服务
                                    true
                                }

                                Key.DirectionLeft -> {
                                    // 左键减少亮度
                                    val newBrightness = (currentBrightness - 0.05f).coerceIn(0f, 1f)
                                    currentBrightness = newBrightness
                                    onAlphaChange(1f - newBrightness) // 转换为透明度传递给服务
                                    true
                                }

                                else -> false
                            }
                        } else {
                            false
                        }
                    }
            )

            Spacer(modifier = Modifier.padding(16.dp))

            // 快速设置按钮
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                Button(onClick = {
                    currentBrightness = 0.75f
                    onAlphaChange(1f - 0.75f) // 转换为透明度25%传递给服务
                }) {
                    Text(text = "75%")  // 亮度75%
                }
                Button(onClick = {
                    currentBrightness = 0.5f
                    onAlphaChange(1f - 0.5f) // 转换为透明度50%传递给服务
                }) {
                    Text(text = "50%")  // 亮度50%
                }
                Button(onClick = {
                    currentBrightness = 0.25f
                    onAlphaChange(1f - 0.25f) // 转换为透明度75%传递给服务
                }) {
                    Text(text = "25%")  // 亮度25%
                }
            }

            Spacer(modifier = Modifier.padding(32.dp))

            // 操作按钮
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(
                    onClick = onBack,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(text = "返回（保持服务运行）")
                }

                Button(
                    onClick = onStopService,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(text = "停止服务并退出")
                }

                Button(
                    onClick = onToggleUI,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(text = "隐藏并保活")
                }
            }

            Spacer(modifier = Modifier.padding(16.dp))

            Text(
                text = "提示：调节滑块实时更改屏幕亮度",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }

    }
}

