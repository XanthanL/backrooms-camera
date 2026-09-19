package com.photoria.backrooms.ui.viewmodel

import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.photoria.backrooms.catalog.FilterCatalog
import com.photoria.backrooms.catalog.FilterParamDef
import com.photoria.backrooms.catalog.FilterPreset
import com.photoria.backrooms.gl.AdjustmentEngine
import com.photoria.backrooms.gl.CurveEngine
import com.photoria.backrooms.gl.ZebraMode
import com.photoria.backrooms.util.FilterPrefs
import com.photoria.backrooms.util.KeyAction
import com.photoria.backrooms.util.KeyRouter
import com.photoria.backrooms.util.VolumeKeyShutter
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlin.math.roundToInt

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
 * 倒计时自拍档位。
 *
 * OFF 必须是 ordinal 0：[FilterPrefs] 按序号持久化，越界回退 OFF。
 */
enum class CountdownSec(val display: String, val seconds: Int) {
    OFF("关", 0),
    S3("3秒", 3),
    S5("5秒", 5),
    S10("10秒", 10)
}

/**
 * 连拍帧数档位。OFF 即单张（frames = 1），走原来的单帧管线。
 *
 * 上限 9 是因为产物是九宫格拼图；再多格子就小到看不清了。
 * 与 [CountdownSec] 同样：OFF 必须是 ordinal 0（持久化存序号）。
 */
enum class BurstCount(val display: String, val frames: Int) {
    OFF("单张", 1),
    THREE("3张", 3),
    FIVE("5张", 5),
    NINE("9张", 9)
}

/**
 * 相机界面 ViewModel。
 * 管理滤镜选择、拍照状态、录像状态、模式切换等 UI 状态。
 */
class CameraViewModel : ViewModel() {

    private companion object {
        const val TAG = "CameraViewModel"

        /**
         * 单帧/多帧拍照的看门狗期限（毫秒）。
         *
         * 正常路径下每个请求都会自行复位（届时看门狗协程随 key 变化被取消），
         * 它只兜"回调彻底丢失"（如 GL 线程随 Surface 销毁）这一种残留情况。
         */
        const val CAPTURE_WATCHDOG_DEFAULT_MS = 6_000L
    }

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

    /**
     * 本次拍照允许的期限（毫秒），驱动 UI 侧的超时看门狗。
     *
     * 必须是每次请求自带的而不是常量：连拍 9 帧要 2 秒以上，
     * 沿用固定 6 秒会在批次中途把快门放开并提前显示"拍照超时"。
     */
    private val _captureDeadlineMs = MutableStateFlow(CAPTURE_WATCHDOG_DEFAULT_MS)
    val captureDeadlineMs: StateFlow<Long> = _captureDeadlineMs.asStateFlow()

    /**
     * 拍照结果（保存路径或错误信息），一次性事件。
     *
     * extraBufferCapacity=1 + DROP_OLDEST：收集者正在处理上一条提示时
     * tryEmit 也不会静默丢事件（无 buffer 的 SharedFlow 会直接丢弃）。
     */
    private val _captureResult = MutableSharedFlow<String?>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
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

    /** 录像结果消息，一次性事件（缓冲策略同 captureResult） */
    private val _recordingResult = MutableSharedFlow<String?>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
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

    /** 滤镜强度（0=原图，1=完全效果），预览/录像/拍照共用 */
    private val _filterStrength = MutableStateFlow(FilterPrefs.getFilterStrength())
    val filterStrength: StateFlow<Float> = _filterStrength.asStateFlow()

    // ── 实时调色（W1：影调/色温/HSL 色域，进照片与录像）─────────────
    /** 调色参数表（UI 值域 -100..100，仅存非零项；键见 AdjustmentEngine） */
    private val _adjustments = MutableStateFlow(FilterPrefs.getAdjustments())
    val adjustments: StateFlow<Map<String, Float>> = _adjustments.asStateFlow()

    /** 调色曲线控制点（通道键 → flat [x,y,...]，仅存偏离对角线的通道；X 批） */
    private val _curves = MutableStateFlow(FilterPrefs.getCurves())
    val curves: StateFlow<Map<String, List<Float>>> = _curves.asStateFlow()

    // ── 取景辅助（只影响取景器，不进照片/录像）────────────────────
    /** 是否显示实时直方图 */
    private val _showHistogram = MutableStateFlow(FilterPrefs.getShowHistogram())
    val showHistogram: StateFlow<Boolean> = _showHistogram.asStateFlow()

    /** 斑马纹模式（关 / 仅过曝 / 过曝+欠曝） */
    private val _zebraMode = MutableStateFlow(FilterPrefs.getZebraMode())
    val zebraMode: StateFlow<ZebraMode> = _zebraMode.asStateFlow()

    /** 峰值对焦开关 */
    private val _focusPeaking = MutableStateFlow(FilterPrefs.getFocusPeaking())
    val focusPeaking: StateFlow<Boolean> = _focusPeaking.asStateFlow()

    /** 峰值对焦灵敏度（越大越灵敏，下发 GL 时换算为边缘阈值 1-灵敏度） */
    private val _peakingSensitivity = MutableStateFlow(FilterPrefs.getPeakingSensitivity())
    val peakingSensitivity: StateFlow<Float> = _peakingSensitivity.asStateFlow()

    /** 是否显示气泡水平仪 */
    private val _showBubbleLevel = MutableStateFlow(FilterPrefs.getShowBubbleLevel())
    val showBubbleLevel: StateFlow<Boolean> = _showBubbleLevel.asStateFlow()

    // ── 快门小工具 ────────────────────────────────────────────────
    /** 音量键快门档位（默认关：多数用户要的是音量，不是快门） */
    private val _volumeKeyShutter = MutableStateFlow(FilterPrefs.getVolumeKeyShutter())
    val volumeKeyShutter: StateFlow<VolumeKeyShutter> = _volumeKeyShutter.asStateFlow()

    /** 倒计时自拍档位（默认关：只有自拍场景才需要这几秒） */
    private val _countdownSec = MutableStateFlow(FilterPrefs.getCountdownSec())
    val countdownSec: StateFlow<CountdownSec> = _countdownSec.asStateFlow()

    /** 声控快门开关（默认关：一直占着麦克风不该是默认行为） */
    private val _voiceEnabled = MutableStateFlow(FilterPrefs.isVoiceShutterOn())
    val voiceEnabled: StateFlow<Boolean> = _voiceEnabled.asStateFlow()

    /** 相对底噪的触发倍数（越大越迟钝） */
    private val _voicePickup = MutableStateFlow(FilterPrefs.getVoicePickup())
    val voicePickup: StateFlow<Float> = _voicePickup.asStateFlow()

    /** 绝对触发下限（归一化 RMS），防止极安静环境里呼吸就出片 */
    private val _voiceMinLevel = MutableStateFlow(FilterPrefs.getVoiceMinLevel())
    val voiceMinLevel: StateFlow<Float> = _voiceMinLevel.asStateFlow()

    /** 连拍帧数档位（默认单张：连拍的产物是一张拼图，不该是默认行为） */
    private val _burstCount = MutableStateFlow(FilterPrefs.getBurstCount())
    val burstCount: StateFlow<BurstCount> = _burstCount.asStateFlow()

    /** 连拍是否额外导出一张 GIF（相册里只多这一个文件） */
    private val _burstGif = MutableStateFlow(FilterPrefs.isBurstGifOn())
    val burstGif: StateFlow<Boolean> = _burstGif.asStateFlow()

    /**
     * 连拍批次是否在跑。
     *
     * 硬闩而不是只靠按钮 enabled：音量键与声控根本不看 enabled，
     * 而 GL 的 captureCallback 只有一个槽，批次中间再进一次请求就是丢帧 + 回调错配。
     */
    @Volatile
    var isBurstRunning: Boolean = false

    /**
     * 由 CameraScreen 在画面存在期间注册的「按快门」回调。
     *
     * 用 @Volatile 而非 StateFlow：这里是在 KeyEvent 分发路径上读，不在重组作用域里。
     */
    @Volatile
    var shutterRequestHandler: (() -> Unit)? = null

    /**
     * 音量键是否允许出片：仅当相机页在前台且相机就绪时为 true。
     * false 时按键照吞（避免切到别的页面后音量键仍被本应用吃掉）。
     */
    @Volatile
    var shutterArmed: Boolean = false

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
     * 冷启动回读持久化状态：上次选中的滤镜 + 每个滤镜的自定义参数。
     *
     * 必须声明在 [savedParamsByFilter] 与 [filterNames] 之后：Kotlin 按声明顺序
     * 交替执行属性初始化与 init 块，提前执行会写入尚未创建的 map。
     */
    init {
        val lastIndex = FilterPrefs.getLastFilterIndex().coerceIn(0, filterNames.lastIndex)
        _currentFilterIndex.value = lastIndex
        _currentFilterName.value = filterNames[lastIndex]
        filterNames.forEach { name ->
            val saved = FilterPrefs.getFilterParams(name)
            if (saved.isNotEmpty()) savedParamsByFilter[name] = saved
        }
        applyFilterParamsForCurrent()
    }

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

    /** 把当前滤镜的生效参数（默认值 + 已存自定义）应用到 _filterParams */
    private fun applyFilterParamsForCurrent() {
        val name = _currentFilterName.value
        val merged = defaultsFor(name).toMutableMap()
        savedParamsByFilter[name]?.forEach { (uniform, value) -> merged[uniform] = value }
        _filterParams.value = merged
        savedParamsByFilter[name] = merged
    }

    /** 指定滤镜的 FilterParamDef 默认值表 */
    private fun defaultsFor(name: String): Map<String, Float> =
        FilterCatalog.paramDefs(name).associate { it.uniformName to it.defaultValue }

    /**
     * 指定滤镜索引的生效参数（默认值 + 已存自定义）。
     * 供滤镜缩略图渲染使用，让缩略图与预览所见效果一致。
     */
    fun effectiveParams(index: Int): Map<String, Float> {
        val name = filterNames.getOrNull(index) ?: return emptyMap()
        return defaultsFor(name) + (savedParamsByFilter[name] ?: emptyMap())
    }

    /** 切换参数面板显示/隐藏 */
    fun toggleParamsPanel() {
        _showParamsPanel.value = !_showParamsPanel.value
    }

    /** 显式设置参数面板开关（与调色面板互斥时用） */
    fun setParamsPanelOpen(open: Boolean) {
        _showParamsPanel.value = open
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

    /** 设置滤镜强度（预览/录像/拍照一致，落盘） */
    fun setFilterStrength(value: Float) {
        val coerced = value.coerceIn(0f, 1f)
        _filterStrength.value = coerced
        FilterPrefs.putFilterStrength(coerced)
    }

    /** 强度复位为完全效果 */
    fun resetFilterStrength() {
        setFilterStrength(1f)
    }

    /**
     * 设置单个调色参数（UI 值域 -100..100，取整避免滑杆浮点噪声）。
     * 0 即从表中移除 —— 「只存非零」让持久化体积与 identity 判断都保持简单。
     */
    fun setAdjustment(key: String, value: Float) {
        val v = value.coerceIn(-100f, 100f).roundToInt().toFloat()
        val updated = _adjustments.value.toMutableMap()
        if (v == 0f) updated.remove(key) else updated[key] = v
        _adjustments.value = updated
        FilterPrefs.putAdjustments(updated)
    }

    /** 应用调色预设：整体替换而非叠加 —— 预设是"出片起点"，不是第二层滤镜 */
    fun applyAdjustmentPreset(name: String) {
        val preset = AdjustmentEngine.PRESETS[name] ?: return
        val cleaned = preset.filterValues { it != 0f }
        _adjustments.value = cleaned
        FilterPrefs.putAdjustments(cleaned)
    }

    /** 一键还原全部调色（回到未改动的原图） */
    fun resetAdjustments() {
        _adjustments.value = emptyMap()
        FilterPrefs.putAdjustments(emptyMap())
    }

    /**
     * 更新某通道曲线控制点（flat [x,y,...]，UI 已保证 x 升序 + y 单调）。
     * 回到对角线即从持久化表中移除 —— 「只存非默认」与影调同一套哲学。
     */
    fun setCurvePoints(channel: String, points: List<Float>) {
        if (channel !in CurveEngine.CHANNELS || points.size < 4) return
        val updated = _curves.value.toMutableMap()
        if (CurveEngine.isDefault(points)) updated.remove(channel) else updated[channel] = points
        _curves.value = updated
        FilterPrefs.putCurves(updated)
    }

    /** 还原全部曲线（影调表不动；面板的「全部还原」两个都调） */
    fun resetCurves() {
        _curves.value = emptyMap()
        FilterPrefs.putCurves(emptyMap())
    }

    /** 直方图显示开关 */
    fun setShowHistogram(show: Boolean) {
        _showHistogram.value = show
        FilterPrefs.putShowHistogram(show)
    }

    /** 斑马纹模式 */
    fun setZebraMode(mode: ZebraMode) {
        _zebraMode.value = mode
        FilterPrefs.putZebraMode(mode)
    }

    /** 峰值对焦开关 */
    fun setFocusPeaking(on: Boolean) {
        _focusPeaking.value = on
        FilterPrefs.putFocusPeaking(on)
    }

    /** 峰值对焦灵敏度 */
    fun setPeakingSensitivity(value: Float) {
        val coerced = value.coerceIn(0.2f, 0.98f)
        _peakingSensitivity.value = coerced
        FilterPrefs.putPeakingSensitivity(coerced)
    }

    /** 气泡水平仪开关 */
    fun setShowBubbleLevel(show: Boolean) {
        _showBubbleLevel.value = show
        FilterPrefs.putShowBubbleLevel(show)
    }

    /** 音量键快门档位 */
    fun setVolumeKeyShutter(mode: VolumeKeyShutter) {
        _volumeKeyShutter.value = mode
        FilterPrefs.putVolumeKeyShutter(mode)
    }

    /** 倒计时自拍档位 */
    fun setCountdownSec(mode: CountdownSec) {
        _countdownSec.value = mode
        FilterPrefs.putCountdownSec(mode)
    }

    /** 声控快门开关 */
    fun setVoiceEnabled(on: Boolean) {
        _voiceEnabled.value = on
        FilterPrefs.putVoiceShutterOn(on)
    }

    /** 声控灵敏度（相对底噪倍数，2..20） */
    fun setVoicePickup(value: Float) {
        val coerced = value.coerceIn(2f, 20f)
        _voicePickup.value = coerced
        FilterPrefs.putVoicePickup(coerced)
    }

    /** 声控绝对下限（归一化 RMS，0.01..0.3） */
    fun setVoiceMinLevel(value: Float) {
        val coerced = value.coerceIn(0.01f, 0.3f)
        _voiceMinLevel.value = coerced
        FilterPrefs.putVoiceMinLevel(coerced)
    }

    /** 连拍帧数档位 */
    fun setBurstCount(mode: BurstCount) {
        _burstCount.value = mode
        FilterPrefs.putBurstCount(mode)
    }

    /** 连拍是否额外导出 GIF */
    fun setBurstGif(on: Boolean) {
        _burstGif.value = on
        FilterPrefs.putBurstGif(on)
    }

    /**
     * Activity 的音量键入口：查 [KeyRouter] 真值表，需要时触发快门。
     *
     * 决策全部委托给纯函数（真值表已在 JVM 上跑过冒烟），这里只做「执行 + 上报」。
     * 未武装时仍然吞键（CONSUME_ONLY），避免用户切到别处后音量键被无声吃掉。
     *
     * @return 本次事件的处理方式，供 Activity 决定是否 return true
     */
    fun onVolumeKey(keyCode: Int, repeatCount: Int, isDown: Boolean): KeyAction {
        val mode = _volumeKeyShutter.value
        val action = if (isDown) {
            KeyRouter.decideDown(mode, keyCode, repeatCount, shutterArmed)
        } else {
            KeyRouter.decideUp(mode, keyCode)
        }
        Log.d(TAG, "ShutterKeys: keyCode=$keyCode down=$isDown repeat=$repeatCount action=$action mode=$mode")
        if (action == KeyAction.SHUTTER) shutterRequestHandler?.invoke()
        return action
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
     * 开始拍照处理（多帧捕获/对齐/融合/保存期间保持 true，用于进度反馈）。
     * 在实际拍照回调完成前阻止重复触发。
     *
     * @param deadlineMs 看门狗期限；连拍必须按帧数放宽，否则批次中途会被判超时
     */
    fun startCaptureProcessing(deadlineMs: Long = CAPTURE_WATCHDOG_DEFAULT_MS) {
        _captureDeadlineMs.value = deadlineMs
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
