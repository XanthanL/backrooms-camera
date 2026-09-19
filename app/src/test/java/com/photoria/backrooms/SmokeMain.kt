package com.photoria.backrooms

import com.photoria.backrooms.camera.VoiceTriggerLogic
import com.photoria.backrooms.gif.GifEncoder
import com.photoria.backrooms.gl.AdjustmentEngine
import com.photoria.backrooms.gl.CurveEngine
import com.photoria.backrooms.util.ExifOrientations
import com.photoria.backrooms.util.KeyAction
import com.photoria.backrooms.util.KeyRouter
import com.photoria.backrooms.util.MosaicLayout
import com.photoria.backrooms.util.VolumeKeyShutter
import java.io.File
import kotlin.math.abs

/**
 * 无真机时的自证入口（故意不用 JUnit —— 工程里没有任何测试依赖，`--offline` 也拉不到）。
 *
 * 用法（Gradle 已封装为任务，classpath 与编译依赖自动处理）：
 *   ./gradlew :app:runSmokeMain --offline [-PsmokeArgs="a.gif b.gif c.gif"]
 * 该任务已接入 `check`，assembleDebug/lint 同级的校验门会跑到这里。
 * 之后用 Pillow 独立解码这三个 gif 交叉校验（见交付说明）。
 *
 * 只覆盖纯 Kotlin 内核：按键真值表、声控触发状态机、拼图布局、GIF 编码结构与像素回环。
 */
object SmokeMain {

    private var checks = 0

    @JvmStatic
    fun main(args: Array<String>) {
        val out = args.getOrNull(0) ?: "smoke.gif"
        val stressOut = args.getOrNull(1) ?: "smoke_stress.gif"
        val burstOut = args.getOrNull(2) ?: "smoke_burst.gif"
        checkKeyRouter()
        checkVoiceTrigger()
        checkMosaicLayout()
        checkExifOrientations()
        checkAdjustmentEngine()
        checkCurveEngine()
        val info = checkGif(File(out))
        val stressInfo = checkGifStress(File(stressOut))
        val burstInfo = checkBurstGif(File(burstOut))
        println("ALL CHECKS PASSED ($checks) → $info")
        println("                                  → $stressInfo")
        println("                                  → $burstInfo")
    }

    // ── 按键 ────────────────────────────────────────────────────

    private fun checkKeyRouter() {
        // 与 Android 常量交叉校验（编译期常量内联，运行期不需要 android.jar）
        eq("keycode DOWN", KeyRouter.KEYCODE_VOLUME_DOWN, android.view.KeyEvent.KEYCODE_VOLUME_DOWN)
        eq("keycode UP", KeyRouter.KEYCODE_VOLUME_UP, android.view.KeyEvent.KEYCODE_VOLUME_UP)

        val D = KeyRouter.KEYCODE_VOLUME_DOWN
        val U = KeyRouter.KEYCODE_VOLUME_UP
        val down = KeyAction.SHUTTER
        val consume = KeyAction.CONSUME_ONLY
        val pass = KeyAction.PASS

        eq("OFF/下/未连按/已武装", KeyRouter.decideDown(VolumeKeyShutter.OFF, D, 0, true), pass)
        eq("DOWN_ONLY/下/已武装", KeyRouter.decideDown(VolumeKeyShutter.DOWN_ONLY, D, 0, true), down)
        eq("DOWN_ONLY/上/已武装", KeyRouter.decideDown(VolumeKeyShutter.DOWN_ONLY, U, 0, true), pass)
        eq("DOWN_ONLY/下/未武装", KeyRouter.decideDown(VolumeKeyShutter.DOWN_ONLY, D, 0, false), consume)
        eq("DOWN_ONLY/下/连发", KeyRouter.decideDown(VolumeKeyShutter.DOWN_ONLY, D, 3, true), consume)
        eq("BOTH/上/已武装", KeyRouter.decideDown(VolumeKeyShutter.BOTH, U, 0, true), down)
        eq("BOTH/下/连发", KeyRouter.decideDown(VolumeKeyShutter.BOTH, D, 5, true), consume)
        eq("BOTH/其它键", KeyRouter.decideDown(VolumeKeyShutter.BOTH, 4, 0, true), pass)

        eq("UP 吞 DOWN_ONLY", KeyRouter.decideUp(VolumeKeyShutter.DOWN_ONLY, D), consume)
        eq("UP 放行 DOWN_ONLY 的上键", KeyRouter.decideUp(VolumeKeyShutter.DOWN_ONLY, U), pass)
        eq("UP 吞 BOTH 上键", KeyRouter.decideUp(VolumeKeyShutter.BOTH, U), consume)
        eq("UP 在 OFF 全放行", KeyRouter.decideUp(VolumeKeyShutter.OFF, D), pass)
    }

    // ── 声控 ────────────────────────────────────────────────────

    private fun checkVoiceTrigger() {
        val logic = VoiceTriggerLogic()
        logic.reset()
        var fires = 0
        var t = 0L
        // 40 帧安静（底噪 0.001）→ 3 帧脉冲（0.5）→ 40 帧安静
        val seq = FloatArray(40) { 0.001f } + floatArrayOf(0.5f, 0.5f, 0.5f) + FloatArray(40) { 0.001f }
        for (v in seq) {
            if (logic.update(v, t)) fires++
            t += 32L
        }
        eq("单次脉冲只触发一次", fires, 1)
        truthy("底噪已贴合安静环境", logic.floor < 0.002f)

        // 冷却窗内第二声必须被抑制；跨过冷却窗的第三声必须再次触发（抑制 + 恢复都要验）
        val logic2 = VoiceTriggerLogic()
        logic2.reset()
        var fires2 = 0
        var t2 = 0L
        fun clap() {
            // 一次「拍手」= 2 帧超线 + 13 帧安静，整周期 480ms < 900ms 冷却
            repeat(2) { if (logic2.update(0.5f, t2)) fires2++; t2 += 32L }
            repeat(13) { logic2.update(0.001f, t2); t2 += 32L }
        }
        clap()
        clap()
        eq("冷却窗内第二声被抑制", fires2, 1)
        repeat(10) { logic2.update(0.001f, t2); t2 += 32L }   // 跨过冷却边界
        clap()
        eq("冷却窗后第三声再次触发", fires2, 2)

        // 单帧毛刺（连续不足 2 帧）不能触发
        val spikeOnly = VoiceTriggerLogic()
        spikeOnly.reset()
        var fired = 0
        var ts = 0L
        for (round in 0 until 5) {
            fired += if (spikeOnly.update(0.9f, ts)) 1 else 0
            ts += 32L
            fired += if (spikeOnly.update(0.001f, ts)) 1 else 0
            ts += 32L
        }
        eq("交替毛刺不触发", fired, 0)

        // 极安静环境也不能靠呼吸声触发（绝对下限）
        val quiet = VoiceTriggerLogic(noisePickup = 5f, absMinLevel = 0.06f)
        quiet.reset()
        var quietFires = 0
        var tq = 0L
        repeat(200) {
            if (quiet.update(0.02f, tq)) quietFires++
            tq += 32L
        }
        eq("低于绝对下限不触发", quietFires, 0)

        // rmsOf 的归一化定义：满幅方波 RMS ≈ 1.0（0.707 是正弦的波峰因数，方波不适用）
        val square = ShortArray(512) { if (it % 2 == 0) 32767.toShort() else (-32768).toShort() }
        val rms = VoiceTriggerLogic.rmsOf(square)
        truthy("满幅方波 RMS ≈ 1.0（实得 $rms）", abs(rms - 1f) < 0.001f)
        // 再钉一个半幅点：单点 ≈1.0 无法区分「除以 32768」与「除以满幅峰值」的巧合
        val halfSquare = ShortArray(512) { if (it % 2 == 0) 16384.toShort() else (-16384).toShort() }
        val rmsHalf = VoiceTriggerLogic.rmsOf(halfSquare)
        truthy("半幅方波 RMS ≈ 0.5（实得 $rmsHalf）", abs(rmsHalf - 0.5f) < 0.001f)
        eq("count=0 时 RMS 为 0", VoiceTriggerLogic.rmsOf(square, 0), 0f)
    }

    // ── 拼图布局 ────────────────────────────────────────────────

    private fun checkMosaicLayout() {
        val l = MosaicLayout.of(cellCount = 9, columns = 3, cellWidth = 405, cellHeight = 540, gap = 6)
        eq("九宫格列数", l.columns, 3)
        eq("九宫格行数", l.rows, 3)
        eq("画布宽", l.width, 3 * 405 + 4 * 6)
        eq("画布高", l.height, 3 * 540 + 4 * 6)
        eq("格子数", l.cells.size, 9)
        // 顺序：左→右、上→下
        eq("第 4 格落在第二行首列", Pair(l.cells[3].x, l.cells[3].y), Pair(6, 552))
        // 互不重叠 + 不越界
        var overlaps = 0
        for (a in l.cells.indices) for (b in a + 1 until l.cells.size) {
            val x = l.cells[a]; val y = l.cells[b]
            if (x.x < y.x + y.width && y.x < x.x + x.width &&
                x.y < y.y + y.height && y.y < x.y + x.height
            ) overlaps++
        }
        eq("格子重叠数", overlaps, 0)
        val edge = l.cells.maxOf { maxOf(it.x + it.width, it.y + it.height) }
        truthy("格子不越界（edge=$edge）", edge <= maxOf(l.width, l.height))

        // 帧数不足一屏时按列数截断，不留空洞
        val few = MosaicLayout.of(cellCount = 2, columns = 3, cellWidth = 100, cellHeight = 100, gap = 0)
        eq("2 帧的列数", few.columns, 2)
        eq("2 帧的行数", few.rows, 1)
        eq("2 帧的画布宽", few.width, 200)

        // 格子尺寸必须跟着帧的宽高比：竖屏 3:4 得到 405×540，不是正方形
        eq("竖屏帧格子", MosaicLayout.cellFor(1440, 1920, 540), 405 to 540)
        eq("横屏帧格子", MosaicLayout.cellFor(1920, 1440, 540), 540 to 405)
        eq("方幅帧格子", MosaicLayout.cellFor(1080, 1080, 540), 540 to 540)
        eq("非法帧格子", MosaicLayout.cellFor(0, 1920, 540), null)

        // 5 帧真实档位（格子 405×540、缝 8px）：排成 3×2，末格在第二行中列
        val five = MosaicLayout.of(cellCount = 5, columns = 3, cellWidth = 405, cellHeight = 540, gap = 8)
        eq("5 帧的列数", five.columns, 3)
        eq("5 帧的行数", five.rows, 2)
        eq("5 帧的格子数", five.cells.size, 5)
        eq("5 帧的画布宽", five.width, 3 * 405 + 4 * 8)
        eq("5 帧的画布高", five.height, 2 * 540 + 3 * 8)
        eq("第 5 格坐标", Pair(five.cells[4].x, five.cells[4].y), Pair(8 + 413, 8 + 548))
    }

    // ── GIF ─────────────────────────────────────────────────────

    /** 4 帧纯色/分区图：期望 Pillow 解出完全相同的像素（量化无损） */
    private fun checkGif(file: File): String {
        val w = 64
        val h = 48
        val white = 0x00FFFFFF.toInt()
        val black = 0x00000000
        val red = 0x00FF0000
        val green = 0x0000FF00
        val blue = 0x000000FF
        val yellow = 0x00FFFF00

        fun flat(color: Int) = IntArray(w * h) { color }
        fun quadrants(): IntArray {
            val px = IntArray(w * h)
            for (y in 0 until h) for (x in 0 until w) {
                px[y * w + x] = when {
                    x < w / 2 && y < h / 2 -> red
                    x >= w / 2 && y < h / 2 -> green
                    x < w / 2 && y >= h / 2 -> blue
                    else -> yellow
                }
            }
            return px
        }
        // 渐变帧只要求「能解码 + 颜色近似」
        fun gradient(base: Int): IntArray {
            val px = IntArray(w * h)
            for (y in 0 until h) for (x in 0 until w) {
                val v = (x * 255) / (w - 1)
                px[y * w + x] = (0xFF shl 24) or
                    ((v * (1 + base) / 3) shl 16) or ((255 - v) shl 8) or (v shl 0).let { it and 0xFF }
            }
            return px
        }

        val frames = arrayOf(flat(white), quadrants(), flat(black), gradient(2))
        val delays = intArrayOf(12, 12, 12, 12)
        val bytes = GifEncoder.encode(frames, w, h, delays, loopInfinite = true)

        checkStructure("smoke", bytes, w, h)

        file.parentFile?.mkdirs()
        file.writeBytes(bytes)

        // 无损性自检：调色板里必须有那 4 个纯色（否则说明中位切分把颜色并掉了）
        val palette = GifEncoder.buildPalette(arrayOf(frames[0], frames[1], frames[2], frames[3]))
        truthy(
            "调色板收录全部 4 个纯色",
            listOf(red, green, blue, yellow).all { c ->
                palette.any { it == (c and 0xFFFFFF) }
            }
        )
        // 平涂色必须原样映射回自身：5-5-5 桶里被别的调色板色抢走 = 整块区域染脏
        val lut = GifEncoder.buildLookup(palette)
        for (c in listOf(red, green, blue, yellow)) {
            val r5 = ((c shr 16) and 0xFF) shr 3
            val g5 = ((c shr 8) and 0xFF) shr 3
            val b5 = (c and 0xFF) shr 3
            val mapped = palette[lut[(r5 shl 10) or (g5 shl 5) or b5]]
            truthy(
                "LUT 原样映射 #${Integer.toHexString(c and 0xFFFFFF)}（实得 #${Integer.toHexString(mapped)}）",
                mapped == (c and 0xFFFFFF)
            )
        }
        eq("输出字节数>1KB", bytes.size > 1024, true)
        return "gif ${w}x$h frames=${frames.size} bytes=${bytes.size} → ${file.absolutePath}"
    }

    /**
     * 压测帧：96×96 的哈希噪声，几乎不可压缩 —— 每像素基本自成一个码字。
     *
     * 3072 像素的小图最多把码表填到 ~3300 项，永远碰不到 12bit 与 clear 分支；
     * 9216 像素则保证码表从 258 涨满到 4096 并复位若干次。位宽边界只有在这里才被真正走过。
     */
    private fun checkGifStress(file: File): String {
        val w = 96
        val h = 96
        fun noise(seed: Int): IntArray {
            val px = IntArray(w * h)
            for (y in 0 until h) for (x in 0 until w) {
                // 整数散列代替 Random：无真机时压测输入也必须可复现
                var v = (x * 374761393 + y * 668265263 + seed * 2246822519L).toInt()
                v = (v xor (v ushr 13)) * 1274126177
                v = v xor (v ushr 16)
                px[y * w + x] = (v and 0xFF) shl 16 or ((v shr 8 and 0xFF) shl 8) or (v shr 16 and 0xFF)
            }
            return px
        }
        val frames = arrayOf(noise(1), noise(2), noise(3))
        val bytes = GifEncoder.encode(frames, w, h, intArrayOf(8, 8, 8), loopInfinite = true)
        checkStructure("stress", bytes, w, h)
        walkFrames("stress", bytes, intArrayOf(8, 8, 8))
        // 不可压缩输入下，输出应接近 1 字节/像素（噪声没有可复用的长串）
        truthy("噪声帧压测输出规模合理（${bytes.size}B / ${w * h * 3}px）", bytes.size > w * h)
        file.parentFile?.mkdirs()
        file.writeBytes(bytes)
        return "stress ${w}x$h frames=${frames.size} bytes=${bytes.size} → ${file.absolutePath}"
    }

    /**
     * 生产形态：9 帧 405×540、每帧 22cs —— 与连拍档位逐项对齐。
     *
     * 格子尺寸来自 MosaicLayout.cellFor(1440, 1920, 540)，延时来自
     * BurstCapture.DEFAULT_INTERVAL_MS / 10（这两处一旦改动，本例就该跟着改）。
     * 小图永远走不到 LZW 的 12bit/clear 分支那么多轮，也测不出"单帧几十万像素"时
     * 的子块切分与收尾 —— 只有按真实尺寸编一遍才算被走过。
     */
    private fun checkBurstGif(file: File): String {
        val w = 405
        val h = 540
        val frameCount = 9
        val delayCs = 22
        fun frame(seed: Int): IntArray {
            val px = IntArray(w * h)
            val bandPos = (seed * 61) % h
            for (y in 0 until h) {
                val inBand = y in bandPos until bandPos + 40
                for (x in 0 until w) {
                    val r = x * 255 / (w - 1)
                    val g = y * 255 / (h - 1)
                    val b = if (inBand) 255 else (x + y * 3) % 256
                    px[y * w + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                }
            }
            return px
        }
        val frames = Array(frameCount) { frame(it) }
        val bytes = GifEncoder.encode(frames, w, h, IntArray(frameCount) { delayCs })
        checkStructure("burst", bytes, w, h)
        walkFrames("burst", bytes, IntArray(frameCount) { delayCs })
        file.parentFile?.mkdirs()
        file.writeBytes(bytes)
        return "burst ${w}x$h frames=$frameCount bytes=${bytes.size} → ${file.absolutePath}"
    }

    /**
     * 按规范整条流经走一遍：块类型 → 子块链 → 下一块。
     *
     * 不用"扫 21 F9 04 特征字节"来数帧：LZW 数据里出现这串的概率不低，会假报警。
     * 走完整个流还顺带证明了每条子块链都以 00 收尾（漏收尾符会让指针跑飞，
     * 于是帧数与延时的断言立刻对不上），并要求最后正好停在流的末尾。
     */
    private fun walkFrames(tag: String, bytes: ByteArray, expectedDelays: IntArray) {
        var i = 13 + 3 * GifEncoder.PALETTE_SIZE   // 头部 + 逻辑屏幕描述符 + 全局色表
        val delays = ArrayList<Int>()
        while (i < bytes.size) {
            val b = bytes[i].toInt() and 0xFF
            if (b == 0x3B) break                    // Trailer，流的终点
            if (b == 0x21) {                        // 扩展：标签 + 子块链
                if (bytes[i + 1].toInt() and 0xFF == 0xF9) {
                    delays.add(
                        bytes[i + 4].toInt() and 0xFF or ((bytes[i + 5].toInt() and 0xFF) shl 8)
                    )
                }
                i = skipSubBlocks(bytes, i + 2)
            } else if (b == 0x2C) {                 // 图像描述符
                val packed = bytes[i + 9].toInt() and 0xFF
                i += 10
                if (packed and 0x80 != 0) {          // 局部色表（本编码器不写，走到了就报）
                    i += 3 * (1 shl ((packed and 0x07) + 1))
                }
                i = skipSubBlocks(bytes, i + 1)      // 跳过 minCodeSize + 数据子块链
            } else {
                throw AssertionError("[$tag] 偏移 $i 处不是已知块类型（0x${b.toString(16)}）")
            }
        }
        // 停在 Trailer（0x3B）上：即整条流除最后一个字节外都被合法消费掉了
        truthy("[$tag] 停在 Trailer（$i / ${bytes.size - 1}）", i == bytes.size - 1)
        eq("[$tag] 帧延时序列", delays.joinToString(","), expectedDelays.joinToString(","))
    }

    /** 跳过一串子块（首字节是长度，0 表示链结束），返回链尾之后的位置 */
    private fun skipSubBlocks(bytes: ByteArray, from: Int): Int {
        var p = from
        while (p < bytes.size) {
            val len = bytes[p].toInt() and 0xFF
            if (len == 0) return p + 1
            p += 1 + len
        }
        return p
    }

    /** 容器结构逐字节校验（头部、逻辑屏幕描述符、全局色表标志、Netscape 循环扩展） */
    private fun checkStructure(tag: String, bytes: ByteArray, w: Int, h: Int) {
        eq("[$tag] Header", String(bytes.copyOfRange(0, 6), Charsets.ISO_8859_1), "GIF89a")
        eq("[$tag] Trailer", bytes[bytes.size - 1].toInt() and 0xFF, 0x3B)
        eq("[$tag] 逻辑屏幕宽", bytes[6].toInt() and 0xFF or ((bytes[7].toInt() and 0xFF) shl 8), w)
        eq("[$tag] 逻辑屏幕高", bytes[8].toInt() and 0xFF or ((bytes[9].toInt() and 0xFF) shl 8), h)
        eq("[$tag] 全局色表标志", bytes[10].toInt() and 0x80, 0x80)
        eq("[$tag] 全局色表项数", 1 shl (((bytes[10].toInt() and 0x07) + 1)), 256)
        // Netscape 循环扩展紧跟 6 字节头 + 7 字节逻辑屏幕描述符 + 768 字节全局色表
        val ext = 781
        eq("[$tag] 扩展引导字节", bytes[ext].toInt() and 0xFF, 0x21)
        eq("[$tag] 应用扩展标识", bytes[ext + 1].toInt() and 0xFF, 0xFF)
        eq("[$tag] 应用标识块长度", bytes[ext + 2].toInt() and 0xFF, 0x0B)
        eq(
            "[$tag] Netscape 扩展标识",
            String(bytes.copyOfRange(ext + 3, ext + 14), Charsets.ISO_8859_1),
            "NETSCAPE2.0"
        )
        eq("[$tag] 循环子块长度", bytes[ext + 14].toInt() and 0xFF, 0x03)
        eq("[$tag] 循环子块编号", bytes[ext + 15].toInt() and 0xFF, 0x01)
        eq(
            "[$tag] 循环次数=0（无限）",
            bytes[ext + 16].toInt() and 0xFF or ((bytes[ext + 17].toInt() and 0xFF) shl 8),
            0
        )
        eq("[$tag] 扩展终止符", bytes[ext + 18].toInt() and 0xFF, 0x00)
    }

    // ── 断言小工具 ──────────────────────────────────────────────

    private fun eq(name: String, actual: Any?, expected: Any?) {
        checks++
        if (actual != expected) {
            throw AssertionError("$name：期望 $expected，实得 $actual")
        }
        println("  ok  $name = $actual")
    }

    // ── EXIF 方向真值表 ─────────────────────────────────────────

    private fun checkExifOrientations() {
        // ExifInterface 常量值：NORMAL=1, FLIP_HORIZONTAL=2, ROTATE_180=3, FLIP_VERTICAL=4,
        // TRANSPOSE=5, ROTATE_90=6, TRANSVERSE=7, ROTATE_270=8
        // 后摄（非镜像）：0→1, 90→6, 180→3, 270→8
        eq("EXIF back 0°", ExifOrientations.forCamera(0, false), 1)
        eq("EXIF back 90°", ExifOrientations.forCamera(90, false), 6)
        eq("EXIF back 180°", ExifOrientations.forCamera(180, false), 3)
        eq("EXIF back 270°", ExifOrientations.forCamera(270, false), 8)
        // 前摄（镜像）：0→2, 90→7, 180→4, 270→5
        eq("EXIF front 0°", ExifOrientations.forCamera(0, true), 2)
        eq("EXIF front 90°", ExifOrientations.forCamera(90, true), 7)
        eq("EXIF front 180°", ExifOrientations.forCamera(180, true), 4)
        eq("EXIF front 270°", ExifOrientations.forCamera(270, true), 5)
        // 非法角度 → NORMAL
        eq("EXIF unknown", ExifOrientations.forCamera(45, false), 1)
        eq("EXIF negative", ExifOrientations.forCamera(-90, false), 8)
        eq("EXIF >360", ExifOrientations.forCamera(450, false), 6)
    }

    // ── 实时调色打包（W1）──────────────────────────────────────

    private fun checkAdjustmentEngine() {
        val zero = AdjustmentEngine.pack(emptyMap())
        eq("pack 长度", zero.size, AdjustmentEngine.PACK_SIZE)
        truthy("空参数 = identity", AdjustmentEngine.isIdentity(zero))
        truthy(
            "1e-6 噪声仍算 identity",
            AdjustmentEngine.isIdentity(FloatArray(36) { if (it == 10) 1e-6f else 0f })
        )
        truthy("任一非零 → 非 identity", !AdjustmentEngine.isIdentity(AdjustmentEngine.pack(mapOf("contrast" to 1f))))

        fun near(name: String, key: Map<String, Float>, index: Int, expected: Float) {
            truthy("$name（[${index}]=${AdjustmentEngine.pack(key)[index]}）",
                abs(AdjustmentEngine.pack(key)[index] - expected) < 1e-6f)
        }
        near("曝光 +100 → +1.5EV", mapOf("exposure" to 100f), 0, 1.5f)
        near("曝光 -50 → -0.75EV", mapOf("exposure" to -50f), 0, -0.75f)
        near("曝光越界钳制", mapOf("exposure" to 999f), 0, 1.5f)
        near("对比度 +50 → 0.5", mapOf("contrast" to 50f), 1, 0.5f)
        near("色温越界钳制", mapOf("temperature" to 999f), 6, 1f)
        near("饱和 [8]", mapOf("saturation" to 100f), 8, 1f)
        near("自然饱和 [9]", mapOf("vibrance" to 100f), 9, 1f)
        near("红色相 +100 → +50°", mapOf("hue_red" to 100f), 12, 50f)
        near("绿色饱和 [21]", mapOf("sat_green" to -100f), 12 + 2 * 4 + 1, -1f)
        near("洋红明度 [34]", mapOf("lum_magenta" to 100f), 12 + 5 * 4 + 2, 1f)
        truthy("未知键被忽略", AdjustmentEngine.isIdentity(AdjustmentEngine.pack(mapOf("nonsense" to 50f))))

        eq("键表大小", AdjustmentEngine.ALL_KEYS.size, 6 + 4 + 18)
        truthy("影调/色彩键全有中文名",
            (AdjustmentEngine.TONE_KEYS + AdjustmentEngine.COLOR_KEYS).all { AdjustmentEngine.LABELS.containsKey(it) })
        eq("色相带数", AdjustmentEngine.BANDS.size, 6)
        eq("带序对齐后缀", AdjustmentEngine.BANDS.size, AdjustmentEngine.BAND_SUFFIX.size)
        eq("bandOf hue_red", AdjustmentEngine.bandOf("hue_red"), 0)
        eq("bandOf lum_magenta", AdjustmentEngine.bandOf("lum_magenta"), 5)
        eq("bandOf contrast", AdjustmentEngine.bandOf("contrast"), null)

        truthy("预设非空", AdjustmentEngine.PRESETS.isNotEmpty())
        AdjustmentEngine.PRESETS.forEach { (name, params) ->
            truthy("预设[$name] 键全在 ALL_KEYS", params.keys.all { it in AdjustmentEngine.ALL_KEYS })
            truthy("预设[$name] 值在值域", params.values.all { it in -100f..100f })
            truthy("预设[$name] 非 identity", !AdjustmentEngine.isIdentity(AdjustmentEngine.pack(params)))
        }
    }

    // ── 调色曲线内核（X）──────────────────────────────────────

    private fun checkCurveEngine() {
        val diag = CurveEngine.defaultPoints()
        eq("对角曲线中点", CurveEngine.evaluate(diag, 0.5f), 0.5f)
        eq("对角曲线越界钳左", CurveEngine.evaluate(diag, -1f), 0f)
        eq("对角曲线越界钳右", CurveEngine.evaluate(diag, 2f), 1f)
        truthy("对角 = 默认", CurveEngine.isDefault(diag))
        truthy("抬中间点 = 非默认", !CurveEngine.isDefault(listOf(0f, 0f, 0.5f, 0.6f, 1f, 1f)))

        // S 形曲线（暗部下压 / 亮部上提）：LUT 必须单调不减、无过冲、端点精确
        val s = listOf(0f, 0f, 0.25f, 0.18f, 0.5f, 0.5f, 0.75f, 0.86f, 1f, 1f)
        val lut = CurveEngine.buildLut(s)
        var monotonic = true
        var bounded = true
        for (i in 1 until lut.size) {
            if (lut[i] < lut[i - 1] - 1e-6f) monotonic = false
            if (lut[i] !in 0f..1f) bounded = false
        }
        truthy("S 曲线 LUT 单调不减", monotonic)
        truthy("S 曲线 LUT 有界", bounded)
        eq("LUT 端点黑", lut[0], 0f)
        eq("LUT 端点白", lut[255], 1f)
        truthy("暗部下压生效", lut[64] < 64 / 255f)
        truthy("亮部上提生效", lut[192] > 192 / 255f)

        // RGBA 打包：空表 = 恒等斜坡；只动 RGB 合成 → 仅 alpha 分量偏离
        truthy("空曲线 = identity", CurveEngine.isIdentity(CurveEngine.buildRgbaBytes(emptyMap())))
        val master = CurveEngine.buildRgbaBytes(mapOf("curve_rgb" to s))
        truthy("合成曲线偏离", !CurveEngine.isIdentity(master))
        val expectA = (CurveEngine.evaluate(s, 100 / 255f) * 255f + 0.5f).toInt()
        eq("合成曲线写入 alpha 通道", master[100 * 4 + 3].toInt() and 0xFF, expectA)
        truthy("R 通道保持斜坡", (master[100 * 4].toInt() and 0xFF) == 100)
        truthy("G 通道保持斜坡", (master[100 * 4 + 1].toInt() and 0xFF) == 100)
        truthy("B 通道保持斜坡", (master[100 * 4 + 2].toInt() and 0xFF) == 100)
        truthy("合成通道偏离斜坡（暗部下压）", expectA < 100)

        // 拖点钳制：端点锁 x、中间点钳在邻点之间、y 钳单调
        val pts4 = listOf(0f, 0f, 0.33f, 0.3f, 0.66f, 0.7f, 1f, 1f)
        eq("左端点 x 锁定", CurveEngine.clampDrag(pts4, 0, 0.4f, 0.2f).first, 0f)
        eq("左端点 y 自由", CurveEngine.clampDrag(pts4, 0, 0.4f, 0.2f).second, 0.2f)
        eq("中间点 x 钳到左邻右舍之间", CurveEngine.clampDrag(pts4, 1, -0.2f, 0.5f).first, 1f / 512f)
        eq("中间点 y 不越过右邻", CurveEngine.clampDrag(pts4, 1, 0.4f, 0.9f).second, 0.7f)
        eq("右端点 y 不低于左邻", CurveEngine.clampDrag(pts4, 3, 1f, -0.5f).second, 0.7f)

        // 加/删点
        val base = listOf(0f, 0f, 0.5f, 0.5f, 1f, 1f)
        val added = CurveEngine.insertPoint(base, 0.25f, 0.2f)
        eq("插点后点数", added.size / 2, 4)
        truthy("插点保 x 升序", added[0] < added[2] && added[2] < added[4] && added[4] < added[6])
        val removed = CurveEngine.removePoint(added, 1)
        truthy("删回中间点还原", removed == base)
        truthy("端点删不动", CurveEngine.removePoint(base, 0) == base && CurveEngine.removePoint(base, 2) == base)
    }


    private fun truthy(name: String, condition: Boolean) {
        checks++
        if (!condition) throw AssertionError("$name：条件不成立")
        println("  ok  $name")
    }
}
