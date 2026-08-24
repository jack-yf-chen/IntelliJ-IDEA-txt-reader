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
import java.awt.GridLayout
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
    private val textPane = ReaderTextPane()
    private val scrollPane = JBScrollPane(textPane)
    private val statusLabel = JLabel("未打开文件")

    private var currentBook: Book? = null
    private var updatingChapterSelector = false

    init {
        border = JBUI.Borders.empty(8)

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
        chapterSelector.addActionListener {
            if (!updatingChapterSelector) {
                renderChapter(chapterSelector.selectedIndex, restoreScroll = false)
            }
        }

        scrollPane.verticalScrollBar.addAdjustmentListener {
            if (!it.valueIsAdjusting) {
                stateService.state.scrollValue = it.value
            }
        }

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
        buttonRows.add(navigationPanel)
        buttonRows.add(readingPanel)

        chapterSelector.minimumSize = Dimension(0, chapterSelector.preferredSize.height)

        toolbar.add(buttonRows, BorderLayout.NORTH)
        toolbar.add(chapterSelector, BorderLayout.CENTER)
        return toolbar
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

    private fun renderChapter(index: Int, restoreScroll: Boolean) {
        val book = currentBook ?: return
        if (index !in book.chapters.indices) {
            return
        }

        val chapter = book.chapters[index]
        stateService.state.chapterIndex = index
        val text = book.content.substring(chapter.startOffset, chapter.endOffset).trimStart()
        textPane.text = text
        applyParagraphStyle()
        textPane.caretPosition = 0

        updatingChapterSelector = true
        chapterSelector.selectedIndex = index
        updatingChapterSelector = false

        val scrollValue = if (restoreScroll) stateService.state.scrollValue else 0
        SwingUtilities.invokeLater {
            scrollPane.verticalScrollBar.value = scrollValue
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
        textPane.font = Font(Font.SERIF, Font.PLAIN, stateService.state.fontSize)
        applyParagraphStyle()
    }

    private fun applyParagraphStyle() {
        val document = textPane.styledDocument
        val attributes = SimpleAttributeSet()
        StyleConstants.setLineSpacing(attributes, stateService.state.lineSpacingPercent / 100f)
        document.setParagraphAttributes(0, document.length, attributes, false)
    }

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

        statusLabel.text = if (book == null) {
            "未打开文件"
        } else {
            val fileName = book.path.fileName.toString()
            "$fileName | 第 ${index + 1}/${book.chapters.size} 章 | 字号 ${stateService.state.fontSize} | 行距 ${100 + stateService.state.lineSpacingPercent}% | ${book.charset.name()}"
        }
    }
}

private class ReaderTextPane : JTextPane() {
    override fun getScrollableTracksViewportWidth(): Boolean = true
}
