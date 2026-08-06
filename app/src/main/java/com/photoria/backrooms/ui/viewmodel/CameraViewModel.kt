package com.photoria.backrooms.ui.viewmodel

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.photoria.backrooms.catalog.FilterCatalog
import com.photoria.backrooms.catalog.FilterParamDef
import com.photoria.backrooms.catalog.FilterPreset
import com.photoria.backrooms.util.FilterPrefs
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * 拍照/录像模式。
 */
enum class CaptureMode {
    PHOTO, VIDEO
}

/**
 * 画幅比例。value 为宽/高（W/H）。
 *
 * 仅保留 4:3（竖屏下为 3:4），移除其它比例选项。
 * 旧偏好中存储的 FULL/R9_16/R1_1 在 [FilterPrefs.getLastAspectRatio] 中
 * 会因 enum 缺失而回退到默认值 R3_4。
 */
enum class AspectRatio(val display: String, val value: Float) {
    R3_4("4:3", 3f / 4f)
}

/**
 * 画幅填充模式。
 * - FILL：裁剪填充（无黑边，可能裁切画面）
 * - FIT ：完整显示（无拉伸，可能有黑边）
 */
enum class FitMode(val display: String) {
    FILL("填充"),
    FIT("适配")
}

/**
 * 相机界面 ViewModel。
 * 管理滤镜选择、拍照状态、录像状态、模式切换等 UI 状态。
 */
class CameraViewModel : ViewModel() {

    /** 当前选中的滤镜索引 */
    private val _currentFilterIndex = MutableStateFlow(0)
    val currentFilterIndex: StateFlow<Int> = _currentFilterIndex.asStateFlow()

    /** 当前选中的滤镜名称 */
    private val _currentFilterName = MutableStateFlow("原画")
    val currentFilterName: StateFlow<String> = _currentFilterName.asStateFlow()

    /** 是否正在拍照（闪白动画期间为 true） */
    private val _isCapturing = MutableStateFlow(false)
    val isCapturing: StateFlow<Boolean> = _isCapturing.asStateFlow()

    /**
     * 是否正在处理拍照（多帧捕获/对齐/融合/回读/保存）。
     *
     * 与 [isCapturing] 区别：后者仅驱动闪白动画（~480ms 自动结束），
     * 而 captureProcessing 在实际拍照回调完成前保持 true，
     * 用于向用户显示进度反馈并阻止重复触发。
     */
    private val _captureProcessing = MutableStateFlow(false)
    val captureProcessing: StateFlow<Boolean> = _captureProcessing.asStateFlow()

    /** 拍照结果（保存路径或错误信息），一次性事件 */
    private val _captureResult = MutableSharedFlow<String?>()
    val captureResult: SharedFlow<String?> = _captureResult.asSharedFlow()

    /** 当前模式（拍照/录像） */
    private val _captureMode = MutableStateFlow(CaptureMode.PHOTO)
    val captureMode: StateFlow<CaptureMode> = _captureMode.asStateFlow()

    /** 是否正在录制视频 */
    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    /** 最近一次拍摄的照片/视频 Uri（用于左下角缩略图） */
    private val _lastMediaUri = MutableStateFlow<Uri?>(null)
    val lastMediaUri: StateFlow<Uri?> = _lastMediaUri.asStateFlow()

    /** 录制时长（秒） */
    private val _recordingDurationSec = MutableStateFlow(0)
    val recordingDurationSec: StateFlow<Int> = _recordingDurationSec.asStateFlow()

    /** 录像结果消息，一次性事件 */
    private val _recordingResult = MutableSharedFlow<String?>()
    val recordingResult: SharedFlow<String?> = _recordingResult.asSharedFlow()

    /** 是否显示参数调节面板 */
    private val _showParamsPanel = MutableStateFlow(false)
    val showParamsPanel: StateFlow<Boolean> = _showParamsPanel.asStateFlow()

    /** 当前画幅比例（固定 4:3） */
    private val _currentAspectRatio = MutableStateFlow(AspectRatio.R3_4)
    val currentAspectRatio: StateFlow<AspectRatio> = _currentAspectRatio.asStateFlow()

    /** 当前画幅填充模式（填充/适配） */
    private val _currentFitMode = MutableStateFlow(FitMode.FILL)
    val currentFitMode: StateFlow<FitMode> = _currentFitMode.asStateFlow()

    /** 是否显示三分线网格 */
    private val _showGrid = MutableStateFlow(FilterPrefs.getShowGrid())
    val showGrid: StateFlow<Boolean> = _showGrid.asStateFlow()

    /** 闪光灯是否开启 */
    private val _torchEnabled = MutableStateFlow(false)
    val torchEnabled: StateFlow<Boolean> = _torchEnabled.asStateFlow()

    /** 当前滤镜的参数值（uniformName → value） */
    private val _filterParams = MutableStateFlow<Map<String, Float>>(emptyMap())
    val filterParams: StateFlow<Map<String, Float>> = _filterParams.asStateFlow()

    /**
     * 已自定义参数的滤镜索引集合（用于选择器指示点）。
     * 派生自 _filterParams + _currentFilterName：仅当参数真正变化时才重算，
     * 避免每次重组都遍历全部滤镜（含录制时每秒重组）。
     *
     * 用 WhileSubscribed(5000) 而非 Eagerly：确保收集在 UI 订阅后才启动，
     * 此时 VM 的 init {} 已执行完毕、savedParamsByFilter 已填充。
     * （Eagerly + Dispatchers.Main.immediate 会在构造期同步执行 combine，
     *  而此时 savedParamsByFilter 尚未初始化，导致 NPE 闪退。）
     */
    val customizedFilterIndices: StateFlow<Set<Int>> = _filterParams
        .combine(_currentFilterName) { _, _ -> computeCustomizedIndices() }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptySet()
        )

    /** 滤镜列表（索引需与 FilterCatalog.filters 一一对应） */
    val filterNames: List<String> = FilterCatalog.names

    /** 获取当前滤镜的参数定义列表 */
    fun getCurrentFilterParamDefs(): List<FilterParamDef> {
        return FilterCatalog.paramDefs(_currentFilterName.value)
    }

    /** 内存缓存：每个滤镜的参数（uniformName → value），从 prefs 加载 */
    private val savedParamsByFilter: MutableMap<String, Map<String, Float>> = mutableMapOf()

    /**
     * 选择滤镜。保留各滤镜的独立参数（切换回来时恢复）。
     */
    fun selectFilter(index: Int, name: String) {
        _currentFilterIndex.value = index
        _currentFilterName.value = name
        FilterPrefs.putLastFilterIndex(index)
        // 切换到该滤镜的已存参数（无则默认）
        applyFilterParamsForCurrent()
    }

    /** 设置滤镜参数（同时持久化到当前滤镜名下） */
    fun setFilterParam(name: String, value: Float) {
        val updated = _filterParams.value.toMutableMap().also { it[name] = value }
        _filterParams.value = updated
        savedParamsByFilter[_currentFilterName.value] = updated
        FilterPrefs.putFilterParams(_currentFilterName.value, updated)
    }

    /** 重置当前滤镜参数为默认值（同时清除已存） */
    fun resetFilterParams() {
        val defs = getCurrentFilterParamDefs()
        val defaults = defs.associate { it.uniformName to it.defaultValue }
        _filterParams.value = defaults
        savedParamsByFilter[_currentFilterName.value] = defaults
        FilterPrefs.putFilterParams(_currentFilterName.value, defaults)
    }

    /** 获取当前滤镜的预设列表 */
    fun getCurrentFilterPresets(): List<FilterPreset> {
        return FilterCatalog.presets(_currentFilterName.value)
    }

    /**
     * 应用预设：将预设参数设为当前滤镜参数（同时持久化）。
     * @return 应用的参数表（供调用方推给 GL）
     */
    fun applyPreset(preset: FilterPreset) {
        // 合并预设参数与默认参数（预设未覆盖的用默认值）
        val defs = getCurrentFilterParamDefs()
        val merged = defs.associate { it.uniformName to it.defaultValue }.toMutableMap()
        preset.params.forEach { (k, v) -> merged[k] = v }
        _filterParams.value = merged
        savedParamsByFilter[_currentFilterName.value] = merged
        FilterPrefs.putFilterParams(_currentFilterName.value, merged)
    }

    /** 当前滤镜是否有非默认的自定义参数（用于设置按钮指示） */
    fun isCurrentFilterCustomized(): Boolean {
        val defs = getCurrentFilterParamDefs()
        val current = _filterParams.value
        return defs.any { (current[it.uniformName] ?: it.defaultValue) != it.defaultValue }
    }

    /** 计算已自定义参数的滤镜索引集合（供 customizedFilterIndices 派生流使用） */
    private fun computeCustomizedIndices(): Set<Int> {
        val out = mutableSetOf<Int>()
        filterNames.forEachIndexed { idx, name ->
            val defs = FilterCatalog.paramDefs(name)
            val saved = savedParamsByFilter[name] ?: return@forEachIndexed
            if (defs.any { (saved[it.uniformName] ?: it.defaultValue) != it.defaultValue }) {
                out.add(idx)
            }
        }
        return out
    }

    /** 把当前滤镜的已存参数（或默认）应用到 _filterParams */
    private fun applyFilterParamsForCurrent() {
        val name = _currentFilterName.value
        val saved = savedParamsByFilter[name]
        if (saved != null && saved.isNotEmpty()) {
            _filterParams.value = saved
        } else {
            // 无已存参数：使用 FilterParamDef 中的默认值，并持久化
            // 确保新默认值（如后室 2.0）同时应用到 UI 和 GL
            val defs = FilterCatalog.paramDefs(name)
            val defaults = defs.associate { it.uniformName to it.defaultValue }
            _filterParams.value = defaults
            savedParamsByFilter[name] = defaults
        }
    }

    /** 切换参数面板显示/隐藏 */
    fun toggleParamsPanel() {
        _showParamsPanel.value = !_showParamsPanel.value
    }

    /** 设置画幅比例 */
    fun setAspectRatio(ratio: AspectRatio) {
        _currentAspectRatio.value = ratio
        FilterPrefs.putLastAspectRatio(ratio)
    }

    /** 设置画幅填充模式 */
    fun setFitMode(mode: FitMode) {
        _currentFitMode.value = mode
        FilterPrefs.putLastFitMode(mode)
    }

    /** 切换三分线网格显示 */
    fun toggleGrid() {
        _showGrid.value = !_showGrid.value
        FilterPrefs.putShowGrid(_showGrid.value)
    }

    /** 设置闪光灯开关状态（仅 UI 状态，实际调用 CameraManager.enableTorch） */
    fun setTorch(on: Boolean) {
        _torchEnabled.value = on
    }

    /** 切换闪光灯 */
    fun toggleTorch() {
        _torchEnabled.value = !_torchEnabled.value
    }

    /**
     * 切换拍照/录像模式。
     */
    fun toggleCaptureMode() {
        _captureMode.value = if (_captureMode.value == CaptureMode.PHOTO) {
            CaptureMode.VIDEO
        } else {
            CaptureMode.PHOTO
        }
    }

    /**
     * 设置拍照/录像模式。
     */
    fun setCaptureMode(mode: CaptureMode) {
        _captureMode.value = mode
    }

    /**
     * 开始拍照（触发闪白动画）。
     */
    fun startCapture() {
        _isCapturing.value = true
    }

    /**
     * 拍照完成（结束闪白动画）。
     */
    fun captureComplete() {
        _isCapturing.value = false
    }

    /**
     * 开始拍照处理（多帧捕获/对齐/融合期间保持 true，用于进度反馈）。
     * 在实际拍照回调完成前阻止重复触发。
     */
    fun startCaptureProcessing() {
        _captureProcessing.value = true
    }

    /**
     * 拍照处理完成（回调已返回， bitmap 已保存或失败）。
     */
    fun captureProcessingComplete() {
        _captureProcessing.value = false
    }

    /**
     * 显示拍照结果（SnackBar 消息）。
     */
    fun showCaptureResult(message: String) {
        _captureResult.tryEmit(message)
    }

    /**
     * 设置录制状态。
     */
    fun setRecording(recording: Boolean) {
        _isRecording.value = recording
        if (!recording) {
            _recordingDurationSec.value = 0
        }
    }

    /**
     * 更新录制时长（秒）。
     */
    fun updateRecordingDuration(seconds: Int) {
        _recordingDurationSec.value = seconds
    }

    /**
     * 显示录像结果（SnackBar 消息）。
     */
    fun showRecordingResult(message: String) {
        _recordingResult.tryEmit(message)
    }

    /** 更新最近一次拍摄的媒体 Uri（用于缩略图显示） */
    fun setLastMediaUri(uri: Uri?) {
        _lastMediaUri.value = uri
    }
}
