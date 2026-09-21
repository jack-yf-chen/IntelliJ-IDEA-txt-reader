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
import org.w3c.dom.Node
import org.xml.sax.EntityResolver
import org.xml.sax.InputSource
import java.io.ByteArrayInputStream
import java.net.URLDecoder
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.util.zip.ZipFile
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilder
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
    private val anchorRegex = Regex("""(?is)<a\b([^>]*)>(.*?)</a>""")
    private val imageRegex = Regex("""(?is)<img\b([^>]*)/?>""")
    private val tableRowRegex = Regex("""(?is)<tr\b[^>]*>(.*?)</tr>""")
    private val tableCellRegex = Regex("""(?is)<t[hd]\b[^>]*>(.*?)</t[hd]>""")
    private val blockquoteRegex = Regex("""(?is)<blockquote\b[^>]*>(.*?)</blockquote>""")
    private val listItemRegex = Regex("""(?is)<li\b[^>]*>(.*?)</li>""")
    private val rubyRegex = Regex("""(?is)<ruby\b[^>]*>(.*?)<rt\b[^>]*>(.*?)</rt>(.*?)</ruby>""")
    private val strongRegex = Regex("""(?is)<(strong|b)\b[^>]*>(.*?)</\1>""")
    private val emphasisRegex = Regex("""(?is)<(em|i)\b[^>]*>(.*?)</\1>""")
    private val preformattedRegex = Regex("""(?is)<pre\b[^>]*>.*?</pre>""")

    /** XHTML 源码的缩进换行：换行符 + 两侧水平空白（不含 `\r`/`\n`，避免吃掉空行结构） */
    private val sourceNewlineRegex = Regex("""[^\S\r\n]*\r?\n[^\S\r\n]*""")
    private val encodingRegex = Regex("""(?i)encoding\s*=\s*["']([^"']+)["']""")
    private val htmlEntityRegex = Regex("""&(#x?[0-9a-fA-F]+|[a-zA-Z][a-zA-Z0-9]+);""")
    private val attributeRegex = Regex("""(?is)\b([:\w-]+)\s*=\s*["']([^"']*)["']""")

    /** 注释条目可能落在这几种块元素里，用于定位"整块删除"的范围 */
    private val footnoteContainerRegex = Regex("""(?is)<(p|div|li|dd|blockquote)\b[^>]*>(.*?)</\1>""")

    /**
     * 脚注引用锚文本，形如 "[1]" / "1" / "〔1〕" / "1." / "1、"。
     *
     * 末尾允许一个 `.,、．`，因为注释条目的编号常写成 "1. 六角括号内的话系译者所加。"
     * ——老实现不放行，导致 `number` 取不到、退回位置序号，出现"书里 [3]、插件显示 [注1]"。
     */
    private val footnoteMarkerRegex = Regex("""^\s*[\[〔【(（]?\s*(\d{1,4})\s*[\]〕】)）]?\s*[.、．]?\s*$""")

    /** 注释正文开头的编号标记（用于取"书里原本的编号"），要求后面还有正文，避免把纯数字段落当成标记 */
    private val leadingMarkerRegex = Regex("""^\s*[\[〔【(（]?\s*\d{1,4}\s*[\]〕】)）]?\s*[.、．]?""")

    /** N1：目录页文件名 */
    private val TOC_NAME_REGEX = Regex("""(?i)(^|[_\-.])(toc|nav|contents|目录|目次)([_.\-]|$)""")

    /** N1：正文里的目录标记（`<nav>` 元素或 `epub:type="toc"`） */
    private val TOC_MARKER_REGEX = Regex(
        """(?is)<nav\b[^>]*>|<[a-zA-Z][\w:-]*\b[^>]*\bepub:type\s*=\s*["'][^"']*\b(toc|toc-brief|landmarks)\b[^"']*["'][^>]*>""",
    )

    /** 通道 A：`epub:type` 取值里的脚注语义 */
    private val FOOTNOTE_TYPE_REGEX = Regex("""(?i)\b(footnote|endnote|rearnote|note)\b""")

    /** 通道 A：`class` / `id` 里的脚注语义（比 [FOOTNOTE_TYPE_REGEX] 宽一点，命中命名习惯） */
    private val FOOTNOTE_NAME_REGEX = Regex("""(?i)(footnote|endnote|rearnote|annotation|\bfn\b|foot-?note)""")

    /** N3：注释条目自己的"返回正文"回链，不是脚注引用 */
    private val BACK_LINK_REGEX = Regex("""(?i)(返回|回正文|回原文|回原处|back|return|↩|↥|↑|\^)""")

    /** N5：`<a>` 里包着图片 —— 图注链接，不是脚注 */
    private val IMAGE_TAG_REGEX = Regex("""(?is)<\s*(img|svg|picture|figure|object|embed)\b""")

    /** N4：href 带 URI scheme（http: / mailto: / …）即外链 */
    private val URI_SCHEME_REGEX = Regex("""^[a-zA-Z][a-zA-Z0-9+.\-]{1,20}:""")

    /** 只有这些元素的裸 `type` 属性才可能是脚注语义；`ol`/`a`/`input` 等的 `type` 是别的意思 */
    private val TYPE_ATTR_TAGS = setOf("aside", "section", "div", "p", "li", "span", "dd", "dt", "blockquote", "footer")

    /**
     * 通道 A 只认**块级**元素。
     *
     * 必须挡住 `<sup>` / `<a>` / `<span>` 这类行内元素：Pandoc / Markdown 系转换器会把
     * **引用标记**写成 `<sup class="footnote-ref" id="fnref1"><a href="#fn1">1</a></sup>`，
     * 它同样带 footnote 语义的 class，但它是"引用点"而不是"注释条目"。
     * 放开行内元素会让这类书每章多出 N 条正文只有 "1" 的假注释，
     * 还把正文里的引用标记整块删掉（引用点消失）。
     */
    private val FOOTNOTE_CONTAINER_TAGS = setOf("aside", "section", "div", "li", "p", "dd", "dt", "blockquote", "footer")

    /** `epub:type` 的标准命名空间 */
    private const val EPUB_TYPE_NAMESPACE = "http://www.idpf.org/2007/ops"

    /** DOM 递归深度上限，防御畸形嵌套导致的栈溢出 */
    private const val MAX_DOM_DEPTH = 64

    /** N2：脚注标记一定是短标记，超过这个长度就是正文里的长文本交叉引用 */
    private const val MAX_MARKER_LABEL_LENGTH = 12

    /**
     * 通道 B 的 `elementIndex` 起始值。
     *
     * 两通道的 `elementIndex` 必须落在**互不相交**的区间，否则 `(docId, elementIndex)`
     * 去重键会把通道 A 的第 0 条和通道 B 的第 0 条当成同一个元素，白白丢掉一条注释。
     */
    private const val MUTUAL_ELEMENT_INDEX_BASE = 1_000_000

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
                    markup = markup,
                    body = body,
                    documentPath = entryPath,
                    resourceIdByPath = resourceIdByPath,
                    resourceEntries = resourceEntries,
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
        markup: String,
        body: String,
        documentPath: String,
        resourceIdByPath: Map<String, String>,
        resourceEntries: Map<String, EpubResourceEntry>,
    ): ChapterBody {
        val isToc = isTocDocument(documentPath, body)

        // 通道 A：epub:type / class / id 标注（DOM 遍历，优先级最高）
        val standardNotes = if (isToc) emptyList() else collectStandardFootnotes(markup, documentPath)
        val standardRanges = standardNotes.mapNotNull { note -> findElementByIdTag(body, note.id) }

        // 通道 B：成对互指的双向锚点（转换器生成、无任何标注）
        val mutualNotes = if (isToc) {
            emptyList()
        } else {
            collectMutualAnchorFootnotes(body, documentPath, standardRanges)
        }
        val notes = mergeFootnotes(standardNotes, mutualNotes)

        // 两通道的注释条目都要从正文里摘掉，避免正文里重复出现一遍注释。
        val withoutNoteBlocks = removeRanges(
            body,
            standardRanges + mutualNotes.mapNotNull { it.bodyBlockRange },
        )
        val withRefs = replaceFootnoteRefs(withoutNoteBlocks, notes)

        val images = mutableListOf<ImageSpec>()
        val withImages = replaceImages(withRefs, documentPath, resourceIdByPath, resourceEntries, images)

        return ChapterBody(text = stripStructuredMarkup(withImages), images = images, notes = notes)
    }

    // ------------------------------------------------------------------ 脚注识别

    /**
     * 通道 A（既有路径，优先级最高）：`epub:type` / `class` / `id` 里带 footnote 语义的块元素。
     *
     * **改用 DOM 遍历而非正则**：老实现用 `<(p|div|li)\b...>(.*?)</\1>` 惰性匹配，
     * 遇到**嵌套同名标签**（`<div class="footnote"><div>…</div>…</div>`）会在第一个 `</div>`
     * 处提前截断，注释正文被砍掉一半。DOM 天然处理嵌套，且能直接读到 `epub:type` 属性。
     *
     * 与老实现一致，只认**带非空 id** 的元素——没有 id 就无法定位引用链接，也无法摘除。
     */
    private fun collectStandardFootnotes(markup: String, documentPath: String): List<EpubFootnote> {
        val document = runCatching { parseXhtml(markup) }.getOrNull() ?: return emptyList()
        val root = document.documentElement ?: return emptyList()

        val hits = mutableListOf<FootnoteElementHit>()
        walkFootnoteElements(root, hits, 0)
        if (hits.isEmpty()) {
            return emptyList()
        }

        val result = mutableListOf<EpubFootnote>()
        var fallbackNumber = 0
        hits.forEachIndexed { index, hit ->
            val text = normalizePlainText(hit.text)
            if (text.isBlank()) {
                return@forEachIndexed
            }
            // 编号优先取"书里原本的标记"（① 元素自身锚点的 label ② 正文开头的 [N] / N.），
            // 取不到才退回位置序号 —— 老实现一律用位置序号，会出现"书里 [3]、插件显示 [注1]"。
            val label = hit.anchorLabel?.takeIf { it.isNotBlank() }
                ?: leadingMarkerLabel(text)
                ?: ""
            val number = footnoteMarkerNumber(label) ?: ++fallbackNumber
            result += EpubFootnote(
                docId = documentPath,
                elementIndex = index,
                id = hit.id,
                refFragment = hit.id,
                number = number,
                label = label,
                text = text,
            )
        }
        return result
    }

    /** 递归找带 footnote 语义的元素；命中后不再深入，避免同一条注释被拆成多条。 */
    private fun walkFootnoteElements(node: Node, hits: MutableList<FootnoteElementHit>, depth: Int) {
        if (depth > MAX_DOM_DEPTH) {
            return
        }
        if (node is Element) {
            val tagName = node.tagName.orEmpty()
            val epubType = node.getAttribute("epub:type")
                .ifBlank { node.getAttributeNS(EPUB_TYPE_NAMESPACE, "type") }
                .ifBlank {
                    // 裸 type 只在少数块级元素上才可能是脚注语义；
                    // <ol type="1"> / <a type="text/html"> 之类必须忽略，否则会炸出大量假脚注。
                    if (tagName.lowercase() in TYPE_ATTR_TAGS) node.getAttribute("type") else ""
                }
            val className = node.getAttribute("class")
            val id = node.getAttribute("id")
            if (id.isNotBlank() && tagName.lowercase() in FOOTNOTE_CONTAINER_TAGS &&
                isFootnoteMarked(epubType, className, id)
            ) {
                hits += FootnoteElementHit(
                    id = id,
                    tagName = tagName,
                    text = node.textContent.orEmpty(),
                    anchorLabel = firstAnchorLabel(node),
                )
                return
            }
        }
        val children = node.childNodes ?: return
        for (index in 0 until children.length) {
            walkFootnoteElements(children.item(index), hits, depth + 1)
        }
    }

    private fun isFootnoteMarked(epubType: String, className: String, id: String): Boolean {
        return FOOTNOTE_TYPE_REGEX.containsMatchIn(epubType) ||
            FOOTNOTE_NAME_REGEX.containsMatchIn(className) ||
            FOOTNOTE_NAME_REGEX.containsMatchIn(id)
    }

    /** 注释条目自身那个 `<a id="...">` 的锚文本，通常就是 "[3]" 这类编号。 */
    private fun firstAnchorLabel(element: Element): String? {
        val anchors = element.getElementsByTagNameNS("*", "a")
        for (index in 0 until anchors.length) {
            val anchor = anchors.item(index) as? Element ?: continue
            if (anchor.getAttribute("id") == element.getAttribute("id")) {
                return normalizePlainText(anchor.textContent.orEmpty())
            }
        }
        return null
    }

    /** 通道 A 的注释条目：按 id 找到开标签，再用**配对计数**算出包含嵌套的完整范围。 */
    private fun findElementByIdTag(markup: String, elementId: String): IntRange? {
        val pattern = Regex(
            """<([a-zA-Z][\w:-]*)\b[^>]*\bid\s*=\s*["']${Regex.escape(elementId)}["'][^>]*>""",
        )
        val match = pattern.find(markup) ?: return null
        return findElementRange(markup, match.range.first, match.groupValues[1])
    }

    /**
     * 从 `start`（指向 `<`）开始，按同名标签配对计数算出元素完整范围（含闭合标签）。
     *
     * 这是修复"嵌套同名标签截断"的另一半：配对计数保证 `<div><div>…</div></div>`
     * 拿到的是外层完整区间，而不是第一个 `</div>`。
     */
    private fun findElementRange(markup: String, start: Int, tagName: String): IntRange? {
        val openPattern = Regex("""<${Regex.escape(tagName)}\b[^>]*>""", RegexOption.IGNORE_CASE)
        val closePattern = Regex("""</${Regex.escape(tagName)}\s*>""", RegexOption.IGNORE_CASE)
        var depth = 1
        var cursor = start + 1
        while (cursor < markup.length && depth > 0) {
            val openStart = openPattern.find(markup, cursor)?.range?.first ?: Int.MAX_VALUE
            val closeStart = closePattern.find(markup, cursor)?.range?.first ?: Int.MAX_VALUE
            when {
                openStart == Int.MAX_VALUE && closeStart == Int.MAX_VALUE -> return null

                openStart < closeStart -> {
                    depth++
                    cursor = (markup.indexOf('>', openStart) + 1).coerceAtLeast(cursor + 1)
                }

                else -> {
                    depth--
                    cursor = (markup.indexOf('>', closeStart) + 1).coerceAtLeast(cursor + 1)
                    if (depth == 0) {
                        return start until cursor
                    }
                }
            }
        }
        return null
    }

    /** 删除若干区间；先按起点合并重叠区间，再从后往前删，保证前面的下标始终有效。 */
    private fun removeRanges(markup: String, ranges: List<IntRange>): String {
        if (ranges.isEmpty()) {
            return markup
        }
        val merged = mutableListOf<IntRange>()
        ranges.sortedBy { it.first }.forEach { range ->
            val last = merged.lastOrNull()
            if (last != null && range.first <= last.last) {
                merged[merged.lastIndex] = last.first..maxOf(last.last, range.last)
            } else {
                merged += range
            }
        }
        var result = markup
        merged.sortedByDescending { it.first }.forEach { range ->
            if (range.first >= 0 && range.last < result.length) {
                result = result.removeRange(range)
            }
        }
        return result
    }

    private fun normalizePlainText(text: String): String {
        return text
            .let(::decodeEntities)
            .replace('\u00A0', ' ')
            .replace(Regex("""[ \t\x0B\f\r]+"""), " ")
            .lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\n")
            .trim()
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
     *
     * ## 排除规则（缺一条就会整本书被毁）
     *
     * - **N1 目录页整页跳过**：文件名含 `toc|nav|contents|目录`，或正文含 `<nav>` /
     *   `epub:type="toc"`。目录页链接密度最高，不排除会产生成百上千条假脚注。
     * - **N1' `<nav>` 内的锚点逐条排除**：某些书把目录内联在正文文档里。
     * - **N2 长文本交叉引用**：脚注标记一定是短标记，锚文本超过 `MAX_MARKER_LABEL_LENGTH` 直接排除。
     * - **N3 "返回正文"回链**：锚文本含 `返回 / 回正文 / back / ↩ / ↑ / ^` 的排除
     *   （它属于注释条目本身，不是脚注引用）。
     * - **N4 外链**：href 带 URI scheme（`http:` `mailto:` …）的排除。
     * - **N5 图片链接**：`<a>` 里包着 `<img>` 的排除。
     * - **N6 悬空链接**：href 没有 fragment，或 fragment 在本文档里找不到对应 id 的，排除。
     * - **N7 自指**：`id == fragment` 的排除。
     * - **N8 两通道去重**：落在通道 A 已消耗区间内的锚点直接跳过（元素级去重，见 [mergeFootnotes]）。
     */
    private fun collectMutualAnchorFootnotes(
        body: String,
        documentPath: String,
        consumedRanges: List<IntRange>,
    ): List<EpubFootnote> {
        val anchors = anchorRegex.findAll(body)
            .mapNotNull { match -> parseAnchor(match) }
            .filter { anchor -> !isInsideNav(body, anchor.range.first) }
            .filter { anchor -> consumedRanges.none { range -> anchor.range.first in range } }
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
            // N6：对端 id 必须真实存在，否则是悬空链接
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
                docId = documentPath,
                elementIndex = MUTUAL_ELEMENT_INDEX_BASE + result.size,
                id = ref.id,
                refFragment = noteAnchor.id,
                number = number,
                label = ref.label,
                text = noteText,
                bodyBlockRange = container.range,
            )
        }
        return result.sortedBy { it.number }
    }

    /** N1：目录页整页跳过。 */
    private fun isTocDocument(documentPath: String, body: String): Boolean {
        val fileName = documentPath.substringAfterLast('/', "").lowercase()
        return TOC_NAME_REGEX.containsMatchIn(fileName) || TOC_MARKER_REGEX.containsMatchIn(body)
    }

    /** N1'：锚点是否落在 `<nav>` 元素内部。 */
    private fun isInsideNav(body: String, position: Int): Boolean {
        val open = body.lastIndexOf("<nav", position, ignoreCase = true)
        if (open < 0) {
            return false
        }
        val close = body.lastIndexOf("</nav", position, ignoreCase = true)
        return close < open
    }

    /** 锚文本 → 脚注编号；不是 "[1]" 这类标记时返回 null。 */
    private fun footnoteMarkerNumber(label: String): Int? {
        val text = stripInlineMarkup(label).trim()
        return footnoteMarkerRegex.find(text)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
    }

    /**
     * 取注释正文开头的编号标记（"1. 六角括号…" → "1."，"[3] 译者注" → "[3]"）。
     *
     * 只认**开头**且后面还有正文的情况；整段就是一个数字的（比如分节号 "1"）不算标记。
     * 这样"书里原本的编号"能传给 [FootnoteRefBlock.label]，弹窗里可以和插件编号并列显示。
     */
    private fun leadingMarkerLabel(text: String): String? {
        val firstLine = text.lineSequence().firstOrNull { it.isNotBlank() }?.trim() ?: return null
        val match = leadingMarkerRegex.find(firstLine) ?: return null
        val label = match.value.trim()
        if (label.isEmpty() || label.length >= firstLine.length) {
            return null
        }
        return label
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

    /**
     * 两通道合并：通道 A（标注）优先级最高，通道 B 只补 A 没认出来的。
     *
     * 元素级去重按 `(docId, elementIndex)`：同一本书里如果两条通道命中了同一个元素，
     * 后到的那条直接丢弃（通道 B 还会额外跳过落在 A 已消耗区间内的锚点）。
     *
     * 编号撞车时顺延到"当前最大编号 + 1"，避免两条注释都叫 "[注1]"
     * （通道 A 的编号取自书里标记，同一章里出现两个 "[1]" 是可能的）。
     */
    private fun mergeFootnotes(
        standard: List<EpubFootnote>,
        mutual: List<EpubFootnote>,
    ): List<EpubFootnote> {
        val usedNumbers = mutableSetOf<Int>()
        val seenElements = mutableSetOf<Pair<String, Int>>()
        val result = mutableListOf<EpubFootnote>()
        var next = 1

        fun add(note: EpubFootnote) {
            if (!seenElements.add(note.docId to note.elementIndex)) {
                return
            }
            val number = if (usedNumbers.add(note.number)) {
                note.number
            } else {
                var candidate = next
                while (!usedNumbers.add(candidate)) {
                    candidate++
                }
                candidate
            }
            next = maxOf(next, number + 1)
            result += if (number == note.number) note else note.copy(number = number)
        }

        standard.forEach(::add)
        mutual.forEach(::add)
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
                        pieces += BodyPiece.FootnoteRef(note.id, note.number, note.label)
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
            return newSecureDocumentBuilder(allowDoctype = false)
                .parse(ByteArrayInputStream(bytes))
        } catch (_: Throwable) {
            error(errorMessage)
        }
    }

    /**
     * 解析 XHTML 正文（带 DOCTYPE，所以不能禁用 doctype-decl）。
     *
     * XXE 防护与 [parseXml] 同源：关闭外部 DTD / SCHEMA、禁用实体展开，
     * 并用空 [EntityResolver] 兜底，外部实体即便被声明也取不到内容。
     */
    private fun parseXhtml(markup: String): Document {
        return newSecureDocumentBuilder(allowDoctype = true)
            .parse(ByteArrayInputStream(markup.toByteArray(utf8)))
    }

    private fun newSecureDocumentBuilder(allowDoctype: Boolean): DocumentBuilder {
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = true
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
        if (!allowDoctype) {
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        }
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
        // 注意：这两个是"只有 setter、没有 getter"的 Java 方法，Kotlin 不会为它们合成属性，
        // 必须显式调用 setter（写成 `isExpandEntityReferences = false` 会编译不过）。
        factory.setExpandEntityReferences(false)
        return factory.newDocumentBuilder().apply {
            setEntityResolver(EntityResolver { _, _ -> InputSource(ByteArrayInputStream(ByteArray(0))) })
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
            .let(::foldSourceNewlines) // 必须第一步：把源码缩进换行折叠掉，见 [foldSourceNewlines]
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

    /**
     * 折叠 XHTML **源码**里的换行（连同两侧缩进空白）为单个空格。
     *
     * 为什么必须做、且必须做在 [stripStructuredMarkup] 的最开头：
     * EPUB 的 XHTML 普遍是缩进排版的，段落里的**行内元素**（最典型的就是脚注引用 `<a>`）
     * 前后各有一个源码换行。它们只是给人看源码用的，**不是语义换行**。
     * 留着的话正文会被切成「…前文 / `[注3]` / 后文…」三块，渲染层只能把 `[注3]`
     * 排成独占一行，段落还被拆出多余空行（0.5.0 用户截图里那个 bug）。
     *
     * 真正的语义换行由**后面**的步骤产生（`<br>` → [lineBreakRegex]、块级标签 → [blockTagRegex]），
     * 所以这两步必须排在折叠之后，不能被折叠吃掉。
     *
     * `<pre>` 是例外：那里的换行是**内容**而非排版，原样保留。
     */
    private fun foldSourceNewlines(markup: String): String {
        val protectedRanges = preformattedRegex.findAll(markup).map { it.range }.toList()
        if (protectedRanges.isEmpty()) {
            return sourceNewlineRegex.replace(markup, " ")
        }
        val builder = StringBuilder(markup.length)
        var cursor = 0
        sourceNewlineRegex.findAll(markup).forEach { match ->
            // 命中 `<pre>` 内部的换行：跳过，不折叠。
            if (protectedRanges.any { range -> match.range.first >= range.first && match.range.last <= range.last }) {
                return@forEach
            }
            builder.append(markup, cursor, match.range.first).append(' ')
            cursor = match.range.last + 1
        }
        builder.append(markup, cursor, markup.length)
        return builder.toString()
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

    /**
     * 一条注释。
     *
     * @property docId 所属文档（zip 路径）；与 [elementIndex] 一起构成去重键。
     * @property elementIndex 文档内元素序号。通道 A 取 `[0, n)`，通道 B 取
     *   `[MUTUAL_ELEMENT_INDEX_BASE, …)`，两区间互不相交，避免去重键误撞。
     * @property id 注释 id：引用点锚点的 id（正文里 `[注N]` 指向它）。
     * @property refFragment 指向注释条目的 href fragment。
     * @property number 插件显示的编号（可能和书里原本的编号不同）。
     * @property label 书里原本的标记文本，形如 "[3]"，用于弹窗标题与对不上时排查。
     * @property text 注释正文纯文本。
     * @property bodyBlockRange 注释条目所在块元素在 body 中的范围（仅通道 B 有，用于整块删除）。
     */
    private data class EpubFootnote(
        val docId: String,
        val elementIndex: Int,
        val id: String,
        val refFragment: String,
        val number: Int,
        val label: String,
        val text: String,
        val bodyBlockRange: IntRange? = null,
    )

    /** 通道 A 的 DOM 命中结果：一个带 footnote 语义、且带 id 的元素。 */
    private data class FootnoteElementHit(
        val id: String,
        val tagName: String,
        val text: String,
        /** 元素自身那个 `<a id="...">` 的锚文本（"[3]" 之类），可能没有 */
        val anchorLabel: String?,
    )

    private data class EpubAnchor(
        val id: String,
        val fragment: String,
        val label: String,
        val range: IntRange,
    )

    /**
     * 解析一个 `<a>` 并应用 N2–N7 排除规则；返回 null 表示它不可能是脚注锚点。
     *
     * 排除：N4 外链（带 URI scheme）、N6 无 fragment、N7 自指、N5 图片链接、
     * N2 长文本交叉引用、N3 "返回正文"回链。
     */
    private fun parseAnchor(match: MatchResult): EpubAnchor? {
        val attrs = match.groupValues[1]
        val id = attributeValue(attrs, "id") ?: return null
        val href = attributeValue(attrs, "href") ?: return null
        if (hasUriScheme(href) || !href.contains('#')) {
            return null
        }
        val fragment = href.substringAfterLast('#', "")
        if (id.isBlank() || fragment.isBlank() || id == fragment) {
            return null
        }
        val inner = match.groupValues[2]
        if (IMAGE_TAG_REGEX.containsMatchIn(inner)) {
            return null
        }
        val label = stripInlineMarkup(inner)
        if (label.length > MAX_MARKER_LABEL_LENGTH || BACK_LINK_REGEX.containsMatchIn(label)) {
            return null
        }
        return EpubAnchor(id, fragment, label, match.range)
    }

    private fun hasUriScheme(href: String): Boolean = URI_SCHEME_REGEX.containsMatchIn(href)

    private sealed interface BodyPiece {
        data class Text(val value: String) : BodyPiece

        data class Image(val spec: ImageSpec, val inline: Boolean) : BodyPiece

        data class FootnoteRef(val footnoteId: String, val number: Int, val label: String) : BodyPiece
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
                        // 纯文本贡献仍是 "[注N]"（与 0.4.1 起的口径一致，位置不会漂移）；
                        // piece.label 只是元数据，供弹窗显示书里原本的编号。
                        val label = "[注${piece.number}]"
                        val start = offset
                        offset += label.length
                        blocks += FootnoteRefBlock(start, offset, piece.footnoteId, piece.number, piece.label)
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
