package com.chen.reader

import com.chen.reader.model.Book
import com.chen.reader.model.Chapter
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.GraphicsEnvironment
import java.awt.Point
import java.awt.RenderingHints
import java.awt.Toolkit
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseWheelEvent
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.AbstractAction
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.Icon
import javax.swing.DefaultListCellRenderer
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JTextPane
import javax.swing.KeyStroke
import javax.swing.SwingUtilities
import javax.swing.UIManager
import javax.swing.text.SimpleAttributeSet
import javax.swing.text.StyleConstants
import java.util.WeakHashMap

class ReaderPanel(private val project: Project) : JPanel(BorderLayout()) {
    private val stateService = ReaderStateService.getInstance(project)
    private val openButton = JButton("打开")
    private val previousButton = JButton("上一章")
    private val nextButton = JButton("下一章")
    private val smallerButton = JButton("A-")
    private val largerButton = JButton("A+")
    private val tighterLineButton = JButton("行距-")
    private val looserLineButton = JButton("行距+")
    private val settingsButton = JButton("设置")
    private val chapterSelector = JComboBox<Chapter>()
    private val fontSelector = JComboBox(loadFontFamilies().toTypedArray())
    private val textColorSelector = JComboBox(textColors.keys.toTypedArray())
    private val themeSelector = JComboBox(readerThemes.map { it.name }.toTypedArray())
    private val widthSelector = JComboBox(widthModes.keys.toTypedArray())
    private val hideCursorCheckBox = JCheckBox("隐藏光标")
    private val textPane = ReaderTextPane()
    private val scrollPane = JBScrollPane(textPane)
    private val statusLabel = JLabel("未打开文件")
    private val defaultTextCursor = Cursor.getPredefinedCursor(Cursor.TEXT_CURSOR)
    private val hiddenCursor = createHiddenCursor()

    private var currentBook: Book? = null
    private var updatingChapterSelector = false
    private var updatingFontSelector = false
    private var updatingTextColorSelector = false
    private var updatingThemeSelector = false
    private var updatingWidthSelector = false
    private var suppressBoundaryNavigation = false
    private var settingsVisible = false
    private var lastScrollValue = 0

    init {
        border = JBUI.Borders.empty(8)
        registerPanel(project, this)
        configureChapterSelector()
        initializeFontSelector()
        initializeTextColorSelector()
        initializeThemeSelector()
        initializeWidthSelector()
        hideCursorCheckBox.isSelected = stateService.state.hideCursor

        textPane.isEditable = false
        textPane.margin = JBUI.insets(14)
        textPane.border = BorderFactory.createEmptyBorder()
        updateReaderStyle()
        updateCursorMode()
        installKeyboardShortcuts()

        add(createToolbar(), BorderLayout.NORTH)
        add(scrollPane, BorderLayout.CENTER)
        add(statusLabel, BorderLayout.SOUTH)
        updateButtonStyle()

        openButton.addActionListener { NovelReaderOpener.openFromFileChooser(project) }
        previousButton.addActionListener { moveChapter(-1) }
        nextButton.addActionListener { moveChapter(1) }
        smallerButton.addActionListener { changeFontSize(-1) }
        largerButton.addActionListener { changeFontSize(1) }
        tighterLineButton.addActionListener { changeLineSpacing(-10) }
        looserLineButton.addActionListener { changeLineSpacing(10) }
        settingsButton.addActionListener { toggleSettingsPanel() }
        fontSelector.addActionListener {
            if (!updatingFontSelector) {
                stateService.state.fontFamily = fontSelector.selectedItem as? String
                updateReaderStyle()
            }
        }
        textColorSelector.addActionListener {
            if (!updatingTextColorSelector) {
                stateService.state.textColorName = textColorSelector.selectedItem as String
                updateReaderStyle()
            }
        }
        themeSelector.addActionListener {
            if (!updatingThemeSelector) {
                stateService.state.themeName = themeSelector.selectedItem as String
                updateReaderStyle()
            }
        }
        widthSelector.addActionListener {
            if (!updatingWidthSelector) {
                stateService.state.widthMode = widthSelector.selectedItem as String
                updateReaderInsets()
            }
        }
        hideCursorCheckBox.addActionListener {
            stateService.state.hideCursor = hideCursorCheckBox.isSelected
            updateCursorMode()
        }
        chapterSelector.addActionListener {
            if (!updatingChapterSelector) {
                renderChapter(chapterSelector.selectedIndex, restoreScroll = false)
            }
        }

        scrollPane.verticalScrollBar.addAdjustmentListener {
            if (!it.valueIsAdjusting) {
                stateService.state.scrollValue = it.value
                lastScrollValue = it.value
                updateStatus()
            }
        }
        scrollPane.addMouseWheelListener(::handleBoundaryWheel)
        scrollPane.viewport.addComponentListener(object : ComponentAdapter() {
            override fun componentResized(event: ComponentEvent) {
                updateReaderInsets()
            }
        })

        updateControls()
    }

    private fun createToolbar(): JPanel {
        val toolbar = JPanel(BorderLayout(0, JBUI.scale(6)))
        val mainRow = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0))
        val settingsPanel = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0))

        mainRow.add(openButton)
        mainRow.add(previousButton)
        mainRow.add(chapterSelector)
        mainRow.add(nextButton)
        mainRow.add(settingsButton)

        settingsPanel.add(smallerButton)
        settingsPanel.add(largerButton)
        settingsPanel.add(tighterLineButton)
        settingsPanel.add(looserLineButton)
        settingsPanel.add(fontSelector)
        settingsPanel.add(textColorSelector)
        settingsPanel.add(themeSelector)
        settingsPanel.add(widthSelector)
        settingsPanel.add(hideCursorCheckBox)
        settingsPanel.isVisible = false
        settingsPanel.name = SETTINGS_PANEL_NAME

        val chapterSelectorSize = Dimension(JBUI.scale(CHAPTER_SELECTOR_WIDTH), chapterSelector.preferredSize.height)
        chapterSelector.minimumSize = chapterSelectorSize
        chapterSelector.preferredSize = chapterSelectorSize
        chapterSelector.maximumSize = chapterSelectorSize
        fontSelector.minimumSize = Dimension(JBUI.scale(120), fontSelector.preferredSize.height)
        fontSelector.preferredSize = Dimension(JBUI.scale(150), fontSelector.preferredSize.height)
        textColorSelector.preferredSize = Dimension(JBUI.scale(100), textColorSelector.preferredSize.height)
        themeSelector.preferredSize = Dimension(JBUI.scale(100), themeSelector.preferredSize.height)
        widthSelector.preferredSize = Dimension(JBUI.scale(80), widthSelector.preferredSize.height)

        toolbar.add(mainRow, BorderLayout.NORTH)
        toolbar.add(settingsPanel, BorderLayout.CENTER)
        return toolbar
    }

    private fun configureChapterSelector() {
        chapterSelector.maximumRowCount = 14
        chapterSelector.renderer = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(
                list: JList<*>?,
                value: Any?,
                index: Int,
                isSelected: Boolean,
                cellHasFocus: Boolean,
            ): java.awt.Component {
                val component = super.getListCellRendererComponent(
                    list,
                    value,
                    index,
                    isSelected,
                    cellHasFocus,
                )
                if (component is JLabel && value is Chapter) {
                    component.text = compactChapterTitle(value, index)
                    component.toolTipText = value.title
                }
                return component
            }
        }
    }

    override fun removeNotify() {
        unregisterPanel(project, this)
        super.removeNotify()
    }

    private fun toggleSettingsPanel() {
        settingsVisible = !settingsVisible
        findSettingsPanel()?.isVisible = settingsVisible
        updateButtonStyle()
        revalidate()
        repaint()
    }

    private fun findSettingsPanel(): JPanel? {
        val toolbar = getComponent(0) as? JPanel ?: return null
        return toolbar.components.filterIsInstance<JPanel>().firstOrNull { it.name == SETTINGS_PANEL_NAME }
    }

    private fun initializeFontSelector() {
        val preferredFont = stateService.state.fontFamily ?: defaultFontFamily()
        if (fontSelector.getIndexOf(preferredFont) >= 0) {
            updatingFontSelector = true
            fontSelector.selectedItem = preferredFont
            updatingFontSelector = false
            stateService.state.fontFamily = preferredFont
        }
    }

    private fun initializeTextColorSelector() {
        val colorName = stateService.state.textColorName
        if (textColorSelector.getIndexOf(colorName) >= 0) {
            updatingTextColorSelector = true
            textColorSelector.selectedItem = colorName
            updatingTextColorSelector = false
        }
    }

    private fun initializeThemeSelector() {
        val themeName = stateService.state.themeName
        if (themeSelector.getIndexOf(themeName) >= 0) {
            updatingThemeSelector = true
            themeSelector.selectedItem = themeName
            updatingThemeSelector = false
        }
    }

    private fun initializeWidthSelector() {
        val widthMode = stateService.state.widthMode
        if (widthSelector.getIndexOf(widthMode) >= 0) {
            updatingWidthSelector = true
            widthSelector.selectedItem = widthMode
            updatingWidthSelector = false
        }
    }

    fun restoreLastBook() {
        val savedPath = stateService.state.filePath?.let(Path::of) ?: return
        if (Files.exists(savedPath)) {
            openBook(savedPath, restoreState = true)
        }
    }

    fun openBook(path: Path, restoreState: Boolean = false) {
        try {
            val state = stateService.state
            val preferredCharset = if (restoreState && state.filePath == path.toString()) state.charsetName else null
            val book = TxtBookLoader.load(path, preferredCharset)
            currentBook = book

            state.filePath = path.toString()
            state.charsetName = book.charset.name()
            if (!restoreState) {
                state.chapterIndex = 0
                state.scrollValue = 0
            }

            updateChapterSelector(book.chapters)
            val index = state.chapterIndex.coerceIn(0, book.chapters.lastIndex)
            renderChapter(index, restoreScroll = restoreState)
        } catch (error: Throwable) {
            Messages.showErrorDialog(project, error.message ?: "打开 TXT 文件失败。", "Novel Reader")
        }
    }

    private fun moveChapter(delta: Int) {
        val book = currentBook ?: return
        val nextIndex = (stateService.state.chapterIndex + delta).coerceIn(0, book.chapters.lastIndex)
        renderChapter(nextIndex, restoreScroll = false)
    }

    private fun renderChapter(index: Int, restoreScroll: Boolean, scrollToBottom: Boolean = false) {
        val book = currentBook ?: return
        if (index !in book.chapters.indices) {
            return
        }

        suppressBoundaryNavigation = true
        val chapter = book.chapters[index]
        stateService.state.chapterIndex = index
        val text = book.content.substring(chapter.startOffset, chapter.endOffset).trimStart()
        textPane.text = text
        applyParagraphStyle()
        textPane.caretPosition = 0

        updatingChapterSelector = true
        chapterSelector.selectedIndex = index
        chapterSelector.toolTipText = chapter.title
        updatingChapterSelector = false

        SwingUtilities.invokeLater {
            val scrollBar = scrollPane.verticalScrollBar
            val maxScrollValue = (scrollBar.maximum - scrollBar.visibleAmount).coerceAtLeast(0)
            val scrollValue = when {
                scrollToBottom -> maxScrollValue
                restoreScroll -> stateService.state.scrollValue.coerceIn(0, maxScrollValue)
                else -> 0
            }
            scrollPane.verticalScrollBar.value = scrollValue
            lastScrollValue = scrollValue
            stateService.state.scrollValue = scrollValue
            suppressBoundaryNavigation = false
            textPane.requestFocusInWindow()
            updateStatus()
        }

        updateControls()
    }

    private fun updateChapterSelector(chapters: List<Chapter>) {
        updatingChapterSelector = true
        chapterSelector.removeAllItems()
        chapters.forEach(chapterSelector::addItem)
        updatingChapterSelector = false
    }

    private fun changeFontSize(delta: Int) {
        val state = stateService.state
        state.fontSize = (state.fontSize + delta).coerceIn(12, 36)
        updateReaderStyle()
    }

    private fun changeLineSpacing(delta: Int) {
        val state = stateService.state
        state.lineSpacingPercent = (state.lineSpacingPercent + delta).coerceIn(0, 80)
        updateReaderStyle()
    }

    private fun updateReaderStyle() {
        textPane.font = Font(selectedFontFamily(), Font.PLAIN, stateService.state.fontSize)
        applyTheme()
        updateReaderInsets()
        applyParagraphStyle()
        updateStatus()
    }

    private fun applyParagraphStyle() {
        val document = textPane.styledDocument
        val attributes = SimpleAttributeSet()
        StyleConstants.setLineSpacing(attributes, stateService.state.lineSpacingPercent / 100f)
        document.setParagraphAttributes(0, document.length, attributes, false)
    }

    private fun applyTheme() {
        val theme = selectedTheme()
        val background = theme.background ?: UIManager.getColor("TextArea.background")
        val foreground = selectedTextColor() ?: theme.foreground ?: UIManager.getColor("TextArea.foreground")
        textPane.background = background
        textPane.foreground = foreground
        textPane.caretColor = foreground
        scrollPane.viewport.background = background
    }

    private fun updateReaderInsets() {
        val base = JBUI.scale(14)
        val viewportWidth = scrollPane.viewport.width.takeIf { it > 0 } ?: 0
        val maxContentWidth = widthModes[stateService.state.widthMode] ?: widthModes.getValue("舒适")
        val sideInset = if (maxContentWidth == null || viewportWidth == 0) {
            base
        } else {
            ((viewportWidth - JBUI.scale(maxContentWidth)) / 2).coerceAtLeast(base)
        }
        textPane.margin = JBUI.insets(base, sideInset, base, sideInset)
    }

    private fun updateCursorMode() {
        textPane.cursor = if (stateService.state.hideCursor) hiddenCursor else defaultTextCursor
        textPane.caret.isVisible = false
        textPane.caret.isSelectionVisible = true
    }

    private fun installKeyboardShortcuts() {
        bindShortcut("向下翻页", KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, 0)) { scrollPage(1) }
        bindShortcut("向上翻页", KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, InputEvent.SHIFT_DOWN_MASK)) { scrollPage(-1) }
        bindShortcut("PageDown", KeyStroke.getKeyStroke(KeyEvent.VK_PAGE_DOWN, 0)) { scrollPage(1) }
        bindShortcut("PageUp", KeyStroke.getKeyStroke(KeyEvent.VK_PAGE_UP, 0)) { scrollPage(-1) }
        bindShortcut("下一章", KeyStroke.getKeyStroke(KeyEvent.VK_RIGHT, InputEvent.ALT_DOWN_MASK)) { moveChapter(1) }
        bindShortcut("上一章", KeyStroke.getKeyStroke(KeyEvent.VK_LEFT, InputEvent.ALT_DOWN_MASK)) { moveChapter(-1) }
        bindShortcut("增大字号", KeyStroke.getKeyStroke(KeyEvent.VK_EQUALS, InputEvent.CTRL_DOWN_MASK)) { changeFontSize(1) }
        bindShortcut("减小字号", KeyStroke.getKeyStroke(KeyEvent.VK_MINUS, InputEvent.CTRL_DOWN_MASK)) { changeFontSize(-1) }
        bindShortcut("增大行距", KeyStroke.getKeyStroke(KeyEvent.VK_CLOSE_BRACKET, InputEvent.CTRL_DOWN_MASK)) { changeLineSpacing(10) }
        bindShortcut("减小行距", KeyStroke.getKeyStroke(KeyEvent.VK_OPEN_BRACKET, InputEvent.CTRL_DOWN_MASK)) { changeLineSpacing(-10) }
    }

    private fun bindShortcut(name: String, keyStroke: KeyStroke, action: () -> Unit) {
        val inputMap = textPane.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
        inputMap.put(keyStroke, name)
        textPane.actionMap.put(name, object : AbstractAction() {
            override fun actionPerformed(event: java.awt.event.ActionEvent?) {
                action()
            }
        })
    }

    private fun scrollPage(direction: Int) {
        val scrollBar = scrollPane.verticalScrollBar
        val delta = (scrollBar.visibleAmount * 0.86).toInt().coerceAtLeast(JBUI.scale(80))
        val nextValue = (scrollBar.value + delta * direction).coerceIn(
            0,
            (scrollBar.maximum - scrollBar.visibleAmount).coerceAtLeast(0),
        )
        scrollBar.value = nextValue
        stateService.state.scrollValue = nextValue
        updateStatus()
    }

    private fun handleBoundaryWheel(event: MouseWheelEvent) {
        val book = currentBook ?: return
        val scrollBar = scrollPane.verticalScrollBar
        val maxScrollValue = (scrollBar.maximum - scrollBar.visibleAmount).coerceAtLeast(0)
        if (suppressBoundaryNavigation) {
            return
        }

        val index = stateService.state.chapterIndex
        val atBottom = scrollBar.value >= maxScrollValue - JBUI.scale(4)
        val atTop = scrollBar.value <= JBUI.scale(4)
        if (event.wheelRotation > 0 && atBottom && index < book.chapters.lastIndex) {
            event.consume()
            renderChapter(index + 1, restoreScroll = false)
        } else if (event.wheelRotation < 0 && atTop && index > 0) {
            event.consume()
            renderChapter(index - 1, restoreScroll = false, scrollToBottom = true)
        }
    }

    private fun selectedFontFamily(): String = stateService.state.fontFamily ?: defaultFontFamily()

    private fun updateControls() {
        val book = currentBook
        val hasBook = book != null
        val index = stateService.state.chapterIndex

        previousButton.isEnabled = hasBook && index > 0
        nextButton.isEnabled = hasBook && index < book.chapters.lastIndex
        chapterSelector.isEnabled = hasBook
        smallerButton.isEnabled = true
        largerButton.isEnabled = true
        tighterLineButton.isEnabled = true
        looserLineButton.isEnabled = true
        fontSelector.isEnabled = true
        themeSelector.isEnabled = true
        widthSelector.isEnabled = true
        hideCursorCheckBox.isEnabled = true

        updateStatus()
    }

    private fun updateStatus() {
        val book = currentBook
        statusLabel.text = if (book == null) {
            "未打开文件"
        } else {
            val fileName = book.path.fileName.toString()
            val index = stateService.state.chapterIndex
            "$fileName | 第 ${index + 1}/${book.chapters.size} 章 | 本章 ${chapterProgress()} | 全书 ${bookProgress()} | ${book.charset.name()}"
        }
    }

    private fun chapterProgress(): String {
        val scrollBar = scrollPane.verticalScrollBar
        val maxScrollValue = (scrollBar.maximum - scrollBar.visibleAmount).coerceAtLeast(0)
        val percent = if (maxScrollValue == 0) 100 else (scrollBar.value * 100 / maxScrollValue).coerceIn(0, 100)
        return "$percent%"
    }

    private fun bookProgress(): String {
        val book = currentBook ?: return "0%"
        val chapter = book.chapters.getOrNull(stateService.state.chapterIndex) ?: return "0%"
        val scrollBar = scrollPane.verticalScrollBar
        val maxScrollValue = (scrollBar.maximum - scrollBar.visibleAmount).coerceAtLeast(0)
        val scrollRatio = if (maxScrollValue == 0) 1.0 else scrollBar.value.toDouble() / maxScrollValue
        val currentOffset = chapter.startOffset + ((chapter.endOffset - chapter.startOffset) * scrollRatio).toInt()
        val percent = if (book.content.isEmpty()) 100 else (currentOffset * 100 / book.content.length).coerceIn(0, 100)
        return "$percent%"
    }

    private fun selectedTheme(): ReaderTheme {
        return readerThemes.firstOrNull { it.name == stateService.state.themeName } ?: readerThemes.first()
    }

    private fun compactChapterTitle(chapter: Chapter, index: Int): String {
        val title = chapter.title.trim()
        chapterPrefix.find(title)?.let { return it.value }
        englishChapterPrefix.find(title)?.let { return it.value }

        val realIndex = if (index >= 0) index else currentBook?.chapters?.indexOf(chapter).orZero()
        return "第 ${realIndex + 1} 章"
    }

    private fun selectedTextColor(): Color? = textColors[stateService.state.textColorName]

    fun updateButtonStyle() {
        val useIcons = stateService.state.buttonStyle == BUTTON_STYLE_ICON
        configureButton(openButton, "打开", ReaderButtonIcon(ButtonIconKind.OPEN), "打开 TXT 文件", useIcons)
        configureButton(previousButton, "上一章", ReaderButtonIcon(ButtonIconKind.PREVIOUS), "上一章", useIcons)
        configureButton(nextButton, "下一章", ReaderButtonIcon(ButtonIconKind.NEXT), "下一章", useIcons)
        configureButton(smallerButton, "A-", ReaderButtonIcon(ButtonIconKind.FONT_SMALLER), "减小字号", useIcons)
        configureButton(largerButton, "A+", ReaderButtonIcon(ButtonIconKind.FONT_LARGER), "增大字号", useIcons)
        configureButton(tighterLineButton, "行距-", ReaderButtonIcon(ButtonIconKind.LINE_TIGHTER), "减小行距", useIcons)
        configureButton(looserLineButton, "行距+", ReaderButtonIcon(ButtonIconKind.LINE_LOOSER), "增大行距", useIcons)
        configureButton(settingsButton, if (settingsVisible) "收起" else "设置", ReaderButtonIcon(ButtonIconKind.SETTINGS), "显示或隐藏阅读设置", useIcons)
        revalidate()
        repaint()
    }

    private fun configureButton(
        button: JButton,
        text: String,
        icon: Icon,
        tooltip: String,
        useIcons: Boolean,
    ) {
        button.toolTipText = tooltip
        button.text = if (useIcons) null else text
        button.icon = if (useIcons) icon else null
        button.preferredSize = if (useIcons) {
            Dimension(JBUI.scale(34), button.preferredSize.height)
        } else {
            null
        }
    }

    private fun JComboBox<String>.getIndexOf(value: String): Int {
        for (index in 0 until itemCount) {
            if (getItemAt(index) == value) {
                return index
            }
        }
        return -1
    }

    private fun defaultFontFamily(): String {
        val availableFonts = loadFontFamilies().toSet()
        return preferredFontFamilies.firstOrNull(availableFonts::contains) ?: Font.SERIF
    }

    private fun loadFontFamilies(): List<String> {
        val installedFonts = GraphicsEnvironment
            .getLocalGraphicsEnvironment()
            .availableFontFamilyNames
            .toList()
            .sorted()
        return (preferredFontFamilies + installedFonts).distinct()
    }

    companion object {
        private val widthModes = linkedMapOf(
            "紧凑" to 680,
            "舒适" to 820,
            "宽松" to 980,
            "填满" to null,
        )

        private val readerThemes = listOf(
            ReaderTheme("跟随 IDE", null, null),
            ReaderTheme("护眼", Color(0xEAF2DD), Color(0x26311F)),
            ReaderTheme("纸张", Color(0xF7F1E3), Color(0x2B2118)),
            ReaderTheme("暗色", Color(0x1F2329), Color(0xD6D6D6)),
        )

        private val textColors = linkedMapOf<String, Color?>(
            "跟随主题" to null,
            "柔白" to Color(0xF2F2F2),
            "墨黑" to Color(0x202124),
            "暖棕" to Color(0x4B3828),
            "护眼绿" to Color(0x31452D),
            "淡灰" to Color(0xC9CDD4),
        )

        private val preferredFontFamilies = listOf(
            "Microsoft YaHei",
            "Microsoft YaHei UI",
            "SimSun",
            "NSimSun",
            "FangSong",
            "KaiTi",
            "SimHei",
            Font.SERIF,
            Font.SANS_SERIF,
            Font.MONOSPACED,
        )

        private const val SETTINGS_PANEL_NAME = "reader-settings-panel"
        private const val CHAPTER_SELECTOR_WIDTH = 96
        private const val BUTTON_STYLE_TEXT = "文字"
        private const val BUTTON_STYLE_ICON = "图标"
        private val chapterPrefix = Regex("""^第[0-9零〇一二两三四五六七八九十百千万]{1,12}[章节回卷集部篇]""")
        private val englishChapterPrefix = Regex("""(?i)^chapter\s+\d+""")
        private val panelsByProject = WeakHashMap<Project, MutableSet<ReaderPanel>>()

        private fun registerPanel(project: Project, panel: ReaderPanel) {
            panelsByProject.getOrPut(project) { mutableSetOf() }.add(panel)
        }

        private fun unregisterPanel(project: Project, panel: ReaderPanel) {
            panelsByProject[project]?.remove(panel)
        }

        fun toggleButtonStyle(project: Project): String {
            val state = ReaderStateService.getInstance(project).state
            state.buttonStyle = if (state.buttonStyle == BUTTON_STYLE_ICON) BUTTON_STYLE_TEXT else BUTTON_STYLE_ICON
            panelsByProject[project]?.forEach { it.updateButtonStyle() }
            return state.buttonStyle
        }
    }
}

private fun Int?.orZero(): Int = this ?: 0

private data class ReaderTheme(
    val name: String,
    val background: Color?,
    val foreground: Color?,
)

private enum class ButtonIconKind {
    OPEN,
    PREVIOUS,
    NEXT,
    FONT_SMALLER,
    FONT_LARGER,
    LINE_TIGHTER,
    LINE_LOOSER,
    SETTINGS,
}

private class ReaderButtonIcon(private val kind: ButtonIconKind) : Icon {
    override fun getIconWidth(): Int = JBUI.scale(16)

    override fun getIconHeight(): Int = JBUI.scale(16)

    override fun paintIcon(component: java.awt.Component?, graphics: Graphics?, x: Int, y: Int) {
        if (graphics == null) {
            return
        }

        val g = graphics.create() as Graphics2D
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.color = component?.foreground ?: UIManager.getColor("Button.foreground")
            when (kind) {
                ButtonIconKind.OPEN -> paintOpen(g, x, y)
                ButtonIconKind.PREVIOUS -> paintArrow(g, x, y, left = true)
                ButtonIconKind.NEXT -> paintArrow(g, x, y, left = false)
                ButtonIconKind.FONT_SMALLER -> paintText(g, x, y, "A-")
                ButtonIconKind.FONT_LARGER -> paintText(g, x, y, "A+")
                ButtonIconKind.LINE_TIGHTER -> paintLines(g, x, y, tight = true)
                ButtonIconKind.LINE_LOOSER -> paintLines(g, x, y, tight = false)
                ButtonIconKind.SETTINGS -> paintText(g, x, y, "...")
            }
        } finally {
            g.dispose()
        }
    }

    private fun paintOpen(g: Graphics2D, x: Int, y: Int) {
        val s = JBUI.scale(16)
        g.drawRect(x + JBUI.scale(2), y + JBUI.scale(5), s - JBUI.scale(4), s - JBUI.scale(7))
        g.drawLine(x + JBUI.scale(3), y + JBUI.scale(5), x + JBUI.scale(6), y + JBUI.scale(2))
        g.drawLine(x + JBUI.scale(6), y + JBUI.scale(2), x + JBUI.scale(10), y + JBUI.scale(2))
    }

    private fun paintArrow(g: Graphics2D, x: Int, y: Int, left: Boolean) {
        val midY = y + JBUI.scale(8)
        val startX = if (left) x + JBUI.scale(11) else x + JBUI.scale(5)
        val endX = if (left) x + JBUI.scale(5) else x + JBUI.scale(11)
        g.drawLine(startX, midY, endX, midY)
        if (left) {
            g.drawLine(endX, midY, endX + JBUI.scale(4), midY - JBUI.scale(4))
            g.drawLine(endX, midY, endX + JBUI.scale(4), midY + JBUI.scale(4))
        } else {
            g.drawLine(endX, midY, endX - JBUI.scale(4), midY - JBUI.scale(4))
            g.drawLine(endX, midY, endX - JBUI.scale(4), midY + JBUI.scale(4))
        }
    }

    private fun paintLines(g: Graphics2D, x: Int, y: Int, tight: Boolean) {
        val gap = JBUI.scale(if (tight) 3 else 5)
        val startY = y + JBUI.scale(if (tight) 5 else 3)
        for (index in 0..2) {
            val lineY = startY + gap * index
            g.drawLine(x + JBUI.scale(3), lineY, x + JBUI.scale(13), lineY)
        }
    }

    private fun paintText(g: Graphics2D, x: Int, y: Int, text: String) {
        g.font = g.font.deriveFont(Font.BOLD, JBUI.scale(10).toFloat())
        val metrics = g.fontMetrics
        val textX = x + ((iconWidth - metrics.stringWidth(text)) / 2)
        val textY = y + ((iconHeight - metrics.height) / 2) + metrics.ascent
        g.drawString(text, textX, textY)
    }
}

private class ReaderTextPane : JTextPane() {
    override fun getScrollableTracksViewportWidth(): Boolean = true
}

private fun createHiddenCursor(): Cursor {
    val image = BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB)
    return Toolkit.getDefaultToolkit().createCustomCursor(image, Point(0, 0), "hidden-reader-cursor")
}
