package com.chen.reader.ui.bookshelf

import com.chen.reader.bookshelf.BookCoverLoader
import com.chen.reader.bookshelf.ShelfEntry
import com.intellij.openapi.util.IconLoader
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Rectangle
import java.awt.RenderingHints
import javax.swing.Icon
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
 * **另外不设 `toolTipText`**：悬浮 tooltip 会盖住卡片右侧的 ★/✕（用户必须先把鼠标
 * 移到卡片上才能点它们，于是永远被盖住）。完整路径改为**画在卡片里**（中间省略），
 * 文件缺失也在卡片里直接标红。
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

            val contentX = GAP * 2 + COVER_W
            val contentRight = (width - GAP - ICON * 2 - GAP).coerceAtLeast(contentX + MIN_CONTENT_WIDTH)
            val contentWidth = contentRight - contentX
            val barWidth = (contentWidth - PERCENT_W - GAP).coerceAtLeast(MIN_BAR_WIDTH)

            // ---- 封面（内存图标，不解码）
            val cover = BookCoverLoader.getInstance().coverFor(current.pathKey) ?: DEFAULT_COVER
            val coverX = GAP
            val coverY = (CELL_HEIGHT - COVER_H) / 2
            cover.paintIcon(this, g2, coverX, coverY)

            // ---- 标题
            g2.font = listFont.deriveFont(Font.BOLD)
            g2.color = primary
            drawClipped(g2, ShelfFormat.displayTitle(current, missing), contentX, GAP + TITLE_H - JBUI.scale(5), contentWidth)

            // ---- 路径（中间省略，替代原来会遮挡卡片的 tooltip）
            g2.font = listFont.deriveFont(Font.PLAIN, listFont.size2D - 1f)
            g2.color = secondary
            drawClipped(g2, truncateMiddle(current.path, PATH_MAX_CHARS), contentX, GAP + TITLE_H + PATH_H - JBUI.scale(4), contentWidth)

            // ---- 进度条 + 百分比
            val barY = GAP + TITLE_H + PATH_H + JBUI.scale(3)
            val percent = current.percent()
            if (percent != null) {
                g2.color = BAR_TRACK_COLOR
                g2.fillRect(contentX, barY, barWidth, BAR_H)
                g2.color = if (selected) selectedForeground else BAR_FILL_COLOR
                g2.fillRect(contentX, barY, (barWidth * percent / 100).coerceIn(0, barWidth), BAR_H)
            }
            g2.font = listFont.deriveFont(Font.PLAIN, listFont.size2D - 1f)
            g2.color = secondary
            drawRightAligned(g2, ShelfFormat.formatPercent(percent), contentX + contentWidth - PERCENT_W, barY - JBUI.scale(4), PERCENT_W)

            // ---- 元信息
            val metaText = if (missing) {
                "文件已被移动或删除"
            } else {
                "${ShelfFormat.formatLastRead(current.lastReadMillis)} · ${current.format.uppercase()}"
            }
            g2.color = if (missing) MISSING_COLOR else secondary
            drawClipped(g2, metaText, contentX, barY + BAR_H + LABEL_H - JBUI.scale(4), contentWidth)

            // ---- ★ / ✕（位置与 starRectFor / closeRectFor 同源）
            val starRect = starRectFor(width)
            val closeRect = closeRectFor(width)
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

    private fun drawClipped(g: Graphics2D, text: String, x: Int, baseline: Int, maxWidth: Int) {
        val fitted = fitToWidth(g, text, maxWidth)
        g.drawString(fitted, x, baseline)
    }

    private fun drawRightAligned(g: Graphics2D, text: String, x: Int, baseline: Int, maxWidth: Int) {
        val fitted = fitToWidth(g, text, maxWidth)
        g.drawString(fitted, x + maxWidth - g.fontMetrics.stringWidth(fitted), baseline)
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

    /** 路径太长时中间省略，保留盘符与文件名两端的辨识信息。 */
    private fun truncateMiddle(text: String, maxChars: Int): String {
        if (text.length <= maxChars) {
            return text
        }
        val head = maxChars / 2
        val tail = maxChars - head - 1
        return text.substring(0, head) + "…" + text.substring(text.length - tail)
    }

    companion object {
        private val GAP = JBUI.scale(8)
        private val COVER_W = JBUI.scale(64)
        private val COVER_H = JBUI.scale(96)
        private val ICON = JBUI.scale(24)
        private val TITLE_H = JBUI.scale(20)
        private val PATH_H = JBUI.scale(16)
        private val LABEL_H = JBUI.scale(16)
        private val BAR_H = JBUI.scale(8)
        private val PERCENT_W = JBUI.scale(44)

        /** 卡片固定高度：两个列表共用，也是 `JBList.fixedCellHeight`。 */
        val CELL_HEIGHT: Int = JBUI.scale(120)

        private const val FALLBACK_WIDTH = 320
        private const val MIN_CONTENT_WIDTH = 80
        private const val MIN_BAR_WIDTH = 40
        private const val PATH_MAX_CHARS = 72

        private val HOVER_BACKGROUND = JBColor(0xE8EEF7, 0x2C3542)
        private val HOVER_RING_COLOR = JBColor(0x9AA7B8, 0x5A6B80)
        private val STAR_ON_COLOR = JBColor(0xE0A800, 0xF0C040)
        private val STAR_HOVER_COLOR = JBColor(0xB08A00, 0xD0A030)
        private val CLOSE_HOVER_COLOR = JBColor(0xC0392B, 0xE06C5A)
        private val BAR_TRACK_COLOR = JBColor(0xD5DAE0, 0x3A4350)
        private val BAR_FILL_COLOR = JBColor(0x4A6FA5, 0x6E9BD8)
        private val MISSING_COLOR = JBColor(0xC0392B, 0xE06C5A)

        /** 默认封面占位（`icons/book.svg`）；`IconLoader` 自带缓存，这里只取一次。 */
        val DEFAULT_COVER: Icon by lazy { IconLoader.getIcon("/icons/book.svg", BookCard::class.java) }

        /**
         * ★ 的热区矩形。与 [paintComponent] 里 `starRectFor(width)` 的调用**同源**，
         * 所以"画在哪"与"点哪算"不可能错位。
         */
        fun starRectFor(width: Int): Rectangle =
            Rectangle(width - GAP - ICON * 2 - GAP, GAP, ICON, ICON)

        /** ✕ 的热区矩形，同上。 */
        fun closeRectFor(width: Int): Rectangle =
            Rectangle(width - GAP - ICON, GAP, ICON, ICON)

        /** 行的首选尺寸：宽度交给 `JList`，高度 = 行数 × 固定行高。 */
        fun preferredFor(base: Dimension?, rows: Int): Dimension =
            Dimension(base?.width ?: FALLBACK_WIDTH, CELL_HEIGHT * rows)
    }
}
