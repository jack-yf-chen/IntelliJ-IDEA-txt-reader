package com.chen.reader

import com.chen.reader.book.EpubResourceEntry
import com.chen.reader.book.EpubResources
import com.chen.reader.book.SVG_MIME
import com.chen.reader.book.probeRasterSize
import com.chen.reader.model.Block
import com.chen.reader.model.Book
import com.chen.reader.model.Chapter
import com.chen.reader.model.FootnoteBodyBlock
import com.chen.reader.model.FootnoteRefBlock
import com.chen.reader.model.ImageBlock
import com.chen.reader.model.InlineImageBlock
import com.chen.reader.model.LineStyle
import com.chen.reader.model.TextBlock
import com.chen.reader.model.imagePlaceholder
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

/**
 * EPUB 读取器。
 *
 * 0.5.0 起输出结构化 [Block] 流而不是一整段纯文本，但**不改变现有纯文本口径**：
 *
 * - 正文仍按 OPF spine 顺序拼接，章节之间仍是 "\n\n"；
 * - `<img>` 的纯文本降级仍是 "[图片：alt]"（现在额外产出 [ImageBlock]）；
 * - 脚注引用仍渲染成 "[注N]"，注释仍汇总到章末 "【注释】"。
 *
 * 这样既有书的阅读位置（globalOffset / anchorText / permille）不会因为这次改造而漂移。
 */
object EpubBookLoader {
    private val utf8 = StandardCharsets.UTF_8
    private val blockTagRegex = Regex("""(?i)</?(p|div|section|article|header|footer|table|nav|body|html|ul|ol|dl|dt|dd)\b[^>]*>""")
    private val lineBreakRegex = Regex("""(?i)<br\b[^>]*>""")
    private val tagRegex = Regex("""<[^>]+>""")
    private val scriptStyleRegex = Regex("""(?is)<(script|style|head)\b[^>]*>.*?</\1>""")
    private val bodyRegex = Regex("""(?is)<body\b[^>]*>(.*?)</body>""")
    private val titleRegex = Regex("""(?is)<title\b[^>]*>(.*?)</title>""")
    private val headingRegex = Regex("""(?is)<h[1-3]\b[^>]*>(.*?)</h[1-3]>""")
    private val anyHeadingRegex = Regex("""(?is)<h([1-6])\b[^>]*>(.*?)</h\1>""")
    private val footnoteElementRegex = Regex(
        """(?is)<(aside|section|div|li|p)\b([^>]*(?:epub:type\s*=\s*["'][^"']*(?:footnote|endnote|note)[^"']*["']|class\s*=\s*["'][^"']*(?:footnote|endnote|annotation|fn)[^"']*["']|id\s*=\s*["'][^"']*(?:footnote|endnote|annotation|fn)[^"']*["'])[^>]*)>(.*?)</\1>""",
    )
    private val anchorRegex = Regex("""(?is)<a\b([^>]*)>(.*?)</a>""")
    private val imageRegex = Regex("""(?is)<img\b([^>]*)/?>""")
    private val tableRowRegex = Regex("""(?is)<tr\b[^>]*>(.*?)</tr>""")
    private val tableCellRegex = Regex("""(?is)<t[hd]\b[^>]*>(.*?)</t[hd]>""")
    private val blockquoteRegex = Regex("""(?is)<blockquote\b[^>]*>(.*?)</blockquote>""")
    private val listItemRegex = Regex("""(?is)<li\b[^>]*>(.*?)</li>""")
    private val rubyRegex = Regex("""(?is)<ruby\b[^>]*>(.*?)<rt\b[^>]*>(.*?)</rt>(.*?)</ruby>""")
    private val strongRegex = Regex("""(?is)<(strong|b)\b[^>]*>(.*?)</\1>""")
    private val emphasisRegex = Regex("""(?is)<(em|i)\b[^>]*>(.*?)</\1>""")
    private val encodingRegex = Regex("""(?i)encoding\s*=\s*["']([^"']+)["']""")
    private val htmlEntityRegex = Regex("""&(#x?[0-9a-fA-F]+|[a-zA-Z][a-zA-Z0-9]+);""")
    private val attributeRegex = Regex("""(?is)\b([:\w-]+)\s*=\s*["']([^"']*)["']""")

    /** 注释条目可能落在这几种块元素里，用于定位"整块删除"的范围 */
    private val footnoteContainerRegex = Regex("""(?is)<(p|div|li|dd|blockquote)\b[^>]*>(.*?)</\1>""")

    /** 脚注引用锚文本，形如 "[1]" / "1" / "〔1〕" */
    private val footnoteMarkerRegex = Regex("""^\s*[\[〔【]?\s*(\d{1,4})\s*[\]〕】]?\s*$""")

    /** 哨兵分隔符。用 \u0000 是因为它不会被引擎的任何一步（去标签、实体解码、空白归一）吃掉。 */
    private const val MARK = '\u0000'
    private const val MARK_IMAGE = "IMG"
    private const val MARK_REF = "FNREF"

    /** 只读文件头探测图片尺寸时最多读取的字节数 */
    private const val HEADER_BYTES = 64 * 1024

    fun load(path: Path): Book {
        val blocks = mutableListOf<Block>()
        val chapters = mutableListOf<Chapter>()
        var resourceEntries = emptyMap<String, EpubResourceEntry>()

        ZipFile(path.toFile(), utf8).use { zip ->
            val opfPath = findPackagePath(zip)
            val opf = parseXml(zip.readEntry(opfPath), "OPF 文件格式异常。")
            val opfDir = opfPath.substringBeforeLast('/', "")
            val manifest = readManifest(opf, opfDir)
            val spinePaths = readSpineIds(opf).mapNotNull { id -> manifest.documents[id] }

            if (spinePaths.isEmpty()) {
                error("EPUB 未找到可阅读的正文 spine。")
            }

            resourceEntries = buildResourceEntries(manifest.resources, zip)
            val resourceIdByPath = resourceEntries.values.associateBy({ it.zipPath }, { it.resourceId })

            val writer = ChapterWriter(blocks)
            spinePaths.forEach { entryPath ->
                val entry = zip.getEntry(entryPath) ?: return@forEach
                val markup = decodeMarkup(zip.getInputStream(entry).readBytes())
                val body = bodyRegex.find(markup)?.groupValues?.getOrNull(1) ?: markup
                if (body.isBlank()) {
                    return@forEach
                }

                val title = extractTitle(markup) ?: "第 ${chapters.size + 1} 章"
                val chapterBody = buildChapterBody(
                    body = body,
                    documentPath = entryPath,
                    resourceIdByPath = resourceIdByPath,
                    resourceEntries = resourceEntries,
                    zip = zip,
                )
                if (chapterBody.text.isBlank()) {
                    return@forEach
                }

                if (writer.offset > 0) {
                    writer.text("\n\n")
                }
                val start = writer.offset
                val normalizedTitle = title.trim()
                if (!chapterBody.text.startsWith(normalizedTitle)) {
                    writer.text("$normalizedTitle\n\n")
                }
                writer.pieces(
                    splitMarkedBody(chapterBody.text, chapterBody.images, chapterBody.notes),
                )
                appendFootnoteSummary(writer, chapterBody.notes)
                chapters += Chapter(title = normalizedTitle.take(80), startOffset = start, endOffset = writer.offset)
            }

            if (writer.offset == 0) {
                error("EPUB 未解析到可阅读正文。")
            }
        }

        return Book(
            path = path,
            charset = utf8,
            blocks = blocks,
            chapters = chapters.ifEmpty {
                listOf(Chapter("全文", 0, blocks.lastOrNull()?.plainEnd ?: 0))
            },
            resources = EpubResources(path, resourceEntries),
        )
    }

    // ------------------------------------------------------------------ 章节正文 → Block

    /** 章末注释汇总区（与 0.4.1 起的纯文本口径保持一致）。 */
    private fun appendFootnoteSummary(writer: ChapterWriter, notes: List<EpubFootnote>) {
        if (notes.isEmpty()) {
            return
        }
        writer.text("\n\n【注释】\n")
        notes.forEachIndexed { index, note ->
            if (index > 0) {
                writer.text("\n")
            }
            writer.footnoteBody(note.id, note.number, note.text)
        }
    }

    /**
     * 把一章的 XHTML 正文变成"带哨兵的纯文本 + 图片规格 + 脚注"。
     *
     * 处理顺序（顺序很关键）：
     * 1. 既有路径（epub:type / class / id 标注）先摘出注释条目；
     * 2. 在剩下的正文里跑**双向锚点**识别，补上转换器生成的成对脚注；
     * 3. 删除注释条目所在的整块，避免正文里重复出现一遍注释；
     * 4. 把所有指向注释的 `<a>` 换成脚注哨兵；
     * 5. 把 `<img>` 换成图片哨兵（**必须在 stripStructuredMarkup 之前**）；
     * 6. 其余标签按老规则转纯文本。
     */
    private fun buildChapterBody(
        body: String,
        documentPath: String,
        resourceIdByPath: Map<String, String>,
        resourceEntries: Map<String, EpubResourceEntry>,
        zip: ZipFile,
    ): ChapterBody {
        val standardNotes = collectStandardFootnotes(body)
        val bodyWithoutStandard = removeStandardFootnoteBlocks(body)
        val mutualNotes = collectMutualAnchorFootnotes(bodyWithoutStandard)
        val notes = mergeFootnotes(standardNotes, mutualNotes)

        val withoutNoteBlocks = removeMutualFootnoteBlocks(bodyWithoutStandard, mutualNotes)
        val withRefs = replaceFootnoteRefs(withoutNoteBlocks, notes)

        val images = mutableListOf<ImageSpec>()
        val withImages = replaceImages(withRefs, documentPath, resourceIdByPath, resourceEntries, images)

        return ChapterBody(text = stripStructuredMarkup(withImages), images = images, notes = notes)
    }

    // ------------------------------------------------------------------ 脚注识别

    /** 既有路径：`epub:type="footnote"` / `class="footnote"` / `id="fn*"` 标注的块。 */
    private fun collectStandardFootnotes(body: String): List<EpubFootnote> {
        return footnoteElementRegex.findAll(body)
            .mapIndexedNotNull { index, match ->
                val id = attributeValue(match.groupValues[2], "id") ?: return@mapIndexedNotNull null
                val text = stripStructuredMarkup(match.groupValues[3])
                if (text.isBlank()) {
                    null
                } else {
                    EpubFootnote(id = id, refFragment = id, number = index + 1, text = text)
                }
            }
            .toList()
    }

    private fun removeStandardFootnoteBlocks(body: String): String {
        return footnoteElementRegex.replace(body) { match ->
            if (attributeValue(match.groupValues[2], "id").isNullOrBlank()) match.value else ""
        }
    }

    /**
     * **新路径**：成对互指的双向锚点脚注。
     *
     * 一些 EPUB 转换器（本例是 z-library / Epubor）不写任何 `epub:type` / `class` 标注，
     * 而是生成两个互为镜像的锚点：
     *
     * ```html
     * <!-- 正文引用点（常在 <sup> 内） -->
     * <a id="sd1e17" href="text00003.html#d1e17">[1]</a>
     * <!-- 章末注释条目，注释正文紧跟在 </a> 之后、同一个 <p> 内 -->
     * <a id="d1e17" href="text00003.html#sd1e17">[1]</a> 六角括号内的话系译者所加。
     * ```
     *
     * **判定"成对"的依据是结构而不是命名**：两个锚点 A、B 满足
     * `A.href 的 fragment == B.id` **且** `B.href 的 fragment == A.id`（严格双向互指）。
     * 之所以不用"s/d 前缀互指"这条规则，是因为前缀只是该转换器的命名习惯；
     * 双向互指才是这类脚注的通用结构特征，换成别的转换器也能认出来。
     *
     * 在此基础上再要求两端锚文本都解析出同一个 `[N]` 编号，避免把普通的
     * "正文链接 + 返回链接"误判成脚注。文档顺序上先出现的那个是引用点，后出现的那个是注释条目。
     */
    private fun collectMutualAnchorFootnotes(body: String): List<EpubFootnote> {
        val anchors = anchorRegex.findAll(body)
            .mapNotNull { match -> parseAnchor(match) }
            .toList()
        if (anchors.isEmpty()) {
            return emptyList()
        }

        val anchorsById = linkedMapOf<String, EpubAnchor>()
        anchors.forEach { anchorsById.putIfAbsent(it.id, it) }

        val used = mutableSetOf<String>()
        val result = mutableListOf<EpubFootnote>()
        anchors.forEach { anchor ->
            if (anchor.id in used) {
                return@forEach
            }
            val peer = anchorsById[anchor.fragment] ?: return@forEach
            if (peer.fragment != anchor.id || peer.id in used) {
                return@forEach
            }
            val number = footnoteMarkerNumber(anchor.label) ?: return@forEach
            if (footnoteMarkerNumber(peer.label) != number) {
                return@forEach
            }

            // 文档顺序：先出现的是引用点，后出现的是注释条目
            val (ref, noteAnchor) = if (anchor.range.first < peer.range.first) anchor to peer else peer to anchor
            val container = findContainingBlock(body, noteAnchor.id) ?: return@forEach
            val noteText = extractFootnoteBodyText(container, noteAnchor.id)
            if (noteText.isBlank()) {
                return@forEach
            }

            used += anchor.id
            used += peer.id
            result += EpubFootnote(
                id = ref.id,
                refFragment = noteAnchor.id,
                number = number,
                text = noteText,
                bodyBlockRange = container.range,
            )
        }
        return result.sortedBy { it.number }
    }

    /** 删除注释条目所在的整个块元素（从后往前删，保证前面的区间仍然有效）。 */
    private fun removeMutualFootnoteBlocks(body: String, notes: List<EpubFootnote>): String {
        var result = body
        notes
            .mapNotNull { it.bodyBlockRange }
            .sortedByDescending { it.first }
            .forEach { range ->
                if (range.first >= 0 && range.last < result.length) {
                    result = result.removeRange(range)
                }
            }
        return result
    }

    /** 锚文本 → 脚注编号；不是 "[1]" 这类标记时返回 null。 */
    private fun footnoteMarkerNumber(label: String): Int? {
        val text = stripInlineMarkup(label).trim()
        return footnoteMarkerRegex.find(text)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
    }

    /** 找到包住指定 anchor id 的块级元素，用于整块摘除注释条目。 */
    private fun findContainingBlock(body: String, anchorId: String): MatchResult? {
        val idPattern = Regex("""id\s*=\s*["']${Regex.escape(anchorId)}["']""")
        return footnoteContainerRegex.findAll(body)
            .firstOrNull { match -> idPattern.containsMatchIn(match.groupValues[2]) }
    }

    /** 注释正文 = 该块元素去掉自身锚点之后的全部内容。 */
    private fun extractFootnoteBodyText(container: MatchResult, anchorId: String): String {
        val inner = container.groupValues[2]
        val withoutAnchor = anchorRegex.replace(inner) { match ->
            val id = attributeValue(match.groupValues[1], "id")
            if (id == anchorId) "" else match.value
        }
        return stripStructuredMarkup(withoutAnchor).trim()
    }

    /** 把所有指向注释条目的 `<a>` 换成脚注哨兵。 */
    private fun replaceFootnoteRefs(body: String, notes: List<EpubFootnote>): String {
        if (notes.isEmpty()) {
            return body
        }
        val notesByFragment = notes.associateBy { it.refFragment }
        return anchorRegex.replace(body) { match ->
            val href = attributeValue(match.groupValues[1], "href") ?: return@replace match.value
            val note = notesByFragment[href.substringAfterLast('#', "")] ?: return@replace match.value
            "$MARK$MARK_REF:${note.id}$MARK"
        }
    }

    /** 既有路径优先；编号撞车时顺延，避免两个脚注都叫 "[注1]"。 */
    private fun mergeFootnotes(
        standard: List<EpubFootnote>,
        mutual: List<EpubFootnote>,
    ): List<EpubFootnote> {
        val used = mutableSetOf<Int>()
        val result = mutableListOf<EpubFootnote>()
        standard.forEach { note ->
            used += note.number
            result += note
        }
        var next = (result.maxOfOrNull { it.number } ?: 0) + 1
        mutual.forEach { note ->
            if (used.add(note.number)) {
                result += note
            } else {
                result += note.copy(number = next++)
            }
        }
        return result.sortedBy { it.number }
    }

    // ------------------------------------------------------------------ 图片

    /**
     * `<img>` → 图片哨兵。
     *
     * `src` 是**相对当前 XHTML 所在目录**的相对路径（本例里 manifest 的 href 不带 `OEBPS/`），
     * 所以必须先按当前文档目录归一化，再去 `manifest id ↔ zip 路径` 映射里查。
     * 查不到就退回旧的纯文本降级，不留哨兵。
     */
    private fun replaceImages(
        body: String,
        documentPath: String,
        resourceIdByPath: Map<String, String>,
        resourceEntries: Map<String, EpubResourceEntry>,
        images: MutableList<ImageSpec>,
    ): String {
        val documentDir = documentPath.substringBeforeLast('/', "")
        return imageRegex.replace(body) { match ->
            val attrs = match.groupValues[1]
            val src = attributeValue(attrs, "src")
            val alt = attributeValue(attrs, "alt").orEmpty()
            if (src.isNullOrBlank()) {
                return@replace ""
            }
            val resolved = normalizeZipPath(documentDir, src)
            val resourceId = resourceIdByPath[resolved]
            if (resourceId == null) {
                return@replace "\n${imagePlaceholder(alt)}\n"
            }
            val entry = resourceEntries[resourceId]
            val index = images.size
            images += ImageSpec(
                resourceId = resourceId,
                alt = alt,
                width = entry?.width ?: 0,
                height = entry?.height ?: 0,
                isVector = entry?.mime == SVG_MIME,
            )
            "\n$MARK$MARK_IMAGE:$index$MARK\n"
        }
    }

    private fun buildResourceEntries(
        resources: Map<String, ManifestItem>,
        zip: ZipFile,
    ): Map<String, EpubResourceEntry> {
        val result = linkedMapOf<String, EpubResourceEntry>()
        resources.forEach { (id, item) ->
            val zipEntry = zip.getEntry(item.zipPath)
            val byteSize = zipEntry?.size ?: 0L
            val (width, height) = if (item.mime.startsWith("image/", ignoreCase = true)) {
                probeEntrySize(zip, item)
            } else {
                0 to 0
            }
            result[id] = EpubResourceEntry(
                resourceId = id,
                zipPath = item.zipPath,
                mime = item.mime,
                byteSize = byteSize,
                width = width,
                height = height,
            )
        }
        return result
    }

    /** 只读前 64 KB 探测内建宽高，**不解码**（§8：排版高度不得依赖解码）。 */
    private fun probeEntrySize(zip: ZipFile, item: ManifestItem): Pair<Int, Int> {
        val zipEntry = zip.getEntry(item.zipPath) ?: return 0 to 0
        return runCatching {
            zip.getInputStream(zipEntry).use { input ->
                val head = ByteArray(HEADER_BYTES)
                val read = input.read(head)
                if (read <= 0) {
                    return@use 0 to 0
                }
                probeRasterSize(head.copyOf(read), item.mime) ?: (0 to 0)
            }
        }.getOrDefault(0 to 0)
    }

    // ------------------------------------------------------------------ 哨兵 → Block

    private fun splitMarkedBody(
        marked: String,
        images: List<ImageSpec>,
        notes: List<EpubFootnote>,
    ): List<BodyPiece> {
        val notesById = notes.associateBy { it.id }
        val pieces = mutableListOf<BodyPiece>()
        val buffer = StringBuilder()
        var index = 0

        fun flush() {
            if (buffer.isNotEmpty()) {
                pieces += BodyPiece.Text(buffer.toString())
                buffer.setLength(0)
            }
        }

        while (index < marked.length) {
            if (marked[index] != MARK) {
                buffer.append(marked[index])
                index++
                continue
            }
            val end = marked.indexOf(MARK, index + 1)
            if (end < 0) {
                buffer.append(marked[index])
                index++
                continue
            }
            flush()
            val payload = marked.substring(index + 1, end)
            val separator = payload.indexOf(':')
            if (separator > 0) {
                val kind = payload.substring(0, separator)
                val value = payload.substring(separator + 1)
                when (kind) {
                    MARK_IMAGE -> images.getOrNull(value.toIntOrNull() ?: -1)?.let { spec ->
                        pieces += BodyPiece.Image(spec, inline = !isAloneOnLine(marked, index, end))
                    }

                    MARK_REF -> notesById[value]?.let { note ->
                        pieces += BodyPiece.FootnoteRef(note.id, note.number)
                    }
                }
            }
            index = end + 1
        }
        flush()
        return pieces
    }

    /** 哨兵是否独占一行：独占则是块级图，与文字同框则是行内图。 */
    private fun isAloneOnLine(marked: String, start: Int, end: Int): Boolean {
        val lineStart = marked.lastIndexOf('\n', start - 1).let { if (it < 0) 0 else it + 1 }
        val lineEnd = marked.indexOf('\n', end + 1).let { if (it < 0) marked.length else it }
        val before = marked.substring(lineStart, start)
        val after = marked.substring((end + 1).coerceAtMost(lineEnd), lineEnd)
        return before.isBlank() && after.isBlank()
    }

    // ------------------------------------------------------------------ OPF / 编码 / 纯文本

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

    /** manifest 现在同时收正文文档和二进制资源；旧实现把图片整类过滤掉了。 */
    private fun readManifest(opf: Document, opfDir: String): Manifest {
        val documents = linkedMapOf<String, String>()
        val resources = linkedMapOf<String, ManifestItem>()
        val items = opf.getElementsByTagNameNS("*", "item")
        for (index in 0 until items.length) {
            val item = items.item(index) as? Element ?: continue
            val id = item.getAttribute("id")
            val href = item.getAttribute("href")
            val mediaType = item.getAttribute("media-type")
            if (id.isBlank() || href.isBlank()) {
                continue
            }
            val zipPath = normalizeZipPath(opfDir, href)
            if (isReadableDocument(mediaType, href)) {
                documents[id] = zipPath
            } else {
                resources[id] = ManifestItem(zipPath, mediaType)
            }
        }
        return Manifest(documents, resources)
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
            ?.let(::stripInlineMarkup)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    }

    /**
     * XHTML → 纯文本。
     *
     * 注意：图片不在这里处理，已在 `replaceImages` 阶段换成哨兵（否则拿不到块级/行内位置和资源 id）。
     */
    private fun stripStructuredMarkup(markup: String): String {
        return markup
            .replace(scriptStyleRegex, " ")
            .replace(rubyRegex) { match ->
                val text = stripInlineMarkup(match.groupValues[1] + match.groupValues[3])
                val ruby = stripInlineMarkup(match.groupValues[2])
                "$text（$ruby）"
            }
            .replace(tableRowRegex) { match ->
                val cells = tableCellRegex.findAll(match.groupValues[1])
                    .map { stripInlineMarkup(it.groupValues[1]).trim() }
                    .filter { it.isNotBlank() }
                    .toList()
                if (cells.isEmpty()) "\n" else "\n${cells.joinToString(" | ")}\n"
            }
            .replace(anyHeadingRegex) { match ->
                val level = match.groupValues[1].toIntOrNull()?.coerceIn(1, 3) ?: 3
                val prefix = "#".repeat(level)
                "\n\n$prefix ${stripInlineMarkup(match.groupValues[2])}\n\n"
            }
            .replace(blockquoteRegex) { match ->
                val quote = stripStructuredMarkup(match.groupValues[1])
                    .lines()
                    .filter { it.isNotBlank() }
                    .joinToString("\n") { "> $it" }
                "\n\n$quote\n\n"
            }
            .replace(listItemRegex) { match ->
                "\n- ${stripStructuredMarkup(match.groupValues[1]).replace("\n", " ").trim()}"
            }
            .replace(strongRegex) { match ->
                "**${stripInlineMarkup(match.groupValues[2])}**"
            }
            .replace(emphasisRegex) { match ->
                "*${stripInlineMarkup(match.groupValues[2])}*"
            }
            .replace(lineBreakRegex, "\n")
            .replace(blockTagRegex, "\n")
            .replace(tagRegex, " ")
            .let(::normalizeStructuredText)
    }

    private fun stripInlineMarkup(markup: String): String {
        return markup
            .replace(scriptStyleRegex, " ")
            .replace(lineBreakRegex, " ")
            .replace(tagRegex, " ")
            .let(::decodeEntities)
            .replace('\u00A0', ' ')
            .replace(Regex("""[ \t\x0B\f\r]+"""), " ")
            .trim()
    }

    private fun normalizeStructuredText(text: String): String {
        val normalizedLines = text
            .let(::decodeEntities)
            .replace('\u00A0', ' ')
            .replace(Regex("""[ \t\x0B\f\r]+"""), " ")
            .lines()
            .map { it.trim() }

        val result = mutableListOf<String>()
        normalizedLines.forEach { line ->
            if (line.isBlank()) {
                if (result.lastOrNull()?.isNotBlank() == true) {
                    result += ""
                }
            } else {
                result += line
            }
        }
        return result.joinToString("\n")
            .replace(Regex("""\n{3,}"""), "\n\n")
            .trim()
    }

    private fun attributeValue(attrs: String, name: String): String? {
        return attributeRegex.findAll(attrs)
            .firstOrNull { it.groupValues[1].equals(name, ignoreCase = true) }
            ?.groupValues
            ?.getOrNull(2)
            ?.let(::decodeEntities)
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

    // ------------------------------------------------------------------ 内部数据结构

    private data class Manifest(
        val documents: Map<String, String>,
        val resources: Map<String, ManifestItem>,
    )

    private data class ManifestItem(
        val zipPath: String,
        val mime: String,
    )

    private data class ChapterBody(
        val text: String,
        val images: List<ImageSpec>,
        val notes: List<EpubFootnote>,
    )

    private data class ImageSpec(
        val resourceId: String,
        val alt: String,
        val width: Int,
        val height: Int,
        val isVector: Boolean,
    )

    private data class EpubFootnote(
        /** 注释 id：引用点锚点的 id */
        val id: String,
        /** 指向注释条目的 href fragment */
        val refFragment: String,
        val number: Int,
        val text: String,
        /** 注释条目所在块元素在原 markup 中的范围（仅双向锚点路径有，用于整块删除） */
        val bodyBlockRange: IntRange? = null,
    )

    private data class EpubAnchor(
        val id: String,
        val fragment: String,
        val label: String,
        val range: IntRange,
    )

    /** 解析一个 `<a>`：必须有 id 与带 fragment 的 href，否则不可能是成对锚点。 */
    private fun parseAnchor(match: MatchResult): EpubAnchor? {
        val attrs = match.groupValues[1]
        val id = attributeValue(attrs, "id") ?: return null
        val href = attributeValue(attrs, "href") ?: return null
        val fragment = href.substringAfterLast('#', "")
        if (id.isBlank() || fragment.isBlank() || id == fragment) {
            return null
        }
        return EpubAnchor(id, fragment, match.groupValues[2], match.range)
    }

    private sealed interface BodyPiece {
        data class Text(val value: String) : BodyPiece

        data class Image(val spec: ImageSpec, val inline: Boolean) : BodyPiece

        data class FootnoteRef(val footnoteId: String, val number: Int) : BodyPiece
    }

    /**
     * 顺序写入 Block 并同步维护 `plainText` 的字符游标。
     *
     * 唯一约束：每写入一个块，`offset` 就前移"该块在 plainText 中的贡献长度"，
     * 且贡献长度必须与 `model.plainContentOf` 完全一致。
     */
    private class ChapterWriter(private val blocks: MutableList<Block>) {
        var offset: Int = 0
            private set

        fun text(value: String, style: LineStyle = LineStyle.BODY) {
            if (value.isEmpty()) {
                return
            }
            val start = offset
            offset += value.length
            blocks += TextBlock(start, offset, value, style)
        }

        fun pieces(pieces: List<BodyPiece>) {
            pieces.forEach { piece ->
                when (piece) {
                    is BodyPiece.Text -> text(piece.value)

                    is BodyPiece.Image -> {
                        val placeholder = imagePlaceholder(piece.spec.alt)
                        val start = offset
                        offset += placeholder.length
                        blocks += if (piece.inline) {
                            InlineImageBlock(
                                plainStart = start,
                                plainEnd = offset,
                                resourceId = piece.spec.resourceId,
                                alt = piece.spec.alt,
                                intrinsicWidth = piece.spec.width,
                                intrinsicHeight = piece.spec.height,
                                isVector = piece.spec.isVector,
                            )
                        } else {
                            ImageBlock(
                                plainStart = start,
                                plainEnd = offset,
                                resourceId = piece.spec.resourceId,
                                alt = piece.spec.alt,
                                intrinsicWidth = piece.spec.width,
                                intrinsicHeight = piece.spec.height,
                                isVector = piece.spec.isVector,
                                placeholder = placeholder,
                            )
                        }
                    }

                    is BodyPiece.FootnoteRef -> {
                        val label = "[注${piece.number}]"
                        val start = offset
                        offset += label.length
                        blocks += FootnoteRefBlock(start, offset, piece.footnoteId, piece.number)
                    }
                }
            }
        }

        fun footnoteBody(footnoteId: String, number: Int, bodyText: String) {
            val contribution = "[注$number] $bodyText"
            val start = offset
            offset += contribution.length
            blocks += FootnoteBodyBlock(start, offset, footnoteId, number, bodyText)
        }
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
