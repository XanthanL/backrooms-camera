package com.photoria.backrooms.gif

/**
 * GIF89a 编码器（纯 Kotlin，零 Android / 零依赖）。
 *
 * 为什么要自己写：MediaCodec 不支持 GIF，Android 的 `Bitmap.compress` 也没有 GIF
 * 格式，而项目硬约束是不引第三方库。
 *
 * 做法：所有帧共用一张中位切分得到的 ≤256 色全局调色板（同输入必同输出，比八叉树稳），
 * 逐帧 Floyd–Steinberg 抖动后只写 8bit 索引，LZW 压缩，每帧全量、不做帧间差分。
 *
 * 输出布局（顺序即写入顺序）：
 *   Header "GIF89a" → 逻辑屏幕描述符 + 256 项全局色表 → Netscape 循环扩展 →
 *   每帧 [图形控制扩展 → 图像描述符 → minCodeSize → 数据子块 → 子块终止符] → Trailer 0x3B
 *
 * 输入帧是行主序 ARGB 数组（alpha 忽略，按不透明处理）。
 */
object GifEncoder {

    /** 调色板固定 256 项（GIF 色表长度必须是 2 的幂；占满可让 LZW 恒为 8bit 起点） */
    const val PALETTE_SIZE = 256

    private const val MIN_CODE_SIZE = 8
    private const val CLEAR_CODE = 1 shl MIN_CODE_SIZE      // 256
    private const val END_CODE = CLEAR_CODE + 1             // 257
    private const val FIRST_CODE = END_CODE + 1             // 258
    private const val MAX_CODE = 1 shl 12                   // 4096 = LZW 码表上限
    private const val MAX_CODE_SIZE = 12
    private const val CHANNEL_R = 0
    private const val CHANNEL_G = 1
    private const val PALETTE_SAMPLE_LIMIT = 60_000

    /** 全局调色板 → 每帧索引 → GIF 字节 */
    fun encode(
        frames: Array<IntArray>,
        width: Int,
        height: Int,
        delayCs: IntArray,
        loopInfinite: Boolean = true
    ): ByteArray {
        require(frames.isNotEmpty()) { "GIF 至少需要 1 帧" }
        require(width > 0 && height > 0) { "GIF 尺寸非法：${width}x$height" }
        require(delayCs.size == frames.size) { "帧延时数量 ${delayCs.size} ≠ 帧数 ${frames.size}" }
        val pixelCount = width * height
        frames.forEachIndexed { i, f ->
            require(f.size == pixelCount) { "第 $i 帧像素数 ${f.size} ≠ $pixelCount" }
        }

        val palette = buildPalette(frames)
        val lookup = buildLookup(palette)
        val indexed = ByteArray(pixelCount)
        val error = FloatArray(pixelCount * 3)

        val out = ByteWriter(pixelCount + pixelCount / 2 + 4096)
        writeHeader(out, width, height, palette)
        writeLoopExtension(out, loopInfinite)
        frames.forEachIndexed { i, pixels ->
            mapFrame(pixels, width, height, palette, lookup, indexed, error)
            writeFrame(out, width, height, indexed, delayCs[i].coerceIn(1, 65535))
        }
        out.b(0x3B)
        return out.toByteArray()
    }

    // ── 调色板 ────────────────────────────────────────────────────

    /**
     * 中位切分建立全局色表：对全部帧按固定步长抽样（上限 [PALETTE_SAMPLE_LIMIT] 点），
     * 不足 256 项时用最后一项补齐 —— 无真机时「可复现」比「最优」重要。
     */
    fun buildPalette(frames: Array<IntArray>, sampleLimit: Int = PALETTE_SAMPLE_LIMIT): IntArray {
        val total = frames.sumOf { it.size }
        val stride = if (sampleLimit <= 0 || total <= sampleLimit) 1
        else (total + sampleLimit - 1) / sampleLimit

        val rgb = IntArray(maxOf((total + stride - 1) / stride, 1))
        val weight = IntArray(rgb.size)
        var written = 0
        var seen = 0
        for (f in frames) {
            for (px in f) {
                if (seen % stride == 0) {
                    rgb[written] = px and 0xFFFFFF
                    weight[written] = 1
                    written++
                }
                seen++
            }
        }

        val boxes = medianCut(rgb, weight, written, PALETTE_SIZE)
        val palette = IntArray(PALETTE_SIZE)
        for (i in boxes.indices) {
            val box = boxes[i]
            palette[i] = averageBox(rgb, weight, box.from, box.to)
        }
        if (boxes.isNotEmpty()) {
            for (i in boxes.size until PALETTE_SIZE) palette[i] = palette[boxes.size - 1]
        }
        return palette
    }

    private class Box(var from: Int, var to: Int)

    /**
     * 迭代式加权中位切分：每次挑「最长通道跨度 × 像素量」最大的箱子，
     * 在该通道加权中位数处劈开。返回若干 [from, to) 半开区间（rgb/weight 已被就地重排）。
     */
    private fun medianCut(
        rgb: IntArray,
        weight: IntArray,
        samples: Int,
        maxBoxes: Int
    ): List<Box> {
        val boxes = ArrayList<Box>()
        if (samples <= 0) return boxes
        boxes.add(Box(0, samples))
        while (boxes.size < maxBoxes) {
            var pick = -1
            var pickScore = 0L
            var pickChannel = CHANNEL_R
            for (b in boxes.indices) {
                val box = boxes[b]
                if (box.to - box.from < 2) continue
                val stat = boxStat(rgb, weight, box.from, box.to)
                val rSpan = stat[0]; val gSpan = stat[1]; val bSpan = stat[2]; val sum = stat[3]
                val spread = maxOf(rSpan, maxOf(gSpan, bSpan))
                if (spread <= 0) continue
                val channel = when (spread) {
                    rSpan -> CHANNEL_R
                    gSpan -> CHANNEL_G
                    else -> 2
                }
                val score = spread.toLong() * sum
                if (score > pickScore) {
                    pickScore = score; pick = b; pickChannel = channel
                }
            }
            if (pick < 0) break
            val box = boxes[pick]
            val mid = splitByChannel(rgb, weight, box.from, box.to, pickChannel)
            boxes.add(Box(mid, box.to))
            box.to = mid
        }
        return boxes
    }

    /** 返回 [rSpan, gSpan, bSpan, 权重和] */
    private fun boxStat(rgb: IntArray, weight: IntArray, from: Int, to: Int): IntArray {
        var rMin = 255; var rMax = 0
        var gMin = 255; var gMax = 0
        var bMin = 255; var bMax = 0
        var sum = 0
        for (i in from until to) {
            val c = rgb[i]
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            if (r < rMin) rMin = r
            if (r > rMax) rMax = r
            if (g < gMin) gMin = g
            if (g > gMax) gMax = g
            if (b < bMin) bMin = b
            if (b > bMax) bMax = b
            sum += weight[i]
        }
        return intArrayOf(rMax - rMin, gMax - gMin, bMax - bMin, sum)
    }

    /**
     * 按 [channel] 的加权中位色阶划分 [from, to)，返回分割点（必有 from < mid < to，
     * 前提是区间长度 ≥2）。中位数只保证「按像素量」平衡，落不到缝隙时退化成对半切。
     */
    private fun splitByChannel(
        rgb: IntArray,
        weight: IntArray,
        from: Int,
        to: Int,
        channel: Int
    ): Int {
        val hist = LongArray(256)
        var total = 0L
        for (i in from until to) {
            val v = channelValue(rgb[i], channel)
            hist[v] += weight[i].toLong()
            total += weight[i].toLong()
        }
        if (total <= 0L) return (from + to) / 2
        var acc = 0L
        var cut = -1
        for (v in 0 until 256) {
            acc += hist[v]
            if (acc * 2 >= total) { cut = v; break }
        }
        if (cut < 0) return (from + to) / 2

        var i = from
        var j = to - 1
        while (i <= j) {
            if (channelValue(rgb[i], channel) < cut) {
                i++
            } else {
                swap(rgb, weight, i, j)
                i++
                j--
            }
        }
        return if (i == from || i == to) (from + to) / 2 else i
    }

    private fun channelValue(c: Int, channel: Int): Int = when (channel) {
        CHANNEL_R -> (c shr 16) and 0xFF
        CHANNEL_G -> (c shr 8) and 0xFF
        else -> c and 0xFF
    }

    private fun swap(rgb: IntArray, weight: IntArray, a: Int, b: Int) {
        if (a == b) return
        val t = rgb[a]; rgb[a] = rgb[b]; rgb[b] = t
        val w = weight[a]; weight[a] = weight[b]; weight[b] = w
    }

    private fun averageBox(rgb: IntArray, weight: IntArray, from: Int, to: Int): Int {
        var r = 0L; var g = 0L; var b = 0L; var n = 0L
        for (i in from until to) {
            val c = rgb[i]
            val w = weight[i].toLong()
            r += ((c shr 16) and 0xFF) * w
            g += ((c shr 8) and 0xFF) * w
            b += (c and 0xFF) * w
            n += w
        }
        if (n <= 0L) return 0
        val rr = (r / n).toInt().coerceIn(0, 255)
        val gg = (g / n).toInt().coerceIn(0, 255)
        val bb = (b / n).toInt().coerceIn(0, 255)
        return (rr shl 16) or (gg shl 8) or bb
    }

    /**
     * 32×32×32 最近色查表：把逐像素 O(256) 搜索换成 O(1)。
     *
     * 两遍构建。先让每个调色板色占住自己所在的 5-5-5 桶（同桶冲突时前者胜出），
     * 这样「画面里真实出现过的颜色」一定映射回自身 —— 只按桶心/桶角找最近色，
     * 纯色会被同桶内另一个略近的调色板色抢走，整块平涂（拼图底色、分隔条）就染脏了。
     * 剩下的空桶再按桶心填最近色，作为该桶的代表色更合理。
     */
    fun buildLookup(palette: IntArray): IntArray {
        val lut = IntArray(32 * 32 * 32)
        val taken = BooleanArray(lut.size)
        for (i in palette.indices) {
            val c = palette[i]
            val key = bucketKey((c shr 16) and 0xFF, (c shr 8) and 0xFF, c and 0xFF)
            if (!taken[key]) {
                taken[key] = true
                lut[key] = i
            }
        }
        for (r5 in 0 until 32) {
            // 空桶按桶心（角点 +4）取最近色
            val r = (r5 shl 3) + 4
            for (g5 in 0 until 32) {
                val g = (g5 shl 3) + 4
                for (b5 in 0 until 32) {
                    val b = (b5 shl 3) + 4
                    val key = (r5 shl 10) or (g5 shl 5) or b5
                    if (taken[key]) continue
                    var best = 0
                    var bestDist = Int.MAX_VALUE
                    for (i in palette.indices) {
                        val c = palette[i]
                        val dr = ((c shr 16) and 0xFF) - r
                        val dg = ((c shr 8) and 0xFF) - g
                        val db = (c and 0xFF) - b
                        val dist = dr * dr + dg * dg + db * db
                        if (dist < bestDist) { bestDist = dist; best = i }
                    }
                    lut[key] = best
                }
            }
        }
        return lut
    }

    private fun bucketKey(r: Int, g: Int, b: Int): Int =
        ((r shr 3) shl 10) or ((g shr 3) shl 5) or (b shr 3)

    /**
     * ARGB → 调色板下标，带 Floyd–Steinberg 误差扩散。
     *
     * [indexed]/[error] 由调用方复用；error 是行主序、每像素 3 个 float（R/G/B）。
     */
    fun mapFrame(
        pixels: IntArray,
        width: Int,
        height: Int,
        palette: IntArray,
        lookup: IntArray,
        indexed: ByteArray,
        error: FloatArray
    ) {
        error.fill(0f)
        val rowStride = width * 3
        for (y in 0 until height) {
            val rowBase = y * width * 3
            for (x in 0 until width) {
                val i = rowBase + x * 3
                val argb = pixels[y * width + x]
                val r = clamp255(((argb shr 16) and 0xFF) + error[i].toInt())
                val g = clamp255(((argb shr 8) and 0xFF) + error[i + 1].toInt())
                val b = clamp255((argb and 0xFF) + error[i + 2].toInt())
                val idx = lookup[((r shr 3) shl 10) or ((g shr 3) shl 5) or (b shr 3)]
                indexed[y * width + x] = idx.toByte()

                val pc = palette[idx]
                val er = r - ((pc shr 16) and 0xFF)
                val eg = g - ((pc shr 8) and 0xFF)
                val eb = b - (pc and 0xFF)

                if (x + 1 < width) {
                    error[i + 3] += er * 7f / 16f
                    error[i + 4] += eg * 7f / 16f
                    error[i + 5] += eb * 7f / 16f
                }
                if (y + 1 >= height) continue
                val below = i + rowStride
                if (x > 0) {
                    error[below - 3] += er * 3f / 16f
                    error[below - 2] += eg * 3f / 16f
                    error[below - 1] += eb * 3f / 16f
                }
                error[below] += er * 5f / 16f
                error[below + 1] += eg * 5f / 16f
                error[below + 2] += eb * 5f / 16f
                if (x + 1 < width) {
                    error[below + 3] += er / 16f
                    error[below + 4] += eg / 16f
                    error[below + 5] += eb / 16f
                }
            }
        }
    }

    // ── 字节布局 ──────────────────────────────────────────────────

    private fun writeHeader(out: ByteWriter, width: Int, height: Int, palette: IntArray) {
        out.ascii("GIF89a")
        out.le16(width)
        out.le16(height)
        // packed：全局色表=1 | 色深字段=7（8bit）| 色表大小字段=7 → 2^(7+1)=256 项
        out.b(0x80 or 0x70 or 0x07)
        out.b(0)   // 背景色下标（不写透明色，留 0）
        out.b(0)   // 像素宽高比（0 = 未指定）
        for (c in palette) {
            out.b((c shr 16) and 0xFF)
            out.b((c shr 8) and 0xFF)
            out.b(c and 0xFF)
        }
    }

    /** Netscape 2.0 应用扩展：循环次数（0 = 无限） */
    private fun writeLoopExtension(out: ByteWriter, loopInfinite: Boolean) {
        out.b(0x21); out.b(0xFF); out.b(0x0B)
        out.ascii("NETSCAPE2.0")
        out.b(0x03); out.b(0x01)
        out.le16(if (loopInfinite) 0 else 1)
        out.b(0x00)
    }

    private fun writeFrame(
        out: ByteWriter,
        width: Int,
        height: Int,
        indexed: ByteArray,
        delayCs: Int
    ) {
        // 图形控制扩展：disposal=1（不清理，下一帧整幅覆盖）、无透明色
        out.b(0x21); out.b(0xF9); out.b(0x04)
        out.b(1 shl 2)
        out.le16(delayCs)
        out.b(0x00)   // 透明色下标（透明度标志为 0 时该字节仍占位）
        out.b(0x00)   // 子块终止符

        // 图像描述符：全幅、无局部色表、非交错
        out.b(0x2C)
        out.le16(0); out.le16(0)
        out.le16(width); out.le16(height)
        out.b(0x00)

        out.b(MIN_CODE_SIZE)
        val packed = lzwCompress(indexed)
        var off = 0
        while (off < packed.size) {
            val len = minOf(255, packed.size - off)
            out.b(len)
            out.bytes(packed, off, len)
            off += len
        }
        out.b(0x00)   // 图像数据子块序列终止符
    }

    // ── LZW ───────────────────────────────────────────────────────

    /**
     * GIF 的 LZW：LSB-first 位打包，码长从 9bit 起按解码器同步规则涨到 12bit，
     * 码表写满 4096 就发 clear 复位。
     *
     * 最容易写错的是「什么时候加宽码长」。编码器在 emit 之后立刻登记新码字，
     * 解码器却要等读到下一个码字才把同一号条目补进表里 —— 编码器恒比解码器快一码。
     * 解码器在 `表长 == 1<<width` 时加宽，对应到这里是 `nextCode == (1<<width) + 1`。
     * 少算这一码，Pillow 会在 9→10bit 边界上直接把流读崩（帧 3 实测停在第 257 个码）。
     */
    fun lzwCompress(indices: ByteArray): ByteArray {
        val out = ByteWriter(indices.size + indices.size / 4 + 64)
        val bits = BitPacker(out)

        // 前缀码表：key = (prefix shl 8) or suffix，开放寻址，槽数为码表上限的 2 倍
        val mask = (MAX_CODE * 2) - 1
        val keys = IntArray(MAX_CODE * 2)
        val values = IntArray(MAX_CODE * 2)

        var codeSize = MIN_CODE_SIZE + 1
        var nextCode = FIRST_CODE

        fun resetTable() {
            keys.fill(-1)
            nextCode = FIRST_CODE
            codeSize = MIN_CODE_SIZE + 1
        }

        fun slot(key: Int): Int {
            var h = (key * 0x0151A7EB) and mask
            while (true) {
                val k = keys[h]
                if (k == -1 || k == key) return h
                h = (h + 1) and mask
            }
        }

        fun emit(code: Int) = bits.write(code, codeSize)

        resetTable()
        emit(CLEAR_CODE)
        var prefix = indices[0].toInt() and 0xFF
        for (i in 1 until indices.size) {
            val suffix = indices[i].toInt() and 0xFF
            val key = (prefix shl 8) or suffix
            val h = slot(key)
            if (keys[h] == key) {
                prefix = values[h]
                continue
            }
            emit(prefix)
            if (nextCode < MAX_CODE) {
                keys[h] = key
                values[h] = nextCode
                nextCode++
                if (nextCode > (1 shl codeSize) && codeSize < MAX_CODE_SIZE) codeSize++
            } else {
                emit(CLEAR_CODE)
                resetTable()
            }
            prefix = suffix
        }
        emit(prefix)
        emit(END_CODE)
        bits.flush()
        return out.toByteArray()
    }

    /** 小端位打包器（GIF 的 LZW 码字从最低位开始填） */
    private class BitPacker(private val out: ByteWriter) {
        private var bitBuf = 0
        private var bitCount = 0

        fun write(code: Int, size: Int) {
            bitBuf = bitBuf or ((code and ((1 shl size) - 1)) shl bitCount)
            bitCount += size
            while (bitCount >= 8) {
                out.b(bitBuf and 0xFF)
                bitBuf = bitBuf ushr 8
                bitCount -= 8
            }
        }

        fun flush() {
            if (bitCount > 0) {
                out.b(bitBuf and 0xFF)
                bitBuf = 0
                bitCount = 0
            }
        }
    }

    private fun clamp255(v: Int): Int = if (v < 0) 0 else if (v > 255) 255 else v

    /** 可增长的小端字节缓冲 */
    class ByteWriter(initialCapacity: Int = 8192) {
        private var buf = ByteArray(initialCapacity.coerceAtLeast(64))
        private var size = 0

        fun b(v: Int) {
            ensure(1)
            buf[size++] = (v and 0xFF).toByte()
        }

        fun le16(v: Int) {
            ensure(2)
            buf[size++] = (v and 0xFF).toByte()
            buf[size++] = ((v ushr 8) and 0xFF).toByte()
        }

        fun bytes(src: ByteArray, off: Int, len: Int) {
            ensure(len)
            System.arraycopy(src, off, buf, size, len)
            size += len
        }

        fun ascii(s: String) {
            for (c in s) b(c.code)
        }

        private fun ensure(extra: Int) {
            if (size + extra <= buf.size) return
            var cap = buf.size * 2
            while (cap < size + extra) cap *= 2
            buf = buf.copyOf(cap)
        }

        fun toByteArray(): ByteArray = buf.copyOf(size)
    }
}
