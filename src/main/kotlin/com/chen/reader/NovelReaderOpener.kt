package com.chen.reader

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
