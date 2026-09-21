package com.chen.reader.model

/**
 * 内容块。
 *
 * `plainStart` / `plainEnd` 是该块在 `Book.plainText` 中的字符区间（左闭右开），
 * 并且恒有 `块的纯文本贡献 == plainText.substring(plainStart, plainEnd)`。
 *
 * 关键约束 —— 所有块在 plainText 中**连续、不重叠**，且：
 *
 * 1. 块与块之间**不隐含任何分隔符**。换行、空行一律写在块自己的内容里
 *    （通常是 [TextBlock] 的 `text` 结尾带 "\n"）。这样拼接就是精确还原，
 *    不会出现"凑不出原文"的情况。
 * 2. 由此保证：位置恢复（globalOffset / anchorText / permille）语义完全不变、
 *    划词选区仍然是 `String.substring`、右键查词链路零改动。
 */
sealed interface Block {
    val plainStart: Int

    val plainEnd: Int
}

/** 行的呈现样式 */
enum class LineStyle {
    BODY,
    CAPTION,
    HEADING1,
    HEADING2,
    HEADING3,
    QUOTE,
}

/**
 * 纯文本块。
 *
 * 一个块通常是一行或一段，可以跨多行（`text` 内部可含 "\n"），
 * 也可以是只含一个换行的"空行块"（用于表达段落之间的空行）。
 */
data class TextBlock(
    override val plainStart: Int,
    override val plainEnd: Int,
    val text: String,
    val style: LineStyle = LineStyle.BODY,
) : Block

/**
 * 块级图片，独占一行（可带图注，图注由紧随其后的 [CaptionBlock] 表达）。
 *
 * `intrinsicWidth` / `intrinsicHeight` 来自 `ResourceMeta`，**不经过解码**即可获得，
 * 用于在排版期确定绘制高度（`docs/design-epub-c-grade.md` §8 硬性约束）。
 */
data class ImageBlock(
    override val plainStart: Int,
    override val plainEnd: Int,
    val resourceId: String,
    val alt: String,
    val intrinsicWidth: Int,
    val intrinsicHeight: Int,
    val isVector: Boolean,
    val placeholder: String,
) : Block

/** 行内图片，与文字同行（C 档图文混排的第二部分，见 T62，可裁剪） */
data class InlineImageBlock(
    override val plainStart: Int,
    override val plainEnd: Int,
    val resourceId: String,
    val alt: String,
    val intrinsicWidth: Int,
    val intrinsicHeight: Int,
    val isVector: Boolean,
) : Block

/** 图注（`<figcaption>`） */
data class CaptionBlock(
    override val plainStart: Int,
    override val plainEnd: Int,
    val text: String,
) : Block

/** 正文中的脚注引用标记，形如 "[注1]"，可点击 → 弹窗 */
data class FootnoteRefBlock(
    override val plainStart: Int,
    override val plainEnd: Int,
    val footnoteId: String,
    val number: Int,
) : Block

/** 章末注释条目。弹窗方案下**不是跳转目标**，仅用于差异化渲染 */
data class FootnoteBodyBlock(
    override val plainStart: Int,
    override val plainEnd: Int,
    val footnoteId: String,
    val number: Int,
    val text: String,
) : Block

/** 该块在 `plainText` 中实际贡献的字符串。必须与 `Book.buildPlainText` 的拼接口径一致。 */
internal fun plainContentOf(block: Block): String = when (block) {
    is TextBlock -> block.text
    is CaptionBlock -> block.text
    is ImageBlock -> block.placeholder
    is InlineImageBlock -> imagePlaceholder(block.alt)
    is FootnoteRefBlock -> "[注${block.number}]"
    is FootnoteBodyBlock -> "[注${block.number}] ${block.text}"
}

/** 图片在纯文本中的降级占位文字。 */
internal fun imagePlaceholder(alt: String): String {
    val trimmed = alt.trim()
    return if (trimmed.isBlank()) "[图片]" else "[图片：$trimmed]"
}
