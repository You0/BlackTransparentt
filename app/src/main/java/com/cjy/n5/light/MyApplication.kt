package com.cjy.n5.light

import android.app.Application
import com.service.framework.Fw

class MyApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        Fw.init(this) {
            enableForegroundService = true
            enableDualProcess = true
            enableNativeDaemon = true
            enableMediaSession = true
            enableOnePixelActivity = true
            enableAlarmManager = true
            enableSystemBroadcast = true
        }
    }

    override fun onTerminate() {
        super.onTerminate()
    }

    override fun onLowMemory() {
        super.onLowMemory()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
    }
}