package com.chen.reader

import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.wm.ToolWindowManager
import java.nio.file.Path
import kotlin.io.path.extension

object NovelReaderOpener {
    private const val TOOL_WINDOW_ID = "Novel Reader"

    fun openFromFileChooser(project: Project) {
        val descriptor = FileChooserDescriptorFactory
            .createSingleFileDescriptor("txt")
            .withTitle("选择 TXT 小说")
            .withDescription("打开本地 TXT 文件")

        val file = FileChooser.chooseFile(descriptor, project, null) ?: return
        val path = file.toNioPath()
        if (!path.extension.equals("txt", ignoreCase = true)) {
            Messages.showWarningDialog(project, "第一版仅支持 TXT 文件。", "Novel Reader")
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
            findReaderPanel(toolWindow)?.openBook(path)
        }
    }

    private fun findReaderPanel(toolWindow: com.intellij.openapi.wm.ToolWindow): ReaderPanel? {
        val contentManager = toolWindow.contentManager
        for (content in contentManager.contents) {
            val component = content.component
            if (component is ReaderPanel) {
                return component
            }
        }
        return null
    }
}

