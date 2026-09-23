package com.chen.reader

import com.chen.reader.bookshelf.BookshelfService
import com.chen.reader.bookshelf.ShelfEntry
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.wm.ToolWindowManager
import java.nio.file.Path
import kotlin.io.path.extension

object NovelReaderOpener {
    private const val TOOL_WINDOW_ID = "Novel Reader"

    fun openFromFileChooser(project: Project) {
        val descriptor = FileChooserDescriptor(true, false, false, false, false, false)
            .withFileFilter { file ->
                !file.isDirectory && BookLoader.supportedExtensions.contains(file.extension?.lowercase())
            }
            .withTitle("选择小说文件")
            .withDescription("打开本地 TXT 或 EPUB 文件")

        val file = FileChooser.chooseFile(descriptor, project, null) ?: return
        val path = file.toNioPath()
        if (!BookLoader.supportedExtensions.contains(path.extension.lowercase())) {
            Messages.showWarningDialog(project, "当前支持 TXT 和 EPUB 文件。", "Novel Reader")
            return
        }

        open(project, path)
    }

    fun open(project: Project, path: Path) {
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID)
        if (toolWindow == null) {
            Messages.showErrorDialog(project, "无法找到 Novel Reader 工具窗口。", "Novel Reader")
            return
        }

        toolWindow.activate {
            findReaderPanel(toolWindow)?.let { panel ->
                panel.openBook(path)
            }
        }
    }

    /**
     * 从书架卡片续读。
     *
     * 关键技巧：先把 [entry] 的位置铺进**项目级** `ReaderState`，再调 `openBook(path)`
     * —— 这样 `ReaderPanel.openBook` 里的 `isSameBookPath(state.filePath, path)` 必然为 true，
     * 自动走现成的三级恢复分支（锚点 → offset → 章内千分比），`ReaderPanel` 因此零改动。
     *
     * 顺序不能反：铺 state 之前必须先把**上一本**的进度快照回书架，否则上一本的位置就丢了。
     */
    fun openFromBookshelf(project: Project, entry: ShelfEntry) {
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID)
        if (toolWindow == null) {
            Messages.showErrorDialog(project, "无法找到 Novel Reader 工具窗口。", "Novel Reader")
            return
        }

        val path = runCatching { Path.of(entry.path) }.getOrNull()
        if (path == null) {
            Messages.showWarningDialog(project, "书架里的这本书路径无效：${entry.path}", "Novel Reader")
            return
        }

        toolWindow.activate {
            val panel = findReaderPanel(toolWindow) ?: return@activate
            // 1. 先把当前这本（= 上一本）的位置存回书架。
            BookshelfService.getInstance().snapshotFromReaderState(project)
            // 2. 再把目标书的位置铺进项目级 state。
            BookshelfService.getInstance().applyToReaderState(ReaderStateService.getInstance(project).state, entry)
            // 3. 打开（restoreState 传 false 也能恢复，因为 isSameBookPath 已经是 true）。
            panel.openBook(path)
        }
    }

    fun openNeatReader(project: Project) {
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID)
        if (toolWindow == null) {
            Messages.showErrorDialog(project, "无法找到 Novel Reader 工具窗口。", "Novel Reader")
            return
        }

        toolWindow.activate {
            selectNeatReaderPanel(toolWindow)
        }
    }

    private fun findReaderPanel(toolWindow: com.intellij.openapi.wm.ToolWindow): ReaderPanel? {
        val contentManager = toolWindow.contentManager
        for (content in contentManager.contents) {
            val component = content.component
            if (component is ReaderPanel) {
                contentManager.setSelectedContent(content)
                return component
            }
        }
        return null
    }

    private fun selectNeatReaderPanel(toolWindow: com.intellij.openapi.wm.ToolWindow) {
        val contentManager = toolWindow.contentManager
        for (content in contentManager.contents) {
            if (content.component is NeatReaderPanel) {
                contentManager.setSelectedContent(content)
                return
            }
        }
    }
}
