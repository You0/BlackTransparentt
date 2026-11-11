package com.cjy.n5.light

import android.os.Build
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.focusable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Presentation only. Service callbacks, brightness conversion and mode gating
// intentionally match the original screen.
private val Foreground = Color(0xFFE8E7E4)
private val Secondary = Color(0xFF9D9E9F)
private val Edge = Color(0xFF303234)
private val Accent = Color(0xFFE2E0DA)
private val DarkInk = Color(0xFF171819)
private val Background = Color(0xFF101112)
private val Surface = Color(0xFF191B1D)
private val RaisedSurface = Color(0xFF222426)
private val SelectedSurface = Color(0xFF303234)
private val CardShape = RoundedCornerShape(18.dp)
private val ControlShape = RoundedCornerShape(12.dp)
private enum class Glyph { Sparkles, Sun, Moon, Cloud, Remote, Settings, Lock, Picture, Exit, Check }

@Composable
private fun UiText(
    text: String,
    size: TextUnit,
    modifier: Modifier = Modifier,
    color: Color = Foreground,
    weight: FontWeight = FontWeight.Normal,
    align: TextAlign? = null
) {
    Text(text, modifier = modifier, color = color, fontSize = size,
        fontWeight = weight, lineHeight = size * 1.3f, letterSpacing = 0.sp,
        textAlign = align)
}

@Composable
private fun ScreenFrame(onSettings: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit) {
    Box(Modifier.fillMaxSize().background(Background)) {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxWidth().height(76.dp).drawBehind {
                drawLine(Edge.copy(alpha = 0.75f), Offset(0f, size.height),
                    Offset(size.width, size.height), strokeWidth = 0.6.dp.toPx())
            }.padding(horizontal = 48.dp)) {
                Row(Modifier.align(Alignment.CenterStart), verticalAlignment = Alignment.CenterVertically) {
                    LineIcon(Glyph.Sun, Modifier.size(23.dp), Secondary)
                    Spacer(Modifier.width(12.dp))
                    UiText("亮度调节", 21.sp, weight = FontWeight.Medium)
                }
                Row(Modifier.align(Alignment.CenterEnd), horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                if (onSettings != null) {
                    var settingsFocused by remember { mutableStateOf(false) }
                    Row(Modifier.clip(RoundedCornerShape(12.dp))
                        .background(if (settingsFocused) Accent else Color.Transparent)
                        .border(1.dp, if (settingsFocused) Foreground else Color.Transparent, RoundedCornerShape(12.dp))
                        .onFocusChanged { settingsFocused = it.isFocused }
                        .clickable(onClick = onSettings).padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        val color = if (settingsFocused) DarkInk else Secondary
                        LineIcon(Glyph.Settings, Modifier.size(22.dp), color)
                        Spacer(Modifier.width(8.dp))
                        UiText("设置", 17.sp, color = color)
                    }
                }
                }
            }
            content()
        }
    }
}

private fun Modifier.panel(): Modifier = clip(CardShape)
    .background(Surface)
    .border(0.65.dp, Edge, CardShape)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrightnessControlScreen(
    onStopService: () -> Unit,
    onReturn: () -> Unit,
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
    var showSettings by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val preferences = remember(context) { BrightnessSettings.preferences(context) }
    var showKeepAliveControls by remember {
        mutableStateOf(preferences.getBoolean(BrightnessSettings.SHOW_KEEP_ALIVE_CONTROLS,
            BrightnessSettings.DEFAULT_SHOW_KEEP_ALIVE_CONTROLS))
    }

    LaunchedEffect(initialAlpha) { currentBrightness = 1f - initialAlpha }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    val brightnessPercent = BrightnessAdjustment.percentText(currentBrightness)

    if (showSettings) {
        BrightnessSettingsDialog(showKeepAliveControls = showKeepAliveControls,
            onShowKeepAliveControlsChange = { enabled ->
                showKeepAliveControls = enabled
                preferences.edit().putBoolean(BrightnessSettings.SHOW_KEEP_ALIVE_CONTROLS, enabled).apply()
            }, onDismiss = {
            showSettings = false
            focusRequester.requestFocus()
        })
    }
    ScreenFrame(onSettings = { showSettings = true }) {
        Row(Modifier.fillMaxWidth().weight(1f).padding(horizontal = 28.dp, vertical = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Column(Modifier.weight(1.5f).fillMaxHeight()
                .then(if (showKeepAliveControls) Modifier.panel() else Modifier)
                .padding(if (showKeepAliveControls) 24.dp else 20.dp)) {
                Row(Modifier.fillMaxWidth().weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    if (!showKeepAliveControls) {
                        Column(Modifier.width(250.dp).padding(start = 12.dp), verticalArrangement = Arrangement.Center) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                UiText("当前亮度", 16.sp, color = Secondary)
                            }
                            Spacer(Modifier.height(16.dp))
                            Row(verticalAlignment = Alignment.Bottom) {
                                UiText("$brightnessPercent", 96.sp, weight = FontWeight.Light)
                                UiText("%", 27.sp, Modifier.padding(bottom = 18.dp), color = Secondary)
                            }
                            Spacer(Modifier.height(12.dp))
                            UiText("0–100% 连续调节", 13.sp, color = Secondary)
                        }
                        Spacer(Modifier.width(40.dp))
                    }
                    Column(Modifier.weight(1f)
                        .then(if (!showKeepAliveControls) Modifier.panel().padding(24.dp) else Modifier)
                        .verticalScroll(rememberScrollState())) {
                        if (showKeepAliveControls) {
                            Row(Modifier.fillMaxWidth().height(64.dp), verticalAlignment = Alignment.CenterVertically) {
                                LineIcon(Glyph.Sun, Modifier.size(28.dp))
                                Spacer(Modifier.width(12.dp))
                                UiText("屏幕亮度", 21.sp, weight = FontWeight.Medium)
                                Spacer(Modifier.weight(1f))
                                Row(verticalAlignment = Alignment.Bottom) {
                                    UiText("$brightnessPercent", 60.sp, weight = FontWeight.Light)
                                    UiText("%", 26.sp, Modifier.padding(bottom = 10.dp), color = Foreground)
                                }
                            }
                            Spacer(Modifier.height(12.dp))
                        } else {
                            UiText("微调亮度", 18.sp, weight = FontWeight.Medium)
                            Spacer(Modifier.height(10.dp))
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            LineIcon(Glyph.Remote, Modifier.size(18.dp), Secondary)
                            Spacer(Modifier.width(10.dp))
                            UiText(if (BrightnessAdjustment.isFine(currentBrightness))
                                "精细调整 · 左右每次 0.1%" else "左右调节 · 低于 20% 精细调整",
                                13.sp, color = Secondary)
                        }
                        Spacer(Modifier.height(14.dp))
                        Slider(
                            value = currentBrightness,
                            onValueChange = {
                                currentBrightness = it
                                onAlphaChange(1f - it)
                            },
                            valueRange = 0f..1f,
                            modifier = Modifier.fillMaxWidth().height(40.dp)
                                .focusRequester(focusRequester)
                                .onFocusChanged { sliderFocused = it.hasFocus }
                                .onPreviewKeyEvent { e ->
                                    // Keep both halves of a horizontal remote key on the
                                    // slider, including when it has reached an endpoint.
                                    if (e.type == KeyEventType.KeyUp &&
                                        (e.key == Key.DirectionRight || e.key == Key.DirectionLeft)) {
                                        return@onPreviewKeyEvent true
                                    }
                                    if (e.type == KeyEventType.KeyDown) {
                                        when (e.key) {
                                            Key.DirectionRight -> {
                                                currentBrightness = BrightnessAdjustment.step(currentBrightness, increase = true)
                                                onAlphaChange(1f - currentBrightness)
                                                true
                                            }
                                            Key.DirectionLeft -> {
                                                currentBrightness = BrightnessAdjustment.step(currentBrightness, increase = false)
                                                onAlphaChange(1f - currentBrightness)
                                                true
                                            }
                                            else -> false
                                        }
                                    } else false
                                },
                            thumb = {
                                Box(Modifier.size(width = 8.dp, height = 22.dp)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(if (sliderFocused) Accent else Foreground)
                                    .border(1.dp, Foreground, RoundedCornerShape(4.dp)))
                            },
                            track = {
                                Canvas(Modifier.fillMaxWidth().height(5.dp)) {
                                    val corner = CornerRadius(size.height / 2)
                                    drawRoundRect(Edge, cornerRadius = corner)
                                    if (currentBrightness > 0f) {
                                        drawRoundRect(Accent,
                                            size = Size(size.width * currentBrightness, size.height), cornerRadius = corner)
                                    }
                                    if (sliderFocused) drawRoundRect(Accent.copy(alpha = 0.6f), cornerRadius = corner,
                                        style = Stroke(1.dp.toPx()))
                                }
                            },
                            colors = SliderDefaults.colors(thumbColor = Foreground, activeTrackColor = Accent)
                        )
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            UiText("0%", 13.sp, color = Secondary)
                            UiText("100%", 13.sp, color = Secondary)
                        }
                        Spacer(Modifier.height(16.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            PresetControl(Glyph.Moon, "25%", currentBrightness == 0.25f, Modifier.weight(1f)) {
                                currentBrightness = 0.25f
                                onAlphaChange(0.75f)
                            }
                            PresetControl(Glyph.Cloud, "60%", currentBrightness == 0.60f, Modifier.weight(1f)) {
                                currentBrightness = 0.60f
                                onAlphaChange(0.40f)
                            }
                            PresetControl(Glyph.Sun, "100%", currentBrightness == 1f, Modifier.weight(1f)) {
                                currentBrightness = 1f
                                onAlphaChange(0f)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(20.dp))
                Box(Modifier.fillMaxWidth().height(0.65.dp).background(Edge))
                Spacer(Modifier.height(20.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (!showKeepAliveControls) {
                        UiText("确认后收起界面，调光继续生效", 13.sp,
                            Modifier.weight(1f), color = Secondary)
                    } else {
                        Spacer(Modifier.weight(1f))
                    }
                    BrightnessAction(Glyph.Check, "确定，保持此亮度", Modifier.width(250.dp),
                        primary = true, onClick = onReturn)
                    BrightnessAction(Glyph.Exit, "退出", Modifier.width(110.dp), onClick = onStopService)
                }
            }
            if (showKeepAliveControls) {
                Column(Modifier.weight(1f).fillMaxHeight().panel().padding(28.dp)
                    .verticalScroll(rememberScrollState())) {
                    Row(Modifier.height(28.dp), verticalAlignment = Alignment.CenterVertically) {
                        LineIcon(Glyph.Settings, Modifier.size(26.dp))
                        Spacer(Modifier.width(12.dp))
                        UiText("保活策略", 20.sp, weight = FontWeight.Medium)
                    }
                    Spacer(Modifier.height(26.dp))
                    ModeControl(Glyph.Lock, "常驻保活", badge = "推荐", active = !isUIVisible,
                        enabled = !pipActive, onClick = onToggleUI)
                    Spacer(Modifier.height(8.dp))
                    UiText(if (!isUIVisible) "保活已开启，退出后自动拉起" else "开启后退出自动保持透明",
                        13.sp, Modifier.fillMaxWidth(), color = if (pipActive) Secondary.copy(alpha = 0.4f) else Secondary,
                        align = TextAlign.Center)
                    Spacer(Modifier.height(26.dp))
                    ModeControl(Glyph.Picture, "画中画保活", active = pipActive,
                        enabled = isUIVisible, onClick = { onTogglePip(!pipActive) })
                    Spacer(Modifier.height(8.dp))
                    UiText(if (pipActive) "开启后请按 Home 键" else "爱奇艺/腾讯专用",
                        13.sp, Modifier.fillMaxWidth(), color = if (!isUIVisible) Secondary.copy(alpha = 0.4f) else Secondary,
                        align = TextAlign.Center)
                }
            }
        }
    }
}

@Composable
private fun BrightnessAction(icon: Glyph, label: String, modifier: Modifier,
    primary: Boolean = false, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val color = if (focused) DarkInk else if (primary) Accent else Secondary
    Row(modifier.height(54.dp).clip(ControlShape)
        .background(if (focused) Accent else if (primary) RaisedSurface else Color.Transparent)
        .border(if (focused) 2.dp else 0.75.dp,
            if (focused) Color.White else if (primary) Secondary.copy(alpha = 0.5f) else Edge, ControlShape)
        .onFocusChanged { focused = it.isFocused }
        .clickable(onClick = onClick).padding(horizontal = 18.dp),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        LineIcon(icon, Modifier.size(23.dp), color)
        Spacer(Modifier.width(10.dp))
        UiText(label, 18.sp, color = color, weight = FontWeight.Medium)
    }
}

@Composable
private fun BrightnessSettingsDialog(showKeepAliveControls: Boolean,
    onShowKeepAliveControlsChange: (Boolean) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val preferences = remember(context) { BrightnessSettings.preferences(context) }
    var allowFullBlack by remember {
        mutableStateOf(preferences.getBoolean(BrightnessSettings.ALLOW_FULL_BLACK,
            BrightnessSettings.DEFAULT_ALLOW_FULL_BLACK))
    }
    val toggleFocus = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    var keepAliveToggleFocused by remember { mutableStateOf(false) }
    var showDiagnostics by remember { mutableStateOf(false) }
    if (showDiagnostics) {
        DiagnosticsDialog(onDismiss = { showDiagnostics = false })
        return
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.width(620.dp).heightIn(max = 480.dp).padding(16.dp).panel().padding(28.dp)
            .verticalScroll(rememberScrollState())) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween) {
                UiText("设置", 24.sp, weight = FontWeight.Medium)
                UiText("v${context.packageManager.getPackageInfo(context.packageName, 0).versionName}",
                    13.sp, color = Secondary)
            }
            Spacer(Modifier.height(20.dp))
            Row(Modifier.fillMaxWidth().clip(ControlShape)
                .background(if (focused) SelectedSurface else RaisedSurface)
                .border(if (focused) 1.5.dp else 0.65.dp, if (focused) Accent else Edge, ControlShape)
                .focusRequester(toggleFocus).onFocusChanged { focused = it.isFocused }
                .toggleable(value = allowFullBlack, role = Role.Switch, onValueChange = { enabled ->
                    allowFullBlack = enabled
                    preferences.edit().putBoolean(BrightnessSettings.ALLOW_FULL_BLACK, enabled).apply()
                }).padding(horizontal = 18.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    UiText("允许完全遮黑", 19.sp, weight = FontWeight.Medium)
                    UiText(if (allowFullBlack) "已开启 · 立即生效" else "已关闭 · 原版触摸穿透", 12.sp, color = Secondary)
                }
                Switch(checked = allowFullBlack, onCheckedChange = null,
                    colors = SwitchDefaults.colors(checkedThumbColor = DarkInk, checkedTrackColor = Accent,
                        uncheckedThumbColor = Secondary, uncheckedTrackColor = Edge))
            }
            Spacer(Modifier.height(16.dp))
            UiText("开启后，0% 可完全遮住画面。极暗时（通常低于 20%），鼠标和触屏无法穿透，遥控器方向键仍可使用。",
                14.sp, color = Secondary)
            Spacer(Modifier.height(10.dp))
            UiText("调到全黑后，保持焦点在亮度滑杆，按遥控器右键可调亮。关闭此开关可恢复原版行为，0% 可能仍看见内容。",
                14.sp, color = Secondary)
            Spacer(Modifier.height(10.dp))
            UiText("设置会自动保存。投影仪自身的底光不会被关闭。", 12.sp, color = Secondary)
            Spacer(Modifier.height(20.dp))
            Row(Modifier.fillMaxWidth().clip(ControlShape)
                .background(if (keepAliveToggleFocused) SelectedSurface else RaisedSurface)
                .border(if (keepAliveToggleFocused) 1.5.dp else 0.65.dp,
                    if (keepAliveToggleFocused) Accent else Edge, ControlShape)
                .onFocusChanged { keepAliveToggleFocused = it.isFocused }
                .toggleable(value = showKeepAliveControls, role = Role.Switch,
                    onValueChange = onShowKeepAliveControlsChange)
                .padding(horizontal = 18.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    UiText("显示保活按钮", 19.sp, weight = FontWeight.Medium)
                    UiText("在主界面显示常驻保活与画中画保活", 12.sp, color = Secondary)
                }
                Switch(checked = showKeepAliveControls, onCheckedChange = null,
                    colors = SwitchDefaults.colors(checkedThumbColor = DarkInk, checkedTrackColor = Accent,
                        uncheckedThumbColor = Secondary, uncheckedTrackColor = Edge))
            }
            Spacer(Modifier.height(10.dp))
            UiText("默认隐藏。此开关只改变按钮显示，不会开启或关闭正在运行的保活。", 12.sp, color = Secondary)
            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ModeControl(Glyph.Settings, "诊断信息", Modifier.weight(1f),
                    onClick = { showDiagnostics = true })
                ModeControl(Glyph.Exit, "完成", Modifier.weight(1f), onClick = onDismiss)
            }
        }
        LaunchedEffect(Unit) { toggleFocus.requestFocus() }
    }
}

@Composable
private fun DiagnosticsDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var refresh by remember { mutableStateOf(0) }
    var tab by remember { mutableStateOf(0) }
    var status by remember { mutableStateOf("无需 ADB 或 root · 仅在本机读取，不上传") }
    var exporting by remember { mutableStateOf(false) }
    var pendingExport by remember { mutableStateOf("") }
    val report by produceState<DiagnosticReport?>(initialValue = null, refresh) {
        value = null
        value = withContext(Dispatchers.IO) { Diagnostics.collect(context.applicationContext) }
    }
    val saveDocument = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) {
            scope.launch {
                exporting = true
                try {
                    withContext(Dispatchers.IO) {
                        val stream = context.contentResolver.openOutputStream(uri) ?: error("无法打开文件")
                        stream.bufferedWriter(Charsets.UTF_8).use { it.write(pendingExport) }
                    }
                    status = "报告已保存到所选位置"
                } catch (e: Exception) {
                    status = "导出失败：${e.javaClass.simpleName}，可使用复制报告"
                } finally { exporting = false }
            }
        }
    }
    val firstFocus = remember { FocusRequester() }
    val listState = rememberLazyListState()
    LaunchedEffect(tab, refresh) { listState.scrollToItem(0) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxWidth(0.92f).fillMaxHeight(0.92f).panel().padding(24.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                UiText("诊断信息", 24.sp, weight = FontWeight.Medium)
                UiText("上下选择条目 · 左右选择操作", 13.sp, color = Secondary)
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DiagnosticAction("系统信息", Modifier.weight(1f).focusRequester(firstFocus), selected = tab == 0) { tab = 0 }
                DiagnosticAction("退出记录", Modifier.weight(1f), selected = tab == 1) { tab = 1 }
                DiagnosticAction("刷新", Modifier.weight(1f), enabled = report != null) { refresh++ }
                DiagnosticAction("复制报告", Modifier.weight(1f), enabled = report != null) {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("亮度调节诊断", report!!.asText()))
                    status = "报告已复制，可粘贴到其他应用"
                }
                DiagnosticAction("导出报告", Modifier.weight(1f), enabled = report != null && !exporting) {
                    val snapshot = report ?: return@DiagnosticAction
                    if (Build.VERSION.SDK_INT >= 29) {
                        scope.launch {
                            exporting = true
                            try {
                                val path = withContext(Dispatchers.IO) { Diagnostics.exportToDownloads(context, snapshot) }
                                status = "已保存：$path"
                            } catch (e: Exception) {
                                status = "导出失败：${e.javaClass.simpleName}，可使用复制报告"
                            } finally { exporting = false }
                        }
                    } else {
                        pendingExport = snapshot.asText()
                        try { saveDocument.launch(Diagnostics.fileName()) }
                        catch (_: android.content.ActivityNotFoundException) {
                            status = "设备没有文件选择器，请使用复制报告"
                        }
                    }
                }
                DiagnosticAction("返回设置", Modifier.weight(1f), onClick = onDismiss)
            }
            Spacer(Modifier.height(10.dp))
            UiText(status, 12.sp, color = Accent)
            if (tab == 1) {
                Spacer(Modifier.height(8.dp))
                UiText(Diagnostics.EXIT_NOTE, 12.sp, color = Secondary)
            }
            Spacer(Modifier.height(12.dp))
            val entries = if (tab == 0) report?.system else report?.exits
            if (entries == null) {
                UiText("正在读取…", 16.sp, color = Secondary)
            } else {
                LazyColumn(Modifier.fillMaxWidth().weight(1f), state = listState,
                    verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(entries) { entry ->
                        var entryFocused by remember { mutableStateOf(false) }
                        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                            .background(if (entryFocused) SelectedSurface else RaisedSurface)
                            .border(1.dp, if (entryFocused) Accent else Edge, RoundedCornerShape(12.dp))
                            .onFocusChanged { entryFocused = it.isFocused }.focusable().padding(14.dp)) {
                            UiText(entry.title, 16.sp, weight = FontWeight.Medium)
                            Spacer(Modifier.height(5.dp))
                            UiText(entry.detail, 13.sp, color = Secondary)
                        }
                    }
                }
            }
        }
        LaunchedEffect(Unit) { firstFocus.requestFocus() }
    }
}

@Composable
private fun DiagnosticAction(label: String, modifier: Modifier, selected: Boolean = false,
    enabled: Boolean = true, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Box(modifier.height(42.dp).clip(RoundedCornerShape(10.dp))
        .background(if (focused) Accent else if (selected) SelectedSurface else RaisedSurface)
        .border(1.dp, if (focused || selected) Accent else Edge, RoundedCornerShape(10.dp))
        .onFocusChanged { focused = it.isFocused }.clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center) {
        UiText(label, 14.sp, color = if (!enabled) Secondary.copy(alpha = 0.4f) else if (focused) DarkInk else Foreground)
    }
}

@Composable
private fun PresetControl(icon: Glyph, label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val color = if (focused) DarkInk else if (selected) Accent else Foreground
    Row(modifier.height(50.dp).clip(ControlShape)
        .background(if (focused) Accent else if (selected) SelectedSurface else RaisedSurface)
        .border(if (focused) 1.5.dp else 0.65.dp, if (focused) Color.White else if (selected) Accent else Edge, ControlShape)
        .onFocusChanged { focused = it.isFocused }.clickable(onClick = onClick)
        .padding(horizontal = 10.dp),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        LineIcon(icon, Modifier.size(24.dp), color)
        Spacer(Modifier.width(10.dp))
        UiText(label, 18.sp, color = color)
    }
}

@Composable
private fun ModeControl(
    icon: Glyph, label: String, modifier: Modifier = Modifier, badge: String? = null,
    active: Boolean = false, enabled: Boolean = true, onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val highlighted = focused && enabled
    val color = if (!enabled) Secondary.copy(alpha = 0.4f) else if (highlighted) DarkInk else Foreground
    Row(modifier.fillMaxWidth().height(58.dp).clip(ControlShape)
        .background(if (highlighted) Accent else if (active) SelectedSurface else RaisedSurface)
        .border(if (highlighted) 1.5.dp else 0.65.dp, if (highlighted) Color.White else if (active) Accent else Edge, ControlShape)
        .onFocusChanged { focused = it.isFocused }.clickable(enabled = enabled, onClick = onClick)
        .padding(horizontal = 12.dp), horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically) {
        LineIcon(icon, Modifier.size(24.dp), color)
        Spacer(Modifier.width(13.dp))
        UiText(label, 18.sp, color = color, weight = FontWeight.Medium)
        if (badge != null) {
            Spacer(Modifier.width(12.dp))
            UiText(badge, 12.sp, Modifier.border(0.8.dp, if (highlighted) DarkInk else Accent, CircleShape)
                .padding(horizontal = 8.dp, vertical = 4.dp), color = if (highlighted) DarkInk else Accent)
        }
    }
}

@Composable
fun PermissionRequestScreen(onRequestPermission: () -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    ScreenFrame {
        Column(Modifier.fillMaxWidth().weight(1f).padding(32.dp).panel().padding(32.dp)
            .verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.Center) {
            UiText("需要悬浮窗权限", 26.sp, weight = FontWeight.Medium)
            Spacer(Modifier.height(16.dp))
            UiText("亮度调节需要悬浮窗权限来显示亮度调节覆盖层。\n请在系统设置中允许「显示在其他应用上层」。", 17.sp, color = Secondary)
            Spacer(Modifier.height(24.dp))
            ModeControl(Glyph.Lock, "开启悬浮窗权限", Modifier.width(300.dp).focusRequester(focus), onClick = onRequestPermission)
        }
    }
}

/** Lightweight vector icons; no font/emoji glyph variations between TV firmwares. */
@Composable
private fun LineIcon(icon: Glyph, modifier: Modifier = Modifier, color: Color = Foreground) {
    Canvas(modifier) {
        withTransform({ scale(size.width / 24f, size.height / 24f, pivot = Offset.Zero) }) {
            val stroke = Stroke(width = 1.3f, cap = StrokeCap.Round)
            fun line(x1: Float, y1: Float, x2: Float, y2: Float) =
                drawLine(color, Offset(x1, y1), Offset(x2, y2), 1.3f, StrokeCap.Round)
            fun path(block: Path.() -> Unit) = drawPath(Path().apply(block), color, style = stroke)
            when (icon) {
                Glyph.Sun -> {
                    drawCircle(color, 5f, Offset(12f, 12f), style = stroke)
                    for (i in 0..7) {
                        val a = i * Math.PI / 4
                        val x = cos(a).toFloat(); val y = sin(a).toFloat()
                        line(12 + x * 8, 12 + y * 8, 12 + x * 10.5f, 12 + y * 10.5f)
                    }
                }
                Glyph.Moon -> path {
                    moveTo(15f, 2.5f); cubicTo(18f, 10f, 12f, 17f, 4f, 16f)
                    cubicTo(8f, 24f, 21f, 21f, 21f, 12f); cubicTo(21f, 8f, 19f, 4.5f, 15f, 2.5f); close()
                }
                Glyph.Cloud -> path {
                    moveTo(5f, 19f); cubicTo(-1f, 18f, 0f, 11f, 5f, 10f)
                    cubicTo(6f, 2f, 17f, 2f, 19f, 10f); cubicTo(25f, 11f, 24f, 19f, 19f, 19f); close()
                }
                Glyph.Lock -> {
                    drawRoundRect(color, Offset(5f, 10f), Size(14f, 11f), CornerRadius(2f), style = stroke)
                    path { moveTo(8f, 10f); lineTo(8f, 6f); cubicTo(8f, 0f, 16f, 0f, 16f, 6f); lineTo(16f, 10f) }
                    drawCircle(color, 1f, Offset(12f, 15f)); line(12f, 15f, 12f, 17f)
                }
                Glyph.Picture -> {
                    drawRoundRect(color, Offset(2f, 3f), Size(20f, 18f), CornerRadius(2f), style = stroke)
                    drawCircle(color, 1.5f, Offset(7f, 8f), style = stroke)
                    path { moveTo(3f, 18f); lineTo(10f, 11f); lineTo(14f, 15f); lineTo(17f, 12f); lineTo(22f, 18f) }
                }
                Glyph.Remote -> {
                    drawRoundRect(color, Offset(6f, 1f), Size(12f, 22f), CornerRadius(4f), style = stroke)
                    drawCircle(color, 2.2f, Offset(12f, 9f), style = stroke)
                    drawCircle(color, 0.8f, Offset(12f, 4.5f))
                    drawCircle(color, 0.8f, Offset(9.5f, 15f)); drawCircle(color, 0.8f, Offset(14.5f, 15f))
                    line(10f, 19f, 14f, 19f)
                }
                Glyph.Check -> {
                    line(4f, 12f, 9f, 17f); line(9f, 17f, 21f, 5f)
                }
                Glyph.Exit -> {
                    path { moveTo(13f, 4f); lineTo(5f, 4f); quadraticTo(3f, 4f, 3f, 6f); lineTo(3f, 19f); quadraticTo(3f, 21f, 5f, 21f); lineTo(13f, 21f) }
                    line(10f, 12f, 22f, 12f); line(18f, 8f, 22f, 12f); line(18f, 16f, 22f, 12f)
                }
                Glyph.Settings -> {
                    path {
                        for (i in 0..31) {
                            val a = i * Math.PI / 16
                            val r = if (i % 4 == 0 || i % 4 == 3) 10f else 8f
                            val x = 12 + cos(a).toFloat() * r; val y = 12 + sin(a).toFloat() * r
                            if (i == 0) moveTo(x, y) else lineTo(x, y)
                        }
                        close()
                    }
                    drawCircle(color, 3.5f, Offset(12f, 12f), style = stroke)
                }
                Glyph.Sparkles -> {
                    path { moveTo(15f, 1f); lineTo(18f, 8f); lineTo(24f, 11f); lineTo(18f, 14f); lineTo(15f, 21f); lineTo(12f, 14f); lineTo(6f, 11f); lineTo(12f, 8f); close() }
                    path { moveTo(4f, 14f); lineTo(5.5f, 18f); lineTo(9f, 19.5f); lineTo(5.5f, 21f); lineTo(4f, 24f); lineTo(2.5f, 21f); lineTo(0f, 19.5f); lineTo(2.5f, 18f); close() }
                }
            }
        }
    }
}
