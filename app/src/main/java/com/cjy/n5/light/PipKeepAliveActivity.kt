package com.cjy.n5.light

import android.app.PictureInPictureParams
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.util.Rational
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf

class PipKeepAliveActivity : ComponentActivity() {

    companion object {
        private const val TAG = "PipKeepAlive"
        val isActive = mutableStateOf(false)
        var instance: PipKeepAliveActivity? = null
    }

    private var overlayService: OverlayService? = null
    private var isBound = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            overlayService = (service as OverlayService.OverlayBinder).getService()
            isBound = true
            Log.d(TAG, "onServiceConnected: 已绑定OverlayService")
        }

        override fun onServiceDisconnected(name: ComponentName) {
            overlayService = null
            isBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        Log.d(TAG, ">>> onCreate, sdk=${Build.VERSION.SDK_INT}")
        super.onCreate(savedInstanceState)
        instance = this

        window.addFlags(
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        )
        window.setBackgroundDrawableResource(android.R.color.transparent)

        bindOverlayService()
    }

    private fun bindOverlayService() {
        val intent = Intent(this, OverlayService::class.java)
        try {
            bindService(intent, connection, Context.BIND_AUTO_CREATE)
        } catch (e: Exception) {
            Log.e(TAG, "bindOverlayService: 失败", e)
        }
    }

    private fun unbindOverlayService() {
        if (isBound) {
            unbindService(connection)
            isBound = false
            overlayService = null
        }
    }

    override fun onResume() {
        Log.d(TAG, ">>> onResume, isInPip=$isInPictureInPictureMode")
        super.onResume()
        enterPipMode()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean) {
        Log.d(TAG, ">>> onPictureInPictureModeChanged: isInPip=$isInPictureInPictureMode")
        super.onPictureInPictureModeChanged(isInPictureInPictureMode)
        if (isInPictureInPictureMode) {
            isActive.value = true
            window.decorView.visibility = android.view.View.INVISIBLE
        } else {
            isActive.value = false
            Log.d(TAG, "已退出画中画，关闭")
            cleanup()
        }
    }

    private fun enterPipMode() {
        if (isInPictureInPictureMode) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val builder = PictureInPictureParams.Builder()
                    .setAspectRatio(Rational(1, 1))
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    builder.setSourceRectHint(android.graphics.Rect(0, 0, 1, 1))
                }
                enterPictureInPictureMode(builder.build())
                Log.d(TAG, "enterPipMode: isInPip=$isInPictureInPictureMode")
            } catch (e: Exception) {
                Log.e(TAG, "enterPipMode: 异常", e)
                cleanup()
            }
        }
    }

    private fun cleanup() {
        unbindOverlayService()
        isActive.value = false
        finish()
    }

    override fun onDestroy() {
        instance = null
        isActive.value = false
        unbindOverlayService()
        super.onDestroy()
    }
}
