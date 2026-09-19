package com.photoria.backrooms.camera

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import android.view.Surface
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.roundToInt

/**
 * 水平仪数学。纯函数，不碰 Android 框架以外的状态，便于单独推演。
 */
object LevelMath {

    /** 归零容差（度）：读数进入 ±该区间算「已水平」 */
    const val LEVEL_TOLERANCE_DEG = 0.7f

    /**
     * 由旋转矩阵求「视觉水平倾角」（度）。
     *
     * 符号约定：**正值 = 屏幕右沿偏低**（气泡向高处漂，故 UI 侧偏移取 -roll）。
     * 推导：R 把设备坐标映射到世界坐标（East-North-Up），因此
     * 屏幕右方向 s 在世界竖直轴上的分量 `s_up = (R·s).z`，屏幕法线 n=(0,0,1)
     * 的 `n_up = R[8]`，倾角 = atan2(-s_up, n_up)。
     * s_up>0 表示右沿偏高，取负号后与上面的约定一致。
     *
     * @param screenRotation Surface.ROTATION_*：界面相对设备自然方向旋转时，
     *   屏幕右方向对应的设备轴会变（竖屏=+X，横屏=±Y），不换算的话横屏下读数会错 90°。
     *   当前 Activity 锁竖屏，实际只会走到 ROTATION_0 分支。
     */
    fun rollFromRotationMatrix(r: FloatArray, screenRotation: Int): Float {
        // 屏幕右方向在设备坐标系中的轴向，投影到世界 Up（R 的第 3 行）
        val rightUp = when (screenRotation) {
            Surface.ROTATION_90 -> r[7]      // 右沿 = 设备 +Y（顶边）
            Surface.ROTATION_180 -> -r[6]    // 右沿 = 设备 -X
            Surface.ROTATION_270 -> -r[7]    // 右沿 = 设备 -Y
            else -> r[6]                     // 右沿 = 设备 +X
        }
        return atan2(-rightUp, r[8]) * 180.0f / Math.PI.toFloat()
    }

    /**
     * 边沿检测：上一次读数在容差外、本次进入容差内才算「刚归零」。
     *
     * 不做边沿检测的话，机身在阈值附近抖动会反复震动。
     */
    fun snappedToLevel(roll: Float, previousRoll: Float?): Boolean {
        if (previousRoll == null) return false
        return abs(previousRoll) > LEVEL_TOLERANCE_DEG && abs(roll) <= LEVEL_TOLERANCE_DEG
    }
}

/**
 * 机身横滚角（水平仪）传感器封装。
 *
 * 传感器优先级：GAME_ROTATION_VECTOR（无磁干扰、延迟低）→ ROTATION_VECTOR →
 * 加速度计 + 磁力计合成。三者都没有时 [available] 为 false，
 * 调用方据此什么都不画（不弹提示、不崩）。
 *
 * 只读不写任何相机状态，因此与拍照/录像管线完全解耦，也不会进照片。
 */
class LevelSensor(context: Context) {

    companion object {
        private const val TAG = "LevelSensor"

        /** 小于该角度变化不重发，避免传感器 50-100Hz 原始频率打满 Compose 重组 */
        private const val EMIT_EPSILON_DEG = 0.2f

        /** 读数探针最小间隔（纳秒）= 1 秒 */
        private const val ROLL_LOG_INTERVAL_NS = 1_000_000_000L
    }

    /** 上次打印读数探针的时间（仅 GL/传感器线程访问） */
    private var lastRollLogNs = 0L

    private val sensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager?

    /** 首选融合传感器（旋转矢量类） */
    private val rotationSensor: Sensor? = run {
        val sm = sensorManager ?: return@run null
        sm.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
            ?: sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    }

    /** 退化路径：无旋转矢量时用加速度计 + 磁力计合成 */
    private val accelerometer: Sensor? =
        sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val magnetometer: Sensor? =
        sensorManager?.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)

    /** 是否有可用的水平仪数据源 */
    val available: Boolean =
        rotationSensor != null || (accelerometer != null && magnetometer != null)

    init {
        // 三者都没有时调用方什么都不画，这里只留一条探针便于真机排查
        if (!available) Log.w(TAG, "无可用姿态传感器（游戏旋转矢量/旋转矢量/加速度+磁力），水平仪不可用")
    }

    /** 当前倾角（度，正=右边偏低）；尚无数据时为 null */
    private val _roll = MutableStateFlow<Float?>(null)
    val roll: StateFlow<Float?> = _roll.asStateFlow()

    private val rotationMatrix = FloatArray(9)
    private val vectorBuffer = FloatArray(4)
    private var accelValues: FloatArray? = null
    private var magValues: FloatArray? = null
    private var screenRotation = Surface.ROTATION_0
    private var registered = false

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            when (event.sensor.type) {
                Sensor.TYPE_ACCELEROMETER -> accelValues = event.values.copyOf(3)
                Sensor.TYPE_MAGNETIC_FIELD -> magValues = event.values.copyOf(3)
                else -> {
                    // 只取前 4 元：未校准陀螺偏置的第 5 元对姿态矩阵无用，
                    // 而 3 参版 getRotationMatrixFromVector 只接受长度 3/4
                    val n = minOf(4, event.values.size)
                    System.arraycopy(event.values, 0, vectorBuffer, 0, n)
                    SensorManager.getRotationMatrixFromVector(rotationMatrix, vectorBuffer)
                    emit(LevelMath.rollFromRotationMatrix(rotationMatrix, screenRotation))
                    return
                }
            }
            val a = accelValues
            val m = magValues
            if (a != null && m != null &&
                SensorManager.getRotationMatrix(rotationMatrix, null, a, m)
            ) {
                emit(LevelMath.rollFromRotationMatrix(rotationMatrix, screenRotation))
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    /**
     * 开始监听。
     *
     * @param screenRotation 当前界面方向（Surface.ROTATION_*），决定水平参考轴
     */
    fun start(screenRotation: Int) {
        val sm = sensorManager ?: return
        if (!available || registered) return
        this.screenRotation = screenRotation
        val sensor = rotationSensor ?: accelerometer!!
        registered = sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_GAME)
        if (!registered) {
            Log.w(TAG, "registerListener 失败: ${sensor.name}")
            return
        }
        if (rotationSensor == null) {
            // 退化路径需要磁力计这一第二数据源
            magnetometer?.let {
                sm.registerListener(listener, it, SensorManager.SENSOR_DELAY_GAME)
            }
        }
        Log.d(TAG, "start sensor=${sensor.name} rotation=$screenRotation")
    }

    /** 停止监听并清空读数（页面不可见/关闭水平仪时调用，防泄漏与耗电） */
    fun stop() {
        val sm = sensorManager ?: return
        if (!registered) return
        sm.unregisterListener(listener)
        registered = false
        accelValues = null
        magValues = null
        _roll.value = null
        Log.d(TAG, "stop")
    }

    private fun emit(rollDeg: Float) {
        // 归一到 (-180, 180]，并量化到 0.1° 便于比较
        val normalized = ((rollDeg + 180f) % 360f + 360f) % 360f - 180f
        val rounded = (normalized * 10f).roundToInt() / 10f
        val prev = _roll.value
        if (prev == null || abs(prev - rounded) >= EMIT_EPSILON_DEG) {
            _roll.value = rounded
            logRollProbe(rounded)
        }
    }

    /** 读数探针：真机核对符号约定用，限流到约 1 次/秒（传感器为 GAME 频率） */
    private fun logRollProbe(rollDeg: Float) {
        val now = System.nanoTime()
        if (now - lastRollLogNs < ROLL_LOG_INTERVAL_NS) return
        lastRollLogNs = now
        Log.d(TAG, "roll=$rollDeg° (正值=屏幕右沿偏低)")
    }
}
