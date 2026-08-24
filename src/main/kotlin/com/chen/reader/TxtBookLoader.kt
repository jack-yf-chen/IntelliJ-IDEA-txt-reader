package com.chen.reader

import com.chen.reader.model.Book
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
                    content = content,
                    charset = charset,
                    chapters = ChapterParser.parse(content),
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

