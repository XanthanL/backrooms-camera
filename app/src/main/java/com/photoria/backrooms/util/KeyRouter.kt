package com.photoria.backrooms.util

/**
 * 音量键快门的决策内核（纯函数，不 import 任何 Android 类，可在 JVM 上跑真值表）。
 *
 * 抽成纯函数的原因：吞不吞键、触发几次这件事在没真机时最容易想错，
 * 而它的全部输入只是「档位 + 键码 + 连按次数 + 是否武装」四个标量。
 */
enum class VolumeKeyShutter(val display: String) {
    OFF("关"),
    DOWN_ONLY("下键"),
    BOTH("上下皆可")
}

/** Activity 对一次按键该怎么做 */
enum class KeyAction {
    /** 触发快门并消费事件 */
    SHUTTER,

    /** 只消费事件（不让系统弹音量条），不出片 */
    CONSUME_ONLY,

    /** 交还系统 */
    PASS
}

object KeyRouter {

    // 与 android.view.KeyEvent 的同名常量一致（这里写死是为了让本文件零 Android 依赖，
    // SmokeMain 会用 KeyEvent 的常量做一次交叉校验）
    const val KEYCODE_VOLUME_UP = 24
    const val KEYCODE_VOLUME_DOWN = 25

    /**
     * 决定一次 DOWN 事件怎么处理。
     *
     * @param repeatCount >0 表示物理按键长按连发；连发只吞不拍，
     *   否则按住音量键几秒就会拍出十几张。
     * @param armed 页面是否处于可出片状态（前台 + 相机就绪 + 未在处理拍照）
     */
    fun decideDown(
        mode: VolumeKeyShutter,
        keyCode: Int,
        repeatCount: Int,
        armed: Boolean
    ): KeyAction = when (mode) {
        VolumeKeyShutter.OFF -> KeyAction.PASS
        VolumeKeyShutter.DOWN_ONLY -> when {
            keyCode != KEYCODE_VOLUME_DOWN -> KeyAction.PASS
            !armed -> KeyAction.CONSUME_ONLY
            repeatCount > 0 -> KeyAction.CONSUME_ONLY
            else -> KeyAction.SHUTTER
        }
        VolumeKeyShutter.BOTH -> when (keyCode) {
            KEYCODE_VOLUME_UP, KEYCODE_VOLUME_DOWN -> when {
                !armed || repeatCount > 0 -> KeyAction.CONSUME_ONLY
                else -> KeyAction.SHUTTER
            }
            else -> KeyAction.PASS
        }
    }

    /**
     * 决定一次 UP 事件怎么处理。
     *
     * DOWN 被吞掉的键，UP 必须一并吞掉：否则系统收到「只有 UP」的音量键事件，
     * 仍会弹音量条（AOSP 的音量面板正是在 UP 时收起的）。
     */
    fun decideUp(mode: VolumeKeyShutter, keyCode: Int): KeyAction = when (mode) {
        VolumeKeyShutter.OFF -> KeyAction.PASS
        VolumeKeyShutter.DOWN_ONLY ->
            if (keyCode == KEYCODE_VOLUME_DOWN) KeyAction.CONSUME_ONLY else KeyAction.PASS
        VolumeKeyShutter.BOTH ->
            if (keyCode == KEYCODE_VOLUME_UP || keyCode == KEYCODE_VOLUME_DOWN)
                KeyAction.CONSUME_ONLY
            else
                KeyAction.PASS
    }
}
