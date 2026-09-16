package com.chen.reader

import com.chen.reader.model.Book
import java.nio.file.Path
import kotlin.io.path.extension

object BookLoader {
    val supportedExtensions = setOf("txt", "epub")

    fun load(path: Path, preferredCharsetName: String? = null): Book {
        return when (path.extension.lowercase()) {
            "txt" -> TxtBookLoader.load(path, preferredCharsetName)
            "epub" -> EpubBookLoader.load(path)
            else -> error("暂不支持该文件格式，请选择 TXT 或 EPUB 文件。")
        }
    }
}
