package com.chen.reader.model

import com.chen.reader.book.BookResources
import java.nio.charset.Charset
import java.nio.file.Path

/**
 * 一本书。
 *
 * 0.5.0 起 `blocks` 成为正文的唯一真源，`plainText` 由 blocks 拼接派生；
 * 旧的 `content` 保留为过渡别名（指向 `plainText`），让 `ReaderPanel` 里现有的
 * 位置恢复 / 选区 / 查词调用点零改动编译。
 */
data class Book(
    val path: Path,
    val charset: Charset,
    val blocks: List<Block>,
    val chapters: List<Chapter>,
    val resources: BookResources,
) {
    /**
     * 等价纯文本视图：位置恢复 / 选区 / 查词 / 进度全部复用它。
     *
     * 拼接口径与 [plainContentOf] 严格一致：块的内容**直接首尾相接**，
     * 不插入任何额外分隔符（换行写在块内容里），因此 ~10 处 `content` 调用点语义不变。
     */
    val plainText: String by lazy { buildPlainText(blocks) }

    /** block[i] 在 plainText 中的起始偏移，用于 offset ↔ block 二分互转 */
    val blockPlainOffsets: IntArray by lazy { buildOffsets(blocks) }

    /** 可点击热区，由 blocks 派生 */
    val hotSpots: List<HotSpot> by lazy { buildHotSpots(blocks) }

    /** 过渡别名：让现有约 10 处 `content` 调用点零改动编译 */
    val content: String get() = plainText

    /**
     * 返回 `offset` 所在 block 的下标；越界时夹到最近的合法下标，空书返回 -1。
     *
     * 注意此处的"所属"只看 `plainStart`，因为块之间不隐含分隔符：
     * 落在两个相邻块的接缝上时，返回后一个块更符合"光标在此处插入"的直觉。
     */
    fun blockIndexForOffset(offset: Int): Int {
        val offsets = blockPlainOffsets
        if (offsets.isEmpty()) {
            return -1
        }
        val result = offsets.binarySearch(offset)
        return if (result >= 0) {
            result.coerceAtMost(offsets.lastIndex)
        } else {
            (-result - 2).coerceIn(0, offsets.lastIndex)
        }
    }
}

private fun buildPlainText(blocks: List<Block>): String {
    val builder = StringBuilder()
    blocks.forEach { builder.append(plainContentOf(it)) }
    return builder.toString()
}

private fun buildOffsets(blocks: List<Block>): IntArray {
    return IntArray(blocks.size) { index -> blocks[index].plainStart }
}

private fun buildHotSpots(blocks: List<Block>): List<HotSpot> {
    // 按 id 分组**保留全部**注释条目（并保持块的原有顺序）。
    // 不能再用全书级 `associateBy`：`footnoteId` 只在文档内唯一，撞 key 时 `associateBy`
    // 只保留最后一个，会把前面章节的引用点解析到别章的注释正文（详见 [resolveFootnoteBody]）。
    val footnoteBodies = blocks
        .filterIsInstance<FootnoteBodyBlock>()
        .groupBy { it.footnoteId }

    val result = mutableListOf<HotSpot>()
    blocks.forEachIndexed { index, block ->
        when (block) {
            is ImageBlock -> result += ImageHotSpot(
                plainStart = block.plainStart,
                plainEnd = block.plainEnd,
                resourceId = block.resourceId,
                alt = block.alt,
                caption = blocks.getOrNull(index + 1)?.let { it as? CaptionBlock }?.text,
                intrinsicWidth = block.intrinsicWidth,
                intrinsicHeight = block.intrinsicHeight,
            )

            is InlineImageBlock -> result += ImageHotSpot(
                plainStart = block.plainStart,
                plainEnd = block.plainEnd,
                resourceId = block.resourceId,
                alt = block.alt,
                caption = null,
                intrinsicWidth = block.intrinsicWidth,
                intrinsicHeight = block.intrinsicHeight,
            )

            is FootnoteRefBlock -> result += FootnoteHotSpot(
                plainStart = block.plainStart,
                plainEnd = block.plainEnd,
                footnoteId = block.footnoteId,
                number = block.number,
                body = resolveFootnoteBody(footnoteBodies, block),
                label = block.label,
            )

            else -> Unit
        }
    }
    return result
}

/**
 * 解析某个引用点对应的注释正文。
 *
 * ## 为什么不能全书级 `associateBy`
 *
 * `footnoteId` 只在**文档内**唯一，但不少转换器在不同 XHTML 文档之间复用
 * `sd1eNN` / `d1eNN` 这类 id（实测 b1 有 15 个 id 出现在 ≥2 个文档）。
 * `associateBy` 撞 key 时保留最后一个，于是前面章节的引用点会解析到**别的章节**的注释正文，
 * 弹窗显示错内容 —— 实测 b1 有 16/296 个引用点中招（0.7.0 起就有 1 处，属既有缺陷）。
 *
 * ## 解析规则：取引用点**之后**第一个同名条目
 *
 * 这不是启发式，而是**布局保证**：`appendFootnoteSummary` 把注释汇总区**永远追加在本章末尾**，
 * 而引用点一定排在它之前。所以引用点之后第一个同名 `FootnoteBodyBlock` 必然同章。
 *
 * 兜底：找不到时退回最后一个同名条目（等价于改动前的行为），避免把「有正文」变成「空弹窗」。
 *
 * 注：仅影响派生的可点击热区，**不触碰 `Book.plainText`**，故不构成 breaking change。
 */
private fun resolveFootnoteBody(
    bodiesById: Map<String, List<FootnoteBodyBlock>>,
    ref: FootnoteRefBlock,
): String {
    val candidates = bodiesById[ref.footnoteId] ?: return ""
    return candidates.firstOrNull { it.plainStart >= ref.plainEnd }?.text
        ?: candidates.lastOrNull()?.text.orEmpty()
}

data class Chapter(
    val title: String,
    val startOffset: Int,
    val endOffset: Int,
) {
    override fun toString(): String = title
}
