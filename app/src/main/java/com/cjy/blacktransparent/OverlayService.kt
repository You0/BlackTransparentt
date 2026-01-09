package com.cjy.blacktransparent

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.os.Binder
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.WindowManager.LayoutParams
import android.widget.Toast
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.core.app.NotificationCompat
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.ProgressBar
import android.util.TypedValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry

class OverlayService : Service(), LifecycleOwner {

    companion object {
        private const val NOTIFICATION_CHANNEL_ID = "overlay_service_channel"
        private const val NOTIFICATION_CHANNEL_NAME = "亮度调节服务"
        private const val NOTIFICATION_ID = 1
        private const val LONG_PRESS_THRESHOLD_MS = 1000L // 长按阈值1秒
    }

    inner class OverlayBinder : Binder() {
        fun getService(): OverlayService = this@OverlayService
    }
    private val binder = OverlayBinder()

    private val lifecycleRegistry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry

    private lateinit var windowManager: WindowManager
    private lateinit var overlayView: View
    private lateinit var params: LayoutParams
    private lateinit var blackOverlayView: View
    private lateinit var controlPanel: LinearLayout
    private lateinit var progressBar: ProgressBar
    private lateinit var alphaText: TextView

    private var overlayAlpha = 0.5f // 初始透明度 50%
    private var isBrightnessControlVisible = false
    private var isActiveMode = false // 激活模式，长按MENU键切换
    private var menuKeyDownTime = 0L // MENU键按下时间戳

    private fun createNotification(): Notification {
        // 创建通知渠道（Android 8.0+）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                NOTIFICATION_CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "亮度调节覆盖层正在运行"
                setShowBadge(false)
            }

            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("亮度调节")
            .setContentText("覆盖层正在运行，使用方向键调节亮度")
            .setSmallIcon(android.R.drawable.ic_menu_edit)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setAutoCancel(false)
            .build()
    }

    private fun updateAlpha() {
        if (::blackOverlayView.isInitialized) {
            blackOverlayView.alpha = overlayAlpha
        }
        if (::progressBar.isInitialized) {
            progressBar.progress = (100 - overlayAlpha * 100).toInt()  // 显示亮度值
        }
        if (::alphaText.isInitialized) {
            alphaText.text = "亮度: ${(100 - overlayAlpha * 100).toInt()}%"
        }
    }

    fun setAlpha(alpha: Float) {
        overlayAlpha = alpha.coerceIn(0f, 1f)
        updateAlpha()
    }

    fun getAlpha(): Float = overlayAlpha

    private fun updateControlPanelVisibility() {
        if (::controlPanel.isInitialized) {
            controlPanel.visibility = if (isBrightnessControlVisible) View.VISIBLE else View.GONE
        }
    }

    private fun dpToPx(dp: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp.toFloat(),
            resources.displayMetrics
        ).toInt()
    }

    override fun onCreate() {
        super.onCreate()
        lifecycleRegistry.currentState = Lifecycle.State.CREATED

        // 必须在前台服务启动后5秒内调用startForeground
        try {
            val notification = createNotification()
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Toast.makeText(this, "startForeground失败: ${e.message}", Toast.LENGTH_LONG).show()
            throw e
        }

        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        // 创建窗口参数
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            LayoutParams.TYPE_PHONE
        }

        params = LayoutParams(
            LayoutParams.MATCH_PARENT,
            LayoutParams.MATCH_PARENT,
            type,
            // 关键标志：
            // FLAG_NOT_FOCUSABLE - 窗口不获取焦点，让下层应用保持焦点（设为false以接收按键）
            // FLAG_NOT_TOUCH_MODAL - 触摸事件传递给下层窗口
            // FLAG_NOT_TOUCHABLE - 完全不接收触摸事件（如果需要完全穿透）
            // FLAG_WATCH_OUTSIDE_TOUCH - 可以接收窗口外的触摸事件
            // FLAG_LAYOUT_NO_LIMITS - 允许窗口扩展到屏幕外
            // 注意：设置FLAG_NOT_FOCUSABLE让窗口不获取焦点，不拦截按键事件
            LayoutParams.FLAG_NOT_FOCUSABLE or
            LayoutParams.FLAG_NOT_TOUCHABLE or
            LayoutParams.FLAG_NOT_TOUCH_MODAL or
            LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
            LayoutParams.FLAG_SHOW_WHEN_LOCKED or
            LayoutParams.FLAG_KEEP_SCREEN_ON,
            PixelFormat.TRANSLUCENT
        )
        // 设置窗口位置和重力
        params.gravity = Gravity.TOP or Gravity.START
        params.x = 0
        params.y = 0

        // 创建传统View作为悬浮窗内容
        val rootView = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        // 黑色覆盖层
        blackOverlayView = View(this).apply {
            setBackgroundColor(0xFF000000.toInt())
            alpha = overlayAlpha
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            // 不拦截触摸事件
            isClickable = false
            isFocusable = false
        }
        rootView.addView(blackOverlayView)

        // 控制面板（居中）
        controlPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(0x80000000.toInt()) // 半透明黑色背景
            setPadding(dpToPx(48), dpToPx(24), dpToPx(48), dpToPx(24))
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.CENTER
            }
            visibility = if (isBrightnessControlVisible) View.VISIBLE else View.GONE
        }

        // 标题
        val titleText = TextView(this).apply {
            text = "亮度调节"
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 20f
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dpToPx(16)
            }
        }
        controlPanel.addView(titleText)

        // 进度条
        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = (100 - overlayAlpha * 100).toInt()  // 显示亮度值
            progressDrawable.setTint(0xFFFFFFFF.toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dpToPx(24)
                bottomMargin = dpToPx(24)
            }
        }
        controlPanel.addView(progressBar)

        // 当前亮度（透明度0%最亮，100%最暗）
        alphaText = TextView(this).apply {
            text = "亮度: ${(100 - overlayAlpha * 100).toInt()}%"
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 16f
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        controlPanel.addView(alphaText)

        // 提示文字
        val hintText = TextView(this).apply {
            text = "使用方向键上下左右调整，返回键退出"
            setTextColor(0xFFCCCCCC.toInt())
            textSize = 14f
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dpToPx(16)
            }
        }
        controlPanel.addView(hintText)

        rootView.addView(controlPanel)

        overlayView = rootView.apply {
            // 设置按键监听
            setOnKeyListener { _, keyCode, event ->
                when (event.action) {
                    KeyEvent.ACTION_DOWN -> handleKeyDown(keyCode, event)
                    KeyEvent.ACTION_UP -> handleKeyUp(keyCode, event)
                    else -> false
                }
            }

            // 窗口设置了FLAG_NOT_FOCUSABLE，无法接收按键事件
            // isFocusable = true
            // isFocusableInTouchMode = true
            // requestFocus()
        }

        // 添加悬浮窗到WindowManager
        try {
            windowManager.addView(overlayView, params)
            lifecycleRegistry.currentState = Lifecycle.State.STARTED
        } catch (e: SecurityException) {
            e.printStackTrace()
            Toast.makeText(
                this,
                "权限拒绝: ${e.message}\n需要悬浮窗权限，请在设置中开启",
                Toast.LENGTH_LONG
            ).show()
            stopForeground(STOP_FOREGROUND_REMOVE) // 立即移除通知
            stopSelf()
        } catch (e: IllegalArgumentException) {
            e.printStackTrace()
            Toast.makeText(
                this,
                "参数错误: ${e.message}\n窗口参数可能无效",
                Toast.LENGTH_LONG
            ).show()
            stopForeground(STOP_FOREGROUND_REMOVE) // 立即移除通知
            stopSelf()
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(
                this,
                "未知错误: ${e.javaClass.simpleName}: ${e.message}",
                Toast.LENGTH_LONG
            ).show()
            stopForeground(STOP_FOREGROUND_REMOVE) // 立即移除通知
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 确保前台服务状态正确设置（即使服务被系统重启）
        val notification = createNotification()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? {
        return binder
    }


    override fun onDestroy() {
        super.onDestroy()
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED

        try {
            if (::windowManager.isInitialized && ::overlayView.isInitialized) {
                windowManager.removeView(overlayView)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }


    private fun handleKeyDown(keyCode: Int, event: KeyEvent): Boolean {

        // 处理MENU键按下，记录时间
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            menuKeyDownTime = System.currentTimeMillis()
            return false // 不消费MENU键按下，让其他应用也能接收
        }

        // 非激活模式下，只监听MENU键，其他按键全部传递
        if (!isActiveMode) {
            return false
        }

        // 激活模式下，处理亮度控制按键
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, // OK键
            KeyEvent.KEYCODE_ENTER -> {
                // 显示/隐藏亮度调节控件
                isBrightnessControlVisible = !isBrightnessControlVisible
                updateControlPanelVisibility()
                true
            }
            KeyEvent.KEYCODE_DPAD_UP -> {
                if (isBrightnessControlVisible) {
                    // 增加透明度（使覆盖层更暗）
                    overlayAlpha = (overlayAlpha + 0.05f).coerceIn(0f, 1f)
                    updateAlpha()
                    true
                } else {
                    false
                }
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (isBrightnessControlVisible) {
                    // 减少透明度（使覆盖层更亮）
                    overlayAlpha = (overlayAlpha - 0.05f).coerceIn(0f, 1f)
                    updateAlpha()
                    true
                } else {
                    false
                }
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (isBrightnessControlVisible) {
                    // 左键减少透明度（使覆盖层更亮）
                    overlayAlpha = (overlayAlpha - 0.05f).coerceIn(0f, 1f)
                    updateAlpha()
                    true
                } else {
                    false
                }
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (isBrightnessControlVisible) {
                    // 右键增加透明度（使覆盖层更暗）
                    overlayAlpha = (overlayAlpha + 0.05f).coerceIn(0f, 1f)
                    updateAlpha()
                    true
                } else {
                    false
                }
            }
            KeyEvent.KEYCODE_BACK -> {
                if (isBrightnessControlVisible) {
                    // 返回键隐藏亮度调节控件
                    isBrightnessControlVisible = false
                    updateControlPanelVisibility()
                    true
                } else {
                    // 返回键停止服务
                    stopSelf()
                    true
                }
            }
            else -> false
        }
    }

    private fun handleKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        // 处理MENU键抬起，检测长按
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            val pressDuration = System.currentTimeMillis() - menuKeyDownTime
            if (pressDuration >= LONG_PRESS_THRESHOLD_MS) {
                // 长按MENU键，切换激活模式
                isActiveMode = !isActiveMode
                // 激活时显示控制面板，非激活时隐藏
                isBrightnessControlVisible = isActiveMode
                updateControlPanelVisibility()
                return true // 消费长按事件，防止触发其他菜单
            }
            // 短按MENU键，不处理，传递给其他应用
        }
        return false
    }
}

