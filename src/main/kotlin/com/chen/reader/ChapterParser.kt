package com.chen.reader

import com.chen.reader.model.Chapter

object ChapterParser {
    private val chapterHeading = Regex(
        pattern = """(?im)^\s*((第[0-9零〇一二两三四五六七八九十百千万]{1,12}[章节回卷集部篇].*)|(chapter\s+\d+.*))\s*$""",
    )

    fun parse(content: String): List<Chapter> {
        val matches = chapterHeading.findAll(content).toList()
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
}

