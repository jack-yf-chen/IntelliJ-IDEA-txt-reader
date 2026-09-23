package com.chen.reader.ui.bookshelf

import com.chen.reader.NovelReaderOpener
import com.chen.reader.bookshelf.BookCoverLoader
import com.chen.reader.bookshelf.BookshelfService
import com.chen.reader.bookshelf.CoverResult
import com.chen.reader.bookshelf.ShelfEntry
import com.chen.reader.bookshelf.ShelfRules
import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBList
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Font
import java.awt.Point
import java.awt.Rectangle
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseMotionAdapter
import java.nio.file.Path
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.ListModel
import javax.swing.ListSelectionModel
import javax.swing.Scrollable
import javax.swing.SwingConstants
import javax.swing.SwingUtilities
import kotlin.io.path.exists

/** 书架 Tab 的主面板：工具栏 + 「最近阅读」/「我的收藏」两个分区。 */
class BookshelfPanel(private val project: Project) : JPanel(BorderLayout()), Disposable {
    private val service: BookshelfService = BookshelfService.getInstance()
    private val coverLoader: BookCoverLoader = BookCoverLoader.getInstance()

    private val recentModel = DefaultListModel<ShelfEntry>()
    private val favoriteModel = DefaultListModel<ShelfEntry>()
    private val recentList = ShelfList(recentModel)
    private val favoriteList = ShelfList(favoriteModel)
    private val recentRenderer = BookCard()
    private val favoriteRenderer = BookCard()

    private val emptyLabel = JLabel("还没有阅读记录，打开一本 TXT / EPUB 后会出现在这里。", SwingConstants.CENTER)
    private val favoriteHint = JLabel("还没有收藏的书籍 —— 把鼠标移到卡片上，点右上角的 ☆ 即可收藏。")
    private val usageHint = JLabel("点击卡片继续阅读　·　☆ / ★ 收藏或取消收藏　·　✕ 从书架移除")
    private val recentHeader = sectionHeader("最近阅读")
    private val favoriteHeader = sectionHeader("我的收藏")
    private val contentPanel = StretchPanel()

    init {
        background = UIUtil.getListBackground()
        contentPanel.add(emptyLabel)
        contentPanel.add(usageHint)
        contentPanel.add(recentHeader)
        contentPanel.add(recentList)
        contentPanel.add(Box.createVerticalStrut(JBUI.scale(12)))
        contentPanel.add(favoriteHeader)
        contentPanel.add(favoriteHint)
        contentPanel.add(favoriteList)
        contentPanel.add(Box.createVerticalGlue())

        recentList.cellRenderer = recentRenderer
        favoriteList.cellRenderer = favoriteRenderer
        attachInteractions(recentList, recentRenderer)
        attachInteractions(favoriteList, favoriteRenderer)

        emptyLabel.foreground = UIUtil.getInactiveTextColor()
        emptyLabel.font = emptyLabel.font.deriveFont(Font.PLAIN)
        emptyLabel.border = JBUI.Borders.empty(24, 12)
        favoriteHint.foreground = UIUtil.getInactiveTextColor()
        favoriteHint.font = favoriteHint.font.deriveFont(Font.PLAIN)
        favoriteHint.border = JBUI.Borders.empty(4, 10, 8, 10)
        favoriteHint.alignmentX = LEFT_ALIGNMENT
        usageHint.foreground = UIUtil.getInactiveTextColor()
        usageHint.font = usageHint.font.deriveFont(Font.PLAIN, usageHint.font.size2D - 1f)
        usageHint.border = JBUI.Borders.empty(6, 10, 2, 10)
        usageHint.alignmentX = LEFT_ALIGNMENT

        // 初始态：还没 refresh 过，只显示空态文案（用户升级后可能先切到书架再开书）。
        usageHint.isVisible = false
        recentHeader.isVisible = false
        recentList.isVisible = false
        favoriteHeader.isVisible = false
        favoriteHint.isVisible = false
        favoriteList.isVisible = false
        emptyLabel.isVisible = true

        add(buildToolbar(), BorderLayout.NORTH)
        add(JScrollPane(contentPanel).apply {
            border = JBUI.Borders.empty()
            // 内容比视口矮时（最典型是空态那一屏），`ViewportLayout` 只对"非 Scrollable
            // 的 view"做撑满处理，而 [StretchPanel] 是 Scrollable 且纵向不跟随视口 ——
            // 视口里多出来的那块会由 `JViewport` 自己画，取的是 `Viewport.background`，
            // 与列表底色不是一个 UIManager key，主题下能看出色差。这里显式对齐。
            viewport.background = UIUtil.getListBackground()
        }, BorderLayout.CENTER)
    }

    private fun buildToolbar(): JPanel {
        val openButton = JButton("打开书籍").apply {
            addActionListener { NovelReaderOpener.openFromFileChooser(project) }
        }
        val clearButton = JButton("清空历史").apply {
            addActionListener { onClearHistory() }
        }
        return JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(4, 6)
            add(JPanel().apply {
                isOpaque = false
                add(openButton)
                add(clearButton)
            }, BorderLayout.WEST)
        }
    }

    private fun sectionHeader(text: String): JLabel = JLabel(text).apply {
        font = font.deriveFont(Font.BOLD)
        foreground = UIUtil.getInactiveTextColor()
        border = JBUI.Borders.empty(6, 8, 4, 8)
        alignmentX = LEFT_ALIGNMENT
    }

    /** 每次切到「书架」Tab 时调用：先同步内存进度，再重建两个分区，最后提交封面请求。 */
    fun refresh() {
        // 老用户升级后第一次进书架：从项目级旧 state 播种 1 条（幂等）。
        service.ensureSeeded(project)
        // 进度只进内存，靠自动保存带上；这里补一次，保证"刚读到 80% 就切 Tab"也能看到 80%。
        service.noteProgress(project)

        val recent = service.recent()
        val favorites = service.favorites()
        replaceModel(recentModel, recent)
        replaceModel(favoriteModel, favorites)

        val hasBooks = recent.isNotEmpty()
        recentHeader.isVisible = hasBooks
        recentList.isVisible = hasBooks
        emptyLabel.isVisible = !hasBooks
        usageHint.isVisible = hasBooks || favorites.isNotEmpty()
        // 「我的收藏」分区**始终显示**（哪怕是空的并给出操作提示）：
        // 实机反馈「书架里看不到收藏功能」—— 原来空收藏时连分区标题都隐藏了。
        favoriteHeader.isVisible = true
        favoriteList.isVisible = favorites.isNotEmpty()
        favoriteHint.isVisible = favorites.isEmpty()

        requestMissingCovers(recent + favorites)
        contentPanel.revalidate()
        contentPanel.repaint()
    }

    private fun replaceModel(model: DefaultListModel<ShelfEntry>, entries: List<ShelfEntry>) {
        model.clear()
        entries.forEach { model.addElement(it) }
        recentRenderer.hoverIndex = -1
        favoriteRenderer.hoverIndex = -1
    }

    /**
     * 封面是**渐进式**填充：这里只提交请求（最多 [MAX_COVER_REQUESTS] 条），
     * 真正解码在 `BookCoverLoader` 的单线程后台队列里，完成后 `invokeLater` 回来 repaint。
     * EDT 上只做 `Files.exists` 这一次 `stat`。
     */
    private fun requestMissingCovers(entries: List<ShelfEntry>) {
        entries.take(MAX_COVER_REQUESTS).forEach { entry ->
            if (entry.format != "epub") {
                return@forEach
            }
            if (coverLoader.isKnown(entry.pathKey)) {
                return@forEach
            }
            if (runCatching { Path.of(entry.path).exists() }.getOrDefault(false)) {
                coverLoader.request(entry) { result -> onCoverReady(result) }
            }
        }
    }

    private fun onCoverReady(result: CoverResult) {
        val titleChanged = service.applyMetaTitle(result.pathKey, result.title)
        if (titleChanged) {
            refresh()
            return
        }
        recentList.repaint()
        favoriteList.repaint()
    }

    // ------------------------------------------------------------------ 交互

    private fun attachInteractions(list: ShelfList, renderer: BookCard) {
        list.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(event: MouseEvent) {
                if (!SwingUtilities.isLeftMouseButton(event)) {
                    return
                }
                dispatchClick(list, event)
            }

            override fun mouseExited(event: MouseEvent) {
                renderer.hoverIndex = -1
                renderer.hoverSpot = SPOT_NONE
                list.repaint()
            }
        })
        list.addMouseMotionListener(object : MouseMotionAdapter() {
            override fun mouseMoved(event: MouseEvent) {
                val spot = spotAt(list, event.point)
                val index = list.locationToIndex(event.point)
                if (renderer.hoverIndex != index || renderer.hoverSpot != spot) {
                    renderer.hoverIndex = index
                    renderer.hoverSpot = spot
                    list.repaint()
                }
                list.cursor = if (spot == SPOT_NONE) {
                    Cursor.getDefaultCursor()
                } else {
                    Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                }
            }
        })
    }

    /** 行内坐标 → 热区编号。★ / ✕ 不是真按钮，全靠这里分派。 */
    private fun spotAt(list: ShelfList, point: Point): Int {
        val index = list.locationToIndex(point)
        if (index < 0) {
            return SPOT_NONE
        }
        val bounds = list.getCellBounds(index, index) ?: return SPOT_NONE
        val local = Point(point.x - bounds.x, point.y - bounds.y)
        return when {
            BookCard.closeRectFor(bounds.width).contains(local) -> SPOT_CLOSE
            BookCard.starRectFor(bounds.width).contains(local) -> SPOT_STAR
            else -> SPOT_NONE
        }
    }

    private fun dispatchClick(list: ShelfList, event: MouseEvent) {
        val index = list.locationToIndex(event.point)
        if (index < 0 || index >= list.model.size) {
            return
        }
        val entry = list.model.getElementAt(index)
        val bounds = list.getCellBounds(index, index) ?: return
        val local = Point(event.x - bounds.x, event.y - bounds.y)
        when {
            BookCard.closeRectFor(bounds.width).contains(local) -> onRemove(entry)
            BookCard.starRectFor(bounds.width).contains(local) -> onToggleFavorite(entry)
            else -> onOpen(entry)
        }
    }

    private fun onOpen(entry: ShelfEntry) {
        val path = runCatching { Path.of(entry.path) }.getOrNull()
        if (path == null || !runCatching { path.exists() }.getOrDefault(false)) {
            val answer = Messages.showYesNoDialog(
                project,
                "文件已被移动或删除：\n${entry.path}\n\n是否把它从书架移除？",
                "无法打开",
                "从书架移除",
                "取消",
                Messages.getWarningIcon(),
            )
            if (answer == Messages.YES) {
                service.remove(entry.pathKey)
                refresh()
            }
            return
        }
        NovelReaderOpener.openFromBookshelf(project, entry)
    }

    private fun onToggleFavorite(entry: ShelfEntry) {
        service.setFavorite(entry.pathKey, !entry.favorite)
        refresh()
    }

    private fun onRemove(entry: ShelfEntry) {
        // ✕ 就在卡片右上角，离鼠标很近，误点代价是丢阅读进度 —— 加一次确认。
        val answer = Messages.showYesNoDialog(
            project,
            "确定把《${ShelfFormat.displayTitle(entry, false)}》从书架移除吗？\n阅读进度记录会一起删除。",
            "从书架移除",
            "移除",
            "取消",
            Messages.getQuestionIcon(),
        )
        if (answer != Messages.YES) {
            return
        }
        service.remove(entry.pathKey)
        refresh()
    }

    private fun onClearHistory() {
        if (service.all().none { !it.favorite }) {
            return
        }
        val answer = Messages.showYesNoDialog(
            project,
            "将移除全部未收藏的阅读记录，收藏的书会保留。",
            "清空历史",
            "清空",
            "取消",
            Messages.getQuestionIcon(),
        )
        if (answer != Messages.YES) {
            return
        }
        service.clearHistory()
        refresh()
    }

    override fun dispose() {
        recentModel.clear()
        favoriteModel.clear()
    }

    /**
     * 固定行高的列表：高度 = 行数 × `CELL_HEIGHT`，**宽度交给外层 `BoxLayout` 拉满**。
     *
     * 注意 [getMaximumSize] 的宽度必须是 `Int.MAX_VALUE`：`BoxLayout(Y_AXIS)` 是按
     * 组件的**最大尺寸**来分配宽度的，若把最大宽度也写死成首选宽度（渲染器算出来的宽度
     * 可能只有几十像素），整张卡片就会被压成窄条 —— 实机反馈"书籍图片很窄、收藏按钮
     * 挤在一起"就是这个原因。高度仍然锁死为首选高度，避免列表自己纵向拉伸。
     */
    private class ShelfList(model: ListModel<ShelfEntry>) : JBList<ShelfEntry>(model) {
        init {
            selectionMode = ListSelectionModel.SINGLE_SELECTION
            fixedCellHeight = BookCard.CELL_HEIGHT
            isOpaque = true
            background = UIUtil.getListBackground()
            border = JBUI.Borders.empty()
            // 必须显式左对齐：默认 `getAlignmentX()` 是 0.5，而同层那些 JLabel 显式设了
            // `LEFT_ALIGNMENT`。混用会让 `BoxLayout` 算出一个约 0.01 的整体对齐值，
            // 结果是列表相对标题右移几个像素、且略微变窄。
            alignmentX = LEFT_ALIGNMENT
        }

        override fun getPreferredSize(): Dimension {
            val rows = model.size
            if (rows == 0) {
                return Dimension(0, 0)
            }
            return BookCard.preferredFor(super.getPreferredSize(), rows)
        }

        override fun getMaximumSize(): Dimension =
            Dimension(Int.MAX_VALUE, getPreferredSize().height)
    }

    /**
     * 滚动区的内容容器：宽度**永远铺满视口**。
     *
     * 默认的 `JScrollPane` 语义是"视图宽度 = 视图的首选宽度"，而本视图的首选宽度来自
     * [ShelfList] → 渲染器（一张没有子组件的 `JPanel`，首选宽度接近 0），于是实机上出现
     * "整张卡片只有封面那么宽、☆/✕ 挤在最左边"。实现 [Scrollable] 并让
     * [getScrollableTracksViewportWidth] 返回 `true` 之后，`JViewport` 会把**视口宽度回灌**
     * 给本容器，内部的 `BoxLayout(Y_AXIS)` 才有足额宽度可以分给每一行卡片。
     *
     * 高度保持 `tracksViewportHeight = false`，这样内容超出时才出现纵向滚动条。
     */
    private class StretchPanel : JPanel(), Scrollable {
        init {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = true
            background = UIUtil.getListBackground()
        }

        override fun getPreferredScrollableViewportSize(): Dimension = preferredSize

        // 两个增量都忽略 orientation / direction：宽度跟随视口，永不横向滚动，那两个
        // 分支不可达（VirtualReaderPane 里同样如此）。
        override fun getScrollableUnitIncrement(
            visibleRect: Rectangle?,
            orientation: Int,
            direction: Int,
        ): Int = (BookCard.CELL_HEIGHT / SCROLL_ROWS_PER_NOTCH).coerceAtLeast(JBUI.scale(16))

        override fun getScrollableBlockIncrement(
            visibleRect: Rectangle?,
            orientation: Int,
            direction: Int,
        ): Int = (visibleRect?.height ?: BookCard.CELL_HEIGHT).coerceAtLeast(JBUI.scale(16))

        override fun getScrollableTracksViewportWidth(): Boolean = true

        override fun getScrollableTracksViewportHeight(): Boolean = false
    }

    companion object {
        /** 规模保护：一次最多提交这么多封面请求（历史上限本身只有 [ShelfRules.MAX_RECENT]）。 */
        private const val MAX_COVER_REQUESTS = 30

        /** 滚轮每格滚过几分之一张卡片（单元增量 = 行高 / 这个值）。 */
        private const val SCROLL_ROWS_PER_NOTCH = 4
    }
}
