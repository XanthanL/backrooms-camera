package com.photoria.backrooms.util

import android.content.Context
import android.util.Log
import com.photoria.backrooms.PhotoriaApp
import com.photoria.backrooms.camera.WbPreset
import com.photoria.backrooms.gl.ZebraMode
import com.photoria.backrooms.ui.viewmodel.AspectRatio
import com.photoria.backrooms.ui.viewmodel.BurstCount
import com.photoria.backrooms.ui.viewmodel.CountdownSec
import com.photoria.backrooms.ui.viewmodel.FitMode
import org.json.JSONObject

/**
 * 滤镜与画幅偏好的持久化工具。
 *
 * 存储内容：
 *   - 上次选中的滤镜索引
 *   - 上次画幅比例
 *   - 上次画幅填充模式
 *   - 每个滤镜的参数表（uniformName → value），以 JSON 字符串保存
 *   - 滤镜强度
 *   - 取景辅助开关（直方图 / 斑马纹 / 峰值对焦 + 灵敏度 / 水平仪）
 *   - 快门小工具（音量键快门 / 倒计时 / 声控 + 两个门限 / 连拍帧数 + 动图）
 *   - 专业采集状态（EV 档位 / 白平衡预设与强度 / 手动曝光 ISO+快门 / HDR / 夜景）
 *   - 实时调色参数（影调 / 色温 / HSL 色域，JSON）
 *
 * 使用 Application Context，避免内存泄漏。
 */
object FilterPrefs {

    private const val TAG = "FilterPrefs"
    private const val PREFS_NAME = "photoria_prefs"
    private const val KEY_LAST_FILTER_INDEX = "last_filter_index"
    private const val KEY_LAST_ASPECT = "last_aspect"
    private const val KEY_LAST_FIT_MODE = "last_fit_mode"
    private const val KEY_FILTER_PARAMS_PREFIX = "filter_params_"
    private const val KEY_SHOW_GRID = "show_grid"
    private const val KEY_FILTER_STRENGTH = "filter_strength"
    private const val KEY_SHOW_HISTOGRAM = "show_histogram"
    private const val KEY_ZEBRA_MODE = "zebra_mode"
    private const val KEY_FOCUS_PEAKING = "focus_peaking"
    private const val KEY_PEAKING_SENSITIVITY = "peaking_sensitivity"
    private const val KEY_SHOW_BUBBLE_LEVEL = "show_bubble_level"
    private const val KEY_ONBOARDING_SHOWN = "onboarding_shown"
    private const val KEY_VOLUME_KEY_SHUTTER = "volume_key_shutter"
    private const val KEY_COUNTDOWN_SECONDS = "countdown_seconds"
    private const val KEY_VOICE_SHUTTER = "voice_shutter"
    private const val KEY_VOICE_PICKUP = "voice_pickup"
    private const val KEY_VOICE_MIN_LEVEL = "voice_min_level"
    private const val KEY_BURST_COUNT = "burst_count"
    private const val KEY_BURST_GIF = "burst_gif"
    private const val KEY_PRO_EV_INDEX = "pro_ev_index"
    private const val KEY_PRO_WB_PRESET = "pro_wb_preset"
    private const val KEY_PRO_WB_INTENSITY = "pro_wb_intensity"
    private const val KEY_PRO_MANUAL_EXPOSURE = "pro_manual_exposure"
    private const val KEY_PRO_MANUAL_ISO = "pro_manual_iso"
    private const val KEY_PRO_MANUAL_SHUTTER_NS = "pro_manual_shutter_ns"
    private const val KEY_HDR_MODE_ON = "hdr_mode_on"
    private const val KEY_NIGHT_MODE_ON = "night_mode_on"
    private const val KEY_FILTER_BAR_COLLAPSED = "filter_bar_collapsed"
    private const val KEY_ADJUSTMENTS = "adjustments"

    private val prefs by lazy {
        PhotoriaApp.appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    // ── 滤镜索引 ───────────────────────────────────────────────────

    fun getLastFilterIndex(): Int {
        val idx = prefs.getInt(KEY_LAST_FILTER_INDEX, 0)
        // 23 种滤镜（0..22）。旧版本曾错误地限制在 0..7，导致索引 8+ 重启后被重置
        return if (idx in 0..22) idx else 0
    }

    fun putLastFilterIndex(index: Int) {
        prefs.edit().putInt(KEY_LAST_FILTER_INDEX, index).apply()
    }

    // ── 画幅比例 ───────────────────────────────────────────────────

    fun getLastAspectRatio(): AspectRatio {
        // 画幅固定为 4:3，旧偏好中存储的已删除枚举值会回退到 R3_4
        val name = prefs.getString(KEY_LAST_ASPECT, null) ?: return AspectRatio.R3_4
        return runCatching { AspectRatio.valueOf(name) }.getOrDefault(AspectRatio.R3_4)
    }

    fun putLastAspectRatio(ratio: AspectRatio) {
        prefs.edit().putString(KEY_LAST_ASPECT, ratio.name).apply()
    }

    // ── 画幅填充模式 ───────────────────────────────────────────────

    fun getLastFitMode(): FitMode {
        val name = prefs.getString(KEY_LAST_FIT_MODE, null) ?: return FitMode.FILL
        return runCatching { FitMode.valueOf(name) }.getOrDefault(FitMode.FILL)
    }

    fun putLastFitMode(mode: FitMode) {
        prefs.edit().putString(KEY_LAST_FIT_MODE, mode.name).apply()
    }

    // ── 网格线 ──────────────────────────────────────────────────────

    fun getShowGrid(): Boolean = prefs.getBoolean(KEY_SHOW_GRID, false)

    fun putShowGrid(show: Boolean) {
        prefs.edit().putBoolean(KEY_SHOW_GRID, show).apply()
    }

    // ── 滤镜强度 ────────────────────────────────────────────────────

    /** 滤镜强度（0=原图，1=完全效果），默认 1 */
    fun getFilterStrength(): Float =
        prefs.getFloat(KEY_FILTER_STRENGTH, 1f).coerceIn(0f, 1f)

    fun putFilterStrength(strength: Float) {
        prefs.edit().putFloat(KEY_FILTER_STRENGTH, strength.coerceIn(0f, 1f)).apply()
    }

    // ── 实时调色（W1/W3：影调/色温/HSL 色域，进照片与录像）──────────

    /** 调色参数表（UI 值域），仅记录非零项；无记录返回空表 */
    fun getAdjustments(): Map<String, Float> {
        val raw = prefs.getString(KEY_ADJUSTMENTS, null) ?: return emptyMap()
        return try {
            val json = JSONObject(raw)
            val out = mutableMapOf<String, Float>()
            val keys = json.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val v = json.getDouble(k).toFloat()
                if (k in com.photoria.backrooms.gl.AdjustmentEngine.ALL_KEYS) out[k] = v
            }
            out
        } catch (e: Exception) {
            Log.w(TAG, "读取调色参数失败", e)
            emptyMap()
        }
    }

    fun putAdjustments(values: Map<String, Float>) {
        try {
            val json = JSONObject()
            for ((k, v) in values) {
                if (v != 0f) json.put(k, v.toDouble())
            }
            prefs.edit().putString(KEY_ADJUSTMENTS, json.toString()).apply()
        } catch (e: Exception) {
            Log.w(TAG, "保存调色参数失败", e)
        }
    }

    // ── 取景辅助（只影响取景器，不进照片/录像）──────────────────────

    fun getShowHistogram(): Boolean = prefs.getBoolean(KEY_SHOW_HISTOGRAM, false)

    fun putShowHistogram(show: Boolean) {
        prefs.edit().putBoolean(KEY_SHOW_HISTOGRAM, show).apply()
    }

    /** 斑马纹模式，默认关闭；存的是枚举序号，越界回退 OFF */
    fun getZebraMode(): ZebraMode =
        ZebraMode.entries.getOrNull(prefs.getInt(KEY_ZEBRA_MODE, 0)) ?: ZebraMode.OFF

    fun putZebraMode(mode: ZebraMode) {
        prefs.edit().putInt(KEY_ZEBRA_MODE, mode.ordinal).apply()
    }

    fun getFocusPeaking(): Boolean = prefs.getBoolean(KEY_FOCUS_PEAKING, false)

    fun putFocusPeaking(on: Boolean) {
        prefs.edit().putBoolean(KEY_FOCUS_PEAKING, on).apply()
    }

    /**
     * 峰值对焦灵敏度（0.2..0.98，越大越灵敏），默认 0.75。
     * 下发 GL 时换算为边缘阈值 = 1 - 灵敏度，默认即 shader 的 0.25。
     */
    fun getPeakingSensitivity(): Float =
        prefs.getFloat(KEY_PEAKING_SENSITIVITY, 0.75f).coerceIn(0.2f, 0.98f)

    fun putPeakingSensitivity(value: Float) {
        prefs.edit().putFloat(KEY_PEAKING_SENSITIVITY, value.coerceIn(0.2f, 0.98f)).apply()
    }

    fun getShowBubbleLevel(): Boolean = prefs.getBoolean(KEY_SHOW_BUBBLE_LEVEL, false)

    fun putShowBubbleLevel(show: Boolean) {
        prefs.edit().putBoolean(KEY_SHOW_BUBBLE_LEVEL, show).apply()
    }

    // ── 快门小工具 ────────────────────────────────────────────────

    /**
     * 音量键快门档位，默认关闭。
     * 存枚举序号（与 [getZebraMode] 一致），越界回退 OFF —— 未设置时音量键必须还是音量键。
     */
    fun getVolumeKeyShutter(): VolumeKeyShutter =
        VolumeKeyShutter.entries.getOrNull(prefs.getInt(KEY_VOLUME_KEY_SHUTTER, 0))
            ?: VolumeKeyShutter.OFF

    fun putVolumeKeyShutter(mode: VolumeKeyShutter) {
        prefs.edit().putInt(KEY_VOLUME_KEY_SHUTTER, mode.ordinal).apply()
    }

    /**
     * 倒计时自拍档位，默认关闭。
     * 越界回退 OFF：与音量键同理，未设置过的用户不该莫名多出几秒延迟。
     */
    fun getCountdownSec(): CountdownSec =
        CountdownSec.entries.getOrNull(prefs.getInt(KEY_COUNTDOWN_SECONDS, 0))
            ?: CountdownSec.OFF

    fun putCountdownSec(mode: CountdownSec) {
        prefs.edit().putInt(KEY_COUNTDOWN_SECONDS, mode.ordinal).apply()
    }

    /** 声控快门开关，默认关（开着麦等于一直占麦克风，必须用户主动要） */
    fun isVoiceShutterOn(): Boolean = prefs.getBoolean(KEY_VOICE_SHUTTER, false)

    fun putVoiceShutterOn(on: Boolean) {
        prefs.edit().putBoolean(KEY_VOICE_SHUTTER, on).apply()
    }

    /** 相对底噪的倍数门限（越大越迟钝），默认 5，限 2..20 */
    fun getVoicePickup(): Float = prefs.getFloat(KEY_VOICE_PICKUP, 5f).coerceIn(2f, 20f)

    fun putVoicePickup(value: Float) {
        prefs.edit().putFloat(KEY_VOICE_PICKUP, value.coerceIn(2f, 20f)).apply()
    }

    /** 绝对触发下限（归一化 RMS），默认 0.06，限 0.01..0.3 */
    fun getVoiceMinLevel(): Float = prefs.getFloat(KEY_VOICE_MIN_LEVEL, 0.06f).coerceIn(0.01f, 0.3f)

    fun putVoiceMinLevel(value: Float) {
        prefs.edit().putFloat(KEY_VOICE_MIN_LEVEL, value.coerceIn(0.01f, 0.3f)).apply()
    }

    /**
     * 连拍帧数档位，默认单张。
     * 越界回退 [BurstCount.OFF]：未设置过的用户不该突然开始连拍。
     */
    fun getBurstCount(): BurstCount =
        BurstCount.entries.getOrNull(prefs.getInt(KEY_BURST_COUNT, 0)) ?: BurstCount.OFF

    fun putBurstCount(mode: BurstCount) {
        prefs.edit().putInt(KEY_BURST_COUNT, mode.ordinal).apply()
    }

    /** 连拍是否额外导出一张动图，默认关（多写一个文件，用户主动要） */
    fun isBurstGifOn(): Boolean = prefs.getBoolean(KEY_BURST_GIF, false)

    fun putBurstGif(on: Boolean) {
        prefs.edit().putBoolean(KEY_BURST_GIF, on).apply()
    }

    // ── 专业采集状态（Camera2 Interop，M1：冷启动恢复）──────────────
    //
    // 语义与内存态一致：写穿持久化后，进程重启由 CameraManager 构造时
    // 回读，首次 bindPreview 经 restoreAfterRebind() 下发到 HAL。
    // 手动曝光档位会原样恢复（用户主动选的 M 档不该因杀进程丢失）。

    /** 曝光补偿档位，默认 0 */
    fun getProEvIndex(): Int = prefs.getInt(KEY_PRO_EV_INDEX, 0)

    fun putProEvIndex(index: Int) {
        prefs.edit().putInt(KEY_PRO_EV_INDEX, index).apply()
    }

    /** 白平衡预设，默认自动；损坏值回退 AUTO */
    fun getWbPreset(): WbPreset =
        WbPreset.entries.getOrNull(prefs.getInt(KEY_PRO_WB_PRESET, 0)) ?: WbPreset.AUTO

    fun putWbPreset(preset: WbPreset) {
        prefs.edit().putInt(KEY_PRO_WB_PRESET, preset.ordinal).apply()
    }

    /** 白平衡强度 0..1，默认 0.5 */
    fun getWbIntensity(): Float =
        prefs.getFloat(KEY_PRO_WB_INTENSITY, 0.5f).coerceIn(0f, 1f)

    fun putWbIntensity(intensity: Float) {
        prefs.edit().putFloat(KEY_PRO_WB_INTENSITY, intensity.coerceIn(0f, 1f)).apply()
    }

    /** 手动曝光（AE OFF）是否开启，默认关 */
    fun isManualExposureOn(): Boolean = prefs.getBoolean(KEY_PRO_MANUAL_EXPOSURE, false)

    fun putManualExposureOn(on: Boolean) {
        prefs.edit().putBoolean(KEY_PRO_MANUAL_EXPOSURE, on).apply()
    }

    /** 手动 ISO，默认 400（下发前还会按传感器范围收敛） */
    fun getManualIso(): Int = prefs.getInt(KEY_PRO_MANUAL_ISO, 400)

    fun putManualIso(iso: Int) {
        prefs.edit().putInt(KEY_PRO_MANUAL_ISO, iso).apply()
    }

    /** 手动快门时长（纳秒），默认 ≈16.7ms（1/60s） */
    fun getManualShutterNs(): Long = prefs.getLong(KEY_PRO_MANUAL_SHUTTER_NS, 16_700_000L)

    fun putManualShutterNs(ns: Long) {
        prefs.edit().putLong(KEY_PRO_MANUAL_SHUTTER_NS, ns).apply()
    }

    /** HDR+ 多帧模式开关，默认关（连拍取景会降帧率，不进默认） */
    fun isHdrModeOn(): Boolean = prefs.getBoolean(KEY_HDR_MODE_ON, false)

    fun putHdrModeOn(on: Boolean) {
        prefs.edit().putBoolean(KEY_HDR_MODE_ON, on).apply()
    }

    /** 夜景多帧模式开关，默认关；与 HDR 互斥由 UI 层保证 */
    fun isNightModeOn(): Boolean = prefs.getBoolean(KEY_NIGHT_MODE_ON, false)

    fun putNightModeOn(on: Boolean) {
        prefs.edit().putBoolean(KEY_NIGHT_MODE_ON, on).apply()
    }

    /** 滤镜栏是否收起（V2：选完滤镜专注拍照），默认展开 */
    fun isFilterBarCollapsed(): Boolean = prefs.getBoolean(KEY_FILTER_BAR_COLLAPSED, false)

    fun putFilterBarCollapsed(collapsed: Boolean) {
        prefs.edit().putBoolean(KEY_FILTER_BAR_COLLAPSED, collapsed).apply()
    }

    // ── 新手引导 ────────────────────────────────────────────────────

    fun isOnboardingShown(): Boolean = prefs.getBoolean(KEY_ONBOARDING_SHOWN, false)

    fun setOnboardingShown() {
        prefs.edit().putBoolean(KEY_ONBOARDING_SHOWN, true).apply()
    }

    // ── 滤镜参数（按滤镜名存储）─────────────────────────────────────

    /**
     * 读取指定滤镜的参数表。无记录返回空表（由调用方填默认）。
     */
    fun getFilterParams(filterName: String): Map<String, Float> {
        val raw = prefs.getString(KEY_FILTER_PARAMS_PREFIX + filterName, null) ?: return emptyMap()
        return try {
            val json = JSONObject(raw)
            val out = mutableMapOf<String, Float>()
            val keys = json.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                out[k] = json.getDouble(k).toFloat()
            }
            out
        } catch (e: Exception) {
            Log.w(TAG, "读取滤镜参数失败: $filterName", e)
            emptyMap()
        }
    }

    /**
     * 保存指定滤镜的参数表。
     */
    fun putFilterParams(filterName: String, params: Map<String, Float>) {
        try {
            val json = JSONObject()
            for ((k, v) in params) {
                json.put(k, v.toDouble())
            }
            prefs.edit().putString(KEY_FILTER_PARAMS_PREFIX + filterName, json.toString()).apply()
        } catch (e: Exception) {
            Log.w(TAG, "保存滤镜参数失败: $filterName", e)
        }
    }
}
