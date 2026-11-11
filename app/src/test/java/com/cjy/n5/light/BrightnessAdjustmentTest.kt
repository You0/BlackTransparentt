package com.cjy.n5.light

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrightnessAdjustmentTest {
    @Test fun fineStepsAndBounds() {
        assertStep(0.1f, true, 0.101f)
        assertStep(0.1f, false, 0.099f)
        assertStep(0f, false, 0f)
        assertStep(0f, true, 0.001f)
        assertStep(0.001f, false, 0f)
        assertStep(1f, true, 1f)
        assertStep(0.99f, true, 1f)
    }

    @Test fun thresholdDoesNotSkipFineRange() {
        assertStep(0.25f, false, 0.23f)
        assertStep(0.23f, false, 0.21f)
        assertStep(0.21f, false, 0.20f)
        assertStep(0.20f, false, 0.199f)
        assertStep(0.199f, true, 0.20f)
        assertStep(0.20f, true, 0.22f)
        assertStep(0.50f, false, 0.48f)
        assertStep(0.50f, true, 0.52f)
        assertTrue(BrightnessAdjustment.isFine(0.199f))
        assertFalse(BrightnessAdjustment.isFine(0.20f))
    }

    @Test fun alphaRoundTripsDoNotAccumulateStepOrDisplayErrors() {
        var brightness = 0f
        for (tenth in 1..200) {
            brightness = BrightnessAdjustment.step(1f - (1f - brightness), true)
            assertEquals(tenth / 1000f, brightness, 0.000001f)
        }
        for (tenth in 199 downTo 0) {
            brightness = BrightnessAdjustment.step(1f - (1f - brightness), false)
            assertEquals(tenth / 1000f, brightness, 0.000001f)
            assertEquals("${tenth / 10}.${tenth % 10}",
                BrightnessAdjustment.percentText(1f - (1f - brightness)))
        }
    }

    @Test fun displayShowsTenthsWithoutTruncatingWholePercentages() {
        assertEquals("19.9", BrightnessAdjustment.percentText(1f - 0.801f))
        assertEquals("20", BrightnessAdjustment.percentText(1f - 0.8f))
        assertEquals("25", BrightnessAdjustment.percentText(0.25f))
        assertEquals("100", BrightnessAdjustment.percentText(1f))
        assertEquals("0.1", BrightnessAdjustment.percentText(0.001f))
        assertEquals("0.0", BrightnessAdjustment.percentText(0f))
    }

    private fun assertStep(from: Float, increase: Boolean, expected: Float) {
        assertEquals(expected, BrightnessAdjustment.step(from, increase), 0.000001f)
    }
}
