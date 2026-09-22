package com.chen.reader.book

import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/**
 * EPUB 目录（TOC）解析 —— **纯函数集合**。
 *
 * 这一层只做「已解析的 DOM → 目录条目表」，不碰 IO、不碰 zip、不碰安全配置：
 * `Document` 由 `EpubBookLoader` 用带 XXE 防护的 `DocumentBuilder` 解析好再传进来。
 * 这样目录解析可以脱离 IDE / 文件系统单测。
 *
 * ## 两种目录来源都必须支持
 *
 * - **EPUB 3 `nav.xhtml`**：`<nav epub:type="toc"> > ol > li > a`（可嵌套 `ol`）。
 * - **EPUB 2 `toc.ncx`**：`<navMap> > navPoint > navLabel/text + content@src`（可嵌套 `navPoint`）。
 *
 * 只认 nav 是不够的：实测样例书里 b1 / b3 **只有 `toc.ncx`、没有 `nav.xhtml`**，
 * 只认 nav 的实现在这两本上会直接退化成「一篇 spine 一章」。
 * 两者都有时（b2）以 **nav 为准**（nav 290 链接 vs ncx 286 条，nav 略全）。
 */
object EpubToc {

    /** 层级选择的条目数下界：某层只有个位数条目时，它通常是"前言/附录/索引"这类零散条目。 */
    private const val MIN_LEVEL_COUNT = 8

    /** 层级选择的条目数上界：b2 的 L3=181 是"节/小节"粒度，作为目录项过碎，必须挡掉。 */
    private const val MAX_LEVEL_COUNT = 120

    /** 补全后的标题长度上限，超过就不补父标题（拼接结果读起来比不补还糟）。 */
    private const val MAX_COMPLETED_TITLE_LENGTH = 80

    /** 触发父标题补全的"标题过短"阈值（去掉空白后）。 */
    private const val SHORT_TITLE_LENGTH = 8

    /** 标题里出现这些成分时说明它自带层级 / 序号，不需要补父标题。 */
    private val SELF_SUFFICIENT_TITLE_REGEX = Regex(
        """第\s*[0-9０-９零〇一二两三四五六七八九十百千万]{1,12}\s*[章节回卷集部篇]|""" +
            """[（(【\[]?\s*\d{1,4}\s*[)）】\]]|""" +
            """[0-9０-９]{1,4}\s*[、.．]|""" +
            """(?i)\b(chapter|part|book|section|appendix|prologue|epilogue)\b|""" +
            // 注意：不能把 `§` 算进来。b1 的二级标题正是「§1」「§2」，它们是**最需要**补父标题的
            // （「§1」单独显示毫无可读性，补全后是「第一篇 世界作为表象初论 · §1」）。
            """[章回卷集部篇]""",
    )

    /**
     * 一条目录条目。
     *
     * @property index 目录顺序下标（也是 `EpubBookLoader` 里 TOC 哨兵的编号）
     * @property depth 层级深度，**1 起**
     * @property parentIndex 最近的有效祖先下标，没有祖先为 -1
     * @property title 目录里的标题原文（未补全）
     * @property docPath 归一化后的 zip 路径（已解析相对路径，未含 fragment）
     * @property fragment URL 解码后的 fragment，没有则为 ""
     */
    data class TocEntry(
        val index: Int,
        val depth: Int,
        val parentIndex: Int,
        val title: String,
        val docPath: String,
        val fragment: String,
    )

    // ------------------------------------------------------------------ 解析入口

    /**
     * 解析 EPUB 3 的 `nav.xhtml`。
     *
     * @param document 已解析好的 nav 文档
     * @param baseDir nav 文件所在目录（zip 内相对路径），用于解析 `href`
     * @return 目录条目（文档顺序、深度递增）；没有可用 `<nav>` 时返回 emptyList
     */
    fun parseNav(document: Document, baseDir: String): List<TocEntry> {
        val nav = findTocNav(document) ?: return emptyList()
        val result = mutableListOf<TocEntry>()
        // nav 下可能直接是 <ol>，也可能包一层 <div>。取 nav 内第一个 <ol>。
        findDirectChild(nav, "ol")?.let { walkNavList(it, 1, -1, baseDir, result) }
        return result
    }

    /**
     * 解析 EPUB 2 的 `toc.ncx`。
     *
     * @param document 已解析好的 ncx 文档
     * @param baseDir ncx 文件所在目录，用于解析 `content/@src`
     */
    fun parseNcx(document: Document, baseDir: String): List<TocEntry> {
        val navMap = firstDescendant(document, "navMap") ?: return emptyList()
        val result = mutableListOf<TocEntry>()
        walkNavPoints(navMap, 1, -1, baseDir, result)
        return result
    }

    // ------------------------------------------------------------------ 层级选择

    /**
     * 按 `[MIN_LEVEL_COUNT, MAX_LEVEL_COUNT]` 规则挑出**最深合格层**，并补上
     * 「更浅层里的叶子条目」。
     *
     * 选中集合 = `{ e | e.depth == L* }` ∪ `{ e | e.depth < L* 且 e 没有任何子条目 }`。
     *
     * 第二项是必要的：一个"附录"式顶层条目如果没有子条目，它本身就是一章，
     * 只取 `depth == L*` 会把它（以及它覆盖的正文）整段漏掉。
     *
     * 三条退化（候选层为空 / 全部超上界）：见实现。
     */
    fun selectLevel(entries: List<TocEntry>): List<TocEntry> {
        if (entries.isEmpty()) {
            return emptyList()
        }
        val countsByDepth = entries.groupingBy { it.depth }.eachCount()
        val candidateDepths = countsByDepth
            .filter { (_, count) -> count in MIN_LEVEL_COUNT..MAX_LEVEL_COUNT }
            .keys
            .sorted()

        val selectedDepth = when {
            // 主规则：候选层里最深的那个
            candidateDepths.isNotEmpty() -> candidateDepths.last()
            // 退化 1：没有合格层 → 取 n ≤ 上界里 n 最大的那层（最接近上界）
            else -> {
                val under = countsByDepth.filter { (_, count) -> count <= MAX_LEVEL_COUNT }
                if (under.isNotEmpty()) {
                    under.maxBy { it.value }.key
                } else {
                    // 退化 2：所有层都超上界 → 取条目最少（最浅）的一层
                    countsByDepth.minBy { it.value }.key
                }
            }
        }

        val hasChild = mutableSetOf<Int>()
        entries.forEach { if (it.parentIndex >= 0) hasChild += it.parentIndex }
        return entries.filter { it.depth == selectedDepth || (it.depth < selectedDepth && it.index !in hasChild) }
    }

    /**
     * 父标题补全：返回与 [entries] 一一对应的**补全后**标题。
     *
     * 触发条件：标题去掉空白后长度 ≤ [SHORT_TITLE_LENGTH]，**或**标题里不含任何
     * 层级 / 序号成分（[SELF_SUFFICIENT_TITLE_REGEX] 不命中）。
     * b1 的 L2 标题是裸的「§1」「§2」，直接显示完全没有可读性，补全成「第一篇 · §1」。
     *
     * 跳过：无祖先、祖先标题与本条相同、拼接后长度 > [MAX_COMPLETED_TITLE_LENGTH]。
     */
    fun completeTitles(entries: List<TocEntry>): List<String> {
        if (entries.isEmpty()) {
            return emptyList()
        }
        val byIndex = entries.associateBy { it.index }
        return entries.map { entry ->
            val trimmed = entry.title.trim()
            val selfSufficient = SELF_SUFFICIENT_TITLE_REGEX.containsMatchIn(trimmed) ||
                trimmed.replace(Regex("""\s+"""), "").length > SHORT_TITLE_LENGTH
            if (selfSufficient) {
                return@map trimmed
            }
            val ancestor = nearestAncestorTitle(entry, byIndex)
            if (ancestor.isNullOrBlank() || ancestor == trimmed) {
                return@map trimmed
            }
            val completed = "$ancestor · $trimmed"
            if (completed.length > MAX_COMPLETED_TITLE_LENGTH) trimmed else completed
        }
    }

    /** 沿 [TocEntry.parentIndex] 向上找第一个标题非空的祖先。 */
    private fun nearestAncestorTitle(entry: TocEntry, byIndex: Map<Int, TocEntry>): String? {
        var cursor = entry.parentIndex
        var guard = 0
        while (cursor >= 0 && guard++ < 64) {
            val parent = byIndex[cursor] ?: return null
            val title = parent.title.trim()
            if (title.isNotBlank()) {
                return title
            }
            cursor = parent.parentIndex
        }
        return null
    }

    // ------------------------------------------------------------------ nav.xhtml

    /**
     * 找到目录用的 `<nav>`。
     *
     * 优先 `epub:type`（或 `type`）含 `toc` 的；没有就取文档里**第一个**
     * 既不是 `landmarks` 也不是 `page-list` 的 `<nav>`。
     */
    private fun findTocNav(document: Document): Element? {
        val navs = document.getElementsByTagNameNS("*", "nav")
        var fallback: Element? = null
        for (index in 0 until navs.length) {
            val nav = navs.item(index) as? Element ?: continue
            val type = nav.getAttribute("epub:type")
                .ifBlank { nav.getAttributeNS("http://www.idpf.org/2007/ops", "type") }
                .ifBlank { nav.getAttribute("type") }
                .lowercase()
            if (type.contains("toc")) {
                return nav
            }
            if (type.contains("landmarks") || type.contains("page-list")) {
                continue
            }
            if (fallback == null) {
                fallback = nav
            }
        }
        return fallback
    }

    /** 递归走 `ol/li/a`，[depth] 为该 `ol` 所在层级。 */
    private fun walkNavList(
        ol: Element,
        depth: Int,
        parentIndex: Int,
        baseDir: String,
        out: MutableList<TocEntry>,
    ) {
        eachDirectChild(ol, "li") { li ->
            val anchor = firstOwnAnchor(li)
            val rawHref = anchor?.getAttribute("href").orEmpty()
            val title = anchor?.textContent?.orEmpty()
                ?.ifBlank { firstDescendant(li, "span")?.textContent.orEmpty() }
                ?.let(::cleanTocTitle)
                .orEmpty()
            if (title.isNotBlank() && rawHref.isNotBlank()) {
                val entry = TocEntry(
                    index = out.size,
                    depth = depth,
                    parentIndex = parentIndex,
                    title = title,
                    docPath = normalizeZipPath(baseDir, rawHref),
                    fragment = decodeFragment(rawHref),
                )
                out += entry
                // 嵌套 ol 只在 li 的直接子节点里找，避免把"孙列表"当成子列表
                findDirectChild(li, "ol")?.let { nested ->
                    walkNavList(nested, depth + 1, entry.index, baseDir, out)
                }
                return@eachDirectChild
            }
            // 没有 <a>（纯分组 <li><span>…</span><ol>…</ol>）：不产条目，但子列表仍要收，
            // 且深度保持 —— 否则"第一章"这种分组层会把子项深度抬高一级。
            findDirectChild(li, "ol")?.let { nested ->
                walkNavList(nested, depth, parentIndex, baseDir, out)
            }
        }
    }

    /** `li` 自己的 `<a>`：直接子节点优先，其次是"不在嵌套 ol 内"的第一个 `<a>`。 */
    private fun firstOwnAnchor(li: Element): Element? {
        findDirectChild(li, "a")?.let { return it }
        val anchors = li.getElementsByTagNameNS("*", "a")
        for (index in 0 until anchors.length) {
            val anchor = anchors.item(index) as? Element ?: continue
            if (!isInsideElement(anchor, "ol")) {
                return anchor
            }
        }
        return null
    }

    // ------------------------------------------------------------------ toc.ncx

    private fun walkNavPoints(
        parent: Element,
        depth: Int,
        parentIndex: Int,
        baseDir: String,
        out: MutableList<TocEntry>,
    ) {
        eachDirectChild(parent, "navPoint") { point ->
            val rawSrc = firstDescendant(point, "content")?.getAttribute("src").orEmpty()
            val title = firstDescendant(point, "navLabel")
                ?.let { firstDescendant(it, "text") }
                ?.textContent
                ?.let(::cleanTocTitle)
                .orEmpty()
            if (title.isNotBlank() && rawSrc.isNotBlank()) {
                val entry = TocEntry(
                    index = out.size,
                    depth = depth,
                    parentIndex = parentIndex,
                    title = title,
                    docPath = normalizeZipPath(baseDir, rawSrc),
                    fragment = decodeFragment(rawSrc),
                )
                out += entry
                walkNavPoints(point, depth + 1, entry.index, baseDir, out)
                return@eachDirectChild
            }
            walkNavPoints(point, depth, parentIndex, baseDir, out)
        }
    }

    // ------------------------------------------------------------------ 工具

    /** 取 `#` 之后的部分并单独 URL 解码；没有 fragment 返回 ""。 */
    private fun decodeFragment(rawHref: String): String {
        val hash = rawHref.indexOf('#')
        if (hash < 0) {
            return ""
        }
        return URLDecoder.decode(rawHref.substring(hash + 1), StandardCharsets.UTF_8)
    }

    /** 目录标题清洗：折叠连续空白（含全角空格 / 不换行空格）为单空格后 trim。 */
    private fun cleanTocTitle(raw: String): String {
        return raw.replace(Regex("""[\s\u3000\u00A0]+"""), " ").trim()
    }

    private fun eachDirectChild(parent: Element, tagName: String, action: (Element) -> Unit) {
        val children = parent.childNodes ?: return
        for (index in 0 until children.length) {
            val child = children.item(index) as? Element ?: continue
            if (child.tagName.equals(tagName, ignoreCase = true)) {
                action(child)
            }
        }
    }

    private fun findDirectChild(parent: Element, tagName: String): Element? {
        val children = parent.childNodes ?: return null
        for (index in 0 until children.length) {
            val child = children.item(index) as? Element ?: continue
            if (child.tagName.equals(tagName, ignoreCase = true)) {
                return child
            }
        }
        return null
    }

    /** 第一个同名后代（广度不优先，够用；目录文件不会深到需要分层搜索）。 */
    private fun firstDescendant(parent: Node, tagName: String): Element? {
        if (parent is Element && parent.tagName.equals(tagName, ignoreCase = true)) {
            return parent
        }
        val children = parent.childNodes ?: return null
        for (index in 0 until children.length) {
            val found = firstDescendant(children.item(index), tagName)
            if (found != null) {
                return found
            }
        }
        return null
    }

    /** [element] 是否位于某个 [tagName] 元素内部（用于排掉嵌套列表里的 `<a>`）。 */
    private fun isInsideElement(element: Element, tagName: String): Boolean {
        var node: Node? = element.parentNode
        var guard = 0
        while (node != null && guard++ < 64) {
            if (node is Element && node.tagName.equals(tagName, ignoreCase = true)) {
                return true
            }
            node = node.parentNode
        }
        return false
    }
}

/**
 * 归一化 zip 内路径（OPF manifest 与目录 src 共用）。
 *
 * **必须传未解码的原始 href**：函数内部自己会对 `#` 之前的部分做 URL 解码。
 * 调用方预先 decode 一遍会造成二次解码（`%20` → 空格 → 再解一次就错了）。
 * 目录 src 的 fragment 由 `EpubToc` 内部单独解码。
 */
internal fun normalizeZipPath(baseDir: String, rawHref: String): String {
    val decodedHref = URLDecoder.decode(rawHref.substringBefore('#'), StandardCharsets.UTF_8)
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
