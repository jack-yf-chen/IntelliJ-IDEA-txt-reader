package com.chen.reader

import com.chen.reader.model.Chapter

object ChapterParser {
    private val chapterHeading = Regex(
        pattern = """(?im)^\s*((第[0-9零〇一二两三四五六七八九十百千万]{1,12}[章节回卷集部篇][^\r\n]*)|(chapter\s+\d+[^\r\n]*))\s*$""",
    )
    private val chineseChapterPrefix = Regex("""^第[0-9零〇一二两三四五六七八九十百千万]{1,12}[章节回卷集部篇]""")
    private val sentencePunctuation = Regex("""[。！？；，,]""")
    private val narrativeConnectorAfterMarker = Regex("""^第[0-9零〇一二两三四五六七八九十百千万]{1,12}[章节回卷集部篇][中里内时后前上下一二三四五六七八九十]""")

    fun parse(content: String): List<Chapter> {
        val matches = chapterHeading.findAll(content)
            .filter { isChapterTitle(it.value.trim()) }
            .toList()
        if (matches.isEmpty()) {
            return listOf(Chapter("全文", 0, content.length))
        }

        return matches.mapIndexed { index, match ->
            val nextStart = matches.getOrNull(index + 1)?.range?.first ?: content.length
            val title = match.value.trim().ifBlank { "第 ${index + 1} 章" }
            Chapter(
                title = title.take(80),
                startOffset = match.range.first,
                endOffset = nextStart,
            )
        }
    }

    private fun isChapterTitle(title: String): Boolean {
        if (title.length > MAX_TITLE_LENGTH) {
            return false
        }
        if (sentencePunctuation.containsMatchIn(title)) {
            return false
        }
        if (narrativeConnectorAfterMarker.containsMatchIn(title)) {
            return false
        }

        val prefix = chineseChapterPrefix.find(title)?.value ?: return true
        val suffix = title.removePrefix(prefix).trim()
        return suffix.isEmpty() || suffix.length <= MAX_CHINESE_TITLE_SUFFIX_LENGTH
    }

    private const val MAX_TITLE_LENGTH = 40
    private const val MAX_CHINESE_TITLE_SUFFIX_LENGTH = 24
}
