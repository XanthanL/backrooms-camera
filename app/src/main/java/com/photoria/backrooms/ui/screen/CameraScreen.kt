package com.photoria.backrooms.ui.screen

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import androidx.lifecycle.viewmodel.compose.viewModel
import com.photoria.backrooms.camera.CameraManager
import com.photoria.backrooms.camera.VideoRecorder
import com.photoria.backrooms.camera.WbPreset
import com.photoria.backrooms.gl.CameraGLSurfaceView
import com.photoria.backrooms.catalog.FilterCatalog
import com.photoria.backrooms.catalog.FilterCategory
import com.photoria.backrooms.ui.components.CameraSettingsPanel
import com.photoria.backrooms.ui.components.CaptureButton
import com.photoria.backrooms.ui.components.FilterCategoryBar
import com.photoria.backrooms.ui.components.FilterParamsPanel
import com.photoria.backrooms.ui.components.FilterSelector
import com.photoria.backrooms.ui.components.TopBar
import com.photoria.backrooms.ui.components.cameraGestures
import com.photoria.backrooms.ui.theme.BackroomsCream
import com.photoria.backrooms.ui.theme.BackroomsShadow
import com.photoria.backrooms.ui.theme.BackroomsYellow
import com.photoria.backrooms.ui.theme.BackroomsYellowOnDark
import com.photoria.backrooms.ui.viewmodel.AspectRatio
import com.photoria.backrooms.ui.viewmodel.CaptureMode
import com.photoria.backrooms.ui.viewmodel.CameraViewModel
import com.photoria.backrooms.util.FilterPrefs
import com.photoria.backrooms.util.ImageSaver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ── Phase 3：智能场景建议阈值 ──
/** 暗光阈值（0..1），低于此值建议开启夜景 */
private const val NIGHT_SUGGESTION_THRESHOLD = 0.25f
/** 建议 dismiss 后冷却时间（毫秒），避免频繁打扰 */
private const val NIGHT_SUGGESTION_COOLDOWN_MS = 60_000L

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
    val captureMode by viewModel.captureMode.collectAsState()
    val isRecording by viewModel.isRecording.collectAsState()
    val recordingDurationSec by viewModel.recordingDurationSec.collectAsState()
    val showParamsPanel by viewModel.showParamsPanel.collectAsState()
    val filterParams by viewModel.filterParams.collectAsState()
    val currentAspectRatio by viewModel.currentAspectRatio.collectAsState()
    val lastMediaUri by viewModel.lastMediaUri.collectAsState()
    val customizedIndices by viewModel.customizedFilterIndices.collectAsState()
    val showGrid by viewModel.showGrid.collectAsState()
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

    // 请求重新生成滤镜缩略图（跨帧分批，完成后在主线程更新 filterThumbnails）
    val requestFilterThumbnails: () -> Unit = {
        glSurfaceViewRef?.requestFilterThumbnails { bitmaps ->
            filterThumbnails = bitmaps.map { it.asImageBitmap() }
        }
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

    LaunchedEffect(Unit) {
        if (!hasCameraPermission || !hasAudioPermission) {
            permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.CAMERA,
                    Manifest.permission.RECORD_AUDIO
                )
            )
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
            if (uri != null) {
                viewModel.setLastMediaUri(uri)
                viewModel.showRecordingResult("视频已保存")
            } else {
                viewModel.showRecordingResult("保存失败")
            }
        }
        videoRecorder.onRecordingError = { e ->
            viewModel.setRecording(false)
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
                        onClick = {
                            when (captureMode) {
                                CaptureMode.PHOTO -> {
                                    // 拍照
                                    hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                                    val glView = glSurfaceViewRef
                                    if (glView != null) {
                                        viewModel.startCapture()
                                        viewModel.startCaptureProcessing()
                                        // 按多帧前处理开关分流到对应管线
                                        // HDR+：Camera2 Burst 包围曝光 → 块匹配对齐 → 曝光加权融合
                                        //      手动曝光模式下 requestBurstCapture 会自动回退到普通多帧
                                        // 夜景：连续多帧捕获 → 块匹配对齐 → 等权平均时域降噪
                                        //      适合手持静态场景，降噪效果 ≈ √N（4 帧 ≈ 2 倍）
                                        // 单帧：直接渲染当前带滤镜的预览帧（轻量、低延迟）
                                        val onBitmap: (Bitmap) -> Unit = { bitmap ->
                                            // 在 IO 线程保存照片，避免阻塞 GL 渲染线程
                                            scope.launch {
                                                val uri = withContext(Dispatchers.IO) {
                                                    ImageSaver.saveToGallery(context, bitmap)
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
                                        when {
                                            hdrEnabled -> glView.captureHdrPhoto(callback = onBitmap)
                                            nightEnabled -> glView.captureAlignedPhoto(callback = onBitmap)
                                            else -> glView.capturePhoto(callback = onBitmap)
                                        }
                                    } else {
                                        viewModel.showCaptureResult("相机未就绪")
                                    }
                                }
                                CaptureMode.VIDEO -> {
                                    // 录像（带 GL 滤镜）
                                    val glView = glSurfaceViewRef
                                    if (glView != null) {
                                        if (isRecording) {
                                            // 停止录制
                                            hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                                            videoRecorder.stopRecording(glView)
                                        } else {
                                            // 开始录制
                                            hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                                            val started = videoRecorder.startRecording(glView)
                                            if (started) {
                                                viewModel.setRecording(true)
                                            } else {
                                                viewModel.showRecordingResult("录制启动失败")
                                            }
                                        }
                                    } else {
                                        viewModel.showRecordingResult("相机未就绪")
                                    }
                                }
                            }
                        }
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
