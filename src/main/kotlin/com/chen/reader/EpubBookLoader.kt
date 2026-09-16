package com.chen.reader

import com.chen.reader.model.Book
import com.chen.reader.model.Chapter
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.net.URLDecoder
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.util.zip.ZipFile
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

object EpubBookLoader {
    private val utf8 = StandardCharsets.UTF_8
    private val blockTagRegex = Regex(
        """(?i)</?(p|div|section|article|header|footer|blockquote|pre|h[1-6]|li|tr|table|nav|body|html)\b[^>]*>""",
    )
    private val lineBreakRegex = Regex("""(?i)<br\b[^>]*>""")
    private val tagRegex = Regex("""<[^>]+>""")
    private val scriptStyleRegex = Regex("""(?is)<(script|style|head)\b[^>]*>.*?</\1>""")
    private val bodyRegex = Regex("""(?is)<body\b[^>]*>(.*?)</body>""")
    private val titleRegex = Regex("""(?is)<title\b[^>]*>(.*?)</title>""")
    private val headingRegex = Regex("""(?is)<h[1-3]\b[^>]*>(.*?)</h[1-3]>""")
    private val encodingRegex = Regex("""(?i)encoding\s*=\s*["']([^"']+)["']""")
    private val htmlEntityRegex = Regex("""&(#x?[0-9a-fA-F]+|[a-zA-Z][a-zA-Z0-9]+);""")

    fun load(path: Path): Book {
        ZipFile(path.toFile(), utf8).use { zip ->
            val opfPath = findPackagePath(zip)
            val opf = parseXml(zip.readEntry(opfPath), "OPF 文件格式异常。")
            val opfDir = opfPath.substringBeforeLast('/', "")
            val manifest = readManifest(opf, opfDir)
            val spineIds = readSpineIds(opf)
            val documents = spineIds.mapNotNull { id -> manifest[id] }

            if (documents.isEmpty()) {
                error("EPUB 未找到可阅读的正文 spine。")
            }

            val builder = StringBuilder()
            val chapters = mutableListOf<Chapter>()
            documents.forEachIndexed { index, entryPath ->
                val entry = zip.getEntry(entryPath) ?: return@forEachIndexed
                val markup = decodeMarkup(zip.getInputStream(entry).readBytes())
                val bodyText = extractBodyText(markup)
                if (bodyText.isBlank()) {
                    return@forEachIndexed
                }

                if (builder.isNotEmpty()) {
                    builder.append("\n\n")
                }
                val start = builder.length
                val title = extractTitle(markup) ?: "第 ${chapters.size + 1} 章"
                appendChapter(builder, title, bodyText)
                chapters += Chapter(
                    title = title.take(80),
                    startOffset = start,
                    endOffset = builder.length,
                )
            }

            if (builder.isEmpty()) {
                error("EPUB 未解析到可阅读正文。")
            }

            return Book(
                path = path,
                content = builder.toString(),
                charset = utf8,
                chapters = chapters.ifEmpty {
                    listOf(Chapter("全文", 0, builder.length))
                },
            )
        }
    }

    private fun findPackagePath(zip: ZipFile): String {
        val containerEntry = zip.getEntry("META-INF/container.xml")
            ?: error("EPUB 缺少 META-INF/container.xml。")
        val container = parseXml(zip.getInputStream(containerEntry).readBytes(), "container.xml 文件格式异常。")
        val rootfile = container
            .getElementsByTagNameNS("*", "rootfile")
            .item(0) as? Element
            ?: error("EPUB 未声明 OPF package 文件。")
        return rootfile.getAttribute("full-path").takeIf { it.isNotBlank() }
            ?: error("EPUB OPF package 路径为空。")
    }

    private fun readManifest(opf: Document, opfDir: String): Map<String, String> {
        val result = linkedMapOf<String, String>()
        val items = opf.getElementsByTagNameNS("*", "item")
        for (index in 0 until items.length) {
            val item = items.item(index) as? Element ?: continue
            val id = item.getAttribute("id")
            val href = item.getAttribute("href")
            val mediaType = item.getAttribute("media-type")
            if (id.isBlank() || href.isBlank() || !isReadableDocument(mediaType, href)) {
                continue
            }
            result[id] = normalizeZipPath(opfDir, href)
        }
        return result
    }

    private fun readSpineIds(opf: Document): List<String> {
        val result = mutableListOf<String>()
        val itemrefs = opf.getElementsByTagNameNS("*", "itemref")
        for (index in 0 until itemrefs.length) {
            val itemref = itemrefs.item(index) as? Element ?: continue
            val idref = itemref.getAttribute("idref")
            if (idref.isNotBlank()) {
                result += idref
            }
        }
        return result
    }

    private fun isReadableDocument(mediaType: String, href: String): Boolean {
        val lowerHref = href.substringBefore('#').lowercase()
        return mediaType.equals("application/xhtml+xml", ignoreCase = true) ||
            mediaType.equals("text/html", ignoreCase = true) ||
            lowerHref.endsWith(".xhtml") ||
            lowerHref.endsWith(".html") ||
            lowerHref.endsWith(".htm")
    }

    private fun normalizeZipPath(baseDir: String, href: String): String {
        val decodedHref = URLDecoder.decode(href.substringBefore('#'), utf8)
            .replace('\\', '/')
        val parts = mutableListOf<String>()
        val fullPath = listOf(baseDir, decodedHref)
            .filter { it.isNotBlank() }
            .joinToString("/")
        fullPath.split('/').forEach { part ->
            when (part) {
                "", "." -> Unit
                ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.lastIndex)
                else -> parts += part
            }
        }
        return parts.joinToString("/")
    }

    private fun parseXml(bytes: ByteArray, errorMessage: String): Document {
        try {
            val factory = DocumentBuilderFactory.newInstance()
            factory.isNamespaceAware = true
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
            return factory.newDocumentBuilder().parse(ByteArrayInputStream(bytes))
        } catch (_: Throwable) {
            error(errorMessage)
        }
    }

    private fun decodeMarkup(bytes: ByteArray): String {
        val charset = detectCharset(bytes)
        return bytes.toString(charset).removePrefix("\uFEFF")
    }

    private fun detectCharset(bytes: ByteArray): Charset {
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            return utf8
        }
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
            return StandardCharsets.UTF_16BE
        }
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
            return StandardCharsets.UTF_16LE
        }

        val header = bytes.copyOfRange(0, bytes.size.coerceAtMost(512)).toString(StandardCharsets.ISO_8859_1)
        val encoding = encodingRegex.find(header)?.groupValues?.getOrNull(1)
        return encoding
            ?.let { runCatching { Charset.forName(it) }.getOrNull() }
            ?: utf8
    }

    private fun extractTitle(markup: String): String? {
        val title = headingRegex.find(markup)?.groupValues?.getOrNull(1)
            ?: titleRegex.find(markup)?.groupValues?.getOrNull(1)
        return title
            ?.let(::stripMarkup)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    }

    private fun extractBodyText(markup: String): String {
        val body = bodyRegex.find(markup)?.groupValues?.getOrNull(1) ?: markup
        return stripMarkup(body)
            .lines()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .joinToString("\n\n")
    }

    private fun appendChapter(builder: StringBuilder, title: String, bodyText: String) {
        val normalizedTitle = title.trim()
        if (!bodyText.startsWith(normalizedTitle)) {
            builder.append(normalizedTitle).append("\n\n")
        }
        builder.append(bodyText)
    }

    private fun stripMarkup(markup: String): String {
        return markup
            .replace(scriptStyleRegex, " ")
            .replace(lineBreakRegex, "\n")
            .replace(blockTagRegex, "\n")
            .replace(tagRegex, " ")
            .let(::decodeEntities)
            .replace('\u00A0', ' ')
            .replace(Regex("""[ \t\x0B\f\r]+"""), " ")
            .replace(Regex("""\n{3,}"""), "\n\n")
            .trim()
    }

    private fun decodeEntities(text: String): String {
        return htmlEntityRegex.replace(text) { match ->
            val entity = match.groupValues[1]
            when {
                entity.startsWith("#x", ignoreCase = true) -> entity.drop(2).toIntOrNull(16)?.toChar()?.toString()
                entity.startsWith("#") -> entity.drop(1).toIntOrNull()?.toChar()?.toString()
                else -> namedEntities[entity]
            } ?: match.value
        }
    }

    private fun ZipFile.readEntry(path: String): ByteArray {
        val entry = getEntry(path) ?: error("EPUB 缺少文件：$path")
        return getInputStream(entry).use { it.readBytes() }
    }

    private val namedEntities = mapOf(
        "amp" to "&",
        "lt" to "<",
        "gt" to ">",
        "quot" to "\"",
        "apos" to "'",
        "nbsp" to " ",
        "mdash" to "—",
        "ndash" to "–",
        "hellip" to "…",
        "lsquo" to "‘",
        "rsquo" to "’",
        "ldquo" to "“",
        "rdquo" to "”",
    )
}
