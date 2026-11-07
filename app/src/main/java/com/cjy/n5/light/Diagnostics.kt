package com.cjy.n5.light

import android.app.ActivityManager
import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.provider.MediaStore
import android.provider.Settings
import android.system.Os
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

internal data class DiagnosticEntry(val title: String, val detail: String)

internal data class DiagnosticReport(
    val generatedAt: Long,
    val system: List<DiagnosticEntry>,
    val exits: List<DiagnosticEntry>
) {
    fun asText(): String = buildString {
        appendLine("亮度调节 · 本机诊断")
        appendLine("生成时间：${Diagnostics.time(generatedAt)}")
        appendLine("仅含设备基础信息与本 App 的退出记录，不包含设备序列号或账号。")
        appendLine("\n系统信息")
        system.forEach { appendLine("${it.title}\n${it.detail}\n") }
        appendLine("退出记录（系统保留的最近记录，并非完整历史）")
        appendLine(Diagnostics.EXIT_NOTE)
        exits.forEach { appendLine("\n${it.title}\n${it.detail}") }
    }
}

internal object Diagnostics {
    const val EXIT_NOTE = "SIGKILL / 未知原因不能证明是厂商清理；内存不足也可能只显示信号终止。先切到其他应用，遮罩消失后重新打开本页查看。"

    fun time(value: Long): String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(Date(value))

    // Keep this mapping independent of platform classes for host-side tests.
    fun reasonLabel(reason: Int): String = when (reason) {
        0 -> "原因未知"
        1 -> "进程自行退出"
        2 -> "收到系统信号而终止"
        3 -> "系统内存不足"
        4 -> "应用崩溃"
        5 -> "原生代码崩溃"
        6 -> "应用无响应（ANR）"
        7 -> "初始化失败"
        8 -> "运行时权限变更"
        9 -> "资源使用过量"
        10 -> "用户请求终止（如强行停止）"
        11 -> "所属系统用户停止"
        12 -> "依赖的进程退出"
        13 -> "其他系统原因"
        14 -> "缓存进程冻结相关终止"
        15 -> "应用或组件状态改变"
        16 -> "应用更新"
        else -> "系统原因代码 $reason（本版本未识别）"
    }

    private fun readValue(block: () -> String): String = try {
        block().ifBlank { "系统未提供" }
    } catch (e: Exception) {
        "无法读取（${e.javaClass.simpleName}）"
    }

    fun collect(context: Context): DiagnosticReport {
        val manager = context.getSystemService(ActivityManager::class.java)
        val lowMemoryReporting = Build.VERSION.SDK_INT >= 30 &&
            runCatching { ActivityManager.isLowMemoryKillReportSupported() }.getOrDefault(false)
        val system = listOf(
            DiagnosticEntry("设备", "${Build.MANUFACTURER} / ${Build.BRAND}\n型号：${Build.MODEL}\n产品：${Build.PRODUCT} / ${Build.DEVICE}"),
            DiagnosticEntry("Android 与安全补丁", "Android ${Build.VERSION.RELEASE}（API ${Build.VERSION.SDK_INT}）\n系统报告的补丁日期：${Build.VERSION.SECURITY_PATCH.ifBlank { "未提供" }}\n补丁日期仅供筛选，不能单独证明漏洞存在或已修复。"),
            DiagnosticEntry("固件", "构建号：${Build.DISPLAY}\n增量版本：${Build.VERSION.INCREMENTAL}\n构建指纹：${Build.FINGERPRINT}"),
            DiagnosticEntry("内核与芯片", readValue {
                val kernel = Os.uname()
                "${kernel.sysname} ${kernel.release}\n${kernel.version}\n架构：${kernel.machine}\n硬件：${Build.HARDWARE} / ${Build.BOARD}\n" +
                    (if (Build.VERSION.SDK_INT >= 31) "SoC：${Build.SOC_MANUFACTURER} / ${Build.SOC_MODEL}\n" else "") +
                    "ABI：${Build.SUPPORTED_ABIS.joinToString()}"
            }),
            DiagnosticEntry("当前 App 与权限", readValue {
                val info = context.packageManager.getPackageInfo(context.packageName, 0)
                val versionCode = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
                val power = context.getSystemService(PowerManager::class.java)
                "${context.packageName}\nv${info.versionName}（$versionCode）\n悬浮窗授权：${if (Settings.canDrawOverlays(context)) "已允许" else "未允许"}\n电池优化豁免：${if (power.isIgnoringBatteryOptimizations(context.packageName)) "已豁免" else "未豁免"}\n当前时区：${TimeZone.getDefault().id}"
            }),
            DiagnosticEntry("系统诊断能力", if (Build.VERSION.SDK_INT < 30)
                "Android 11 以下不提供进程历史退出接口。"
            else "低内存退出专门上报：${if (lowMemoryReporting) "支持" else "不支持或无法确定"}\n仅查询本 App；系统只保留有限条记录，厂商可能省略细节。")
        )
        val exits = if (Build.VERSION.SDK_INT >= 30) {
            try {
                manager.getHistoricalProcessExitReasons(context.packageName, 0, 20).map { entry ->
                    DiagnosticEntry("${time(entry.timestamp)} · ${reasonLabel(entry.reason)}", buildString {
                        appendLine("进程：${entry.processName} / PID ${entry.pid}")
                        appendLine("原因代码：${entry.reason}；退出状态/信号：${entry.status}")
                        appendLine("退出时重要性：${entry.importance}")
                        appendLine("最后内存采样：PSS ${entry.pss} KB / RSS ${entry.rss} KB（0 可能表示无采样）")
                        append("系统描述：${entry.description?.take(4000)?.ifBlank { "未提供" } ?: "未提供"}")
                    })
                }.ifEmpty { listOf(DiagnosticEntry("暂无退出记录", "系统未返回记录，不代表 App 从未被杀。请复现后重新打开本页。")) }
            } catch (e: Exception) {
                listOf(DiagnosticEntry("退出记录读取失败", "${e.javaClass.simpleName}；固件可能限制此接口。系统信息仍可导出。"))
            }
        } else listOf(DiagnosticEntry("当前系统不支持", "需要 Android 11 或更新版本才能查询历史退出原因。"))
        return DiagnosticReport(System.currentTimeMillis(), system, exits)
    }

    fun fileName() = "brightness-diagnostics-${SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date())}.txt"

    @androidx.annotation.RequiresApi(29)
    fun exportToDownloads(context: Context, report: DiagnosticReport): String {
        val name = fileName()
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/BlackTransparent")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("无法创建报告文件")
        try {
            val stream = resolver.openOutputStream(uri) ?: error("无法打开报告文件")
            stream.bufferedWriter(Charsets.UTF_8).use { it.write(report.asText()) }
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            throw e
        }
        return "${Environment.DIRECTORY_DOWNLOADS}/BlackTransparent/$name"
    }
}
