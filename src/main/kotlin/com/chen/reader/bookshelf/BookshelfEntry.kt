package com.chen.reader.bookshelf

import java.nio.file.Path
import kotlin.io.path.extension

/**
 * 书架里的一本书。
 *
 * **只存可序列化字段**：封面 `Icon` 等运行时态一律放 [BookCoverLoader]，不进持久化。
 *
 * 用**普通 class + `var` 默认值**而不是 data class，是为了让 `XmlSerializer` 在
 * 遇到旧版本缺字段的 XML 时"取默认值"，而不是"反序列化失败 → IDE 启动崩溃"。
 * 应用级 state 在启动期加载，这里崩了整个 IDE 都起不来，所以容错优先级最高。
 */
class ShelfEntry {
    /** 绝对路径原样（用于打开、tooltip 展示） */
    var path: String = ""

    /** 归一化键：见 [ShelfRules.pathKeyOf]，用于 Map / 去重 / 封面缓存 */
    var pathKey: String = ""

    /** 书名：EPUB 后台补 `dc:title`，补不到就是文件名去扩展名 */
    var title: String = ""

    /** `"txt"` / `"epub"` */
    var format: String = ""

    var charsetName: String = ""

    /** 最后阅读时间（epoch millis）；`0` = 未知（播种数据） */
    var lastReadMillis: Long = 0L

    var favorite: Boolean = false

    /** 全书字符数（= `Book.plainText.length`）；`0` = 未知 → 进度显示 "—" */
    var totalLength: Int = 0

    /** 阅读位置：与 `ReaderState` 同名同义同口径 */
    var globalOffset: Int = 0

    var anchorText: String = ""

    var chapterIndex: Int = 0

    var progressInChapterPermille: Int = 0

    /**
     * 进度百分比；分母未知（`totalLength == 0`，播种数据）返回 `null` → 卡片显示 `—`。
     *
     * 中间结果走 `Long`：大部头 `globalOffset * 100` 会超出 `Int` 范围。
     */
    fun percent(): Int? {
        if (totalLength <= 0) {
            return null
        }
        val value = (globalOffset.toLong().coerceAtLeast(0L) * 100L / totalLength.toLong()).toInt()
        return value.coerceIn(0, 100)
    }

    /**
     * 把项目级 `ReaderState` 里的最新位置拷进本条目（**纯内存赋值**，不排序、不落盘）。
     *
     * 与 [percent] 一样是纯函数语义，便于脱离 IDE 用探针验证"快照合并"逻辑。
     */
    fun applyPosition(
        globalOffset: Int,
        anchorText: String,
        chapterIndex: Int,
        progressInChapterPermille: Int,
    ) {
        this.globalOffset = globalOffset.coerceAtLeast(0)
        this.anchorText = anchorText
        this.chapterIndex = chapterIndex.coerceAtLeast(0)
        this.progressInChapterPermille = progressInChapterPermille.coerceIn(0, 1000)
    }
}

/** 应用级持久化容器，落 `<IDE config>/options/bookshelf.xml`。 */
class BookshelfState {
    var version: Int = 1

    /** 是否已从项目级旧状态播种过（幂等标记） */
    var seeded: Boolean = false

    var books: MutableList<ShelfEntry> = mutableListOf()
}

/**
 * 书架数据层的**纯函数**规则集：清洗、排序、上限、路径键。
 *
 * 全部不依赖 IntelliJ 运行时，可以直接用临时探针验证（见 0.11.0 开发记录）。
 * `BookshelfService` 只做"IDE 接线"，真正的口径都在这里，保证口径唯一。
 */
object ShelfRules {
    /** 历史上限；淘汰时**跳过收藏条目**。 */
    const val MAX_RECENT = 50

    /** 与 `BookLoader.supportedExtensions` 一致的白名单。 */
    val ALLOWED_FORMATS: Set<String> = setOf("txt", "epub")

    private const val WINDOWS = "windows"

    /**
     * 路径键的**唯一**实现：`abs.normalize().toString()`，Windows 上再 lowercase
     * （Windows 文件系统大小写不敏感，`C:\A.epub` 与 `c:\a.epub` 是同一本书）。
     *
     * 所有 Map 查找 / 去重 / 封面缓存 key / 收藏与移除的入参都必须用它，
     * 任何地方都不许自己拼字符串 —— 口径不统一会导致"收藏点了没反应"。
     */
    fun pathKeyOf(path: Path): String {
        val normalized = runCatching { path.toAbsolutePath().normalize().toString() }
            .getOrElse { path.toString() }
        return if (System.getProperty("os.name").orEmpty().contains(WINDOWS, ignoreCase = true)) {
            normalized.lowercase()
        } else {
            normalized
        }
    }

    /** 文件扩展名小写；取不到返回 `""`（调用方按白名单丢弃）。 */
    fun formatOf(path: Path): String = runCatching { path.extension.lowercase() }.getOrDefault("")

    /**
     * 清洗：丢掉不可用的条目，钳住越界数值，按 `pathKey` 去重（保留最近阅读的），
     * 最后截到 [MAX_RECENT]（收藏条目优先保留）。
     *
     * **永不抛异常**（内部逐字段 `runCatching`），保证坏数据只降级、不让 IDE 启动失败。
     */
    fun sanitize(books: Iterable<ShelfEntry>, nowMillis: Long = System.currentTimeMillis()): List<ShelfEntry> {
        val kept = LinkedHashMap<String, ShelfEntry>()
        books.forEach { raw ->
            val entry = raw
            val path = entry.path.trim()
            if (path.isEmpty()) {
                return@forEach
            }
            if (entry.format !in ALLOWED_FORMATS) {
                return@forEach
            }
            val key = entry.pathKey.trim().ifEmpty { runCatching { pathKeyOf(Path.of(path)) }.getOrElse { "" } }
            if (key.isEmpty()) {
                return@forEach
            }

            entry.path = path
            entry.pathKey = key
            if (entry.lastReadMillis < 0L || entry.lastReadMillis > nowMillis) {
                entry.lastReadMillis = if (entry.lastReadMillis < 0L) 0L else nowMillis
            }
            if (entry.totalLength < 0) {
                entry.totalLength = 0
            }
            if (entry.globalOffset < 0) {
                entry.globalOffset = 0
            }
            if (entry.chapterIndex < 0) {
                entry.chapterIndex = 0
            }
            if (entry.progressInChapterPermille !in 0..1000) {
                entry.progressInChapterPermille = entry.progressInChapterPermille.coerceIn(0, 1000)
            }

            val existing = kept[key]
            if (existing == null || entry.lastReadMillis >= existing.lastReadMillis) {
                kept[key] = entry
            }
        }
        return limit(kept.values)
    }

    /** 按 `lastReadMillis` 倒序取前 [limit] 条；含收藏的书。 */
    fun recent(books: Iterable<ShelfEntry>, limit: Int = MAX_RECENT): List<ShelfEntry> =
        books.sortedByDescending { it.lastReadMillis }.take(limit.coerceAtLeast(0))

    /** 收藏子集，按 `lastReadMillis` 倒序（不新增"收藏时间"字段）。 */
    fun favorites(books: Iterable<ShelfEntry>): List<ShelfEntry> =
        books.filter { it.favorite }.sortedByDescending { it.lastReadMillis }

    /** 收藏优先保留，其余按最近阅读倒序填满 [MAX_RECENT]。 */
    fun limit(books: Iterable<ShelfEntry>): List<ShelfEntry> {
        val sorted = books.sortedByDescending { it.lastReadMillis }
        val favorites = sorted.filter { it.favorite }
        val others = sorted.filterNot { it.favorite }.take((MAX_RECENT - favorites.size).coerceAtLeast(0))
        return (favorites + others).sortedByDescending { it.lastReadMillis }
    }
}
