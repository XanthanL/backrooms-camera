package com.photoria.backrooms.util

import android.content.Context
import android.util.Log
import com.photoria.backrooms.PhotoriaApp
import com.photoria.backrooms.ui.viewmodel.AspectRatio
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
    private const val KEY_ONBOARDING_SHOWN = "onboarding_shown"

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
