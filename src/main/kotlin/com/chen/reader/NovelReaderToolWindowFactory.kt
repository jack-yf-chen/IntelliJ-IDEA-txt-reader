package com.chen.reader

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory

class NovelReaderToolWindowFactory : ToolWindowFactory {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val contentFactory = ContentFactory.getInstance()
        val readerPanel = ReaderPanel(project)
        val readerContent = contentFactory.createContent(readerPanel, "本地阅读", false)
        toolWindow.contentManager.addContent(readerContent)

        val neatReaderPanel = NeatReaderPanel(project)
        val neatReaderContent = contentFactory.createContent(neatReaderPanel, "Neat Reader", false)
        toolWindow.contentManager.addContent(neatReaderContent)

        readerPanel.restoreLastBook()
    }
}
