package com.chen.reader

import com.chen.reader.book.EmptyResources
import com.chen.reader.model.Book
import com.chen.reader.model.LineStyle
import com.chen.reader.model.TextBlock
import java.io.InputStreamReader
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

object TxtBookLoader {
    private val fallbackCharsets = listOf(
        StandardCharsets.UTF_8,
        Charset.forName("GB18030"),
        Charset.forName("GBK"),
    )

    fun load(path: Path, preferredCharsetName: String? = null): Book {
        val charsets = buildList {
            preferredCharsetName
                ?.let { runCatching { Charset.forName(it) }.getOrNull() }
                ?.let(::add)
            addAll(fallbackCharsets)
        }.distinct()

        val errors = mutableListOf<String>()
        for (charset in charsets) {
            try {
                val content = readStrict(path, charset).removePrefix("\uFEFF")
                return Book(
                    path = path,
                    charset = charset,
                    // TXT 没有内嵌资源，正文整体就是一个 TextBlock：
                    // 块的区间覆盖全文，`plainText` 与旧实现的 `content` 完全一致。
                    blocks = listOf(TextBlock(0, content.length, content, LineStyle.BODY)),
                    chapters = ChapterParser.parse(content),
                    resources = EmptyResources,
                )
            } catch (error: CharacterCodingException) {
                errors += "${charset.name()}: ${error.message ?: "decode failed"}"
            }
        }

        error("无法识别 TXT 文件编码。已尝试: ${errors.joinToString("; ")}")
    }

    private fun readStrict(path: Path, charset: Charset): String {
        val decoder = charset
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)

        Files.newInputStream(path).use { input ->
            InputStreamReader(input, decoder).use { reader ->
                return reader.readText()
            }
        }
    }
}

