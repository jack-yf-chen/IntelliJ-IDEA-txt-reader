package com.chen.reader.ui.bookshelf

import com.chen.reader.bookshelf.BookCoverLoader
import com.chen.reader.bookshelf.ShelfEntry
import com.intellij.openapi.util.IconLoader
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.Color
import java.awt.Component
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Rectangle
import java.awt.RenderingHints
import javax.swing.Icon
import javax.swing.ImageIcon
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListCellRenderer

/** 卡片内热区编号：0 = 其它（续读）、1 = ★ 收藏、2 = ✕ 移除。 */
const val SPOT_NONE = 0
const val SPOT_STAR = 1
const val SPOT_CLOSE = 2

/**
 * 书架卡片 renderer。
 *
 * **整张卡片由 [paintComponent] 自己绘制**，不用子组件。
 *
 * 为什么不用 `JLabel` + `setBounds`（0.11.0/0.11.1 的做法，实机反馈"只看得见封面、
 * 书名/进度/★/✕ 全都不显示"）：渲染器组件不在视图树里，`JList` 只在绘制瞬间把它
 * 当作一个普通组件 `paint` 一次，子组件的布局与绘制时机不受我们控制 ——
 * 实测只有封面（带 `Icon` 的那个）画了出来，其余带 `text` 的标签一律没画。
 * 自己画既避开了这个坑，也保证"看到的位置"与 [starRectFor] / [closeRectFor]
 * 描述的热区**必然一致**（两者共用 [ICON] / [GAP] 常量）。
 *
 * 卡片常驻信息只放书名和上次阅读时间；路径、格式、进度等详情交给列表 tooltip。
 * 这样窄窗口下不会再把文字、进度条和操作入口挤到封面上。
 *
 * **EDT 零 IO**：封面只从 `BookCoverLoader.coverFor` 读内存，绝不在这里触发任何加载。
 */
class BookCard : JPanel(), ListCellRenderer<ShelfEntry> {
    private var entry: ShelfEntry? = null
    private var selected: Boolean = false
    private var hovered: Boolean = false
    private var spot: Int = SPOT_NONE
    private var listFont: Font = font
    private var normalBackground: Color = UIUtil.getListBackground()
    private var normalForeground: Color = UIUtil.getListForeground()
    private var selectedBackground: Color = UIUtil.getListSelectionBackground(true)
    private var selectedForeground: Color = UIUtil.getListSelectionForeground(true)

    /** 由 `BookshelfPanel` 的鼠标移动监听写入，驱动 hover 高亮。 */
    var hoverIndex: Int = -1

    /** 见 [SPOT_NONE] / [SPOT_STAR] / [SPOT_CLOSE]。 */
    var hoverSpot: Int = SPOT_NONE

    init {
        isOpaque = true
    }

    override fun getListCellRendererComponent(
        list: JList<out ShelfEntry>?,
        value: ShelfEntry?,
        index: Int,
        isSelected: Boolean,
        cellHasFocus: Boolean,
    ): Component {
        entry = value
        selected = isSelected
        hovered = index == hoverIndex
        spot = if (hovered) hoverSpot else SPOT_NONE
        if (list != null) {
            listFont = list.font ?: listFont
            normalBackground = list.background ?: normalBackground
            normalForeground = list.foreground ?: normalForeground
            selectedBackground = list.selectionBackground ?: selectedBackground
            selectedForeground = list.selectionForeground ?: selectedForeground
        }
        background = when {
            isSelected -> selectedBackground
            hovered -> HOVER_BACKGROUND
            else -> normalBackground
        }
        font = listFont
        return this
    }

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        val current = entry ?: return
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            val width = width
            val primary = if (selected) selectedForeground else normalForeground
            val secondary = if (selected) selectedForeground else UIUtil.getInactiveTextColor()
            val missing = ShelfFormat.isMissing(current)

            // ---- 标题（最多两行）
            val titleX = GAP
            val titleY = GAP
            val titleWidth = (width - GAP * 2).coerceAtLeast(0)
            g2.font = listFont.deriveFont(Font.BOLD)
            val titleColor = if (missing) MISSING_COLOR else primary
            g2.color = titleColor
            val titleLines = fitToLines(g2, ShelfFormat.displayTitle(current, missing), titleWidth, MAX_TITLE_LINES)
            val titleLineHeight = g2.fontMetrics.height
            titleLines.forEachIndexed { lineIndex, line ->
                g2.drawString(line, titleX, titleY + g2.fontMetrics.ascent + lineIndex * titleLineHeight)
            }

            // ---- 封面（内存图标，不解码）
            // 必须**等比缩放进封面槽**：`Icon.paintIcon` 是按图标自身尺寸绘制的，而
            // `BookCoverLoader` 给的缩略图最长边是 320 px（b1 实测 216×320），直接
            // `paintIcon` 会画出一张比整行卡片还大的图，把标题 / 路径 / 进度条 / 元信息
            // 全压在底下 —— 实机反馈"封面把卡片内容盖住了"就是这个原因。
            val coverX = (width - COVER_W) / 2
            val coverY = GAP + TITLE_AREA_H
            val cover = BookCoverLoader.getInstance().coverFor(current.pathKey)
            if (cover != null) {
                drawCoverFitted(g2, cover, coverX, coverY, COVER_W, COVER_H)
            } else {
                drawCoverPlaceholder(g2, coverX, coverY, COVER_W, COVER_H)
            }

            // ---- 上次阅读时间
            g2.font = listFont.deriveFont(Font.PLAIN, listFont.size2D - 1f)
            g2.color = if (missing) MISSING_COLOR else secondary
            val lastReadText = if (missing) {
                "文件已被移动或删除"
            } else {
                "上次阅读：${ShelfFormat.formatLastRead(current.lastReadMillis)}"
            }
            val starRect = starRectFor(width)
            val closeRect = closeRectFor(width)
            val footerTextWidth = (starRect.x - GAP * 2).coerceAtLeast(0)
            val footerBaseline = starRect.y + (starRect.height - g2.fontMetrics.height) / 2 + g2.fontMetrics.ascent
            drawClipped(g2, lastReadText, GAP, footerBaseline, footerTextWidth)

            // ---- ★ / ✕（底部右侧，避免占用书名和封面区域）
            g2.font = listFont.deriveFont(Font.PLAIN, listFont.size2D + 4f)
            g2.color = when {
                current.favorite -> STAR_ON_COLOR
                spot == SPOT_STAR -> STAR_HOVER_COLOR
                else -> primary
            }
            drawCentered(g2, if (current.favorite) "★" else "☆", starRect)
            g2.font = listFont.deriveFont(Font.PLAIN, listFont.size2D + 2f)
            g2.color = if (spot == SPOT_CLOSE) CLOSE_HOVER_COLOR else primary
            drawCentered(g2, "✕", closeRect)

            // ---- hover 到 ★ / ✕ 时画一圈底，明确"这里能点"
            g2.color = HOVER_RING_COLOR
            when (spot) {
                SPOT_STAR -> g2.drawRect(starRect.x - 1, starRect.y - 1, starRect.width + 1, starRect.height + 1)
                SPOT_CLOSE -> g2.drawRect(closeRect.x - 1, closeRect.y - 1, closeRect.width + 1, closeRect.height + 1)
            }
        } finally {
            g2.dispose()
        }
    }

    /**
     * 把封面**等比缩放**到 `boxW × boxH` 的槽内并居中，最后描一圈淡边与卡片底色分界。
     *
     * 为什么不能直接 `icon.paintIcon`：那是按图标自身尺寸绘制的，而书架缩略图最长边
     * 是 320 px，直接画会溢出封面槽、盖住整行的文字与进度条。
     *
     * 两条分支：[ImageIcon] 走 `drawImage` 双线性缩放（快，且不失真）；其它 [Icon]
     * （只可能是插画式的 `IconLoader` 图标）用变换矩阵缩放后 `paintIcon`。
     */
    private fun drawCoverFitted(g: Graphics2D, icon: Icon, boxX: Int, boxY: Int, boxW: Int, boxH: Int) {
        val iconW = icon.iconWidth.coerceAtLeast(1)
        val iconH = icon.iconHeight.coerceAtLeast(1)
        val scale = minOf(boxW.toDouble() / iconW, boxH.toDouble() / iconH)
        val drawW = (iconW * scale).toInt().coerceAtLeast(1)
        val drawH = (iconH * scale).toInt().coerceAtLeast(1)
        val x = boxX + (boxW - drawW) / 2
        val y = boxY + (boxH - drawH) / 2
        val old = g.transform
        val oldClip = g.clip
        try {
            g.clip = Rectangle(boxX, boxY, boxW, boxH)
            g.color = COVER_PLACEHOLDER_COLOR
            g.fillRect(boxX, boxY, boxW, boxH)
            val image = (icon as? ImageIcon)?.image
            if (image != null) {
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
                g.drawImage(image, x, y, drawW, drawH, null)
            } else {
                g.translate(x.toDouble(), y.toDouble())
                g.scale(drawW.toDouble() / iconW, drawH.toDouble() / iconH)
                icon.paintIcon(this, g, 0, 0)
            }
        } finally {
            g.transform = old
            g.clip = oldClip
        }
        g.color = COVER_BORDER_COLOR
        g.drawRect(boxX, boxY, boxW - 1, boxH - 1)
    }

    /**
     * 没有封面时的占位：淡底 + 居中一个默认书图标。
     *
     * 这里**故意不放大**图标 —— 默认图标是 16×16 的 SVG 光栅化结果，拉满 64×96 只会糊。
     * 尺寸不够时干脆不画，宁可只留一个空槽。
     */
    private fun drawCoverPlaceholder(g: Graphics2D, boxX: Int, boxY: Int, boxW: Int, boxH: Int) {
        g.color = COVER_PLACEHOLDER_COLOR
        g.fillRect(boxX, boxY, boxW, boxH)
        g.color = COVER_BORDER_COLOR
        g.drawRect(boxX, boxY, boxW - 1, boxH - 1)
        val iconW = DEFAULT_COVER.iconWidth
        val iconH = DEFAULT_COVER.iconHeight
        if (iconW in 1..boxW && iconH in 1..boxH) {
            DEFAULT_COVER.paintIcon(this, g, boxX + (boxW - iconW) / 2, boxY + (boxH - iconH) / 2)
        }
    }

    private fun drawClipped(g: Graphics2D, text: String, x: Int, baseline: Int, maxWidth: Int) {
        val fitted = fitToWidth(g, text, maxWidth)
        g.drawString(fitted, x, baseline)
    }

    private fun drawCentered(g: Graphics2D, text: String, rect: Rectangle) {
        val fm = g.fontMetrics
        g.drawString(
            text,
            rect.x + (rect.width - fm.stringWidth(text)) / 2,
            rect.y + (rect.height - fm.height) / 2 + fm.ascent,
        )
    }

    /** 超宽就截断加省略号；`ellipsis` 也量不出来时返回空串，绝不画出界。 */
    private fun fitToWidth(g: Graphics2D, text: String, maxWidth: Int): String {
        if (maxWidth <= 0) {
            return ""
        }
        val fm = g.fontMetrics
        if (fm.stringWidth(text) <= maxWidth) {
            return text
        }
        val ellipsis = "…"
        val ellipsisWidth = fm.stringWidth(ellipsis)
        var end = text.length
        while (end > 0) {
            val candidate = text.substring(0, end) + ellipsis
            if (fm.stringWidth(candidate) <= maxWidth) {
                return candidate
            }
            end--
        }
        return if (ellipsisWidth <= maxWidth) ellipsis else ""
    }

    /** 把长标题折成固定行数；最后一行自动省略，避免文字盖住封面或操作入口。 */
    private fun fitToLines(g: Graphics2D, text: String, maxWidth: Int, maxLines: Int): List<String> {
        if (maxWidth <= 0 || maxLines <= 0) {
            return emptyList()
        }
        val lines = mutableListOf<String>()
        var start = 0
        while (start < text.length && lines.size < maxLines) {
            var end = text.length
            var fitted = text.substring(start, end)
            while (end > start && g.fontMetrics.stringWidth(fitted) > maxWidth) {
                end--
                fitted = text.substring(start, end)
            }
            if (end <= start) {
                lines += fitToWidth(g, text.substring(start), maxWidth)
                break
            }
            if (lines.size == maxLines - 1 && end < text.length) {
                lines += fitToWidth(g, text.substring(start), maxWidth)
                break
            }
            lines += fitted
            start = end
        }
        return lines
    }

    companion object {
        private val GAP = JBUI.scale(8)
        private val COVER_W = JBUI.scale(104)
        private val COVER_H = JBUI.scale(150)
        private val ICON = JBUI.scale(24)
        private val TITLE_AREA_H = JBUI.scale(44)
        private const val MAX_TITLE_LINES = 2

        /** 卡片固定尺寸：两个列表共用，也是 `JBList.fixedCellWidth/Height`。 */
        val CELL_WIDTH: Int = JBUI.scale(164)
        val CELL_HEIGHT: Int = JBUI.scale(252)

        private val HOVER_BACKGROUND = JBColor(0xE8EEF7, 0x2C3542)
        private val HOVER_RING_COLOR = JBColor(0x9AA7B8, 0x5A6B80)
        private val STAR_ON_COLOR = JBColor(0xE0A800, 0xF0C040)
        private val STAR_HOVER_COLOR = JBColor(0xB08A00, 0xD0A030)
        private val CLOSE_HOVER_COLOR = JBColor(0xC0392B, 0xE06C5A)
        private val MISSING_COLOR = JBColor(0xC0392B, 0xE06C5A)
        private val COVER_BORDER_COLOR = JBColor(0xC8CED6, 0x4A5464)
        private val COVER_PLACEHOLDER_COLOR = JBColor(0xEDF0F4, 0x333C49)

        /** 默认封面占位（`icons/book.svg`）；`IconLoader` 自带缓存，这里只取一次。 */
        val DEFAULT_COVER: Icon by lazy { IconLoader.getIcon("/icons/book.svg", BookCard::class.java) }

        /**
         * ★ 的热区矩形。与 [paintComponent] 里 `starRectFor(width)` 的调用**同源**，
         * 所以"画在哪"与"点哪算"不可能错位。
         */
        fun starRectFor(width: Int): Rectangle =
            Rectangle(width - GAP - ICON * 2 - GAP, CELL_HEIGHT - GAP - ICON, ICON, ICON)

        /** ✕ 的热区矩形，同上。 */
        fun closeRectFor(width: Int): Rectangle =
            Rectangle(width - GAP - ICON, CELL_HEIGHT - GAP - ICON, ICON, ICON)

    }
}
