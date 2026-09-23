package com.chen.reader.ui.bookshelf

import com.chen.reader.bookshelf.ShelfEntry
import com.chen.reader.bookshelf.ShelfRules
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 书架的格式化纯函数：路径键 / 相对时间 / 进度 / 显示书名。
 *
 * 全部不依赖 Swing，也不依赖 IntelliJ 运行时（[isMissing] 只做一次 `Files.exists`，
 * 是 EDT 上唯一被允许的 IO），可以直接用临时探针验证。
 */
object ShelfFormat {
    private val TIME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    private val DAY_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("M月d日")
    private val FULL_DAY_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")

    private const val MINUTE_MILLIS = 60_000L
    private const val HOUR_MILLIS = 3_600_000L

    /** 路径键的**唯一**入口；实现在 [ShelfRules.pathKeyOf]，这里只是给 UI 层的同名转发。 */
    fun pathKeyOf(path: Path): String = ShelfRules.pathKeyOf(path)

    /** 文件名去扩展名，用作 EPUB 真实书名的兜底。 */
    fun titleFromFileName(path: Path): String {
        val name = runCatching { path.fileName?.toString() }.getOrNull()?.trim().orEmpty()
        if (name.isEmpty()) {
            return path.toString()
        }
        val dot = name.lastIndexOf('.')
        return if (dot > 0) name.substring(0, dot) else name
    }

    /**
     * 相对时间。口径见设计文档 §12.3：`刚刚` / `N 分钟前` / `今天 HH:mm` / `昨天 HH:mm` /
     * `M月d日`（同年）/ `yyyy-MM-dd`（跨年）；`millis <= 0` 表示未知 → `—`。
     *
     * 自然日一律用 `LocalDate` 比较（系统默认时区），不用毫秒差算"几天前"。
     */
    fun formatLastRead(millis: Long, nowMillis: Long = System.currentTimeMillis()): String {
        if (millis <= 0L) {
            return "—"
        }
        val diff = (nowMillis - millis).coerceAtLeast(0L)
        if (diff < MINUTE_MILLIS) {
            return "刚刚"
        }
        if (diff < HOUR_MILLIS) {
            return "${diff / MINUTE_MILLIS} 分钟前"
        }
        val zone = ZoneId.systemDefault()
        val time = LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), zone)
        val day = time.toLocalDate()
        val today = LocalDate.now(zone)
        return when {
            day == today -> "今天 ${TIME_FORMATTER.format(time)}"
            day == today.minusDays(1) -> "昨天 ${TIME_FORMATTER.format(time)}"
            day.year == today.year -> DAY_FORMATTER.format(day)
            else -> FULL_DAY_FORMATTER.format(day)
        }
    }

    /** 进度文本；`null`（分母未知）显示 `—` 而不是 `0%`，否则会误导"这本书你一页没读"。 */
    fun formatPercent(percent: Int?): String = percent?.let { "$it%" } ?: "—"

    /** 卡片上的书名；文件缺失时追加"（文件已不存在）"。 */
    fun displayTitle(entry: ShelfEntry, missing: Boolean): String {
        val base = entry.title.takeIf { it.isNotBlank() }
            ?: runCatching { titleFromFileName(Path.of(entry.path)) }.getOrDefault(entry.path)
        return if (missing) "$base（文件已不存在）" else base
    }

    /** 文件是否已被移动 / 删除。EDT 上唯一允许的 IO 就是这一下 `stat`。 */
    fun isMissing(entry: ShelfEntry): Boolean {
        val path = runCatching { Path.of(entry.path) }.getOrNull() ?: return true
        return runCatching { !Files.exists(path) }.getOrDefault(false)
    }
}
