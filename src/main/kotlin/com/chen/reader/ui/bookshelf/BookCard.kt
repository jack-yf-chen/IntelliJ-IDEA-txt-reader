package com.chen.reader.ui.bookshelf

import com.chen.reader.bookshelf.BookCoverLoader
import com.chen.reader.bookshelf.ShelfEntry
import com.intellij.openapi.util.IconLoader
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.Component
import java.awt.Dimension
import java.awt.Font
import java.awt.Rectangle
import javax.swing.Icon
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JProgressBar
import javax.swing.ListCellRenderer
import javax.swing.SwingConstants

/** 卡片内热区编号：0 = 其它（续读）、1 = ★ 收藏、2 = ✕ 移除。 */
const val SPOT_NONE = 0
const val SPOT_STAR = 1
const val SPOT_CLOSE = 2

/**
 * 书架卡片 renderer。
 *
 * **无状态、每次全量 `apply()`**：`JBList` 的 renderer 组件只有这一个实例、被所有行复用，
 * 且**不在**视图树里（在它上面 `addActionListener` 永远不会触发）。
 * 所以 ★ / ✕ 不是真按钮，而是画出来的图形 + 常量矩形热区，由 `BookshelfPanel`
 * 用 `locationToIndex` + 行内坐标分派（见 [starRectFor] / [closeRectFor]）。
 *
 * **EDT 零 IO**：封面只从 `BookCoverLoader.coverFor` 读内存，绝不在这里触发任何加载。
 */
class BookCard : JPanel(null), ListCellRenderer<ShelfEntry> {
    private val coverLabel = JLabel()
    private val titleLabel = JLabel()
    private val progressBar = JProgressBar(0, 100)
    private val percentLabel = JLabel()
    private val metaLabel = JLabel()
    private val starLabel = JLabel()
    private val closeLabel = JLabel()

    /** 由 `BookshelfPanel` 的鼠标移动监听写入，驱动 hover 高亮。 */
    var hoverIndex: Int = -1

    /** 见 [SPOT_NONE] / [SPOT_STAR] / [SPOT_CLOSE]。 */
    var hoverSpot: Int = SPOT_NONE

    init {
        isOpaque = true
        coverLabel.horizontalAlignment = SwingConstants.CENTER
        coverLabel.verticalAlignment = SwingConstants.CENTER
        titleLabel.font = titleLabel.font.deriveFont(Font.BOLD)
        percentLabel.horizontalAlignment = SwingConstants.RIGHT
        starLabel.horizontalAlignment = SwingConstants.CENTER
        closeLabel.horizontalAlignment = SwingConstants.CENTER
        // 收藏 / 移除是唯一的两个操作入口，字号放大一档，避免被当成装饰。
        starLabel.font = starLabel.font.deriveFont(starLabel.font.size2D + 4f)
        closeLabel.font = closeLabel.font.deriveFont(closeLabel.font.size2D + 2f)
        progressBar.isBorderPainted = false
        progressBar.isStringPainted = false
        add(coverLabel)
        add(titleLabel)
        add(progressBar)
        add(percentLabel)
        add(metaLabel)
        add(starLabel)
        add(closeLabel)
    }

    override fun getListCellRendererComponent(
        list: JList<out ShelfEntry>?,
        value: ShelfEntry?,
        index: Int,
        isSelected: Boolean,
        cellHasFocus: Boolean,
    ): Component {
        val entry = value ?: return this
        val width = list?.let { it.width - it.insets.left - it.insets.right }?.takeIf { it > 0 } ?: FALLBACK_WIDTH
        val spot = if (index == hoverIndex) hoverSpot else SPOT_NONE
        apply(entry, width, isSelected, index == hoverIndex, spot, list)
        return this
    }

    private fun apply(
        entry: ShelfEntry,
        width: Int,
        selected: Boolean,
        hovered: Boolean,
        spot: Int,
        list: JList<out ShelfEntry>?,
    ) {
        val missing = ShelfFormat.isMissing(entry)
        val foreground = if (selected && list != null) list.selectionForeground else UIUtil.getListForeground()
        val secondary = if (selected && list != null) list.selectionForeground else UIUtil.getInactiveTextColor()
        background = when {
            selected && list != null -> list.selectionBackground
            hovered -> HOVER_BACKGROUND
            list != null -> list.background
            else -> UIUtil.getListBackground()
        }

        coverLabel.icon = BookCoverLoader.getInstance().coverFor(entry.pathKey) ?: DEFAULT_COVER
        titleLabel.text = ShelfFormat.displayTitle(entry, missing)
        titleLabel.foreground = foreground

        val percent = entry.percent()
        progressBar.value = percent ?: 0
        progressBar.isVisible = percent != null
        percentLabel.text = ShelfFormat.formatPercent(percent)
        percentLabel.foreground = secondary

        metaLabel.text = "${ShelfFormat.formatLastRead(entry.lastReadMillis)} · ${entry.format.uppercase()}"
        metaLabel.foreground = secondary

        starLabel.text = if (entry.favorite) "★" else "☆"
        starLabel.foreground = when {
            entry.favorite -> STAR_ON_COLOR
            spot == SPOT_STAR -> STAR_HOVER_COLOR
            // 未收藏时**不能用** getInactiveTextColor()：它和卡片底色太接近，
            // 实机反馈"完全看不到收藏入口"。用列表前景色保证可辨识。
            else -> foreground
        }
        closeLabel.text = "✕"
        closeLabel.foreground = if (spot == SPOT_CLOSE) CLOSE_HOVER_COLOR else foreground

        toolTipText = buildTooltip(entry, missing)
        layoutChildren(width)
    }

    private fun layoutChildren(width: Int) {
        val contentX = GAP * 2 + COVER_W
        val contentRight = width - GAP - ICON * 2
        val contentWidth = (contentRight - contentX).coerceAtLeast(MIN_CONTENT_WIDTH)
        val barWidth = (contentWidth - PERCENT_W - GAP).coerceAtLeast(MIN_BAR_WIDTH)

        coverLabel.setBounds(GAP, (CELL_HEIGHT - COVER_H) / 2, COVER_W, COVER_H)
        titleLabel.setBounds(contentX, GAP, contentWidth, TITLE_H)
        progressBar.setBounds(contentX, GAP + TITLE_H + 4, barWidth, BAR_H)
        percentLabel.setBounds(contentX + barWidth + GAP, GAP + TITLE_H, PERCENT_W, LABEL_H)
        metaLabel.setBounds(contentX, GAP + TITLE_H + 4 + BAR_H + 4, contentWidth, LABEL_H)
        closeLabel.setBounds(width - GAP - ICON, GAP, ICON, ICON)
        starLabel.setBounds(width - GAP - ICON * 2, GAP, ICON, ICON)
    }

    private fun buildTooltip(entry: ShelfEntry, missing: Boolean): String {
        val lines = mutableListOf<String>()
        lines += escapeHtml(ShelfFormat.displayTitle(entry, false))
        lines += "路径：${escapeHtml(entry.path)}"
        lines += "最后阅读：${ShelfFormat.formatLastRead(entry.lastReadMillis)}　进度：${ShelfFormat.formatPercent(entry.percent())}"
        if (missing) {
            lines += "<font color='#C0392B'>文件已被移动或删除</font>"
        }
        return "<html>${lines.joinToString("<br>")}</html>"
    }

    private fun escapeHtml(text: String): String = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")

    companion object {
        private val GAP = JBUI.scale(8)
        private val COVER_W = JBUI.scale(64)
        private val COVER_H = JBUI.scale(96)
        private val ICON = JBUI.scale(24)
        private val TITLE_H = JBUI.scale(20)
        private val LABEL_H = JBUI.scale(16)
        private val BAR_H = JBUI.scale(8)
        private val PERCENT_W = JBUI.scale(44)

        /** 卡片固定高度：两个列表共用，也是 `JBList.fixedCellHeight`。 */
        val CELL_HEIGHT: Int = JBUI.scale(120)

        private const val FALLBACK_WIDTH = 320
        private const val MIN_CONTENT_WIDTH = 80
        private const val MIN_BAR_WIDTH = 40

        private val HOVER_BACKGROUND = JBColor(0xE8EEF7, 0x2C3542)
        private val STAR_ON_COLOR = JBColor(0xE0A800, 0xF0C040)
        private val STAR_HOVER_COLOR = JBColor(0xB08A00, 0xD0A030)
        private val CLOSE_HOVER_COLOR = JBColor(0xC0392B, 0xE06C5A)

        /** 默认封面占位（`icons/book.svg`）；`IconLoader` 自带缓存，这里只取一次。 */
        val DEFAULT_COVER: Icon by lazy { IconLoader.getIcon("/icons/book.svg", BookCard::class.java) }

        /**
         * ★ 的热区矩形。布局固定，所以可以静态算出 —— 与 [layoutChildren] 里
         * `starLabel.setBounds` 的参数**必须**逐字一致，否则会出现"看着在点、点了没反应"。
         */
        fun starRectFor(width: Int): Rectangle =
            Rectangle(width - GAP - ICON * 2, GAP, ICON, ICON)

        /** ✕ 的热区矩形，同上。 */
        fun closeRectFor(width: Int): Rectangle =
            Rectangle(width - GAP - ICON, GAP, ICON, ICON)

        /** 行的首选尺寸：宽度交给 `JList`，高度 = 行数 × 固定行高。 */
        fun preferredFor(base: Dimension?, rows: Int): Dimension =
            Dimension(base?.width ?: FALLBACK_WIDTH, CELL_HEIGHT * rows)
    }
}
