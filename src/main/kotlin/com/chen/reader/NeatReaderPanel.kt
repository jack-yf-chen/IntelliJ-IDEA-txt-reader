package com.chen.reader

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.ui.components.JBLabel
import com.intellij.ui.jcef.JBCefApp
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.JButton
import javax.swing.JPanel

class NeatReaderPanel(project: Project) : JPanel(BorderLayout()), Disposable {
    private var browser: JBCefBrowser? = null

    init {
        border = JBUI.Borders.empty(8)
        add(createToolbar(), BorderLayout.NORTH)

        if (JBCefApp.isSupported()) {
            val jcefBrowser = JBCefBrowser(NEAT_READER_WEB_APP_URL)
            browser = jcefBrowser
            Disposer.register(this, jcefBrowser)
            Disposer.register(project, this)
            add(jcefBrowser.component, BorderLayout.CENTER)
        } else {
            add(
                JBLabel("当前 IDE 运行环境不支持内嵌 JCEF 浏览器，可使用右上角按钮在外部浏览器打开 Neat Reader。"),
                BorderLayout.CENTER,
            )
        }
    }

    private fun createToolbar(): JPanel {
        val toolbar = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0))
        val openWebAppButton = JButton("Web端")
        val homeButton = JButton("官网")
        val externalButton = JButton("外部打开")

        openWebAppButton.toolTipText = "在内嵌浏览器中打开 Neat Reader Web App"
        homeButton.toolTipText = "在内嵌浏览器中打开 Neat Reader 官网"
        externalButton.toolTipText = "使用系统浏览器打开 Neat Reader Web App"

        openWebAppButton.addActionListener {
            browser?.loadURL(NEAT_READER_WEB_APP_URL) ?: BrowserUtil.browse(NEAT_READER_WEB_APP_URL)
        }
        homeButton.addActionListener {
            browser?.loadURL(NEAT_READER_HOME_URL) ?: BrowserUtil.browse(NEAT_READER_HOME_URL)
        }
        externalButton.addActionListener {
            BrowserUtil.browse(NEAT_READER_WEB_APP_URL)
        }

        toolbar.add(openWebAppButton)
        toolbar.add(homeButton)
        toolbar.add(externalButton)
        return toolbar
    }

    override fun dispose() {
        browser = null
    }

    companion object {
        const val NEAT_READER_HOME_URL = "https://www.neat-reader.cn/"
        const val NEAT_READER_WEB_APP_URL = "https://www.neat-reader.cn/webapp"
    }
}
