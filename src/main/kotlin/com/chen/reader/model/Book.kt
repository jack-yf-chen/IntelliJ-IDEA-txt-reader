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
    val footnoteBodies = blocks
        .filterIsInstance<FootnoteBodyBlock>()
        .associateBy { it.footnoteId }

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
                body = footnoteBodies[block.footnoteId]?.text.orEmpty(),
                label = block.label,
            )

            else -> Unit
        }
    }
    return result
}

data class Chapter(
    val title: String,
    val startOffset: Int,
    val endOffset: Int,
) {
    override fun toString(): String = title
}
