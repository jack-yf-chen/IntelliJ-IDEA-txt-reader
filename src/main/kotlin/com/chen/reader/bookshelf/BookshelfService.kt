package com.chen.reader.bookshelf

import com.chen.reader.ReaderState
import com.chen.reader.ReaderStateService
import com.chen.reader.ui.bookshelf.ShelfFormat
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.util.Alarm
import java.nio.file.Files
import java.nio.file.Path

private val LOG = Logger.getInstance(BookshelfService::class.java)

/**
 * 书架（应用级）：每本书一条记录，存 `<IDE config>/options/bookshelf.xml`。
 *
 * 为什么是应用级、而不是扩展项目级 `ReaderStateService`：书架是**用户资产**，
 * 换工程不该消失；而把 `ReaderStateService` 整体改成应用级是 breaking change，
 * 会丢 17 个阅读偏好（字体、字号、主题…）。所以这里是应用级**兄弟** service，
 * `ReaderStateService` 零改动。
 *
 * 落盘纪律：只有 [requestFlush] 允许调 `saveSettings()`，且走去抖（2 s 延迟 + 60 s 最小间隔）。
 * 滚动时的进度**只改内存**（[noteProgress]），靠 IDEA 自带的自动保存带上。
 */
@State(
    name = "NovelReaderBookshelf",
    storages = [Storage("bookshelf.xml")],
)
@Service(Service.Level.APP)
class BookshelfService : PersistentStateComponent<BookshelfState>, Disposable {
    private var state: BookshelfState = BookshelfState()

    private val flushAlarm: Alarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)

    @Volatile
    private var lastFlushAt: Long = 0L

    override fun getState(): BookshelfState = state

    /**
     * 反序列化 + **清洗**。应用级 state 在启动期加载，这里抛异常会让 IDE 起不来，
     * 所以整体 `runCatching`，坏数据只降级为空书架 + 一条 warn。
     */
    override fun loadState(state: BookshelfState) {
        this.state = runCatching {
            BookshelfState().apply {
                version = CURRENT_VERSION
                seeded = state.seeded
                books = ShelfRules.sanitize(state.books).toMutableList()
            }
        }.getOrElse { error ->
            LOG.warn("书架状态解析失败，已回退到空书架。", error)
            BookshelfState().apply {
                version = CURRENT_VERSION
                seeded = state.seeded
            }
        }
    }

    // ------------------------------------------------------------------ 查询

    /** 最近阅读（按 `lastReadMillis` 倒序，含收藏的书）。 */
    fun recent(limit: Int = ShelfRules.MAX_RECENT): List<ShelfEntry> = ShelfRules.recent(state.books, limit)

    /** 我的收藏（按 `lastReadMillis` 倒序）。 */
    fun favorites(): List<ShelfEntry> = ShelfRules.favorites(state.books)

    /** 全部条目（未排序）。 */
    fun all(): List<ShelfEntry> = state.books.toList()

    fun find(pathKey: String): ShelfEntry? = state.books.firstOrNull { it.pathKey == pathKey }

    // ------------------------------------------------------------------ 埋点

    /**
     * 埋点 1：**加载新书之前**，把**上一本**的位置从项目级 state 快照回书架。
     *
     * 顺序反了（先加载再快照）会导致"打开 B 之后 A 的进度永远停在上一次换页前"
     * —— 书架显示 30%、实际读到 80% 的那个坑就在这一行。
     */
    fun snapshotFromReaderState(project: Project) {
        ensureSeeded(project)
        val readerState = ReaderStateService.getInstance(project).state
        val entry = entryForReaderState(readerState) ?: return
        entry.applyPosition(
            globalOffset = readerState.globalOffset,
            anchorText = readerState.anchorText,
            chapterIndex = readerState.chapterIndex,
            progressInChapterPermille = readerState.progressInChapterPermille,
        )
        requestFlush()
    }

    /**
     * 埋点 2：加载成功之后，登记"打开过这本书"。
     *
     * upsert：新条目 `title` 先用文件名（EPUB 真实书名由 `BookCoverLoader` 后台补），
     * `lastReadMillis = now`，写 `totalLength / charsetName / format`，超过上限按
     * `lastReadMillis` 淘汰最旧的**非收藏**条目。
     */
    fun noteOpened(path: Path, totalLength: Int, charsetName: String) {
        val key = ShelfFormat.pathKeyOf(path)
        val format = ShelfRules.formatOf(path)
        if (format !in ShelfRules.ALLOWED_FORMATS) {
            return
        }
        val now = System.currentTimeMillis()
        val absolute = runCatching { path.toAbsolutePath().normalize().toString() }.getOrDefault(path.toString())
        val existing = find(key)
        if (existing == null) {
            state.books += ShelfEntry().apply {
                this.path = absolute
                this.pathKey = key
                this.title = ShelfFormat.titleFromFileName(path)
                this.format = format
                this.charsetName = charsetName
                this.lastReadMillis = now
                this.totalLength = totalLength.coerceAtLeast(0)
            }
        } else {
            existing.path = absolute
            existing.format = format
            existing.charsetName = charsetName
            existing.totalLength = totalLength.coerceAtLeast(0)
            existing.lastReadMillis = now
            if (existing.title.isBlank()) {
                existing.title = ShelfFormat.titleFromFileName(path)
            }
        }
        state.books = ShelfRules.limit(state.books).toMutableList()
        requestFlush()
    }

    /**
     * 进度同步：**只改内存**，不排序、不刷 UI、不落盘。
     *
     * 在 `saveReadingAnchor` 里落盘会让"滚一次 = 全量 `saveSettings()`"，直接把 IDE 卡出顿挫感；
     * 改内存对象则由 IDEA 的下一次自动保存自动带上，可靠性与现状（项目级 `ReaderState`）同级。
     */
    fun noteProgress(project: Project) {
        val readerState = ReaderStateService.getInstance(project).state
        val entry = entryForReaderState(readerState) ?: return
        entry.applyPosition(
            globalOffset = readerState.globalOffset,
            anchorText = readerState.anchorText,
            chapterIndex = readerState.chapterIndex,
            progressInChapterPermille = readerState.progressInChapterPermille,
        )
    }

    /** 后台补回来的 `dc:title` 只在"书名还是占位值"时才覆盖，避免冲掉用户改过的书名。 */
    fun applyMetaTitle(pathKey: String, title: String?): Boolean {
        val trimmed = title?.trim().orEmpty()
        if (trimmed.isEmpty()) {
            return false
        }
        val entry = find(pathKey) ?: return false
        val placeholder = runCatching { ShelfFormat.titleFromFileName(Path.of(entry.path)) }.getOrDefault("")
        if (entry.title.isNotBlank() && entry.title != placeholder) {
            return false
        }
        if (entry.title == trimmed) {
            return false
        }
        entry.title = trimmed
        requestFlush()
        return true
    }

    // ------------------------------------------------------------------ 操作

    fun setFavorite(pathKey: String, value: Boolean) {
        find(pathKey)?.favorite = value
        requestFlush()
    }

    /** 从书架移除（两个分区同时消失，因为数据只有一份）。 */
    fun remove(pathKey: String) {
        val before = state.books.size
        state.books.removeAll { it.pathKey == pathKey }
        if (state.books.size != before) {
            requestFlush()
        }
    }

    /** 清空历史：只删**未收藏**的条目，收藏的书保留。 */
    fun clearHistory() {
        val before = state.books.size
        state.books.removeAll { !it.favorite }
        if (state.books.size != before) {
            requestFlush()
        }
    }

    /**
     * 续读：把 [entry] 的位置铺进**项目级** [ReaderState]。
     *
     * 铺完之后 `ReaderPanel.openBook` 里的 `isSameBookPath(state.filePath, path)` 必然为 true，
     * 自动走现成的三级恢复分支（锚点 → offset → 章内千分比），所以 `ReaderPanel` 零改动。
     */
    fun applyToReaderState(state: ReaderState, entry: ShelfEntry) {
        state.filePath = entry.path
        state.charsetName = entry.charsetName.takeIf { it.isNotBlank() }
        state.globalOffset = entry.globalOffset
        state.anchorText = entry.anchorText
        state.chapterIndex = entry.chapterIndex
        state.progressInChapterPermille = entry.progressInChapterPermille
        state.scrollValue = 0
    }

    // ------------------------------------------------------------------ 播种

    /**
     * 一次性幂等播种：把当前工程项目级 state 里那本书生成 1 条记录。
     *
     * **只读**项目级 state，不写、不删、不改任何字段 —— 老用户升级后阅读位置与 17 个
     * 阅读偏好全部原样保留。`totalLength = 0` → 卡片进度显示 `—`（不显示 0%，
     * 否则会误导"这本书你一页没读"）。
     */
    fun seedFromProjectState(project: Project) {
        state.seeded = true
        val readerState = ReaderStateService.getInstance(project).state
        val rawPath = readerState.filePath?.takeIf { it.isNotBlank() } ?: return
        val path = runCatching { Path.of(rawPath) }.getOrNull() ?: return
        if (!runCatching { Files.exists(path) }.getOrDefault(false)) {
            return
        }
        val format = ShelfRules.formatOf(path)
        if (format !in ShelfRules.ALLOWED_FORMATS) {
            return
        }
        val key = ShelfFormat.pathKeyOf(path)
        if (find(key) != null) {
            return
        }
        state.books += ShelfEntry().apply {
            this.path = runCatching { path.toAbsolutePath().normalize().toString() }.getOrDefault(rawPath)
            this.pathKey = key
            this.title = ShelfFormat.titleFromFileName(path)
            this.format = format
            this.charsetName = readerState.charsetName.orEmpty()
            // 没有真实阅读时间，用文件 mtime 兜底。
            this.lastReadMillis = runCatching { Files.getLastModifiedTime(path).toMillis() }
                .getOrElse { System.currentTimeMillis() }
            this.totalLength = 0
            this.globalOffset = readerState.globalOffset
            this.anchorText = readerState.anchorText
            this.chapterIndex = readerState.chapterIndex
            this.progressInChapterPermille = readerState.progressInChapterPermille
        }
        requestFlush()
    }

    /**
     * 确保播种跑过（幂等）。
     *
     * 应用级 service 构造时拿不到 `Project`（播种要读项目级旧 state），
     * 所以改为在**第一次有机会碰到项目**的时候调用：`openBook` 埋点 1，
     * 以及书架 Tab 的 `refresh()`（用户升级后可能直接切到书架而不开书）。
     */
    fun ensureSeeded(project: Project) {
        if (state.seeded) {
            return
        }
        if (state.books.isNotEmpty()) {
            state.seeded = true
            return
        }
        seedFromProjectState(project)
    }

    // ------------------------------------------------------------------ 落盘

    /**
     * 去抖落盘：2 s 延迟 + 60 s 最小间隔。
     *
     * 用 `Alarm` 而不是 `javax.swing.Timer`：前者随 `Disposable` 自动取消，
     * 不会在应用关闭后还在排队。只在 SWING 线程调 `saveSettings()`，避免锁竞争。
     */
    private fun requestFlush() {
        flushAlarm.cancelAllRequests()
        flushAlarm.addRequest({
            val now = System.currentTimeMillis()
            if (now - lastFlushAt < MIN_FLUSH_INTERVAL_MS) {
                return@addRequest
            }
            lastFlushAt = now
            ApplicationManager.getApplication().saveSettings()
        }, FLUSH_DELAY_MS)
    }

    override fun dispose() {
        flushAlarm.cancelAllRequests()
    }

    private fun entryForReaderState(readerState: ReaderState): ShelfEntry? {
        val rawPath = readerState.filePath?.takeIf { it.isNotBlank() } ?: return null
        val path = runCatching { Path.of(rawPath) }.getOrNull() ?: return null
        return find(ShelfFormat.pathKeyOf(path))
    }

    companion object {
        private const val CURRENT_VERSION = 1
        private const val FLUSH_DELAY_MS = 2_000
        private const val MIN_FLUSH_INTERVAL_MS = 60_000

        fun getInstance(): BookshelfService =
            ApplicationManager.getApplication().getService(BookshelfService::class.java)
    }
}
