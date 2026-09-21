package com.chen.reader

import com.chen.reader.model.Chapter

/**
 * 纯文本（TXT）章节识别。
 *
 * ## 规则分「强 / 弱」，弱规则必须带「布局证据」
 *
 * 用户反馈「章节识别支持太弱」——症状是常见写法识别不出、标题显示不对。放宽是主方向，
 * 但放宽必然引入误判（正文里成串的编号列表最容易中招）。
 *
 * 取舍原则：**强规则命中即认；新增/放宽的弱规则必须额外满足「布局证据」，保证最坏情况不比放宽前差。**
 *
 * - 强规则：`第N章/回/节/卷/集/部/篇`、无「第」的 `卷N/篇N/章N/部N`、英文
 *   `Chapter 3 / Chapter One / Prologue / Part I`、独立标题词、Markdown `#` 标题。
 * - 弱规则：中文序号行 `一、…`、阿拉伯序号行 `1. …`、罗马数字行 `IV. …`。
 *   它们必须满足 [hasLayoutEvidence]：本行是文件首行 / 上一行是空行 / 上一行以句末标点结尾。
 *   于是 `他列出了三点：` 紧跟的 `1. 第一点内容` 通不过（上一行以 `：` 收尾），
 *   而 `\n\n一、稻草人手记` 能通过。
 *
 * ## 三个已知误判点的修正
 *
 * 1. 阿拉伯数字行会吃掉小数/日期（`1.5 倍`、`2024.10.01`）→ 分隔符后加负向断言 `(?![0-9０-９])`。
 * 2. 罗马数字行若允许空白分隔会吃掉英文句子（`I am a student`）→ 只允许 `、.．`，且**只认大写**（不用 `i` 标志）。
 * 3. 单独一行「开头」要有下限阈值：前导内容不足 [PREAMBLE_MIN_LENGTH] 时不补，避免多出一个几十字的垃圾章节。
 *
 * 另加一条（设计文档里没有）：`第二卷第216页` 这类跨卷页码引用会被强规则吃掉，显式拒绝。
 */
object ChapterParser {

    /** 行首/行尾水平空白：半角空格、制表符、全角空格 U+3000、不换行空格 U+00A0 */
    private const val HS = "[ \\t\\u3000\\u00A0]"

    /** 数字：半角/全角阿拉伯数字 + 中文数字 */
    private const val NUM = "[0-9０-９零〇一二两三四五六七八九十百千万]{1,12}"

    /** 独立标题词。长词必须排在短词前面，否则「序言」会被「序」抢先匹配 */
    private const val STANDALONE =
        "序言|自序|代序|总序|译序|推荐序|序|前言|引言|引子|楔子|缘起|绪论|后记|后序|尾声|终章|结语|结束语|跋|附录|附记|番外|外传|特别篇|内容简介|内容提要|简介|导读|主要人物|人物表"

    /**
     * 一条识别规则。
     *
     * @param pattern 行级正则（一律锚定 `^…$`，`(?m)` 多行模式）
     * @param needsLayoutEvidence 弱规则：命中后还必须通过 [hasLayoutEvidence]
     */
    private data class HeadingRule(val pattern: Regex, val needsLayoutEvidence: Boolean)

    /** 规则表。**顺序即优先级**：先命中的规则认领该行起点，后面的规则不再重复认领。 */
    private val rules = listOf(
        // 1 第N章 / 回 / 节 / 卷 / 集 / 部 / 篇
        HeadingRule(Regex("""(?im)^$HS*第$HS*$NUM$HS*[章节回卷集部篇][^\r\n]*$HS*$"""), false),
        // 2 无「第」：卷N / 篇N / 章N / 部N
        HeadingRule(Regex("""(?im)^$HS*[卷篇章部]$HS*$NUM[^\r\n]*$HS*$"""), false),
        // 3 英文
        HeadingRule(
            Regex(
                """(?im)^$HS*(chapter|part|book|section|prologue|epilogue|appendix|preface|foreword|introduction|afterword)\b[^\r\n]{0,60}$HS*$""",
            ),
            false,
        ),
        // 4 独立标题词：整行 / 词+分隔符+短尾 / 词+空格+短尾
        HeadingRule(
            Regex("""(?im)^$HS*(?:$STANDALONE)(?:$HS*$|$HS*[:：·、\-—(（][^\r\n]{0,30}$|$HS+[^\r\n]{0,30}$)"""),
            false,
        ),
        // 5 Markdown
        HeadingRule(Regex("""(?im)^$HS*#{1,6}$HS+\S[^\r\n]{0,60}$"""), false),
        // 6 中文序号行（弱）
        HeadingRule(
            Regex("""(?im)^$HS*[零〇一二两三四五六七八九十百千]{1,4}$HS*[、.．](?![0-9０-９])[^\r\n]{1,40}$HS*$"""),
            true,
        ),
        // 7 阿拉伯序号行（弱）
        HeadingRule(Regex("""(?im)^$HS*\d{1,4}[、.．](?![0-9０-９])[^\r\n]{1,40}$HS*$"""), true),
        // 8 罗马数字行（弱，只认大写，分隔符只允许 、.．）
        HeadingRule(Regex("""(?m)^$HS*[IVXLCDM]{1,7}[、.．](?![0-9])[^\r\n]{1,40}$HS*$"""), true),
    )

    private val chineseChapterPrefix = Regex("""^第$NUM[章节回卷集部篇]""")

    /** 句末标点：出现即判为正文而非标题（**逗号已移出**，这是本次的关键放宽） */
    private val sentencePunctuation = Regex("""[。！？；]""")

    private val narrativeConnectorAfterMarker =
        Regex("""^第$NUM[章节回卷集部篇][中里内时后前上下一二三四五六七八九十之的了]""")

    /** `第二卷第216页` 这类跨卷页码引用，不是章节标题。 */
    private val pageReference = Regex("""^第$NUM[卷篇部]$HS*第?\d{1,5}$HS*页$""")

    /** 判定「布局证据」用的句末标点集合（含中英文引号与右括号）。 */
    private val SENTENCE_END_CHARS =
        setOf('。', '！', '？', '…', '“', '”', '‘', '’', '"', '\'', '』', '」', '）', '】', '〕')

    private val markdownPrefix = Regex("""^#{1,6}[ \t\u3000\u00A0]*""")
    private val whitespaceRun = Regex("""[\s\u3000\u00A0]+""")

    fun parse(content: String): List<Chapter> {
        val matches = collectHeadings(content)
        if (matches.isEmpty()) {
            return listOf(Chapter("全文", 0, content.length))
        }

        val chapters = mutableListOf<Chapter>()
        val firstStart = matches.first().range.first
        // 前导内容够长才补「开头」；太短就留在原位，免得凭空多出一个几十字的垃圾章节。
        if (firstStart >= PREAMBLE_MIN_LENGTH) {
            chapters += Chapter("开头", 0, firstStart)
        }
        matches.forEachIndexed { index, match ->
            val nextStart = matches.getOrNull(index + 1)?.range?.first ?: content.length
            val title = cleanTitle(match.value).ifBlank { "第 ${index + 1} 章" }
            chapters += Chapter(
                title = title,
                startOffset = match.range.first,
                endOffset = nextStart,
            )
        }
        return chapters
    }

    /**
     * 按规则表顺序收集所有命中行。
     *
     * 高优先级规则先认领行起点（`byStart` 以行起点为键），后面的规则遇到同一行直接跳过；
     * 弱规则还要再过一道 [hasLayoutEvidence]。
     */
    private fun collectHeadings(content: String): List<MatchResult> {
        val byStart = linkedMapOf<Int, MatchResult>()
        rules.forEach { rule ->
            rule.pattern.findAll(content).forEach { match ->
                val lineStart = match.range.first
                if (byStart.containsKey(lineStart)) {
                    return@forEach
                }
                if (!isChapterTitle(cleanTitle(match.value))) {
                    return@forEach
                }
                if (rule.needsLayoutEvidence && !hasLayoutEvidence(content, lineStart)) {
                    return@forEach
                }
                byStart[lineStart] = match
            }
        }
        return byStart.values.sortedBy { it.range.first }
    }

    /**
     * 「布局证据」：挡的是正文里成串的编号列表。满足其一即可：
     *
     * - 本行是文件首行；
     * - 上一行是空行；
     * - 上一行（非空）以句末标点结尾。
     *
     * @param content 全文
     * @param lineStart 命中行的起点偏移
     */
    private fun hasLayoutEvidence(content: String, lineStart: Int): Boolean {
        if (lineStart <= 0) {
            return true
        }
        // 只回退**一个**行终止符（\n / \r\n / \r）——多退会把上一行的空行一起跳过去，
        // 于是「空行在前」的弱规则会被误判成「上一行是正文」。
        var previousEnd = lineStart
        if (content[previousEnd - 1] == '\n') {
            previousEnd--
            if (previousEnd > 0 && content[previousEnd - 1] == '\r') {
                previousEnd--
            }
        } else if (content[previousEnd - 1] == '\r') {
            previousEnd--
        }
        // 上一行起点
        var previousStart = previousEnd
        while (previousStart > 0 && content[previousStart - 1] != '\n' && content[previousStart - 1] != '\r') {
            previousStart--
        }
        val previousLine = content.substring(previousStart, previousEnd)
        if (previousLine.isBlank()) {
            return true
        }
        return previousLine.trimEnd().lastOrNull() in SENTENCE_END_CHARS
    }

    /** 标题是否可接受：长度、句末标点、叙事连接词、页码引用、中文前缀后缀长度。 */
    private fun isChapterTitle(title: String): Boolean {
        if (title.length > MAX_TITLE_LENGTH) {
            return false
        }
        if (sentencePunctuation.containsMatchIn(title)) {
            return false
        }
        if (narrativeConnectorAfterMarker.containsMatchIn(title)) {
            return false
        }
        if (pageReference.containsMatchIn(title)) {
            return false
        }

        val prefix = chineseChapterPrefix.find(title)?.value ?: return true
        val suffix = title.removePrefix(prefix).trim()
        return suffix.isEmpty() || suffix.length <= MAX_CHINESE_TITLE_SUFFIX_LENGTH
    }

    /** 标题清洗：去 Markdown `#` 前缀 → 连续空白折叠为单空格 → trim → 去行尾 `。：`。 */
    private fun cleanTitle(raw: String): String {
        val withoutHash = raw.trim().replaceFirst(markdownPrefix, "")
        val collapsed = whitespaceRun.replace(withoutHash, " ")
        return collapsed.trim().trimEnd('。', '：')
    }

    /** 章节标题最大长度。 */
    private const val MAX_TITLE_LENGTH = 60

    /** `第N章` 之后允许的标题尾巴最大长度。 */
    private const val MAX_CHINESE_TITLE_SUFFIX_LENGTH = 40

    /** 前导内容达到这个长度才补「开头」章节。 */
    private const val PREAMBLE_MIN_LENGTH = 200
}
