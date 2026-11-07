package com.cjy.n5.light

import android.content.Context

internal object BrightnessSettings {
    const val ALLOW_FULL_BLACK = "allow_full_black"
    const val DEFAULT_ALLOW_FULL_BLACK = true
    const val SHOW_KEEP_ALIVE_CONTROLS = "show_keep_alive_controls"
    const val DEFAULT_SHOW_KEEP_ALIVE_CONTROLS = false

    fun preferences(context: Context) =
        context.getSharedPreferences("brightness_settings", Context.MODE_PRIVATE)
}
