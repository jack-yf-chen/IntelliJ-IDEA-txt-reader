package com.chen.reader

import com.google.gson.stream.JsonReader
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets

object LocalDictionary {
    private val data: DictionaryData by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        DictionaryData(
            words = loadWords(),
            phrases = loadPhrases(),
        )
    }

    fun lookup(text: String): DictionaryResult? {
        val query = text.trim()
        if (query.isBlank()) {
            return null
        }

        data.phrases[query]?.let { explanation ->
            return DictionaryResult(
                title = query,
                body = "类型：词语\n\n释义：\n${explanation.limitForDialog()}",
            )
        }

        data.words[query]?.let { entry ->
            return DictionaryResult(
                title = query,
                body = buildString {
                    appendLine("类型：汉字")
                    appendLine("拼音：${entry.pinyin.ifBlank { "无" }}")
                    appendLine("部首：${entry.radicals.ifBlank { "无" }}")
                    appendLine("笔画：${entry.strokes.ifBlank { "无" }}")
                    appendLine()
                    appendLine("释义：")
                    append(entry.explanation.limitForDialog())
                },
            )
        }

        return null
    }

    private fun loadPhrases(): Map<String, String> {
        val result = HashMap<String, String>(270_000)
        jsonReader("/dictionary/ci.json").use { reader ->
            reader.beginArray()
            while (reader.hasNext()) {
                var phrase = ""
                var explanation = ""
                reader.beginObject()
                while (reader.hasNext()) {
                    when (reader.nextName()) {
                        "ci" -> phrase = reader.nextString().trim()
                        "explanation" -> explanation = reader.nextString().trim()
                        else -> reader.skipValue()
                    }
                }
                reader.endObject()

                if (phrase.isNotBlank() && explanation.isNotBlank()) {
                    result.putIfAbsent(phrase, explanation)
                }
            }
            reader.endArray()
        }
        return result
    }

    private fun loadWords(): Map<String, WordEntry> {
        val result = HashMap<String, WordEntry>(18_000)
        jsonReader("/dictionary/word.json").use { reader ->
            reader.beginArray()
            while (reader.hasNext()) {
                var word = ""
                var strokes = ""
                var pinyin = ""
                var radicals = ""
                var explanation = ""
                reader.beginObject()
                while (reader.hasNext()) {
                    when (reader.nextName()) {
                        "word" -> word = reader.nextString().trim()
                        "strokes" -> strokes = reader.nextString().trim()
                        "pinyin" -> pinyin = reader.nextString().trim()
                        "radicals" -> radicals = reader.nextString().trim()
                        "explanation" -> explanation = reader.nextString().trim()
                        else -> reader.skipValue()
                    }
                }
                reader.endObject()

                if (word.isNotBlank()) {
                    result.putIfAbsent(
                        word,
                        WordEntry(
                            strokes = strokes,
                            pinyin = pinyin,
                            radicals = radicals,
                            explanation = explanation,
                        ),
                    )
                }
            }
            reader.endArray()
        }
        return result
    }

    private fun jsonReader(resourcePath: String): JsonReader {
        val stream = LocalDictionary::class.java.getResourceAsStream(resourcePath)
            ?: error("找不到本地词典资源：$resourcePath")
        return JsonReader(InputStreamReader(stream, StandardCharsets.UTF_8))
    }

    private fun String.limitForDialog(): String {
        return if (length <= MAX_DIALOG_TEXT_LENGTH) this else take(MAX_DIALOG_TEXT_LENGTH) + "\n\n……内容较长，已截断显示。"
    }

    private const val MAX_DIALOG_TEXT_LENGTH = 2200
}

data class DictionaryResult(
    val title: String,
    val body: String,
)

private data class DictionaryData(
    val words: Map<String, WordEntry>,
    val phrases: Map<String, String>,
)

private data class WordEntry(
    val strokes: String,
    val pinyin: String,
    val radicals: String,
    val explanation: String,
)
