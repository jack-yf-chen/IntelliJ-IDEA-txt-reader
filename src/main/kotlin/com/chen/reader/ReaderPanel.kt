package com.chen.reader

import com.chen.reader.model.Book
import com.chen.reader.model.Chapter
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.*
import java.awt.event.*
import java.awt.image.BufferedImage
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.*
import javax.swing.*
import javax.swing.Timer
import javax.swing.event.PopupMenuEvent
import javax.swing.event.PopupMenuListener
import kotlin.math.sign

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
    private val textPane = VirtualReaderPane()
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
    private var settingsVisible = false
    private var lastScrollValue = 0
    private val relayoutTimer = Timer(160) { restoreViewportAfterRelayout() }.apply {
        isRepeats = false
    }

    init {
        border = JBUI.Borders.empty(8)
        registerPanel(project, this)
        configureChapterSelector()
        initializeFontSelector()
        initializeTextColorSelector()
        initializeThemeSelector()
        initializeWidthSelector()
        hideCursorCheckBox.isSelected = stateService.state.hideCursor

        textPane.isFocusable = true
        textPane.border = BorderFactory.createEmptyBorder()
        textPane.componentPopupMenu = createSelectionPopupMenu()
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
                focusReader()
            }
        }
        textColorSelector.addActionListener {
            if (!updatingTextColorSelector) {
                stateService.state.textColorName = textColorSelector.selectedItem as String
                updateReaderStyle()
                focusReader()
            }
        }
        themeSelector.addActionListener {
            if (!updatingThemeSelector) {
                stateService.state.themeName = themeSelector.selectedItem as String
                updateReaderStyle()
                focusReader()
            }
        }
        widthSelector.addActionListener {
            if (!updatingWidthSelector) {
                stateService.state.widthMode = widthSelector.selectedItem as String
                updateReaderInsets()
                focusReader()
            }
        }
        hideCursorCheckBox.addActionListener {
            stateService.state.hideCursor = hideCursorCheckBox.isSelected
            updateCursorMode()
            focusReader()
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
                updateReadingPositionFromViewport()
                updateStatus()
            }
        }
        scrollPane.addMouseWheelListener(::handleWheelScroll)
        scrollPane.viewport.addComponentListener(object : ComponentAdapter() {
            override fun componentResized(event: ComponentEvent) {
                updateReaderInsets()
                scheduleRelayoutRestore()
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

    private fun createSelectionPopupMenu(): JPopupMenu {
        val popup = JPopupMenu()
        val localDictionaryItem = JMenuItem("本地词典查找")
        val dictionaryItem = JMenuItem("汉典查词")
        val searchItem = JMenuItem("百度搜索")
        val copyItem = JMenuItem("复制")

        localDictionaryItem.addActionListener { showLocalDictionaryLookup() }
        dictionaryItem.addActionListener { openDictionaryLookup() }
        searchItem.addActionListener { openBrowserSearch() }
        copyItem.addActionListener {
            textPane.copySelection()
            focusReader()
        }

        popup.add(localDictionaryItem)
        popup.add(dictionaryItem)
        popup.add(searchItem)
        popup.addSeparator()
        popup.add(copyItem)
        popup.addPopupMenuListener(object : PopupMenuListener {
            override fun popupMenuWillBecomeVisible(event: PopupMenuEvent?) {
                val hasSelection = selectedLookupText() != null
                localDictionaryItem.isEnabled = hasSelection
                dictionaryItem.isEnabled = hasSelection
                searchItem.isEnabled = hasSelection
                copyItem.isEnabled = hasSelection
            }

            override fun popupMenuWillBecomeInvisible(event: PopupMenuEvent?) = Unit

            override fun popupMenuCanceled(event: PopupMenuEvent?) = Unit
        })
        return popup
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
            textPane.setBook(book)

            state.filePath = path.toString()
            state.charsetName = book.charset.name()
            if (!restoreState) {
                state.chapterIndex = 0
                state.scrollValue = 0
                state.globalOffset = 0
                state.anchorText = ""
                state.progressInChapterPermille = 0
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

        val chapter = book.chapters[index]
        stateService.state.chapterIndex = index

        updatingChapterSelector = true
        chapterSelector.selectedIndex = index
        chapterSelector.toolTipText = chapter.title
        updatingChapterSelector = false

        val targetOffset = when {
            scrollToBottom -> chapter.endOffset
            restoreScroll -> restoreOffset(book, chapter) ?: chapter.startOffset
            else -> chapter.startOffset
        }
        scrollToGlobalOffset(targetOffset, alignEnd = scrollToBottom, preserveAnchor = restoreScroll)
        updateControls()
    }

    private fun scrollToGlobalOffset(
        targetOffset: Int,
        alignEnd: Boolean = false,
        preserveAnchor: Boolean = false,
    ) {
        val book = currentBook ?: return
        val scrollBar = scrollPane.verticalScrollBar
        val maxScrollValue = (scrollBar.maximum - scrollBar.visibleAmount).coerceAtLeast(0)
        val scrollValue = textPane.scrollValueForOffset(
            targetOffset.coerceIn(0, book.content.length),
            viewportHeight = scrollPane.viewport.height,
            alignEnd = alignEnd,
            preserveAnchor = preserveAnchor,
            anchorRatio = VIEWPORT_ANCHOR_RATIO,
        ).coerceIn(0, maxScrollValue)

        scrollBar.value = scrollValue
        lastScrollValue = scrollValue
        stateService.state.scrollValue = scrollValue
        updateReadingPositionForGlobalOffset(viewportAnchorOffset())
        focusReader()
        updateStatus()
    }

    private fun updateCurrentChapterFromScroll() {
        updateReadingPositionFromViewport()
    }

    private fun updateReadingPositionFromViewport() {
        updateReadingPositionForGlobalOffset(viewportAnchorOffset())
    }

    private fun updateReadingPositionForGlobalOffset(offset: Int) {
        val book = currentBook ?: return
        val index = chapterIndexForOffset(book, offset)
        saveReadingAnchor(book, index, offset)
        if (index == stateService.state.chapterIndex) {
            return
        }

        stateService.state.chapterIndex = index
        updatingChapterSelector = true
        chapterSelector.selectedIndex = index
        chapterSelector.toolTipText = book.chapters[index].title
        updatingChapterSelector = false
        updateControls()
    }

    private fun saveReadingAnchor(book: Book, chapterIndex: Int, offset: Int) {
        val chapter = book.chapters.getOrNull(chapterIndex) ?: return
        val safeOffset = offset.coerceIn(0, book.content.length)
        val chapterLength = (chapter.endOffset - chapter.startOffset).coerceAtLeast(1)
        val progress = ((safeOffset - chapter.startOffset).coerceIn(0, chapterLength) * 1000 / chapterLength)
            .coerceIn(0, 1000)

        val state = stateService.state
        state.globalOffset = safeOffset
        state.anchorText = anchorTextAt(book.content, safeOffset)
        state.progressInChapterPermille = progress
    }

    private fun restoreOffset(book: Book, chapter: Chapter): Int? {
        val state = stateService.state
        val savedOffset = state.globalOffset.takeIf { it in 0..book.content.length }
        val anchor = state.anchorText.takeIf { it.isNotBlank() }

        if (savedOffset != null && anchor != null) {
            findAnchorNear(book.content, anchor, savedOffset)?.let { return it }
        }

        if (savedOffset != null && state.globalOffset > 0) {
            return savedOffset
        }

        if (state.progressInChapterPermille in 1..1000) {
            val chapterLength = (chapter.endOffset - chapter.startOffset).coerceAtLeast(1)
            return chapter.startOffset + chapterLength * state.progressInChapterPermille / 1000
        }

        return null
    }

    private fun findAnchorNear(content: String, anchor: String, savedOffset: Int): Int? {
        val start = (savedOffset - ANCHOR_SEARCH_RADIUS).coerceAtLeast(0)
        val end = (savedOffset + ANCHOR_SEARCH_RADIUS + anchor.length).coerceAtMost(content.length)
        val localIndex = content.substring(start, end).indexOf(anchor)
        if (localIndex >= 0) {
            return start + localIndex
        }

        val globalIndex = content.indexOf(anchor)
        return globalIndex.takeIf { it >= 0 }
    }

    private fun anchorTextAt(content: String, offset: Int): String {
        val start = offset.coerceIn(0, content.length)
        val end = (start + ANCHOR_TEXT_LENGTH).coerceAtMost(content.length)
        return content.substring(start, end)
    }

    private fun chapterIndexForOffset(book: Book, offset: Int): Int {
        return book.chapters.indexOfLast { offset >= it.startOffset }.coerceAtLeast(0)
    }

    private fun viewportAnchorOffset(): Int {
        val viewport = scrollPane.viewport
        return textPane.offsetAtY(viewport.viewPosition.y + (viewport.height * VIEWPORT_ANCHOR_RATIO).toInt())
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
        applyTheme()
        updateReaderInsets()
        textPane.updateReaderStyle(
            font = Font(selectedFontFamily(), Font.PLAIN, stateService.state.fontSize),
            foreground = selectedForegroundColor(),
            lineSpacingPercent = stateService.state.lineSpacingPercent,
        )
        updateStatus()
        scheduleRelayoutRestore()
    }

    private fun applyTheme() {
        val theme = selectedTheme()
        val background = theme.background ?: UIManager.getColor("TextArea.background")
        textPane.background = background
        scrollPane.viewport.background = background
    }

    private fun selectedForegroundColor(): Color {
        val theme = selectedTheme()
        return selectedTextColor() ?: theme.foreground ?: UIManager.getColor("TextArea.foreground")
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
        val rightInset = sideInset + JBUI.scale(RIGHT_SELECTION_GUTTER)
        textPane.updateContentInsets(Insets(base, sideInset, base, rightInset))
    }

    private fun scheduleRelayoutRestore() {
        if (currentBook == null) {
            return
        }
        relayoutTimer.restart()
    }

    private fun restoreViewportAfterRelayout() {
        val book = currentBook ?: return
        val targetOffset = stateService.state.globalOffset.coerceIn(0, book.content.length)
        scrollToGlobalOffset(targetOffset, preserveAnchor = true)
    }

    private fun updateCursorMode() {
        textPane.cursor = if (stateService.state.hideCursor) hiddenCursor else defaultTextCursor
    }

    private fun installKeyboardShortcuts() {
        bindShortcut("向下滚动", KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, 0)) { scrollLines(1) }
        bindShortcut("向上滚动", KeyStroke.getKeyStroke(KeyEvent.VK_UP, 0)) { scrollLines(-1) }
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
        val readerAction = object : AbstractAction() {
            override fun actionPerformed(event: java.awt.event.ActionEvent?) {
                action()
            }
        }
        bindShortcutTo(this, JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT, name, keyStroke, readerAction)
        bindShortcutTo(textPane, JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT, name, keyStroke, readerAction)
        bindShortcutTo(textPane, JComponent.WHEN_FOCUSED, name, keyStroke, readerAction)
    }

    private fun bindShortcutTo(
        component: JComponent,
        condition: Int,
        name: String,
        keyStroke: KeyStroke,
        action: AbstractAction,
    ) {
        component.getInputMap(condition).put(keyStroke, name)
        component.actionMap.put(name, action)
    }

    private fun scrollLines(direction: Int) {
        val lineStep = (textPane.getFontMetrics(textPane.font).height * 3).coerceAtLeast(JBUI.scale(36))
        scrollBy(lineStep * direction)
    }

    private fun scrollPage(direction: Int) {
        val scrollBar = scrollPane.verticalScrollBar
        val delta = (scrollBar.visibleAmount * 0.86).toInt().coerceAtLeast(JBUI.scale(80))
        scrollBy(delta * direction)
    }

    private fun scrollBy(delta: Int) {
        currentBook ?: return
        val scrollBar = scrollPane.verticalScrollBar
        val maxScrollValue = (scrollBar.maximum - scrollBar.visibleAmount).coerceAtLeast(0)
        val currentValue = scrollBar.value
        val nextValue = (currentValue + delta).coerceIn(
            0,
            maxScrollValue,
        )

        scrollBar.value = nextValue
        stateService.state.scrollValue = nextValue
        lastScrollValue = nextValue
        updateCurrentChapterFromScroll()
        updateStatus()
    }

    private fun focusReader() {
        SwingUtilities.invokeLater {
            textPane.requestFocusInWindow()
        }
    }

    private fun showLocalDictionaryLookup() {
        val text = selectedLookupText(MAX_DICTIONARY_SELECTION_LENGTH) ?: return
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "本地词典查找", false) {
            override fun run(indicator: ProgressIndicator) {
                indicator.text = "正在查找：$text"
                val message = try {
                    val result = LocalDictionary.lookup(text)
                    if (result == null) {
                        "本地词典未找到：$text"
                    } else {
                        result.body
                    }
                } catch (error: Throwable) {
                    "本地词典加载或查找失败：${error.message ?: error.javaClass.simpleName}"
                }

                ApplicationManager.getApplication().invokeLater {
                    Messages.showInfoMessage(project, message, "本地词典：$text")
                    focusReader()
                }
            }
        })
    }

    private fun openDictionaryLookup() {
        val text = selectedLookupText(MAX_DICTIONARY_SELECTION_LENGTH) ?: return
        BrowserUtil.browse("https://www.zdic.net/hans/${encodeUrlComponent(text)}")
        focusReader()
    }

    private fun openBrowserSearch() {
        val text = selectedLookupText(MAX_TRANSLATION_SELECTION_LENGTH) ?: return
        BrowserUtil.browse("https://www.baidu.com/s?wd=${encodeUrlComponent(text)}")
        focusReader()
    }

    private fun selectedLookupText(maxLength: Int = MAX_TRANSLATION_SELECTION_LENGTH): String? {
        val normalizedText = textPane.selectedText()
            ?.replace(whitespaceRegex, " ")
            ?.trim()
            ?: return null
        return normalizedText
            .takeIf { it.isNotBlank() }
            ?.take(maxLength)
    }

    private fun encodeUrlComponent(text: String): String {
        return URLEncoder.encode(text, StandardCharsets.UTF_8).replace("+", "%20")
    }

    private fun handleWheelScroll(event: MouseWheelEvent) {
        SwingUtilities.invokeLater {
            updateCurrentChapterFromScroll()
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
        val book = currentBook ?: return "0%"
        val chapter = book.chapters.getOrNull(stateService.state.chapterIndex) ?: return "0%"
        val chapterLength = (chapter.endOffset - chapter.startOffset).coerceAtLeast(1)
        val currentOffset = viewportAnchorOffset().coerceIn(chapter.startOffset, chapter.endOffset)
        val percent = ((currentOffset - chapter.startOffset) * 100 / chapterLength).coerceIn(0, 100)
        return "$percent%"
    }

    private fun bookProgress(): String {
        val book = currentBook ?: return "0%"
        val currentOffset = viewportAnchorOffset()
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
        private const val MAX_DICTIONARY_SELECTION_LENGTH = 80
        private const val MAX_TRANSLATION_SELECTION_LENGTH = 500
        private const val ANCHOR_TEXT_LENGTH = 80
        private const val ANCHOR_SEARCH_RADIUS = 3000
        private const val VIEWPORT_ANCHOR_RATIO = 0.25
        private const val RIGHT_SELECTION_GUTTER = 28
        private const val BUTTON_STYLE_TEXT = "文字"
        private const val BUTTON_STYLE_ICON = "图标"
        private val chapterPrefix = Regex("""^第[0-9零〇一二两三四五六七八九十百千万]{1,12}[章节回卷集部篇]""")
        private val englishChapterPrefix = Regex("""(?i)^chapter\s+\d+""")
        private val whitespaceRegex = Regex("""\s+""")
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

private class VirtualReaderPane : JComponent(), Scrollable {
    private var book: Book? = null
    private var lines: List<VirtualLine> = emptyList()
    private var contentInsets: Insets = JBUI.insets(14)
    private var lineHeight = JBUI.scale(28)
    private var ascent = JBUI.scale(20)
    private var foregroundColor = UIManager.getColor("TextArea.foreground")
    private var lineSpacingPercent = 20
    private var selectionStart: Int? = null
    private var selectionEnd: Int? = null

    init {
        background = UIManager.getColor("TextArea.background")
        foreground = foregroundColor
        isOpaque = true
        installSelectionHandlers()
    }

    fun setBook(book: Book) {
        this.book = book
        clearSelection()
        rebuildLayout()
    }

    fun updateReaderStyle(font: Font, foreground: Color, lineSpacingPercent: Int) {
        this.font = font
        this.foregroundColor = foreground
        this.foreground = foreground
        this.lineSpacingPercent = lineSpacingPercent
        rebuildLayout()
    }

    fun updateContentInsets(insets: Insets) {
        contentInsets = insets
        rebuildLayout()
    }

    fun offsetAtY(y: Int): Int {
        val currentBook = book ?: return 0
        if (lines.isEmpty()) {
            return 0
        }

        val index = lineIndexForY(y)
        val line = lines[index]
        return line.startOffset.coerceIn(0, currentBook.content.length)
    }

    fun scrollValueForOffset(
        offset: Int,
        viewportHeight: Int,
        alignEnd: Boolean,
        preserveAnchor: Boolean,
        anchorRatio: Double,
    ): Int {
        val line = lineForOffset(offset)
        val lineTop = line?.y ?: contentInsets.top
        return when {
            alignEnd -> lineTop - viewportHeight + lineHeight + JBUI.scale(24)
            preserveAnchor -> lineTop - (viewportHeight * anchorRatio).toInt()
            else -> lineTop
        }
    }

    fun selectedText(): String? {
        val currentBook = book ?: return null
        val start = selectionStart ?: return null
        val end = selectionEnd ?: return null
        val from = minOf(start, end).coerceIn(0, currentBook.content.length)
        val to = maxOf(start, end).coerceIn(0, currentBook.content.length)
        if (from == to) {
            return null
        }
        return currentBook.content.substring(from, to)
    }

    fun copySelection() {
        val text = selectedText() ?: return
        Toolkit.getDefaultToolkit().systemClipboard.setContents(
            java.awt.datatransfer.StringSelection(text),
            null,
        )
    }

    override fun paintComponent(graphics: Graphics) {
        super.paintComponent(graphics)
        val currentBook = book ?: return
        val g = graphics.create() as Graphics2D
        try {
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            g.font = font

            val clipTop = g.clipBounds.y
            val clipBottom = clipTop + g.clipBounds.height
            val startIndex = lineIndexForY((clipTop - lineHeight).coerceAtLeast(0))
            val endIndex = lineIndexForY(clipBottom + lineHeight)

            paintSelection(g, startIndex, endIndex)
            g.color = foregroundColor
            for (index in startIndex..endIndex) {
                val line = lines.getOrNull(index) ?: continue
                if (line.endOffset <= line.startOffset) {
                    continue
                }
                val text = currentBook.content.substring(line.startOffset, line.endOffset)
                g.drawString(text, contentInsets.left, line.y + ascent)
            }
        } finally {
            g.dispose()
        }
    }

    override fun getPreferredSize(): Dimension {
        val lineCount = lines.size.coerceAtLeast(1)
        val height = contentInsets.top + contentInsets.bottom + lineCount * lineHeight
        return Dimension(parent?.width ?: JBUI.scale(800), height)
    }

    override fun getPreferredScrollableViewportSize(): Dimension = preferredSize

    override fun getScrollableUnitIncrement(visibleRect: Rectangle, orientation: Int, direction: Int): Int {
        return lineHeight.coerceAtLeast(JBUI.scale(24))
    }

    override fun getScrollableBlockIncrement(visibleRect: Rectangle, orientation: Int, direction: Int): Int {
        return (visibleRect.height * 0.86).toInt().coerceAtLeast(lineHeight)
    }

    override fun getScrollableTracksViewportWidth(): Boolean = true

    override fun getScrollableTracksViewportHeight(): Boolean = false

    private fun rebuildLayout() {
        val currentBook = book
        val metrics = getFontMetrics(font ?: UIManager.getFont("TextArea.font"))
        ascent = metrics.ascent
        lineHeight = (metrics.height * (1f + lineSpacingPercent / 100f)).toInt().coerceAtLeast(metrics.height)

        lines = if (currentBook == null) {
            emptyList()
        } else {
            layoutContent(currentBook.content, metrics)
        }

        revalidate()
        repaint()
    }

    private fun layoutContent(content: String, metrics: FontMetrics): List<VirtualLine> {
        if (content.isEmpty()) {
            return listOf(VirtualLine(0, 0, contentInsets.top))
        }

        val result = mutableListOf<VirtualLine>()
        val availableWidth = width
            .takeIf { it > 0 }
            ?: (parent as? JViewport)?.width
            ?: JBUI.scale(800)
        val maxWidth = (availableWidth - contentInsets.left - contentInsets.right).coerceAtLeast(JBUI.scale(120))
        var lineStart = 0
        var index = 0
        var currentWidth = 0

        fun addLine(endOffset: Int) {
            val y = contentInsets.top + result.size * lineHeight
            result.add(VirtualLine(lineStart, endOffset.coerceAtLeast(lineStart), y))
        }

        while (index < content.length) {
            val char = content[index]
            if (char == '\r') {
                index++
                continue
            }
            if (char == '\n') {
                addLine(index)
                index++
                lineStart = index
                currentWidth = 0
                continue
            }

            val charWidth = metrics.charWidth(char).coerceAtLeast(1)
            if (currentWidth > 0 && currentWidth + charWidth > maxWidth) {
                addLine(index)
                lineStart = index
                currentWidth = 0
                continue
            }
            currentWidth += charWidth
            index++
        }

        addLine(content.length)
        return result
    }

    private fun installSelectionHandlers() {
        addMouseListener(object : MouseAdapter() {
            override fun mousePressed(event: MouseEvent) {
                if (SwingUtilities.isLeftMouseButton(event)) {
                    val offset = offsetAtPoint(event.point)
                    selectionStart = offset
                    selectionEnd = offset
                    requestFocusInWindow()
                    repaint()
                }
            }

            override fun mouseReleased(event: MouseEvent) {
                if (SwingUtilities.isLeftMouseButton(event)) {
                    selectionEnd = offsetAtPoint(event.point)
                    repaint()
                }
            }
        })
        addMouseMotionListener(object : MouseMotionAdapter() {
            override fun mouseDragged(event: MouseEvent) {
                selectionEnd = offsetAtPoint(event.point)
                repaint()
            }
        })
    }

    private fun clearSelection() {
        selectionStart = null
        selectionEnd = null
        repaint()
    }

    private fun paintSelection(g: Graphics2D, startIndex: Int, endIndex: Int) {
        val currentBook = book ?: return
        val start = selectionStart ?: return
        val end = selectionEnd ?: return
        val from = minOf(start, end).coerceIn(0, currentBook.content.length)
        val to = maxOf(start, end).coerceIn(0, currentBook.content.length)
        if (from == to) {
            return
        }

        val selectionColor = UIManager.getColor("TextArea.selectionBackground") ?: Color(0x4A6FA5)
        g.color = selectionColor
        for (index in startIndex..endIndex) {
            val line = lines.getOrNull(index) ?: continue
            val selectedStart = maxOf(from, line.startOffset)
            val selectedEnd = minOf(to, line.endOffset)
            if (selectedStart >= selectedEnd) {
                continue
            }
            val x = contentInsets.left + textWidth(currentBook.content, line.startOffset, selectedStart)
            val width = textWidth(currentBook.content, selectedStart, selectedEnd).coerceAtLeast(JBUI.scale(2))
            g.fillRect(x, line.y, width, lineHeight)
        }
    }

    private fun offsetAtPoint(point: Point): Int {
        val currentBook = book ?: return 0
        if (lines.isEmpty()) {
            return 0
        }
        val line = lines[lineIndexForY(point.y)]
        val x = (point.x - contentInsets.left).coerceAtLeast(0)
        var offset = line.startOffset
        var width = 0
        while (offset < line.endOffset) {
            val charWidth = getFontMetrics(font).charWidth(currentBook.content[offset]).coerceAtLeast(1)
            if (x < width + charWidth / 2) {
                break
            }
            width += charWidth
            offset++
        }
        return offset.coerceIn(0, currentBook.content.length)
    }

    private fun lineIndexForY(y: Int): Int {
        if (lines.isEmpty()) {
            return 0
        }
        return ((y - contentInsets.top) / lineHeight).coerceIn(0, lines.lastIndex)
    }

    private fun lineForOffset(offset: Int): VirtualLine? {
        if (lines.isEmpty()) {
            return null
        }
        val index = lines.binarySearch {
            when {
                offset < it.startOffset -> 1
                offset > it.endOffset -> -1
                else -> 0
            }
        }
        if (index >= 0) {
            return lines[index]
        }
        val insertionPoint = -index - 1
        return lines.getOrNull((insertionPoint - 1).coerceIn(0, lines.lastIndex))
    }

    private fun textWidth(content: String, start: Int, end: Int): Int {
        if (start >= end) {
            return 0
        }
        return getFontMetrics(font).stringWidth(content.substring(start, end))
    }
}

private data class VirtualLine(
    val startOffset: Int,
    val endOffset: Int,
    val y: Int,
)

private fun createHiddenCursor(): Cursor {
    val image = BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB)
    return Toolkit.getDefaultToolkit().createCustomCursor(image, Point(0, 0), "hidden-reader-cursor")
}
