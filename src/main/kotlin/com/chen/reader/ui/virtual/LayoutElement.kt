package com.chen.reader.ui.virtual

/**
 * 排版元素：`VirtualReaderPane` 一次 layout 的产出。
 *
 * 0.5.0 之前排版只有一种元素——等高的文本行（老 `VirtualLine`），行高恒定，
 * 所以 `y = 行序号 × lineHeight` 一步除法就能定位。引入图片后**行高不再相等**，
 * 因此改成"密封接口 + y 游标累积"：每个元素自带 `y` 和 `height`，
 * 所有 offset ↔ y 的换算统一走元素表，不再假设等高。
 *
 * 不变式（位置恢复依赖它，改动前请三思）：
 *
 * 1. 元素按 `y` 升序、**互不重叠**；
 * 2. 元素按 `startOffset` 升序、**互不重叠**，且 `endOffset` 与下一个 `startOffset` 之间
 *    最多只差"被跳过的空块"——`plainText` 的字符偏移语义不受影响；
 * 3. 文本行的 `[startOffset, endOffset)` 就是 `plainText` 的真区间，
 *    所以划词选区仍然是 `String.substring`，`ReaderPanel` 的位置恢复链路零改动。
 */
internal sealed interface LayoutElement {
    /** 该元素在 `Book.plainText` 中的起始字符偏移（左闭） */
    val startOffset: Int

    /** 该元素在 `Book.plainText` 中的结束字符偏移（右开） */
    val endOffset: Int

    /** 元素顶部 y 坐标（组件坐标系，已含 `contentInsets.top`） */
    val y: Int

    /** 元素占的高度，单位像素 */
    val height: Int

    /** 元素底部 y 坐标（右开） */
    val bottom: Int get() = y + height
}

/**
 * 一行文本。
 *
 * `xPositions[i]` 是"行内第 i 个字符之前"的横向像素偏移，长度 `text.length + 1`，
 * 用于把鼠标 x 坐标换算回字符偏移（划词选区）。与老 `VirtualLine` 的算法完全一致。
 */
internal data class TextLineElement(
    override val startOffset: Int,
    override val endOffset: Int,
    override val y: Int,
    override val height: Int,
    val text: String,
    val xPositions: IntArray,
    /**
     * 行首**不显示也不占宽度**的字符数（标题的 markdown 前缀 `## `）。
     *
     * 这些字符在 `xPositions` 里宽度为 0，但仍然留在 `[startOffset, endOffset)` 区间内 ——
     * "行覆盖全文"的既有假设不能破，否则 `#` 那几个字符不属于任何行，
     * 位置恢复 / 选区 / `scrollValueForOffset` 全都会错位。
     */
    val hiddenPrefixLength: Int = 0,
    /**
     * 整行的水平绘制偏移（标题居中用），≥ 0。
     *
     * 只是**绘制偏移**：`plainText` 与字符偏移语义完全不受影响。
     */
    val centerShift: Int = 0,
) : LayoutElement {
    /** 字符偏移 → 行内 x 像素（**不含** [centerShift]） */
    fun xForOffset(offset: Int): Int {
        val index = (offset - startOffset).coerceIn(0, xPositions.lastIndex)
        return xPositions[index]
    }

    /** 字符偏移 → 实际绘制的 x 像素（含 [centerShift]） */
    fun drawXForOffset(offset: Int): Int = centerShift + xForOffset(offset)

    /** 行内 x 像素 → 字符偏移（用于鼠标划词），取最近的一个字符边界 */
    fun offsetForX(x: Int): Int {
        if (xPositions.isEmpty()) {
            return startOffset
        }
        val insertion = xPositions.binarySearch(x)
        val index = if (insertion >= 0) {
            insertion
        } else {
            val next = (-insertion - 1).coerceIn(0, xPositions.lastIndex)
            val previous = (next - 1).coerceAtLeast(0)
            if (x - xPositions[previous] <= xPositions[next] - x) previous else next
        }
        return (startOffset + index).coerceIn(startOffset, endOffset)
    }

    /**
     * 绘制坐标 x → 字符偏移（含 [centerShift] 反算）。
     *
     * 结果**不会**落进隐藏前缀里：那些字符没有视觉宽度，点不到它们。
     */
    fun offsetForDrawX(x: Int): Int =
        offsetForX(x - centerShift).coerceAtLeast((startOffset + hiddenPrefixLength).coerceAtMost(endOffset))
}

/**
 * 一个图片块（块级图与行内图在 0.5.0 首批都按块级渲染，见 T62）。
 *
 * **高度只由 `intrinsicWidth` / `intrinsicHeight` 换算得出**，这两个值来自 `ResourceMeta`、
 * 不经过解码（设计文档 §8 硬性约束）。解码是 T43 的后台异步动作，
 * 解码完成后**只 repaint，绝不 relayout**——图片高度在排版期就已经定死了。
 */
internal data class ImageElement(
    override val startOffset: Int,
    override val endOffset: Int,
    override val y: Int,
    override val height: Int,
    /** 图片框左边距（= `contentInsets.left`） */
    val x: Int,
    /** 图片框宽度（已按可用宽度与内建宽高缩放） */
    val width: Int,
    val resourceId: String,
    val alt: String,
    val isVector: Boolean,
    /** 纯文本降级占位，形如 "[图片：alt]"；解码失败时显示在框里 */
    val placeholder: String,
    /** 后台解码的目标宽度：按显示宽度解码，避免为缩略图解码整张原图 */
    val targetWidth: Int,
    /** 图片真正占据的高度（= `height` 减去上下留白） */
    val boxHeight: Int,
) : LayoutElement
