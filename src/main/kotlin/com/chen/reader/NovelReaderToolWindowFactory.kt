package com.chen.reader

import com.chen.reader.ui.bookshelf.BookshelfPanel
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.content.ContentManagerEvent
import com.intellij.ui.content.ContentManagerListener

class NovelReaderToolWindowFactory : ToolWindowFactory {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val contentFactory = ContentFactory.getInstance()
        val readerPanel = ReaderPanel(project)
        val readerContent = contentFactory.createContent(readerPanel, "本地阅读", false)
        toolWindow.contentManager.addContent(readerContent)

        // Tab 顺序：本地阅读 → 书架 → Neat Reader（书架与阅读入口语义相邻）。
        val bookshelfPanel = BookshelfPanel(project)
        val bookshelfContent = contentFactory.createContent(bookshelfPanel, "书架", false)
        toolWindow.contentManager.addContent(bookshelfContent)

        val neatReaderPanel = NeatReaderPanel(project)
        val neatReaderContent = contentFactory.createContent(neatReaderPanel, "Neat Reader", false)
        toolWindow.contentManager.addContent(neatReaderContent)

        // 每次切到「书架」时刷新：进度可能与上次渲染时不同，封面也可能还没加载。
        toolWindow.contentManager.addContentManagerListener(object : ContentManagerListener {
            override fun selectionChanged(event: ContentManagerEvent) {
                if (event.content.component === bookshelfPanel) {
                    bookshelfPanel.refresh()
                }
            }
        })

        readerPanel.restoreLastBook()
    }
}
