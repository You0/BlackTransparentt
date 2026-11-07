package com.cjy.n5.light

import org.junit.Assert.*
import org.junit.Test

class DiagnosticsTest {
    @Test fun signalExitDoesNotBlameVendorOrAssumeLowMemory() {
        assertEquals("收到系统信号而终止", Diagnostics.reasonLabel(2))
        assertFalse(Diagnostics.reasonLabel(2).contains("内存"))
        assertFalse(Diagnostics.reasonLabel(2).contains("厂商"))
    }

    @Test fun unknownFutureReasonRetainsRawCode() {
        assertTrue(Diagnostics.reasonLabel(12345).contains("12345"))
    }

    @Test fun reportRetainsSystemEvidenceAndLimitations() {
        val report = DiagnosticReport(0L,
            listOf(DiagnosticEntry("固件", "test-build")),
            listOf(DiagnosticEntry("退出", "原因代码：2；退出状态/信号：9")))
        val text = report.asText()
        assertTrue(text.contains("test-build"))
        assertTrue(text.contains("退出状态/信号：9"))
        assertTrue(text.contains("不能证明是厂商清理"))
        assertTrue(text.contains("并非完整历史"))
    }
}
