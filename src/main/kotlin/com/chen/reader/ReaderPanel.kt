package com.chen.reader

import com.chen.reader.model.Book
import com.chen.reader.model.Chapter
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.GraphicsEnvironment
import java.awt.GridLayout
import java.awt.event.MouseWheelEvent
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTextPane
import javax.swing.SwingUtilities
import javax.swing.UIManager
import javax.swing.text.SimpleAttributeSet
import javax.swing.text.StyleConstants

class ReaderPanel(private val project: Project) : JPanel(BorderLayout()) {
    private val stateService = ReaderStateService.getInstance(project)
    private val openButton = JButton("打开")
    private val previousButton = JButton("上一章")
    private val nextButton = JButton("下一章")
    private val smallerButton = JButton("A-")
    private val largerButton = JButton("A+")
    private val tighterLineButton = JButton("行距-")
    private val looserLineButton = JButton("行距+")
    private val chapterSelector = JComboBox<Chapter>()
    private val fontSelector = JComboBox(loadFontFamilies().toTypedArray())
    private val textPane = ReaderTextPane()
    private val scrollPane = JBScrollPane(textPane)
    private val statusLabel = JLabel("未打开文件")

    private var currentBook: Book? = null
    private var updatingChapterSelector = false
    private var updatingFontSelector = false
    private var suppressBoundaryNavigation = false
    private var lastScrollValue = 0

    init {
        border = JBUI.Borders.empty(8)
        initializeFontSelector()

        textPane.isEditable = false
        textPane.margin = JBUI.insets(14)
        textPane.background = UIManager.getColor("TextArea.background")
        textPane.border = BorderFactory.createEmptyBorder()
        updateReaderStyle()

        add(createToolbar(), BorderLayout.NORTH)
        add(scrollPane, BorderLayout.CENTER)
        add(statusLabel, BorderLayout.SOUTH)

        openButton.addActionListener { NovelReaderOpener.openFromFileChooser(project) }
        previousButton.addActionListener { moveChapter(-1) }
        nextButton.addActionListener { moveChapter(1) }
        smallerButton.addActionListener { changeFontSize(-1) }
        largerButton.addActionListener { changeFontSize(1) }
        tighterLineButton.addActionListener { changeLineSpacing(-10) }
        looserLineButton.addActionListener { changeLineSpacing(10) }
        fontSelector.addActionListener {
            if (!updatingFontSelector) {
                stateService.state.fontFamily = fontSelector.selectedItem as? String
                updateReaderStyle()
            }
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
            }
        }
        scrollPane.addMouseWheelListener(::handleBoundaryWheel)

        updateControls()
    }

    private fun createToolbar(): JPanel {
        val toolbar = JPanel(BorderLayout(0, JBUI.scale(6)))
        val buttonRows = JPanel(GridLayout(0, 1, 0, JBUI.scale(4)))
        val navigationPanel = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0))
        val readingPanel = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0))

        navigationPanel.add(openButton)
        navigationPanel.add(previousButton)
        navigationPanel.add(nextButton)
        readingPanel.add(smallerButton)
        readingPanel.add(largerButton)
        readingPanel.add(tighterLineButton)
        readingPanel.add(looserLineButton)
        readingPanel.add(fontSelector)
        buttonRows.add(navigationPanel)
        buttonRows.add(readingPanel)

        chapterSelector.minimumSize = Dimension(0, chapterSelector.preferredSize.height)
        fontSelector.minimumSize = Dimension(JBUI.scale(120), fontSelector.preferredSize.height)
        fontSelector.preferredSize = Dimension(JBUI.scale(150), fontSelector.preferredSize.height)

        toolbar.add(buttonRows, BorderLayout.NORTH)
        toolbar.add(chapterSelector, BorderLayout.CENTER)
        return toolbar
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
        applyParagraphStyle()
    }

    private fun applyParagraphStyle() {
        val document = textPane.styledDocument
        val attributes = SimpleAttributeSet()
        StyleConstants.setLineSpacing(attributes, stateService.state.lineSpacingPercent / 100f)
        document.setParagraphAttributes(0, document.length, attributes, false)
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

        statusLabel.text = if (book == null) {
            "未打开文件"
        } else {
            val fileName = book.path.fileName.toString()
            "$fileName | 第 ${index + 1}/${book.chapters.size} 章 | ${selectedFontFamily()} | 字号 ${stateService.state.fontSize} | 行距 ${100 + stateService.state.lineSpacingPercent}% | ${book.charset.name()}"
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
    }
}

private class ReaderTextPane : JTextPane() {
    override fun getScrollableTracksViewportWidth(): Boolean = true
}
