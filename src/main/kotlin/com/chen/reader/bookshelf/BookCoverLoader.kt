package com.chen.reader.bookshelf

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.security.MessageDigest
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.zip.ZipFile
import javax.imageio.ImageIO
import javax.swing.Icon
import javax.swing.ImageIcon

private val LOG = Logger.getInstance(BookCoverLoader::class.java)

/** 后台解析的产出：封面图标 + 顺手带回来的 EPUB 真实书名。 */
data class CoverResult(
    val pathKey: String,
    val icon: Icon?,
    /** `dc:title`；只有本次真正解了 OPF 才有值（磁盘缓存命中时为 null） */
    val title: String?,
)

/**
 * 封面加载器（应用级）：磁盘缩略图缓存 + 内存 LRU + **单线程**后台队列 + `invokeLater` 回填。
 *
 * 三条硬纪律：
 *
 * 1. **EDT 零 IO** —— 所有 `ZipFile` / `ImageIO` / 文件读写都在 [executor] 里；
 *    [coverFor] 只查内存，`request` 只提交任务。
 * 2. **负结果也缓存**（`memory` 的 value 可以是 `null`）—— 否则"确认没有封面"这件事
 *    每次打开书架都要重解一遍 OPF。
 * 3. **任何异常只降级为"无封面"**，绝不向上抛。
 */
@Service(Service.Level.APP)
class BookCoverLoader : Disposable {
    /** 磁盘缩略图最长边；UI 只显示 64×96，320 px 在 2× HiDPI 下仍清晰。 */
    private val memory: MutableMap<String, Icon?> = object : LinkedHashMap<String, Icon?>(MAX_CACHED_ICONS + 1, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Icon?>?): Boolean = size > MAX_CACHED_ICONS
    }

    /** 已提交但还没回填的 `pathKey`，防止同一本书被重复排队。 */
    private val pending: MutableSet<String> = HashSet()

    @Volatile
    private var disposed: Boolean = false

    private val executor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "novel-reader-cover").apply { isDaemon = true }
    }

    /** 缩略图目录：`system/novel-reader/covers/`。可再生派生数据，不放 `config/`。 */
    private val cacheDir: Path = Paths.get(PathManager.getSystemPath(), "novel-reader", "covers")

    init {
        // 目录治理也放后台：服务构造可能在 EDT 上，列目录属于 IO。
        executor.execute { CoverCache.trim(cacheDir, MAX_CACHE_FILES, TRIM_TO_FILES) }
    }

    /** 内存里已确认的封面；`null` = 已确认这本书没有封面。只看内存，绝不做 IO。 */
    fun coverFor(pathKey: String): Icon? = memory[pathKey]

    /** 是否已经探测过（含"确认没有封面"）。 */
    fun isKnown(pathKey: String): Boolean = memory.containsKey(pathKey)

    /**
     * 请求封面。命中内存（含负结果）时**同步**回调一次 [onReady]；否则提交后台队列，
     * 完成后在 EDT 回调。
     *
     * @param onReady 在 EDT 上执行，参数是 [CoverResult]
     */
    fun request(entry: ShelfEntry, onReady: (CoverResult) -> Unit) {
        if (disposed) {
            return
        }
        val key = entry.pathKey
        if (memory.containsKey(key)) {
            onReady(CoverResult(key, memory[key], null))
            return
        }
        if (!pending.add(key)) {
            return
        }
        executor.execute {
            val result = runCatching { resolveCover(entry) }
                .onFailure { LOG.warn("书架封面解析失败：${entry.path}", it) }
                .getOrElse { CoverResult(key, null, null) }
            ApplicationManager.getApplication().invokeLater {
                // disposed 检查必须在 invokeLater **里面**：应用可能已经关了。
                if (disposed) {
                    return@invokeLater
                }
                pending.remove(key)
                memory[key] = result.icon
                onReady(result)
            }
        }
    }

    /** 后台线程：查磁盘缓存 → 解 OPF → 取封面 → 缩放 → 写 PNG。 */
    private fun resolveCover(entry: ShelfEntry): CoverResult {
        val key = entry.pathKey
        val path = runCatching { Path.of(entry.path) }.getOrNull()
            ?: return CoverResult(key, null, null)
        if (!Files.isRegularFile(path)) {
            return CoverResult(key, null, null)
        }
        val size = runCatching { Files.size(path) }.getOrElse { 0L }
        val mtime = runCatching { Files.getLastModifiedTime(path).toMillis() }.getOrElse { 0L }
        val cacheFile = CoverCache.cacheFile(cacheDir, entry.path, size, mtime)

        // 磁盘命中：只读 PNG，不碰 zip。
        CoverCache.readPng(cacheFile)?.let { return CoverResult(key, ImageIcon(it), null) }

        if (entry.format != "epub") {
            return CoverResult(key, null, null)
        }
        val meta = EpubMetaReader.read(path) ?: return CoverResult(key, null, null)
        val coverZipPath = meta.coverZipPath ?: return CoverResult(key, null, meta.title)

        val bytes = readZipEntry(path, coverZipPath)
        val source = bytes?.let { runCatching { ImageIO.read(ByteArrayInputStream(it)) }.getOrNull() }
        val thumb = source?.let { CoverCache.scaleToMaxEdge(it, COVER_MAX_EDGE) }
        if (thumb != null) {
            CoverCache.writePng(cacheFile, thumb)
        }
        return CoverResult(key, thumb?.let { ImageIcon(it) }, meta.title)
    }

    private fun readZipEntry(path: Path, entryName: String): ByteArray? = runCatching {
        ZipFile(path.toFile()).use { zip ->
            val entry = zip.getEntry(entryName) ?: return@runCatching null
            zip.getInputStream(entry).readBytes()
        }
    }.getOrNull()

    override fun dispose() {
        disposed = true
        executor.shutdownNow()
        synchronized(memory) { memory.clear() }
        pending.clear()
    }

    companion object {
        const val COVER_MAX_EDGE = 320
        const val MAX_CACHED_ICONS = 32
        const val MAX_CACHE_FILES = 300
        const val TRIM_TO_FILES = 200

        fun getInstance(): BookCoverLoader =
            ApplicationManager.getApplication().getService(BookCoverLoader::class.java)
    }
}

/**
 * 封面缓存的纯工具：文件名指纹、缩略图缩放、目录治理。
 *
 * 不依赖 IntelliJ 运行时（`BookCoverLoader` 负责把 `PathManager` 算出来的根目录传进来），
 * 因此可以用临时探针单独验证。
 */
internal object CoverCache {
    /** 文件名指纹：`sha1(path)_size_mtime.png` —— 书被替换 / 重新导出后自动失效。 */
    fun cacheFileName(path: String, size: Long, mtime: Long): String =
        "${sha1Hex(path)}_${size}_$mtime.png"

    fun cacheFile(root: Path, path: String, size: Long, mtime: Long): Path =
        root.resolve(cacheFileName(path, size, mtime))

    fun sha1Hex(text: String): String {
        val digest = MessageDigest.getInstance("SHA-1").digest(text.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { byte -> "%02x".format(byte) }
    }

    /** 等比缩放到最长边不超过 [maxEdge]；不放大。返回 null 表示源图尺寸非法。 */
    fun scaleToMaxEdge(source: BufferedImage, maxEdge: Int): BufferedImage? {
        val width = source.width
        val height = source.height
        if (width <= 0 || height <= 0 || maxEdge <= 0) {
            return null
        }
        val longest = maxOf(width, height)
        if (longest <= maxEdge) {
            return source
        }
        val scale = maxEdge.toDouble() / longest.toDouble()
        val targetWidth = (width * scale).toInt().coerceAtLeast(1)
        val targetHeight = (height * scale).toInt().coerceAtLeast(1)
        val target = BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_ARGB)
        val graphics = target.createGraphics()
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        graphics.drawImage(source, 0, 0, targetWidth, targetHeight, null)
        graphics.dispose()
        return target
    }

    fun readPng(file: Path): BufferedImage? = runCatching {
        if (!Files.isRegularFile(file)) {
            return@runCatching null
        }
        ImageIO.read(file.toFile())
    }.getOrNull()

    fun writePng(file: Path, image: BufferedImage): Boolean = runCatching {
        Files.createDirectories(file.parent)
        ImageIO.write(image, "png", file.toFile())
    }.getOrDefault(false)

    /** 目录超过 [maxFiles] 时按 mtime 删到 [keep]。 */
    fun trim(root: Path, maxFiles: Int, keep: Int) {
        val files = runCatching {
            if (!Files.isDirectory(root)) {
                return@runCatching emptyList<Path>()
            }
            Files.list(root).use { stream ->
                stream.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".png") }.toList()
            }
        }.getOrDefault(emptyList())
        if (files.size <= maxFiles) {
            return
        }
        val sorted = files.mapNotNull { file ->
            runCatching { file to Files.getLastModifiedTime(file).toMillis() }.getOrNull()
        }.sortedBy { it.second }
        val dropCount = sorted.size - keep
        sorted.take(dropCount).forEach { (file, _) -> runCatching { Files.deleteIfExists(file) } }
    }
}
