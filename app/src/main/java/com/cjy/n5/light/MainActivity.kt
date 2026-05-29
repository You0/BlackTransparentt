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
import androidx.compose.foundation.layout.wrapContentHeight
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


@Composable
fun PermissionRequestScreen(onRequestPermission: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize()
    ) {
        // Radial gradient background
        Canvas(modifier = Modifier.fillMaxSize()) {
            val cx = size.width * 0.2f
            val cy = size.height * 0.3f
            val radius = maxOf(size.width, size.height) * 1.2f
            drawCircle(
                brush = Brush.radialGradient(
                    0.0f to BgGradientStart,
                    1.0f to BgGradientEnd,
                    center = Offset(cx, cy),
                    radius = radius
                ),
                center = Offset(cx, cy),
                radius = radius
            )
        }

        // Centered panel
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(48.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth(0.7f)
                    .clip(RoundedCornerShape(48.dp))
                    .background(PanelBg)
                    .border(1.dp, PanelBorderColor, RoundedCornerShape(48.dp))
            ) {
                // .top-bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(TopBarBg)
                        .drawBehind {
                            drawLine(
                                color = TopBarBorderColor,
                                start = Offset(0f, size.height),
                                end = Offset(size.width, size.height),
                                strokeWidth = 1.dp.toPx()
                            )
                        }
                        .padding(horizontal = 32.dp, vertical = 20.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("✨", fontSize = 26.sp)
                    Spacer(Modifier.width(12.dp))
                    Text(
                        "亮度调节",
                        fontSize = 24.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = TextPrimary,
                        style = TextStyle(letterSpacing = (-0.2).sp)
                    )
                }

                // Content
                Column(
                    modifier = Modifier.padding(40.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        "🔐 需要悬浮窗权限",
                        fontSize = 22.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = TextPrimary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(Modifier.height(24.dp))

                    Text(
                        "亮度调节需要悬浮窗权限来显示亮度调节覆盖层。\n\n" +
                                "这不会影响其他应用的正常运行，视频播放不会自动暂停。\n\n" +
                                "请点击下方按钮开启权限，然后在系统设置中允许「显示在其他应用上层」权限。",
                        fontSize = 15.sp,
                        color = TextSecondary,
                        textAlign = TextAlign.Center,
                        lineHeight = 22.sp,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(Modifier.height(36.dp))

                    // Styled action button
                    var focused by remember { mutableStateOf(false) }
                    val btnBg = if (focused) AccentBlue.copy(alpha = 0.85f) else AccentBlue
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp)
                            .clip(RoundedCornerShape(56.dp))
                            .background(btnBg)
                            .onFocusChanged { focused = it.isFocused }
                            .clickable { onRequestPermission() },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "开启悬浮窗权限",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = ComposeColor.White
                        )
                    }

                    Spacer(Modifier.height(16.dp))

                    Text(
                        "开启权限后需要确认启动服务",
                        fontSize = 12.sp,
                        color = HelperFooterColor,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}

// ==================== 颜色常量 (100% 匹配 界面.html) ====================

private val PanelBg = ComposeColor(0xFF08080C).copy(alpha = 0.82f)
private val PanelBorderColor = ComposeColor.White.copy(alpha = 0.08f)
private val TopBarBg = ComposeColor.Black.copy(alpha = 0.4f)
private val TopBarBorderColor = ComposeColor.White.copy(alpha = 0.06f)
private val CardBgColor = ComposeColor(0xFF121218).copy(alpha = 0.6f)
private val CardBorderColor = ComposeColor.White.copy(alpha = 0.04f)
private val TextPrimary = ComposeColor(0xFFF0F4FF)
private val TextSecondary = ComposeColor(0xFFAAAEC5)
private val TextTertiary = ComposeColor(0xFF90A0BB)
private val AccentBlue = ComposeColor(0xFF0A84FF)
private val AccentGlowBlue = ComposeColor(0xFF2D9EFF)
private val LiveDotGreen = ComposeColor(0xFF0AFFB3)
private val LiveDotGreenGlow = ComposeColor(0xFF0AFFB3).copy(alpha = 0.6f)
private val PresetBtnBg = ComposeColor(0xFF20222C)
private val PresetActiveBg = ComposeColor(0xFF0A84FF)
private val PresetFocusedBg = ComposeColor(0xFF326B9E)
private val ActionBtnBg = ComposeColor(0xFF1E1E24)
private val ActionBtnBorder = ComposeColor.White.copy(alpha = 0.08f)
private val ActionFocusedBg = ComposeColor(0xFF2A2E3C)
private val ActionFocusedBorder = ComposeColor(0xFF0A84FF)
private val DisabledBtnBg = ComposeColor(0xFF1A1A1E)
private val DisabledBtnBorder = ComposeColor.White.copy(alpha = 0.04f)
private val DisabledTextColor = ComposeColor(0xFF555560)
private val PipActiveBg = ComposeColor(0xFF1E3A3F)
private val PipActiveBorder = ComposeColor(0xFF00C8E8)
private val PipActiveFocusedBg = ComposeColor(0xFF1E4A52)
private val PipActiveFocusedBorder = ComposeColor(0xFF00E0FF)
private val DangerBtnBg = ComposeColor(0xFF2A1C1C)
private val DangerBtnBorder = ComposeColor(0xFFFF5046).copy(alpha = 0.3f)
private val DangerTextColor = ComposeColor(0xFFFFAAA0)
private val DangerFocusedBg = ComposeColor(0xFF4A2A28)
private val DangerFocusedBorder = ComposeColor(0xFFFF6A5C)
private val BadgeBorderColor = ComposeColor(0xFF64B4FA).copy(alpha = 0.6f)
private val BadgeTextColor = ComposeColor(0xFF8ABDF0)
private val HelperFooterColor = ComposeColor(0xFF7A88A0)
private val BtnNoteColor = ComposeColor(0xFF8A98B0)
private val SpecDescTextColor = ComposeColor(0xFFA0ACC8)
private val BgGradientStart = ComposeColor(0xFF0B0B12)
private val BgGradientEnd = ComposeColor(0xFF000000)
private val PercentSignColor = ComposeColor(0xFF9AAEC9)
private val SectionTitleColor = ComposeColor(0xFFEEF3FF)
private val SliderLabelColor = ComposeColor(0xFFAAAEC5)
private val PresetTextColor = ComposeColor(0xFFCCDEFF)
private val ActionTextColor = ComposeColor(0xFFE0E4F0)
private val PipActiveTextColor = ComposeColor(0xFFE0F4FF)
private val PodBg = ComposeColor.Black.copy(alpha = 0.35f)
private val SpecBg = ComposeColor.Black.copy(alpha = 0.3f)
private val PercentGradientStart = ComposeColor.White
private val PercentGradientEnd = ComposeColor(0xFF92CAFF)
private val SliderThumbBorder = ComposeColor(0xFF0E7AFF)
private val SliderTrackStart = ComposeColor(0xFF1C567B)
private val SliderTrackEnd = ComposeColor(0xFF74C0FF)

// ==================== LiveDot 动画 (匹配 HTML .live-dot) ====================

@Composable
private fun LiveDot() {
    val infiniteTransition = rememberInfiniteTransition(label = "liveDot")
    val scale by infiniteTransition.animateFloat(
        initialValue = 0.9f,
        targetValue = 1.2f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "liveDotScale"
    )
    Box(
        Modifier
            .size(8.dp)
            .graphicsLayer(scaleX = scale, scaleY = scale)
            .drawBehind {
                drawCircle(LiveDotGreenGlow, radius = 12f)
            }
            .background(LiveDotGreen, RoundedCornerShape(10.dp))
    )
}

// ==================== PresetButton (匹配 HTML .preset-btn) ====================

@Composable
private fun PresetButton(
    text: String,
    isActive: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }

    val bg = when {
        isActive -> PresetActiveBg
        focused -> PresetFocusedBg
        else -> PresetBtnBg
    }
    val scl = when {
        isActive && focused -> 1.04f
        focused -> 1.02f
        else -> 1f
    }

    Box(
        modifier = modifier
            .height(48.dp)
            .graphicsLayer(scaleX = scl, scaleY = scl)
            .clip(RoundedCornerShape(60.dp))
            .background(bg)
            .onFocusChanged { focused = it.isFocused }
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (isActive || focused) ComposeColor.White else PresetTextColor,
            style = TextStyle(letterSpacing = 0.5.sp)
        )
    }
}

// ==================== TvActionButton (匹配 HTML .tv-action-btn) ====================

@Composable
private fun TvActionButton(
    icon: String,
    text: String,
    modifier: Modifier = Modifier,
    badge: String? = null,
    isActive: Boolean = false,
    isDanger: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }

    val bg = when {
        !enabled -> DisabledBtnBg
        isDanger && focused -> DangerFocusedBg
        isDanger -> DangerBtnBg
        isActive && focused -> PipActiveFocusedBg
        isActive -> PipActiveBg
        focused -> ActionFocusedBg
        else -> ActionBtnBg
    }
    val borderColor = when {
        !enabled -> DisabledBtnBorder
        isDanger && focused -> DangerFocusedBorder
        isDanger -> DangerBtnBorder
        isActive && focused -> PipActiveFocusedBorder
        isActive -> PipActiveBorder
        focused -> ActionFocusedBorder
        else -> ActionBtnBorder
    }
    val textColor = when {
        !enabled -> DisabledTextColor
        isDanger && focused -> ComposeColor.White
        isDanger -> DangerTextColor
        isActive -> PipActiveTextColor
        focused -> ComposeColor.White
        else -> ActionTextColor
    }
    val scl = if (focused && enabled) 1.02f else 1f

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(52.dp)
            .graphicsLayer(scaleX = scl, scaleY = scl)
            .clip(RoundedCornerShape(56.dp))
            .background(bg)
            .border(1.dp, borderColor, RoundedCornerShape(56.dp))
            .onFocusChanged { focused = it.isFocused }
            .clickable(enabled = enabled) { onClick() },
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(icon, fontSize = 18.sp)
        Spacer(Modifier.width(12.dp))
        Text(
            text = text,
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
            color = textColor
        )
        if (badge != null) {
            Text(
                text = badge,
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium,
                color = BadgeTextColor,
                modifier = Modifier
                    .padding(start = 8.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .border(1.dp, BadgeBorderColor, RoundedCornerShape(20.dp))
                    .padding(horizontal = 8.dp, vertical = 2.dp)
            )
        }
    }
}

// ==================== BrightnessControlScreen (100% 匹配 界面.html) ====================

@Composable
fun BrightnessControlScreen(
    onStopService: () -> Unit,
    initialAlpha: Float = 0.5f,
    onAlphaChange: (Float) -> Unit,
    isUIVisible: Boolean = true,
    onToggleUI: () -> Unit = {},
    onTogglePip: (Boolean) -> Unit = {}
) {
    val initialBrightness = 1f - initialAlpha
    var currentBrightness by remember { mutableStateOf(initialBrightness) }
    val focusRequester = remember { FocusRequester() }
    val pipActive = PipKeepAliveActivity.isActive.value
    var sliderFocused by remember { mutableStateOf(false) }

    LaunchedEffect(initialAlpha) { currentBrightness = 1f - initialAlpha }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    val brightnessPercent = (currentBrightness * 100).toInt()

    // Full screen with radial gradient (matching HTML body)
    Box(
        modifier = Modifier.fillMaxSize()
    ) {
        // Radial gradient background: circle at 20% 30%, #0b0b12 -> #000000
        Canvas(modifier = Modifier.fillMaxSize()) {
            val cx = size.width * 0.2f
            val cy = size.height * 0.3f
            val radius = maxOf(size.width, size.height) * 1.2f
            drawCircle(
                brush = Brush.radialGradient(
                    0.0f to BgGradientStart,
                    1.0f to BgGradientEnd,
                    center = Offset(cx, cy),
                    radius = radius
                ),
                center = Offset(cx, cy),
                radius = radius
            )
        }

        // Centered .tv-panel
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .wrapContentHeight()
                    .verticalScroll(rememberScrollState())
                    .clip(RoundedCornerShape(48.dp))
                    .background(PanelBg)
                    .border(1.dp, PanelBorderColor, RoundedCornerShape(48.dp))
            ) {
                // ========== .top-bar ==========
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(TopBarBg)
                        .drawBehind {
                            drawLine(
                                color = TopBarBorderColor,
                                start = Offset(0f, size.height),
                                end = Offset(size.width, size.height),
                                strokeWidth = 1.dp.toPx()
                            )
                        }
                        .padding(horizontal = 32.dp, vertical = 20.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // .logo-area
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("✨", fontSize = 26.sp)
                        Spacer(Modifier.width(12.dp))
                        Text(
                            "亮度调节",
                            fontSize = 24.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = TextPrimary,
                            style = TextStyle(letterSpacing = (-0.2).sp)
                        )
                        Spacer(Modifier.width(12.dp))
                        LiveDot()
                    }
                }

                // ========== .core-dual ==========
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(start = 32.dp, end = 32.dp, top = 32.dp, bottom = 36.dp),
                    horizontalArrangement = Arrangement.spacedBy(28.dp)
                ) {
                    // ===== .brightness-hero (1.4fr) =====
                    Column(
                        modifier = Modifier
                            .weight(1.4f)
                            .clip(RoundedCornerShape(40.dp))
                            .background(CardBgColor)
                            .border(1.dp, CardBorderColor, RoundedCornerShape(40.dp))
                            .padding(horizontal = 26.dp, vertical = 28.dp)
                    ) {
                        // .bright-header
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.Bottom
                        ) {
                            Text(
                                "☀️ 屏幕亮度",
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Medium,
                                color = SectionTitleColor
                            )
                            Row(verticalAlignment = Alignment.Bottom) {
                                Text(
                                    "$brightnessPercent",
                                    fontSize = 56.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    style = TextStyle(
                                        brush = Brush.linearGradient(
                                            colors = listOf(PercentGradientStart, PercentGradientEnd),
                                            start = Offset.Zero,
                                            end = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY)
                                        )
                                    )
                                )
                                Text("%", fontSize = 28.sp, color = PercentSignColor)
                            }
                        }

                        Spacer(Modifier.height(18.dp))

                        // .slider-area
                        Column {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    "🕹️ 遥控器左右微调",
                                    fontSize = 14.sp,
                                    color = SliderLabelColor
                                )
                                Text("$brightnessPercent%", fontSize = 14.sp, color = SliderLabelColor)
                            }

                            Spacer(Modifier.height(10.dp))

                            // Slider with gradient track
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp)
                            ) {
                                Canvas(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(8.dp)
                                        .align(Alignment.Center)
                                ) {
                                    drawRoundRect(
                                        brush = Brush.horizontalGradient(
                                            colors = listOf(SliderTrackStart, SliderTrackEnd),
                                            startX = 0f,
                                            endX = size.width
                                        ),
                                        cornerRadius = CornerRadius(10f, 10f)
                                    )
                                }
                                Slider(
                                    value = currentBrightness,
                                    onValueChange = {
                                        currentBrightness = it
                                        onAlphaChange(1f - it)
                                    },
                                    valueRange = 0f..1f,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .focusRequester(focusRequester)
                                        .focusable()
                                        .onFocusChanged { sliderFocused = it.isFocused }
                                        .onKeyEvent { e ->
                                            if (e.type == KeyEventType.KeyDown) {
                                                when (e.key) {
                                                    Key.DirectionRight -> {
                                                        currentBrightness = (currentBrightness + 0.02f).coerceIn(0f, 1f)
                                                        onAlphaChange(1f - currentBrightness)
                                                        true
                                                    }
                                                    Key.DirectionLeft -> {
                                                        currentBrightness = (currentBrightness - 0.02f).coerceIn(0f, 1f)
                                                        onAlphaChange(1f - currentBrightness)
                                                        true
                                                    }
                                                    else -> false
                                                }
                                            } else false
                                        },
                                    colors = SliderDefaults.colors(
                                        thumbColor = if (sliderFocused) ComposeColor(0xFFFFC107) else ComposeColor.White,
                                        activeTrackColor = ComposeColor.Transparent,
                                        inactiveTrackColor = ComposeColor.Transparent
                                    )
                                )
                            }
                        }

                        Spacer(Modifier.height(32.dp))

                        // .presets-row
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            PresetButton("🌙 25%", currentBrightness == 0.25f, Modifier.weight(1f)) {
                                currentBrightness = 0.25f
                                onAlphaChange(0.75f)
                            }
                            PresetButton("☁️ 60%", currentBrightness == 0.60f, Modifier.weight(1f)) {
                                currentBrightness = 0.60f
                                onAlphaChange(0.40f)
                            }
                            PresetButton("☀️ 100%", currentBrightness == 1f, Modifier.weight(1f)) {
                                currentBrightness = 1f
                                onAlphaChange(0f)
                            }
                        }

                        Spacer(Modifier.height(24.dp))

                        Text(
                            "← → 调节亮度",
                            fontSize = 11.sp,
                            color = HelperFooterColor
                        )
                    }

                    // ===== .control-pod (1fr) =====
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(40.dp))
                            .background(CardBgColor)
                            .border(1.dp, CardBorderColor, RoundedCornerShape(40.dp))
                            .padding(horizontal = 26.dp, vertical = 28.dp),
                        verticalArrangement = Arrangement.spacedBy(24.dp)
                    ) {
                        // .pod-section
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(32.dp))
                                .background(PodBg)
                                .padding(20.dp)
                        ) {
                            // .pod-title
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("⚙️", fontSize = 14.sp)
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    "保活策略",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = TextTertiary,
                                    style = TextStyle(letterSpacing = 1.2.sp)
                                )
                            }

                            Spacer(Modifier.height(18.dp))

                            // .dual-actions
                            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                                Column {
                                    TvActionButton(
                                        icon = "🔒",
                                        text = "常驻保活",
                                        badge = "推荐",
                                        isActive = !isUIVisible,
                                        enabled = !pipActive,
                                        onClick = onToggleUI
                                    )
                                    Spacer(Modifier.height(6.dp))
                                    Text(
                                        if (!isUIVisible) "保活已开启，退出后自动拉起" else "开启后退出自动保持透明",
                                        fontSize = 11.sp,
                                        color = if (pipActive) DisabledTextColor else BtnNoteColor,
                                        textAlign = TextAlign.Center,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }

                                Column {
                                    TvActionButton(
                                        icon = "🖼️",
                                        text = "画中画保活",
                                        isActive = pipActive,
                                        enabled = isUIVisible,
                                        onClick = { onTogglePip(!pipActive) }
                                    )
                                    Spacer(Modifier.height(6.dp))
                                    Text(
                                        if (pipActive) "开启后请按 Home 键" else "爱奇艺/腾讯专用",
                                        fontSize = 11.sp,
                                        color = if (!isUIVisible) DisabledTextColor else BtnNoteColor,
                                        textAlign = TextAlign.Center,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                            }

                            Spacer(Modifier.height(12.dp))

                            // .spec-desc
                            Text(
                                "🎬 画中画模式 · 右下角会有一个小阴影区域（保活标识）",
                                fontSize = 11.sp,
                                color = SpecDescTextColor,
                                textAlign = TextAlign.Center,
                                lineHeight = 15.sp,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(28.dp))
                                    .background(SpecBg)
                                    .padding(horizontal = 12.dp, vertical = 8.dp)
                            )
                        }

                        // .exit-container
                        Column {
                            TvActionButton(
                                icon = "🚪",
                                text = "退出应用",
                                isDanger = true,
                                onClick = onStopService
                            )
                            Spacer(Modifier.height(10.dp))
                            Text(
                                "停止保活并退出",
                                fontSize = 11.sp,
                                color = HelperFooterColor,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            }
        }
    }
}
