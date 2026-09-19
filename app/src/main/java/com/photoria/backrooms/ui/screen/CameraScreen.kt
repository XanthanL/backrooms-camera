package com.photoria.backrooms.ui.screen

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.NightsStay
import androidx.compose.material3.Icon
import androidx.compose.runtime.produceState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import com.photoria.backrooms.camera.CameraManager
import com.photoria.backrooms.camera.LevelMath
import com.photoria.backrooms.camera.LevelSensor
import com.photoria.backrooms.camera.VideoRecorder
import com.photoria.backrooms.camera.VoiceShutter
import com.photoria.backrooms.camera.WbPreset
import com.photoria.backrooms.capture.BurstCapture
import com.photoria.backrooms.gl.CameraGLSurfaceView
import com.photoria.backrooms.gl.HistogramBins
import com.photoria.backrooms.gl.ProOverlayConfig
import com.photoria.backrooms.catalog.FilterCatalog
import com.photoria.backrooms.catalog.FilterCategory
import com.photoria.backrooms.gif.GifEncoder
import com.photoria.backrooms.ui.components.BubbleLevel
import com.photoria.backrooms.ui.components.CameraSettingsPanel
import com.photoria.backrooms.ui.components.CaptureButton
import com.photoria.backrooms.ui.components.CountdownOverlay
import com.photoria.backrooms.ui.components.FilterCategoryBar
import com.photoria.backrooms.ui.components.FilterParamsPanel
import com.photoria.backrooms.ui.components.FilterSelector
import com.photoria.backrooms.ui.components.HistogramBox
import com.photoria.backrooms.ui.components.TopBar
import com.photoria.backrooms.ui.components.cameraGestures
import com.photoria.backrooms.ui.theme.BackroomsCream
import com.photoria.backrooms.ui.theme.BackroomsShadow
import com.photoria.backrooms.ui.theme.BackroomsYellow
import com.photoria.backrooms.ui.theme.BackroomsYellowOnDark
import com.photoria.backrooms.ui.viewmodel.AspectRatio
import com.photoria.backrooms.ui.viewmodel.CaptureMode
import com.photoria.backrooms.ui.viewmodel.CameraViewModel
import com.photoria.backrooms.ui.viewmodel.CountdownSec
import com.photoria.backrooms.util.FilterPrefs
import com.photoria.backrooms.util.ImageSaver
import com.photoria.backrooms.util.MosaicComposer
import com.photoria.backrooms.util.MosaicLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "CameraScreen"

// ── Phase 3：智能场景建议阈值 ──
/** 暗光阈值（0..1），低于此值建议开启夜景 */
private const val NIGHT_SUGGESTION_THRESHOLD = 0.25f
/** 建议 dismiss 后冷却时间（毫秒），避免频繁打扰 */
private const val NIGHT_SUGGESTION_COOLDOWN_MS = 60_000L

/**
 * 连拍拼图的列数（上限 9 帧即 3×3）。
 *
 * 帧数不足一行的尾部留空：拼图按实际帧数收缩，不会多出一块黑底。
 */
private const val MOSAIC_COLUMNS = 3

/** 拼图格间距（像素）：黑底上的细缝，再宽就开始吃掉画面了 */
private const val MOSAIC_GAP_PX = 8

@Composable
fun CameraScreen(viewModel: CameraViewModel = viewModel()) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val hapticFeedback = LocalHapticFeedback.current
    val cameraManager = remember { CameraManager(context) }
    val snackbarHostState = remember { SnackbarHostState() }

    // ViewModel 状态收集
    val currentFilterIndex by viewModel.currentFilterIndex.collectAsState()
    val isCapturing by viewModel.isCapturing.collectAsState()
    val captureProcessing by viewModel.captureProcessing.collectAsState()
    val captureDeadlineMs by viewModel.captureDeadlineMs.collectAsState()
    val captureMode by viewModel.captureMode.collectAsState()
    val isRecording by viewModel.isRecording.collectAsState()
    val recordingDurationSec by viewModel.recordingDurationSec.collectAsState()
    val showParamsPanel by viewModel.showParamsPanel.collectAsState()
    val filterParams by viewModel.filterParams.collectAsState()
    val currentAspectRatio by viewModel.currentAspectRatio.collectAsState()
    val lastMediaUri by viewModel.lastMediaUri.collectAsState()
    val customizedIndices by viewModel.customizedFilterIndices.collectAsState()
    val showGrid by viewModel.showGrid.collectAsState()
    val filterStrength by viewModel.filterStrength.collectAsState()
    val showHistogram by viewModel.showHistogram.collectAsState()
    val zebraMode by viewModel.zebraMode.collectAsState()
    val focusPeaking by viewModel.focusPeaking.collectAsState()
    val peakingSensitivity by viewModel.peakingSensitivity.collectAsState()
    val showBubbleLevel by viewModel.showBubbleLevel.collectAsState()
    val volumeKeyShutter by viewModel.volumeKeyShutter.collectAsState()
    val countdownSec by viewModel.countdownSec.collectAsState()
    val voiceEnabled by viewModel.voiceEnabled.collectAsState()
    val voicePickup by viewModel.voicePickup.collectAsState()
    val voiceMinLevel by viewModel.voiceMinLevel.collectAsState()
    val burstCount by viewModel.burstCount.collectAsState()
    val burstGif by viewModel.burstGif.collectAsState()
    val torchEnabled by viewModel.torchEnabled.collectAsState()
    val zoomRatio by cameraManager.zoomRatio.collectAsState()
    // Phase 3：场景亮度（0..1，EMA 平滑），用于智能建议夜景
    val brightness by cameraManager.brightness.collectAsState()

    // 闪光灯可用性（后摄且具备闪光灯单元），绑定/切换摄像头后刷新
    var torchAvailable by remember { mutableStateOf(false) }
    val refreshTorchAvailable = { torchAvailable = cameraManager.isTorchAvailable() }

    // 新手引导（一次性）
    var showOnboarding by remember { mutableStateOf(!FilterPrefs.isOnboardingShown()) }

    // 滤镜缩略图（index → 图片，与 filterNames 对齐；空 = 尚未生成，选择器回退首字）
    var filterThumbnails by remember { mutableStateOf<List<ImageBitmap?>>(emptyList()) }

    // 闪白动画
    val flashAlpha = remember { Animatable(0f) }

    // ── 滤镜分类筛选 ──
    var selectedCategory by remember { mutableStateOf(FilterCategory.ALL) }
    val displayIndices = remember(selectedCategory) {
        FilterCatalog.indicesByCategory(selectedCategory)
    }

    // ── 专业相机设置面板状态 ──
    var showCameraSettings by remember { mutableStateOf(false) }
    var wbPreset by remember { mutableStateOf(cameraManager.getWbPreset()) }
    var wbIntensity by remember { mutableStateOf(cameraManager.getWbIntensity()) }
    var evIndex by remember { mutableStateOf(cameraManager.getCurrentEvIndex()) }
    var manualExposure by remember { mutableStateOf(cameraManager.isManualExposure()) }
    var manualIso by remember { mutableStateOf(400) }
    var manualShutterNs by remember { mutableStateOf(16_700_000L) }
    var hdrEnabled by remember { mutableStateOf(false) }
    var nightEnabled by remember { mutableStateOf(false) }

    // ── Phase 3：智能场景建议 ──
    // 暗光场景下显示"建议开启夜景"浮层；用户 dismiss 后 60 秒内不再打扰
    var nightSuggestionDismissedAt by remember { mutableStateOf(0L) }
    val showNightSuggestion by remember(brightness, hdrEnabled, nightEnabled, nightSuggestionDismissedAt, captureMode) {
        derivedStateOf {
            // 仅 PHOTO 模式、未开启任何多帧前处理、亮度持续低于阈值、且非刚 dismiss
            captureMode == CaptureMode.PHOTO &&
                !hdrEnabled && !nightEnabled &&
                brightness < NIGHT_SUGGESTION_THRESHOLD &&
                System.currentTimeMillis() - nightSuggestionDismissedAt > NIGHT_SUGGESTION_COOLDOWN_MS
        }
    }

    // 获取当前显示旋转角度（随配置变化更新）
    val configuration = LocalConfiguration.current
    val displayRotation = remember(configuration) {
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.rotation
    }

    // GLSurfaceView 引用
    var glSurfaceViewRef by remember { mutableStateOf<CameraGLSurfaceView?>(null) }

    // 直方图统计（GL 线程取样 → 主线程写入；由 HistogramBox 在叶子节点解包）
    val histogramBins = remember { mutableStateOf<HistogramBins?>(null) }

    // 取景辅助配置：灵敏度换算为 shader 的边缘阈值（越灵敏 = 阈值越低）
    val proOverlayConfig = remember(showHistogram, zebraMode, focusPeaking, peakingSensitivity) {
        ProOverlayConfig(
            zebra = zebraMode,
            peaking = focusPeaking,
            peakingThreshold = 1f - peakingSensitivity,
            histogram = showHistogram
        )
    }

    // 取景辅助变化 / Surface 就绪：下发 GL
    LaunchedEffect(proOverlayConfig, glSurfaceViewRef) {
        glSurfaceViewRef?.setProOverlay(proOverlayConfig)
        if (!showHistogram) histogramBins.value = null
    }

    // ── 气泡水平仪：只在开关打开且页面在前台时监听传感器 ──────────
    val levelSensor = remember { LevelSensor(context) }
    DisposableEffect(showBubbleLevel, levelSensor.available, displayRotation) {
        val shouldRun = showBubbleLevel && levelSensor.available
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME ->
                    if (shouldRun) levelSensor.start(displayRotation)
                Lifecycle.Event.ON_PAUSE -> levelSensor.stop()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        // 组合时可能已处于 RESUMED（观察者只收到之后的变化），补一次启动
        if (shouldRun && lifecycleOwner.lifecycle.currentState == Lifecycle.State.RESUMED) {
            levelSensor.start(displayRotation)
        }
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            levelSensor.stop()
        }
    }

    // 归零震动：在协程里收集读数做边沿检测，避免整屏随传感器频率重组
    LaunchedEffect(showBubbleLevel) {
        if (!showBubbleLevel) return@LaunchedEffect
        var previousRoll: Float? = null
        levelSensor.roll.collect { current ->
            if (current != null && LevelMath.snappedToLevel(current, previousRoll)) {
                hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
            }
            previousRoll = current
        }
    }

    // 请求重新生成滤镜缩略图（跨帧分批，完成后在主线程更新 filterThumbnails）
    val requestFilterThumbnails: () -> Unit = {
        glSurfaceViewRef?.requestFilterThumbnails(
            callback = { bitmaps ->
                filterThumbnails = bitmaps.map { it.asImageBitmap() }
            },
            paramsProvider = { index -> viewModel.effectiveParams(index) }
        )
    }

    // 切换滤镜：更新 ViewModel + GL（选择器与 HDR 联动共用）
    val applyFilterIndex: (Int) -> Unit = { index ->
        viewModel.selectFilter(index, viewModel.filterNames[index])
        glSurfaceViewRef?.let { glView ->
            glView.setFilterByIndex(index)
            viewModel.filterParams.value.forEach { (uniform, value) ->
                glView.setFilterParam(uniform, value)
            }
        }
    }

    // ── B1 对焦框状态 ──
    // 对焦框位置（null = 不显示）
    var focusIndicator by remember { mutableStateOf<Offset?>(null) }
    // 预览视图尺寸（用于 MeteringPointFactory）
    var previewSize by remember { mutableStateOf(IntSize.Zero) }

    // ── B3 长按看原图状态 ──
    var isPeekingOriginal by remember { mutableStateOf(false) }

    // 协程作用域（替代 MainScope，避免泄漏）
    val scope = rememberCoroutineScope()

    // CameraProvider 就绪标志
    var cameraProviderReady by remember { mutableStateOf(false) }

    // VideoRecorder 实例
    val videoRecorder = remember { VideoRecorder(context) }

    // 声控快门（与录像抢同一个麦克风，故与 videoRecorder 放在一起管理）
    val voiceShutter = remember { VoiceShutter(context) }
    // 麦克风是否仍被录像器占用：setRecording(false) 只是 UI 态，
    // 音频编码器还要排空收尾，此时开麦会读到静音甚至把录制打断
    var micBusy by remember { mutableStateOf(false) }
    // 是否在前台：退到后台要停掉倒数与监听
    var isResumed by remember { mutableStateOf(true) }

    // 权限处理
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
                    == PackageManager.PERMISSION_GRANTED
        )
    }
    var hasAudioPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                    == PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        hasCameraPermission = permissions[Manifest.permission.CAMERA] ?: false
        hasAudioPermission = permissions[Manifest.permission.RECORD_AUDIO] ?: false
    }

    // 冷启动只请求 CAMERA；RECORD_AUDIO 只在拍照模式下用不到，
    // 开机就弹"相机+麦克风"双权限会吓退纯拍照用户，延后到真正开麦时再要。
    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            permissionLauncher.launch(arrayOf(Manifest.permission.CAMERA))
        }
    }

    // 首次进入录像模式或开启声控快门时才请求麦克风；
    // 被拒绝则录像无声、声控保持关闭（shouldListen 已含 hasAudioPermission 门槛）
    LaunchedEffect(captureMode, voiceEnabled) {
        if (!hasAudioPermission &&
            (captureMode == CaptureMode.VIDEO || voiceEnabled)
        ) {
            permissionLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
        }
    }

    // 初始化 CameraProvider（异步），就绪后设置标志
    LaunchedEffect(hasCameraPermission) {
        if (hasCameraPermission) {
            cameraManager.initialize {
                cameraProviderReady = true
            }
        }
    }

    // 等待 CameraProvider 和 GLSurfaceView 都就绪后再绑定预览
    LaunchedEffect(cameraProviderReady, glSurfaceViewRef) {
        if (cameraProviderReady && glSurfaceViewRef != null) {
            // 如果 GL Surface 已就绪，直接绑定；否则由 onGlSurfaceReady 回调触发
            if (glSurfaceViewRef!!.getSurfaceTexture() != null) {
                cameraManager.bindPreview(lifecycleOwner, glSurfaceViewRef!!, displayRotation)
                refreshTorchAvailable()
            }
            // 首次生成滤镜缩略图（跨帧分批，完成后回调主线程）
            requestFilterThumbnails()
        }
    }

    // 画幅变化时推给 GL 渲染器
    LaunchedEffect(currentAspectRatio, glSurfaceViewRef) {
        glSurfaceViewRef?.setTargetAspect(currentAspectRatio.value)
    }

    // 参数面板关闭时刷新缩略图（反映最新自定义参数）
    var wasParamsPanelOpen by remember { mutableStateOf(false) }
    LaunchedEffect(showParamsPanel) {
        if (wasParamsPanelOpen && !showParamsPanel) {
            requestFilterThumbnails()
        }
        wasParamsPanelOpen = showParamsPanel
    }

    // GL Surface 首次就绪：应用持久化的初始滤镜 + 参数 + 画幅
    LaunchedEffect(glSurfaceViewRef) {
        val glView = glSurfaceViewRef ?: return@LaunchedEffect
        glView.setFilterByIndex(currentFilterIndex)
        viewModel.filterParams.value.forEach { (uniform, value) ->
            glView.setFilterParam(uniform, value)
        }
        glView.setTargetAspect(currentAspectRatio.value)
        glView.setFilterStrength(filterStrength)
    }

    // 滤镜强度变化：下发 GL（预览/录像/拍照共用同一次混合）
    LaunchedEffect(filterStrength, glSurfaceViewRef) {
        glSurfaceViewRef?.setFilterStrength(filterStrength)
    }

    // 对焦框 2.5 秒后自动消失
    LaunchedEffect(focusIndicator) {
        if (focusIndicator != null) {
            delay(2500L)
            focusIndicator = null
        }
    }

    // 长按看原图：切换到 PassthroughFilter（index 0），松手恢复原滤镜
    LaunchedEffect(isPeekingOriginal) {
        val glView = glSurfaceViewRef ?: return@LaunchedEffect
        if (isPeekingOriginal) {
            // 临时切换到原图（不改变 ViewModel 状态）
            glView.setFilterByIndex(0)
        } else {
            // 恢复原滤镜 + 参数
            glView.setFilterByIndex(currentFilterIndex)
            viewModel.filterParams.value.forEach { (uniform, value) ->
                glView.setFilterParam(uniform, value)
            }
        }
    }

    // 录像结果回调
    LaunchedEffect(Unit) {
        videoRecorder.onVideoSaved = { uri ->
            viewModel.setRecording(false)
            micBusy = false
            if (uri != null) {
                viewModel.setLastMediaUri(uri)
                viewModel.showRecordingResult("视频已保存")
            } else {
                viewModel.showRecordingResult("保存失败")
            }
        }
        videoRecorder.onRecordingError = { e ->
            viewModel.setRecording(false)
            micBusy = false
            viewModel.showRecordingResult("录制失败: ${e.message}")
        }
    }

    // 录制时长计时器（含最大时长限制）
    LaunchedEffect(isRecording) {
        if (isRecording) {
            val maxDurationSec = 180 // 最大 3 分钟
            while (isRecording) {
                delay(1000L)
                val sec = videoRecorder.getRecordingDurationSec()
                viewModel.updateRecordingDuration(sec)
                if (sec >= maxDurationSec) {
                    // 达到最大时长自动停止
                    glSurfaceViewRef?.let { videoRecorder.stopRecording(it) }
                    viewModel.setRecording(false)
                    break
                }
            }
        }
    }

    DisposableEffect(hasCameraPermission) {
        onDispose {
            if (hasCameraPermission) {
                videoRecorder.release()
                // 释放 GL 资源（纹理、FBO、shader program）
                glSurfaceViewRef?.release()
                cameraManager.shutdown()
            }
        }
    }

    // 拍照结果 SnackBar
    LaunchedEffect(Unit) {
        viewModel.captureResult.collect { message ->
            message?.let {
                snackbarHostState.showSnackbar(it)
            }
        }
    }

    // 录像结果 SnackBar
    LaunchedEffect(Unit) {
        viewModel.recordingResult.collect { message ->
            message?.let {
                snackbarHostState.showSnackbar(it)
            }
        }
    }

    // 闪白动画：快速闪白 + 缓慢消退
    LaunchedEffect(isCapturing) {
        if (isCapturing) {
            flashAlpha.animateTo(0.8f, animationSpec = tween(durationMillis = 80))
            flashAlpha.animateTo(0f, animationSpec = tween(durationMillis = 400))
            viewModel.captureComplete()
        }
    }

    // 拍照看门狗：正常路径必然自行复位（届时本协程随这两个 key 变化被取消），
    // 只有回调彻底丢失才会走到这里放开快门。
    // 期限由 ViewModel 按本次请求给出（连拍 9 帧要 2 秒以上，写死 6 秒会中途误判超时）。
    LaunchedEffect(captureProcessing, captureDeadlineMs) {
        if (!captureProcessing) return@LaunchedEffect
        delay(captureDeadlineMs)
        viewModel.captureProcessingComplete()
        viewModel.showCaptureResult("拍照超时，已重置")
    }

    // ── 快门出口 ──────────────────────────────────────────────────
    // performShutter 只负责"立刻出片 / 切换录制"；requestShutter（紧随其后）才是
    // 屏幕按钮、音量键、倒计时与声控共用的唯一入口。
    // 分层的原因：倒计时本身不是出片，它是"到点后再调一次出口"，
    // 放在入口处能让所有触发源自动获得同一套自拍延迟语义。
    // 可变状态一律通过 viewModel.* 或 remember 的 State 委托即时读取，
    // 因此注册一次的闭包不会锁死某次重组的快照。
    val performShutter: () -> Unit = perform@{
        when (viewModel.captureMode.value) {
            CaptureMode.PHOTO -> {
                // 按钮的 enabled 只挡得住屏幕点击；音量键/声控没有 enabled，
                // 所以在入口处自己挡一次重入。连拍还要再加一道硬闩：
                // 批次比看门狗期限长时 captureProcessing 会被提前复位，
                // 而 GL 那一格回调此刻仍然占用中，放进去就是丢帧 + 回调错配。
                if (viewModel.captureProcessing.value || viewModel.isBurstRunning) return@perform
                val glView = glSurfaceViewRef
                if (glView == null) {
                    viewModel.showCaptureResult("相机未就绪")
                    return@perform
                }
                hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)

                val frameCount = burstCount.frames
                if (frameCount > 1) {
                    // 连拍强制走单帧管线：HDR/夜景各自要占 PreProcessor 状态机与
                    // GL 的单槽回调，混跑只会得到"相机正忙"外加缺帧
                    val degraded = hdrEnabled || nightEnabled
                    if (degraded) {
                        Log.d(TAG, "Burst: 与 HDR/夜景互斥，本批只用单帧管线")
                    }
                    viewModel.isBurstRunning = true
                    viewModel.startCapture()
                    // 整批只置一次处理中，期限按帧数给（写死 6 秒会在中途放开快门）
                    viewModel.startCaptureProcessing(
                        BurstCapture.deadlineFor(frameCount, BurstCapture.DEFAULT_INTERVAL_MS)
                    )
                    val gifEnabled = burstGif
                    scope.launch {
                        val message = try {
                            captureBurstFrames(
                                context, glView, frameCount,
                                gifEnabled = gifEnabled
                            ) { uri ->
                                viewModel.setLastMediaUri(uri)
                            }
                        } finally {
                            viewModel.captureProcessingComplete()
                            viewModel.isBurstRunning = false
                        }
                        viewModel.showCaptureResult(
                            if (degraded) "$message（连拍不含 HDR/夜景）" else message
                        )
                    }
                    return@perform
                }

                viewModel.startCapture()
                viewModel.startCaptureProcessing()
                // 按多帧前处理开关分流到对应管线
                // HDR+：Camera2 Burst 包围曝光 → 块匹配对齐 → 曝光加权融合
                //      手动曝光模式下 requestBurstCapture 会自动回退到普通多帧
                // 夜景：连续多帧捕获 → 块匹配对齐 → 等权平均时域降噪
                //      适合手持静态场景，降噪效果 ≈ √N（4 帧 ≈ 2 倍）
                // 单帧：GL 渲染当前带滤镜的预览帧（Preview 缓冲现已为传感器最高分辨率）
                val onBitmap: (Bitmap, Int) -> Unit = { bitmap, exifOrientation ->
                    // 在 IO 线程保存照片，避免阻塞 GL 渲染线程
                    scope.launch {
                        val uri = withContext(Dispatchers.IO) {
                            ImageSaver.saveToGallery(context, bitmap, exifOrientation)
                        }
                        bitmap.recycle()
                        viewModel.captureProcessingComplete()
                        if (uri != null) {
                            viewModel.setLastMediaUri(uri)
                            viewModel.showCaptureResult("照片已保存")
                        } else {
                            viewModel.showCaptureResult("保存失败")
                        }
                    }
                }
                // 失败必须复位处理中状态并提示：
                // 否则快门（enabled = !captureProcessing）永久禁用
                val onError: (String) -> Unit = { reason ->
                    viewModel.captureProcessingComplete()
                    viewModel.showCaptureResult("拍照失败：$reason")
                }
                when {
                    hdrEnabled -> {
                        val orient = glView?.cameraManager?.yuvCaptureOrientation()
                            ?: android.media.ExifInterface.ORIENTATION_NORMAL
                        glView.captureHdrPhoto(
                            callback = { bmp -> onBitmap(bmp, orient) },
                            onError = onError
                        )
                    }
                    nightEnabled -> {
                        val orient = glView?.cameraManager?.yuvCaptureOrientation()
                            ?: android.media.ExifInterface.ORIENTATION_NORMAL
                        glView.captureAlignedPhoto(
                            callback = { bmp -> onBitmap(bmp, orient) },
                            onError = onError
                        )
                    }
                    else -> glView.capturePhoto(
                        callback = { bmp -> onBitmap(bmp, android.media.ExifInterface.ORIENTATION_NORMAL) },
                        onError = onError
                    )
                }
            }
            CaptureMode.VIDEO -> {
                // 录像（带 GL 滤镜）
                val glView = glSurfaceViewRef
                if (glView == null) {
                    viewModel.showRecordingResult("相机未就绪")
                    return@perform
                }
                hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                if (viewModel.isRecording.value) {
                    videoRecorder.stopRecording(glView)
                    // 立即退出录制态：保存是异步的，等回调会让快门
                    // 在约 1 秒内仍显示"录制中"并可重复点击
                    viewModel.setRecording(false)
                } else {
                    // 必须先交还麦克风再启动录像器：Android 上后开的 AudioRecord
                    // 往往只能读到静音，会把录像的音轨悄悄废掉
                    voiceShutter.stop()
                    if (videoRecorder.startRecording(glView)) {
                        micBusy = true
                        viewModel.setRecording(true)
                    } else {
                        micBusy = false
                        viewModel.showRecordingResult("录制启动失败")
                    }
                }
            }
        }
    }

    // ── 倒计时自拍 ────────────────────────────────────────────────
    // null = 未在倒数。倒数中再按一次快门即取消（人已就位或改主意了）。
    // 放在 Composable 层而非抽 controller：它只是 UI 时序，
    // 取消时 key 变化会顺带掐掉计时协程，协程一死就不可能出片。
    var countdownLeft by remember { mutableStateOf<Int?>(null) }

    /**
     * 所有触发源的唯一入口：屏幕按钮、音量键、（后续）声控与连拍。
     *
     * 录像模式刻意不套倒计时：录制已经有明确的开始/停止反馈，
     * 中间插一层倒数只会让"到底在录没录"更难判断。
     */
    val requestShutter: () -> Unit = entry@{
        val timerOn = countdownSec != CountdownSec.OFF &&
            viewModel.captureMode.value == CaptureMode.PHOTO
        if (!timerOn) {
            performShutter()
            return@entry
        }
        hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
        val left = countdownLeft
        if (left != null) {
            Log.d(TAG, "Countdown: cancel left=$left")
            countdownLeft = null
        } else {
            Log.d(TAG, "Countdown: start n=${countdownSec.seconds}")
            countdownLeft = countdownSec.seconds
        }
    }

    // 每秒递减，走到 0 才真正出片：3→2→1 各显示 1 秒，第 3 秒末按下快门
    LaunchedEffect(countdownLeft) {
        val left = countdownLeft ?: return@LaunchedEffect
        if (left <= 0) {
            countdownLeft = null
            Log.d(TAG, "Countdown: fire")
            performShutter()
            return@LaunchedEffect
        }
        delay(1000L)
        countdownLeft = left - 1
    }

    // 切到录像、或把档位改成"关"，正在进行的倒数都不该继续兑现
    LaunchedEffect(captureMode, countdownSec) {
        countdownLeft = null
    }

    // 把入口交给 Activity（音量键路径）。VM 是 Activity 作用域，与 CameraScreen 同一个实例。
    DisposableEffect(Unit) {
        viewModel.shutterRequestHandler = requestShutter
        onDispose {
            viewModel.shutterRequestHandler = null
            viewModel.shutterArmed = false
        }
    }

    // 「可出片」门：权限、CameraProvider、GL 视图、新手引导任一不满足都不该出片。
    // 前台判断在 MainActivity 做（那里才拿得到 lifecycle）。
    LaunchedEffect(hasCameraPermission, cameraProviderReady, glSurfaceViewRef, showOnboarding) {
        viewModel.shutterArmed = hasCameraPermission && cameraProviderReady &&
            glSurfaceViewRef != null && !showOnboarding
    }

    // ── 声控快门 ──────────────────────────────────────────────────
    // 监听条件全是「不该听的场景」：录像在占麦、退到后台、拍照处理中、引导页还没走完。
    LaunchedEffect(
        voiceEnabled, hasAudioPermission, micBusy, isResumed, showOnboarding, captureProcessing
    ) {
        val shouldListen = voiceEnabled && hasAudioPermission && !micBusy &&
            isResumed && !showOnboarding && !captureProcessing
        if (shouldListen) {
            voiceShutter.setNoisePickup(voicePickup)
            voiceShutter.setAbsMinLevel(voiceMinLevel)
            // 触发从采集线程到达，而 requestShutter 会碰 Compose 状态、快门震动、
            // 甚至直接启动录像器 —— 一律回主线程再执行
            voiceShutter.start { scope.launch { requestShutter() } }
        } else {
            voiceShutter.stop()
        }
    }

    // 拖滑块不该让麦克风关开一次：参数直接推进正在跑的实例
    LaunchedEffect(voicePickup, voiceMinLevel) {
        voiceShutter.setNoisePickup(voicePickup)
        voiceShutter.setAbsMinLevel(voiceMinLevel)
    }

    // 退到后台（切应用/锁屏）时取消倒数：计时协程不受前台门控制，
    // 不取消的话会在 Surface 已暂停时打出一张陈旧画面。
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> isResumed = true
                Lifecycle.Event.ON_STOP -> {
                    isResumed = false
                    countdownLeft = null
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            voiceShutter.stop()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        if (hasCameraPermission) {
            // 全屏相机预览（含手势：点击对焦/捏合缩放/长按看原图）
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .onSizeChanged { previewSize = it }
                    .cameraGestures(
                        cameraManager = cameraManager,
                        previewSize = { previewSize },
                        onFocusStarted = { pos -> focusIndicator = pos },
                        onLongPressStart = { isPeekingOriginal = true },
                        onLongPressEnd = { isPeekingOriginal = false }
                    )
            ) {
                AndroidView(
                    factory = { ctx ->
                        CameraGLSurfaceView(ctx, cameraManager, lifecycleOwner).also { view ->
                            view.onGlSurfaceReady = {
                                // GL Surface 就绪后，如果 CameraProvider 也已就绪，则绑定预览
                                if (cameraProviderReady) {
                                    cameraManager.bindPreview(lifecycleOwner, view, displayRotation)
                                    refreshTorchAvailable()
                                }
                            }
                            glSurfaceViewRef = view
                            view.onHistogramBins = { bins -> histogramBins.value = bins }
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )

                // 三分线网格（后室主题：奶油黄细线，覆盖 4:3 预览区域）
                if (showGrid) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(configuration.screenWidthDp.dp * 4f / 3f)
                            .align(Alignment.TopCenter)
                    ) {
                        RuleOfThirdsGrid(Modifier.fillMaxSize())
                    }
                }

                // 实时直方图（左上，避开对焦框与顶部居中的缩放胶囊）
                if (showHistogram) {
                    HistogramBox(
                        binsState = histogramBins,
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(start = 12.dp, top = 100.dp)
                    )
                }

                // 气泡水平仪（顶部居中，紧贴缩放胶囊下方；读数在本组件内收集）
                if (showBubbleLevel) {
                    BubbleLevel(
                        roll = levelSensor.roll,
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 96.dp)
                    )
                }

                // 倒计时自拍（正中央；顶部那几个浮层各占 64/96/100/112，居中不与之重叠）
                CountdownOverlay(
                    seconds = countdownLeft,
                    modifier = Modifier.align(Alignment.Center)
                )

                // 缩放倍率胶囊（顶部居中，位于 TopBar 之下）
                Box(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 64.dp)
                        .background(BackroomsShadow.copy(alpha = 0.75f), CircleShape)
                        .padding(horizontal = 12.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = String.format("%.1fx", zoomRatio),
                        color = BackroomsCream,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
                    )
                }

                // 对焦框指示器
                focusIndicator?.let { pos ->
                    FocusIndicatorBox(position = pos)
                }

                // 长按看原图提示
                if (isPeekingOriginal) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 160.dp)
                            .background(BackroomsShadow.copy(alpha = 0.75f), RoundedCornerShape(12.dp))
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = "原图预览",
                            color = BackroomsCream,
                            fontSize = 12.sp
                        )
                    }
                }

                // Phase 3：智能夜景建议（暗光场景自动提示）
                AnimatedVisibility(
                    visible = showNightSuggestion,
                    enter = androidx.compose.animation.fadeIn(tween(250)),
                    exit = androidx.compose.animation.fadeOut(tween(250)),
                    modifier = Modifier.align(Alignment.TopCenter)
                ) {
                    Row(
                        modifier = Modifier
                            .padding(top = 112.dp)
                            .clip(RoundedCornerShape(20.dp))
                            .background(BackroomsShadow.copy(alpha = 0.88f))
                            .clickable {
                                // 一键启用夜景
                                nightEnabled = true
                                viewModel.showCaptureResult("夜景已开启（多帧时域降噪）")
                            }
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.NightsStay,
                            contentDescription = null,
                            tint = BackroomsYellow,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = "光线较暗，开启夜景",
                            color = BackroomsCream,
                            fontSize = 12.sp
                        )
                        Text(
                            text = "✕",
                            color = BackroomsCream.copy(alpha = 0.6f),
                            fontSize = 13.sp,
                            modifier = Modifier.clickable {
                                nightSuggestionDismissedAt = System.currentTimeMillis()
                            }
                        )
                    }
                }
            }

            // 顶部栏
            TopBar(
                onSwitchCamera = {
                    glSurfaceViewRef?.let { glView ->
                        cameraManager.switchCamera(lifecycleOwner, glView, displayRotation)
                    }
                    // 切换后强制关灯并刷新闪光灯可用性（前置无闪光灯）
                    viewModel.setTorch(false)
                    refreshTorchAvailable()
                    // 同步曝光补偿档位（重绑后可能被设备收敛）
                    evIndex = cameraManager.getCurrentEvIndex()
                },
                showGrid = showGrid,
                onToggleGrid = { viewModel.toggleGrid() },
                torchEnabled = torchEnabled,
                torchAvailable = torchAvailable,
                onToggleTorch = {
                    val newState = !torchEnabled
                    viewModel.setTorch(newState)
                    cameraManager.enableTorch(newState)
                },
                cameraSettingsActive = showCameraSettings,
                onToggleCameraSettings = { showCameraSettings = !showCameraSettings },
                modifier = Modifier.align(Alignment.TopCenter)
            )

            // 专业相机设置面板（右侧抽屉，位于顶部栏与底部控制区之间）
            if (showCameraSettings) {
                CameraSettingsPanel(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(top = 84.dp, bottom = 250.dp, end = 12.dp)
                        .width(248.dp),
                    filterStrength = filterStrength,
                    filterApplied = currentFilterIndex != 0,
                    onFilterStrength = { value -> viewModel.setFilterStrength(value) },
                    onResetFilterStrength = { viewModel.resetFilterStrength() },
                    wbPreset = wbPreset,
                    wbIntensity = wbIntensity,
                    onWbPreset = { preset ->
                        wbPreset = preset
                        cameraManager.setWbPreset(preset)
                    },
                    onWbIntensity = { value ->
                        wbIntensity = value
                        cameraManager.setWbIntensity(value)
                    },
                    evIndex = evIndex,
                    evRange = cameraManager.getExposureCompensationRange(),
                    onEvChange = { index ->
                        evIndex = index
                        cameraManager.setExposureCompensation(index)
                    },
                    manualExposure = manualExposure,
                    onManualExposureToggle = { on ->
                        manualExposure = on
                        if (on) {
                            cameraManager.setManualExposure(manualIso, manualShutterNs)
                        } else {
                            cameraManager.resetManualExposure()
                        }
                    },
                    iso = manualIso,
                    isoRange = cameraManager.getIsoRange(),
                    onIsoChange = { newIso ->
                        manualIso = newIso
                        if (manualExposure) {
                            cameraManager.setManualExposure(newIso, manualShutterNs)
                        }
                    },
                    shutterNs = manualShutterNs,
                    shutterRange = cameraManager.getExposureTimeRangeNs(),
                    onShutterChange = { ns ->
                        manualShutterNs = ns
                        if (manualExposure) {
                            cameraManager.setManualExposure(manualIso, ns)
                        }
                    },
                    hdrEnabled = hdrEnabled,
                    onHdrToggle = { on ->
                        hdrEnabled = on
                        if (on && nightEnabled) {
                            // HDR+ 与夜景互斥：开启 HDR+ 时关闭夜景
                            nightEnabled = false
                        }
                        viewModel.showCaptureResult(if (on) "HDR+ 已开启（多帧包围曝光）" else "HDR+ 已关闭")
                    },
                    nightEnabled = nightEnabled,
                    onNightToggle = { on ->
                        nightEnabled = on
                        if (on && hdrEnabled) {
                            // 互斥：开启夜景时关闭 HDR+
                            hdrEnabled = false
                        }
                        viewModel.showCaptureResult(if (on) "夜景已开启（多帧时域降噪）" else "夜景已关闭")
                    },
                    histogramEnabled = showHistogram,
                    onHistogramToggle = { on -> viewModel.setShowHistogram(on) },
                    zebraMode = zebraMode,
                    onZebraMode = { mode -> viewModel.setZebraMode(mode) },
                    peakingEnabled = focusPeaking,
                    onPeakingToggle = { on -> viewModel.setFocusPeaking(on) },
                    peakingSensitivity = peakingSensitivity,
                    onPeakingSensitivity = { value -> viewModel.setPeakingSensitivity(value) },
                    levelEnabled = showBubbleLevel,
                    onLevelToggle = { on -> viewModel.setShowBubbleLevel(on) },
                    volumeKeyShutter = volumeKeyShutter,
                    onVolumeKeyShutter = { mode -> viewModel.setVolumeKeyShutter(mode) },
                    countdownSec = countdownSec,
                    onCountdownSec = { mode -> viewModel.setCountdownSec(mode) },
                    voiceEnabled = voiceEnabled,
                    onVoiceToggle = { on -> viewModel.setVoiceEnabled(on) },
                    voicePickup = voicePickup,
                    onVoicePickup = { value -> viewModel.setVoicePickup(value) },
                    voiceMinLevel = voiceMinLevel,
                    onVoiceMinLevel = { value -> viewModel.setVoiceMinLevel(value) },
                    voiceMeter = voiceShutter.meter,
                    burstCount = burstCount,
                    onBurstCount = { mode -> viewModel.setBurstCount(mode) },
                    burstGif = burstGif,
                    onBurstGifToggle = { on -> viewModel.setBurstGif(on) },
                    onResetAll = {
                        wbPreset = WbPreset.AUTO
                        cameraManager.setWbPreset(WbPreset.AUTO)
                        wbIntensity = 0.5f
                        cameraManager.setWbIntensity(0.5f)
                        evIndex = 0
                        cameraManager.setExposureCompensation(0)
                        manualExposure = false
                        cameraManager.resetManualExposure()
                        hdrEnabled = false
                        nightEnabled = false
                    }
                )
            }

            // 底部控制区
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // 分类标签栏
                FilterCategoryBar(
                    categories = FilterCategory.values().toList(),
                    selected = selectedCategory,
                    onSelect = { selectedCategory = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp)
                )

                // 滤镜选择器 + 参数调节按钮
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterSelector(
                        filters = viewModel.filterNames,
                        selectedIndex = currentFilterIndex,
                        onFilterSelected = { index ->
                            applyFilterIndex(index)
                        },
                        modifier = Modifier.weight(1f),
                        customizedIndices = customizedIndices,
                        thumbnails = filterThumbnails,
                        displayIndices = displayIndices
                    )

                    // 参数调节按钮（仅当当前滤镜有可调参数时显示）
                    if (viewModel.getCurrentFilterParamDefs().isNotEmpty()) {
                        ParamsButton(
                            isActive = showParamsPanel,
                            isCustomized = viewModel.isCurrentFilterCustomized(),
                            onClick = { viewModel.toggleParamsPanel() }
                        )
                    }
                }

                // 参数调节面板
                FilterParamsPanel(
                    visible = showParamsPanel,
                    paramDefs = viewModel.getCurrentFilterParamDefs(),
                    currentValues = filterParams,
                    onParamChange = { name, value ->
                        viewModel.setFilterParam(name, value)
                        glSurfaceViewRef?.setFilterParam(name, value)
                    },
                    onReset = {
                        viewModel.resetFilterParams()
                        // 重置 GL 端的参数
                        viewModel.getCurrentFilterParamDefs().forEach { def ->
                            glSurfaceViewRef?.setFilterParam(def.uniformName, def.defaultValue)
                        }
                    },
                    presets = viewModel.getCurrentFilterPresets(),
                    onPresetSelected = { preset ->
                        viewModel.applyPreset(preset)
                        // 把预设参数推给 GL
                        viewModel.filterParams.value.forEach { (uniform, value) ->
                            glSurfaceViewRef?.setFilterParam(uniform, value)
                        }
                    }
                )

                Spacer(modifier = Modifier.height(24.dp))

                // PHOTO | VIDEO 分段切换
                ModeSegmentedControl(
                    currentMode = captureMode,
                    onModeSwitch = { viewModel.toggleCaptureMode() },
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .padding(bottom = 16.dp)
                )

                // 拍照/录像按钮 + 缩略图（三等分居中布局）
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 左：留白（保证快门居中）
                    Box(modifier = Modifier.weight(1f))

                    // 中：拍照/录像按钮
                    CaptureButton(
                        isCapturing = isCapturing,
                        isVideoMode = captureMode == CaptureMode.VIDEO,
                        isRecording = isRecording,
                        recordingDurationSec = recordingDurationSec,
                        enabled = !captureProcessing,
                        onClick = { requestShutter() }
                    )

                    // 右：最近一张照片缩略图 + 点击打开相册
                    Box(
                        modifier = Modifier.weight(1f),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        LastPhotoThumbnail(
                            uri = lastMediaUri,
                            onClick = {
                                // 打开系统相册
                                val intent = Intent(Intent.ACTION_VIEW).apply {
                                    setDataAndType(
                                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                                        "image/*"
                                    )
                                }
                                runCatching { context.startActivity(intent) }
                            },
                            modifier = Modifier.padding(start = 8.dp)
                        )
                    }
                }
            }

            // 拍照闪白效果覆盖层
            if (flashAlpha.value > 0f) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.White.copy(alpha = flashAlpha.value))
                )
            }

            // 拍照处理进度反馈（闪白结束后仍在处理多帧对齐/融合/保存时显示）
            if (captureProcessing) {
                CaptureProgressOverlay()
            }

            // 新手引导（一次性，点击任意处关闭）
            if (showOnboarding) {
                OnboardingOverlay(
                    onDismiss = {
                        showOnboarding = false
                        FilterPrefs.setOnboardingShown()
                    }
                )
            }
        } else {
            Text(
                text = "需要相机和麦克风权限",
                color = BackroomsCream,
                modifier = Modifier.align(Alignment.Center)
            )
        }

        // SnackBar
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 140.dp)
        )
    }
}

/**
 * 连拍出片：串行收帧 → 一张九宫格（开了动图再多一张 GIF）→ 存相册，返回给用户的话术。
 *
 * 只产出一张拼图：相册里多出 N 张连拍原图是噪声而不是产物。
 * 帧位图在这里一律 recycle（含协程被取消的路径）—— 9 帧降采样后仍有约 8MB，
 * 留到下一次 GC 会直接把下一次拍照的 FBO 分配挤爆。
 * 拼接与量化走 Default 线程：主线程上做 9 帧抖动映射就是几百毫秒的预览掉帧。
 */
private suspend fun captureBurstFrames(
    context: Context,
    glView: CameraGLSurfaceView,
    frameCount: Int,
    gifEnabled: Boolean,
    onSaved: (Uri) -> Unit
): String {
    val result = BurstCapture.runBurst(glView, frameCount)
    val cells = result.frames
    if (cells.isEmpty()) {
        return "连拍失败：${result.failedReason ?: "取景器没有出帧"}"
    }
    return try {
        withContext(Dispatchers.Default) {
            val first = cells.first()
            val cell = MosaicLayout.cellFor(first.width, first.height, BurstCapture.CELL_LONG_SIDE)
            val saved = cell?.let { (cellW, cellH) ->
                val mosaic = MosaicComposer.compose(
                    cells,
                    MosaicLayout.of(cells.size, MOSAIC_COLUMNS, cellW, cellH, MOSAIC_GAP_PX)
                )
                mosaic?.let {
                    try {
                        withContext(Dispatchers.IO) { ImageSaver.saveToGallery(context, it) }
                    } finally {
                        it.recycle()
                    }
                }
            }
            if (saved == null) {
                "连拍失败：拼图未能生成"
            } else {
                onSaved(saved)
                burstResultNote(cells.size, frameCount, result.failedReason) +
                    if (gifEnabled) saveBurstGif(context, cells) else ""
            }
        }
    } finally {
        cells.forEach { if (!it.isRecycled) it.recycle() }
    }
}

/**
 * 把整批帧再编成一张 GIF89a 存进相册，返回拼在话术尾巴上的补充说明。
 *
 * 少于 2 帧、或帧尺寸不一致时直接放弃：动图的意义是"看见过程"，凑不出来不该硬造。
 */
private suspend fun saveBurstGif(context: Context, cells: List<Bitmap>): String {
    val w = cells.first().width
    val h = cells.first().height
    if (cells.size < 2 || w <= 0 || h <= 0 || cells.any { it.width != w || it.height != h }) {
        Log.d(TAG, "Gif: 跳过 frames=${cells.size} size=${w}x$h")
        return "，动图未生成"
    }
    val pixels = Array(cells.size) { i ->
        IntArray(w * h).also { cells[i].getPixels(it, 0, 0, w, h, 0, 0) }
    }
    // 每帧停留 = 收帧间隔，播出来的节奏和拍的时候一致
    val delayCs = (BurstCapture.DEFAULT_INTERVAL_MS / 10).toInt().coerceAtLeast(1)
    val bytes = GifEncoder.encode(pixels, w, h, IntArray(cells.size) { delayCs })
    Log.d(TAG, "Gif: frames=${pixels.size} ${w}x$h bytes=${bytes.size}")
    val uri = withContext(Dispatchers.IO) {
        ImageSaver.saveBytesToGallery(context, bytes, "image/gif", "gif")
    }
    return if (uri == null) "，动图保存失败" else " + 动图"
}

/** 缺帧要说清楚：拿 5 张报"9 张"是骗人，只报"5 张"又像是功能坏了 */
private fun burstResultNote(got: Int, requested: Int, reason: String?): String {
    if (got >= requested || reason == null) return "拼图已保存（$got 张）"
    return "拼图已保存（$requested 张只拍到 $got 张：$reason）"
}

/**
 * 参数调节按钮（后室主题）。使用 Material 图标，已自定义参数时显示荧光黄指示点。
 */
@Composable
private fun ParamsButton(
    isActive: Boolean,
    isCustomized: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    // 后室配色：激活态荧光黄底 + 深棕图标；否则暗黄棕底 + 奶油黄图标
    val bgColor by animateColorAsState(
        targetValue = if (isActive) BackroomsYellow else BackroomsShadow.copy(alpha = 0.5f),
        label = "paramsBg"
    )
    val tint by animateColorAsState(
        targetValue = if (isActive) BackroomsYellowOnDark else BackroomsCream.copy(alpha = 0.9f),
        label = "paramsTint"
    )
    Box(
        modifier = modifier
            .size(40.dp)
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(bgColor)
                .clickable { onClick() }
                .align(Alignment.Center),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Filled.Tune,
                contentDescription = "参数调节",
                tint = tint,
                modifier = Modifier.size(20.dp)
            )
        }
        // 已自定义指示点（荧光黄）
        if (isCustomized && !isActive) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(BackroomsYellow)
            )
        }
    }
}

/**
 * PHOTO | VIDEO 分段切换控件（后室主题）。
 */
@Composable
private fun ModeSegmentedControl(
    currentMode: CaptureMode,
    onModeSwitch: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(BackroomsShadow.copy(alpha = 0.75f))
            .padding(3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SegmentedOption(
            text = "拍照",
            selected = currentMode == CaptureMode.PHOTO,
            onClick = { if (currentMode != CaptureMode.PHOTO) onModeSwitch() }
        )
        SegmentedOption(
            text = "录像",
            selected = currentMode == CaptureMode.VIDEO,
            onClick = { if (currentMode != CaptureMode.VIDEO) onModeSwitch() }
        )
    }
}

@Composable
private fun SegmentedOption(
    text: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val bg by animateColorAsState(
        targetValue = if (selected) BackroomsYellow else Color.Transparent,
        label = "segmentBg"
    )
    val fg by animateColorAsState(
        targetValue = if (selected) BackroomsYellowOnDark else BackroomsCream.copy(alpha = 0.85f),
        label = "segmentFg"
    )
    Text(
        text = text,
        color = fg,
        fontSize = 13.sp,
        fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(bg)
            .clickable { onClick() }
            .padding(horizontal = 22.dp, vertical = 6.dp)
    )
}

/**
 * 拍照处理进度反馈覆盖层。
 *
 * 闪白动画结束后，多帧捕获/对齐/融合/保存仍在进行时显示：
 *   - 半透明遮罩阻止误触
 *   - 居中转圈 + "处理中…" 文案，向用户传达正在处理
 *
 * 极简灰白风格，与整体 UI 一致。
 */
@Composable
private fun CaptureProgressOverlay() {
    Box(
        modifier = Modifier
            .fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            CircularProgressIndicator(
                color = BackroomsCream,
                strokeWidth = 2.dp,
                modifier = Modifier.size(36.dp)
            )
            Text(
                text = "处理中…",
                color = BackroomsCream,
                fontSize = 13.sp
            )
        }
    }
}

/**
 * 三分线网格（后室主题奶油黄细线）。
 */
@Composable
private fun RuleOfThirdsGrid(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val lineColor = BackroomsCream.copy(alpha = 0.35f)
        val stroke = Stroke(width = 1f)
        for (i in 1..2) {
            val x = w * i / 3f
            drawLine(lineColor, Offset(x, 0f), Offset(x, h), strokeWidth = stroke.width)
            val y = h * i / 3f
            drawLine(lineColor, Offset(0f, y), Offset(w, y), strokeWidth = stroke.width)
        }
    }
}

/**
 * 新手引导覆盖层：点按对焦 / 双指缩放 / 长按看原图。
 * 点击任意处关闭并标记已显示（一次性）。
 */
@Composable
private fun OnboardingOverlay(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.75f))
            .clickable { onDismiss() },
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "手势提示",
                color = BackroomsYellow,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(20.dp))
            OnboardingHint("点按", "对焦 / 测光")
            OnboardingHint("双指捏合", "缩放取景")
            OnboardingHint("长按", "预览原图")
            Spacer(modifier = Modifier.height(28.dp))
            Text(
                text = "点击任意处开始",
                color = BackroomsCream.copy(alpha = 0.7f),
                fontSize = 12.sp
            )
        }
    }
}

@Composable
private fun OnboardingHint(gesture: String, action: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(vertical = 6.dp)
    ) {
        Text(
            text = gesture,
            color = BackroomsYellow,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.width(72.dp)
        )
        Text(
            text = action,
            color = BackroomsCream,
            fontSize = 14.sp
        )
    }
}

/**
 * 最近一张照片缩略图。点击打开相册。
 * uri 为 null 时显示占位图标。
 */
@Composable
private fun LastPhotoThumbnail(
    uri: Uri?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    // 在 IO 线程解码缩略图
    val thumbnail by produceState<Bitmap?>(initialValue = null, uri) {
        if (uri == null) { value = null; return@produceState }
        value = withContext(Dispatchers.IO) {
            runCatching {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    // 先 decode bounds
                    val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeStream(input, null, opts)
                    // 计算采样率（目标 ~160px）
                    val sample = maxOf(opts.outWidth, opts.outHeight) / 160
                    val opts2 = BitmapFactory.Options().apply {
                        inSampleSize = if (sample > 1) sample else 1
                    }
                    context.contentResolver.openInputStream(uri)?.use { inp2 ->
                        BitmapFactory.decodeStream(inp2, null, opts2)
                    }
                }
            }.getOrNull()
        }
    }

    Box(
        modifier = modifier
            .size(44.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(BackroomsShadow.copy(alpha = 0.6f))
            .border(1.dp, BackroomsCream.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        val bmp = thumbnail
        if (bmp != null) {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = "最近照片",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Icon(
                imageVector = Icons.Filled.PhotoCamera,
                contentDescription = "相册",
                tint = BackroomsCream.copy(alpha = 0.5f),
                modifier = Modifier.size(20.dp)
            )
        }
    }
}
@Composable
private fun FocusIndicatorBox(position: Offset, modifier: Modifier = Modifier) {
    val scale = remember { Animatable(1.4f) }
    val alpha = remember { Animatable(1f) }
    LaunchedEffect(position) {
        scale.snapTo(1.4f)
        alpha.snapTo(1f)
        scale.animateTo(1f, tween(200))
        delay(1800L)
        alpha.animateTo(0f, tween(500))
    }
    val sizeDp = 80.dp
    Box(
        modifier = modifier
            .offset {
                IntOffset(
                    (position.x - sizeDp.toPx() / 2).roundToInt(),
                    (position.y - sizeDp.toPx() / 2).roundToInt()
                )
            }
            .size(sizeDp)
            .scale(scale.value)
            .alpha(alpha.value)
            .border(2.dp, BackroomsYellow, RoundedCornerShape(8.dp))
    )
}
