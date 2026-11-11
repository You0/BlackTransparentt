package com.cjy.n5.light

import kotlin.math.roundToInt

/** Remote input and display only; values still use the existing 0..1 brightness scale. */
internal object BrightnessAdjustment {
    private const val SCALE = 1000
    private const val FINE_LIMIT = 200
    private const val COARSE_STEP = 20

    private fun units(brightness: Float): Int =
        (brightness.coerceIn(0f, 1f) * SCALE).roundToInt()

    fun step(brightness: Float, increase: Boolean): Float {
        val current = units(brightness)
        val next = when {
            increase && current < FINE_LIMIT -> current + 1
            increase -> current + COARSE_STEP
            current <= FINE_LIMIT -> current - 1
            // Land on 20% before entering fine adjustment, even from an odd preset.
            else -> (current - COARSE_STEP).coerceAtLeast(FINE_LIMIT)
        }
        return next.coerceIn(0, SCALE) / SCALE.toFloat()
    }

    fun isFine(brightness: Float): Boolean = units(brightness) < FINE_LIMIT

    fun percentText(brightness: Float): String {
        val value = units(brightness)
        return if (value < FINE_LIMIT || value % 10 != 0) {
            "${value / 10}.${value % 10}"
        } else {
            (value / 10).toString()
        }
    }
}
