package com.chen.reader.book

import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.Logger
import java.awt.Graphics2D
import java.awt.Image
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.lang.ref.SoftReference
import java.nio.file.Path
import java.util.LinkedHashMap
import java.util.zip.ZipFile
import javax.imageio.ImageIO

internal const val SVG_MIME = "image/svg+xml"

/** 位图缓存默认像素预算：约 64 MB（按 ARGB 每像素 4 字节折算约 16.7 M 像素） */
private const val DEFAULT_PIXEL_BUDGET = 16_000_000L

/** 原图（软引用）缓存的最大条目数；超了按访问顺序淘汰最久未用的 */
private const val MAX_FULL_CACHE_ENTRIES = 8

private val LOG = Logger.getInstance(EpubResources::class.java)

/**
 * 资源元信息。
 *
 * **只读文件头部即可获得，不做任何解码** —— 这是排版期确定图片高度的关键
 * （见 `docs/design-epub-c-grade.md` §8 硬性约束：排版高度不得依赖解码）。
 */
data class ResourceMeta(
    val resourceId: String,
    val mime: String,
    val intrinsicWidth: Int,
    val intrinsicHeight: Int,
    val byteSize: Long,
) {
    val isVector: Boolean get() = mime == SVG_MIME
}

/**
 * 书籍二进制资源（图片 / 字体等）的访问接口。
 *
 * 实现分两类：[EpubResources]（从 EPUB zip 按需取数）、[EmptyResources]（TXT 等无资源的书）。
 */
interface BookResources : Disposable {
    /** 只读元数据，绝不解码。用于排版期算高与占位框比例 */
    fun meta(resourceId: String): ResourceMeta?

    /**
     * 取到位图。`targetWidth <= 0` 表示"按原始尺寸返回"（灯箱高清用）。
     *
     * 返回 null 表示不支持或失败，调用方应绘制占位框。
     *
     * **两级缓存，必须分开存**（0.5.0「灯箱图片失真」的根因就是没分开）：
     * - `targetWidth > 0`：按显示宽度缩放的**小图**，走像素预算 LRU（见 [cache]）；
     * - `targetWidth <= 0`：**原图**，走软引用缓存（见 [fullCache]），不占常驻内存。
     *
     * 两者如果共用一份以 resourceId 为 key 的缓存，阅读区先用几百 px 解码并缓存，
     * 灯箱再请求原图就会命中那张小图，放大显示必然模糊。
     *
     * **必须在后台线程调用**（评审 H2：`ImageIO` 解码不能落在 EDT）。
     */
    fun rasterize(resourceId: String, targetWidth: Int): BufferedImage?

    /** 原始字节，供"另存为 / 用外部程序打开" */
    fun bytes(resourceId: String): ByteArray?

    /** 全部图片资源 id，供"图片列表"侧栏（可选增值） */
    fun imageIds(): List<String>
}

/** TXT 等没有内嵌资源的书籍使用的空实现。 */
object EmptyResources : BookResources {
    override fun meta(resourceId: String): ResourceMeta? = null

    override fun rasterize(resourceId: String, targetWidth: Int): BufferedImage? = null

    override fun bytes(resourceId: String): ByteArray? = null

    override fun imageIds(): List<String> = emptyList()

    override fun dispose() = Unit
}

/**
 * EPUB 里一个二进制资源的索引项。
 *
 * 由 `EpubBookLoader` 在解析 OPF manifest 时一次性算好（含宽高），
 * 之后 [EpubResources.meta] 无需再读文件。
 */
data class EpubResourceEntry(
    val resourceId: String,
    val zipPath: String,
    val mime: String,
    val byteSize: Long,
    val width: Int,
    val height: Int,
)

/**
 * EPUB 资源访问实现。
 *
 * 设计取舍：**不为长连接持有 ZipFile**。每次操作按需打开再关闭，
 * 避免 [Disposable] 未被及时 dispose 时把整个 EPUB 句柄一直占住；
 * 代价是每次读取要付一次 zip 头解析开销，但 [rasterize] 的结果会被缓存，
 * 同一张图不会被重复解码。
 */
class EpubResources(
    private val zipPath: Path,
    private val entries: Map<String, EpubResourceEntry>,
    private val pixelBudget: Long = DEFAULT_PIXEL_BUDGET,
) : BookResources {
    private val lock = Any()

    /**
     * **小图缓存**：按显示宽度缩放后的位图，按访问顺序淘汰、受像素预算约束。
     *
     * key 仍是 `resourceId` 一个维度，因为阅读区对同一张图的请求宽度基本固定；
     * 命中时若位图比本次请求宽度还窄，会重新解码替换（见 [rasterizeScaled]），
     * 否则窗口拉宽后正文里会一直显示被放大的糊图。
     */
    private val cache = LinkedHashMap<String, BufferedImage>(16, 0.75f, true)

    /**
     * **原图缓存**：`targetWidth <= 0` 的解码结果，用 [SoftReference] 持有。
     *
     * 原图动辄几百万像素，放进像素预算 LRU 会瞬间把小图全挤掉，
     * 所以用软引用单独一层：内存够就留着复用，不够时交给 GC 回收，不占常驻。
     * 这层**不参与** [cachedPixels] 记账——记账只管受预算约束的小图那一层。
     */
    private val fullCache = LinkedHashMap<String, SoftReference<BufferedImage>>(8, 0.75f, true)

    /** 只统计**小图缓存**占用的像素数；原图走软引用，不进预算。 */
    private var cachedPixels = 0L

    override fun meta(resourceId: String): ResourceMeta? {
        val entry = entries[resourceId] ?: return null
        return ResourceMeta(
            resourceId = entry.resourceId,
            mime = entry.mime,
            intrinsicWidth = entry.width,
            intrinsicHeight = entry.height,
            byteSize = entry.byteSize,
        )
    }

    override fun rasterize(resourceId: String, targetWidth: Int): BufferedImage? {
        val entry = entries[resourceId] ?: return null
        if (entry.mime == SVG_MIME || !isSupportedRasterMime(entry.mime)) {
            // 矢量与其它不支持的格式，留给后续 T60/T61 与降级策略处理（本批只做占位框）。
            return null
        }
        return if (targetWidth <= 0) {
            rasterizeFull(entry)
        } else {
            rasterizeScaled(entry, targetWidth)
        }
    }

    /** 小图档位：按显示宽度缩放，走像素预算 LRU。 */
    private fun rasterizeScaled(entry: EpubResourceEntry, targetWidth: Int): BufferedImage? {
        val resourceId = entry.resourceId
        // 请求宽度不需要超过原图宽度，否则缓存命中判断会永远不成立、每次都重解。
        val effectiveWidth = entry.width.takeIf { it > 0 }?.let { minOf(targetWidth, it) } ?: targetWidth

        synchronized(lock) { cache[resourceId] }
            // 缓存的图比这次要的宽度还窄 → 放大显示会糊，必须重新解码替换。
            ?.takeIf { it.width >= effectiveWidth }
            ?.let { return it }

        val decoded = decodeEntry(entry) ?: return null
        val scaled = if (decoded.width > targetWidth) {
            scaleToWidth(decoded, targetWidth)
        } else {
            decoded
        }
        publish(resourceId, scaled)
        return scaled
    }

    /** 原图档位：不缩放，走软引用缓存。 */
    private fun rasterizeFull(entry: EpubResourceEntry): BufferedImage? {
        val resourceId = entry.resourceId

        synchronized(lock) { fullCache[resourceId]?.get() }?.let { return it }

        // 小图缓存里如果已经是原图尺寸，直接复用，省一次解码。
        synchronized(lock) { cache[resourceId] }
            ?.takeIf { entry.width <= 0 || it.width >= entry.width }
            ?.let { return it }

        val decoded = decodeEntry(entry) ?: return null
        synchronized(lock) {
            fullCache[resourceId] = SoftReference(decoded)
            trimFullCacheLocked()
        }
        LOG.info(
            "EPUB 原图解码：resourceId=$resourceId 位图=${decoded.width}x${decoded.height} " +
                "内建=${entry.width}x${entry.height} mime=${entry.mime}",
        )
        return decoded
    }

    private fun decodeEntry(entry: EpubResourceEntry): BufferedImage? {
        val bytes = readZipEntry(entry.zipPath) ?: return null
        return runCatching { ImageIO.read(ByteArrayInputStream(bytes)) }.getOrNull()
    }

    override fun bytes(resourceId: String): ByteArray? {
        val entry = entries[resourceId] ?: return null
        return readZipEntry(entry.zipPath)
    }

    override fun imageIds(): List<String> {
        return entries.values
            .filter { it.mime.startsWith("image/") }
            .map { it.resourceId }
    }

    override fun dispose() {
        synchronized(lock) {
            cache.clear()
            fullCache.clear()
            cachedPixels = 0L
        }
    }

    /** 放入缓存并按像素预算淘汰最久未使用项。 */
    private fun publish(resourceId: String, image: BufferedImage) {
        synchronized(lock) {
            val previous = cache.put(resourceId, image)
            if (previous != null) {
                cachedPixels -= pixelsOf(previous)
            }
            cachedPixels += pixelsOf(image)
            while (cachedPixels > pixelBudget && cache.isNotEmpty()) {
                val eldest = cache.entries.firstOrNull() ?: break
                cache.remove(eldest.key)
                cachedPixels -= pixelsOf(eldest.value)
            }
        }
    }

    /**
     * 清理原图软引用缓存：先丢掉已被 GC 回收的条目，再按访问顺序淘汰到条目上限。
     *
     * 只做引用表清理，**不做像素记账**（原图不占像素预算）。
     */
    private fun trimFullCacheLocked() {
        val iterator = fullCache.entries.iterator()
        while (iterator.hasNext()) {
            if (iterator.next().value.get() == null) {
                iterator.remove()
            }
        }
        while (fullCache.size > MAX_FULL_CACHE_ENTRIES) {
            val eldest = fullCache.entries.firstOrNull() ?: break
            fullCache.remove(eldest.key)
        }
    }

    private fun pixelsOf(image: BufferedImage): Long {
        return image.width.toLong() * image.height.toLong()
    }

    private fun readZipEntry(entryPath: String): ByteArray? {
        return runCatching {
            ZipFile(zipPath.toFile()).use { zip ->
                val entry = zip.getEntry(entryPath) ?: return@runCatching null
                zip.getInputStream(entry).use { it.readBytes() }
            }
        }.getOrNull()
    }

    private fun scaleToWidth(source: BufferedImage, targetWidth: Int): BufferedImage {
        val targetHeight = (source.height.toDouble() * targetWidth / source.width.toDouble())
            .toInt()
            .coerceAtLeast(1)
        val scaled = BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_ARGB)
        val graphics: Graphics2D = scaled.createGraphics()
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            graphics.drawImage(
                source.getScaledInstance(targetWidth, targetHeight, Image.SCALE_SMOOTH),
                0,
                0,
                null,
            )
        } finally {
            graphics.dispose()
        }
        return scaled
    }

    private fun isSupportedRasterMime(mime: String): Boolean {
        return mime.equals("image/jpeg", ignoreCase = true) ||
            mime.equals("image/png", ignoreCase = true) ||
            mime.equals("image/gif", ignoreCase = true) ||
            mime.equals("image/bmp", ignoreCase = true) ||
            mime.equals("image/webp", ignoreCase = true)
    }
}

/**
 * 只读文件头部探测光栅图的内建宽高，**不解码、不分配 BufferedImage**。
 *
 * 供 `EpubBookLoader` 在建 `ImageBlock` 时一次性取尺寸，确保排版期高度可得。
 * 探测失败返回 null，由调用方回退到默认尺寸（仍不解码，满足 §8）。
 */
internal fun probeRasterSize(bytes: ByteArray, mime: String = ""): Pair<Int, Int>? {
    if (bytes.isEmpty()) {
        return null
    }
    val probed = probePng(bytes)
        ?: probeGif(bytes)
        ?: probeBmp(bytes)
        ?: probeRiff(bytes)
        ?: probeJpeg(bytes)
    return probed?.takeIf { it.first > 0 && it.second > 0 }
}

private fun probePng(bytes: ByteArray): Pair<Int, Int>? {
    val signature = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    if (bytes.size < 24 || !signature.indices.all { bytes[it] == signature[it] }) {
        return null
    }
    return Pair(readInt32(bytes, 16, bigEndian = true), readInt32(bytes, 20, bigEndian = true))
}

private fun probeGif(bytes: ByteArray): Pair<Int, Int>? {
    if (bytes.size < 10 || bytes[0] != 0x47.toByte() || bytes[1] != 0x49.toByte() || bytes[2] != 0x46.toByte()) {
        return null
    }
    return Pair(readInt16(bytes, 6, bigEndian = false), readInt16(bytes, 8, bigEndian = false))
}

private fun probeBmp(bytes: ByteArray): Pair<Int, Int>? {
    if (bytes.size < 26 || bytes[0] != 0x42.toByte() || bytes[1] != 0x4D.toByte()) {
        return null
    }
    return Pair(readInt32(bytes, 18, bigEndian = false), readInt32(bytes, 22, bigEndian = false))
}

/** WEBP：容器为 RIFF/WEBP，画布尺寸在 VP8 / VP8X 块里。 */
private fun probeRiff(bytes: ByteArray): Pair<Int, Int>? {
    if (bytes.size < 30) {
        return null
    }
    if (bytes[0] != 0x52.toByte() || bytes[1] != 0x49.toByte() || bytes[2] != 0x46.toByte() || bytes[3] != 0x46.toByte()) {
        return null
    }
    if (bytes[8] != 0x57.toByte() || bytes[9] != 0x45.toByte() || bytes[10] != 0x42.toByte() || bytes[11] != 0x50.toByte()) {
        return null
    }
    val chunk = String(bytes, 12, 4, Charsets.US_ASCII)
    return when (chunk) {
        "VP8 " -> {
            val width = readInt16(bytes, 26, bigEndian = false) and 0x3FFF
            val height = readInt16(bytes, 28, bigEndian = false) and 0x3FFF
            Pair(width, height)
        }

        "VP8X" -> {
            val width = readInt24(bytes, 24) + 1
            val height = readInt24(bytes, 27) + 1
            Pair(width, height)
        }

        else -> null
    }
}

/** JPEG：跳过段前缀找 SOFn 标记，宽高在标记后的第 5~8 字节。 */
private fun probeJpeg(bytes: ByteArray): Pair<Int, Int>? {
    if (bytes.size < 4 || bytes[0] != 0xFF.toByte() || bytes[1] != 0xD8.toByte()) {
        return null
    }
    var position = 2
    while (position + 9 < bytes.size) {
        if (bytes[position] != 0xFF.toByte()) {
            position++
            continue
        }
        val marker = bytes[position + 1].toInt() and 0xFF
        if (marker == 0xD8 || marker == 0x01 || (marker in 0xD0..0xD7)) {
            position += 2
            continue
        }
        if (marker == 0xD9 || marker == 0xDA) {
            return null
        }
        val segmentLength = readInt16(bytes, position + 2, bigEndian = true)
        if (segmentLength < 2) {
            return null
        }
        val isStartOfFrame = marker == 0xC0 || marker == 0xC1 || marker == 0xC2 || marker == 0xC3 ||
            marker == 0xC5 || marker == 0xC6 || marker == 0xC7 ||
            marker == 0xC9 || marker == 0xCA || marker == 0xCB ||
            marker == 0xCD || marker == 0xCE || marker == 0xCF
        if (isStartOfFrame) {
            val height = readInt16(bytes, position + 5, bigEndian = true)
            val width = readInt16(bytes, position + 7, bigEndian = true)
            return Pair(width, height)
        }
        position += 2 + segmentLength
    }
    return null
}

private fun readInt16(bytes: ByteArray, offset: Int, bigEndian: Boolean): Int {
    if (offset + 1 >= bytes.size) {
        return 0
    }
    val low = bytes[offset].toInt() and 0xFF
    val high = bytes[offset + 1].toInt() and 0xFF
    return if (bigEndian) (low shl 8) or high else (high shl 8) or low
}

private fun readInt24(bytes: ByteArray, offset: Int): Int {
    if (offset + 2 >= bytes.size) {
        return 0
    }
    val first = bytes[offset].toInt() and 0xFF
    val second = bytes[offset + 1].toInt() and 0xFF
    val third = bytes[offset + 2].toInt() and 0xFF
    return first or (second shl 8) or (third shl 16)
}

private fun readInt32(bytes: ByteArray, offset: Int, bigEndian: Boolean): Int {
    if (offset + 3 >= bytes.size) {
        return 0
    }
    val b0 = bytes[offset].toInt() and 0xFF
    val b1 = bytes[offset + 1].toInt() and 0xFF
    val b2 = bytes[offset + 2].toInt() and 0xFF
    val b3 = bytes[offset + 3].toInt() and 0xFF
    return if (bigEndian) {
        (b0 shl 24) or (b1 shl 16) or (b2 shl 8) or b3
    } else {
        (b3 shl 24) or (b2 shl 16) or (b1 shl 8) or b0
    }
}
