package com.chen.reader.model

import java.nio.charset.Charset
import java.nio.file.Path

data class Book(
    val path: Path,
    val content: String,
    val charset: Charset,
    val chapters: List<Chapter>,
)

data class Chapter(
    val title: String,
    val startOffset: Int,
    val endOffset: Int,
) {
    override fun toString(): String = title
}

