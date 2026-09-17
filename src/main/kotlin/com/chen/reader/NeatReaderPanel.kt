package com.chen.reader

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.ui.components.JBLabel
import com.intellij.ui.jcef.JBCefApp
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.util.ui.JBUI
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.handler.CefLifeSpanHandlerAdapter
import org.cef.handler.CefLoadHandlerAdapter
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingUtilities

class NeatReaderPanel(project: Project) : JPanel(BorderLayout()), Disposable {
    private var browser: JBCefBrowser? = null
    private val zoomLabel = JLabel()
    private var currentZoomLevel = DEFAULT_ZOOM_LEVEL
    private var manualZoom = false

    init {
        border = JBUI.Borders.empty(8)
        add(createToolbar(), BorderLayout.NORTH)

        if (JBCefApp.isSupported()) {
            val jcefBrowser = JBCefBrowser(NEAT_READER_WEB_APP_URL)
            browser = jcefBrowser
            Disposer.register(this, jcefBrowser)
            Disposer.register(project, this)
            installBrowserHandlers(jcefBrowser)
            add(jcefBrowser.component, BorderLayout.CENTER)
            SwingUtilities.invokeLater {
                applyAutoZoom()
            }
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
        val zoomOutButton = JButton("缩小")
        val zoomResetButton = JButton("自适应")
        val zoomInButton = JButton("放大")

        openWebAppButton.toolTipText = "在内嵌浏览器中打开 Neat Reader Web App"
        homeButton.toolTipText = "在内嵌浏览器中打开 Neat Reader 官网"
        externalButton.toolTipText = "使用系统浏览器打开 Neat Reader Web App"
        zoomOutButton.toolTipText = "缩小内嵌网页"
        zoomResetButton.toolTipText = "恢复按工具窗口宽度自动缩放"
        zoomInButton.toolTipText = "放大内嵌网页"

        openWebAppButton.addActionListener {
            loadInEmbeddedBrowser(NEAT_READER_WEB_APP_URL)
        }
        homeButton.addActionListener {
            loadInEmbeddedBrowser(NEAT_READER_HOME_URL)
        }
        externalButton.addActionListener {
            BrowserUtil.browse(NEAT_READER_WEB_APP_URL)
        }
        zoomOutButton.addActionListener {
            manualZoom = true
            applyZoom((currentZoomLevel - ZOOM_STEP).coerceIn(MIN_ZOOM_LEVEL, MAX_ZOOM_LEVEL))
        }
        zoomResetButton.addActionListener {
            manualZoom = false
            applyAutoZoom()
        }
        zoomInButton.addActionListener {
            manualZoom = true
            applyZoom((currentZoomLevel + ZOOM_STEP).coerceIn(MIN_ZOOM_LEVEL, MAX_ZOOM_LEVEL))
        }

        toolbar.add(openWebAppButton)
        toolbar.add(homeButton)
        toolbar.add(externalButton)
        toolbar.add(zoomOutButton)
        toolbar.add(zoomResetButton)
        toolbar.add(zoomInButton)
        toolbar.add(zoomLabel)
        return toolbar
    }

    private fun installBrowserHandlers(jcefBrowser: JBCefBrowser) {
        jcefBrowser.setOpenLinksInExternalBrowser(false)
        jcefBrowser.jbCefClient.addLifeSpanHandler(
            object : CefLifeSpanHandlerAdapter() {
                override fun onBeforePopup(
                    browser: CefBrowser,
                    frame: CefFrame,
                    targetUrl: String,
                    targetFrameName: String,
                ): Boolean {
                    if (targetUrl.isNotBlank() && targetUrl != "about:blank") {
                        SwingUtilities.invokeLater {
                            loadInEmbeddedBrowser(targetUrl)
                        }
                    }
                    return true
                }
            },
            jcefBrowser.cefBrowser,
        )
        jcefBrowser.jbCefClient.addLoadHandler(
            object : CefLoadHandlerAdapter() {
                override fun onLoadEnd(browser: CefBrowser, frame: CefFrame, httpStatusCode: Int) {
                    if (!frame.isMain) {
                        return
                    }
                    injectSingleWindowScript(jcefBrowser)
                    if (!manualZoom) {
                        SwingUtilities.invokeLater {
                            applyAutoZoom()
                        }
                    }
                }
            },
            jcefBrowser.cefBrowser,
        )
        jcefBrowser.component.addComponentListener(object : ComponentAdapter() {
            override fun componentResized(event: ComponentEvent) {
                applyAutoZoom()
            }
        })
        injectSingleWindowScript(jcefBrowser)
    }

    private fun loadInEmbeddedBrowser(url: String) {
        val jcefBrowser = browser
        if (jcefBrowser == null) {
            BrowserUtil.browse(url)
            return
        }

        jcefBrowser.loadURL(url)
        injectSingleWindowScript(jcefBrowser)
        if (!manualZoom) {
            SwingUtilities.invokeLater {
                applyAutoZoom()
            }
        }
    }

    private fun applyAutoZoom() {
        if (manualZoom || browser == null) {
            return
        }

        val width = browser?.component?.width?.takeIf { it > 0 } ?: width
        val zoomLevel = when {
            width <= 560 -> -2.6
            width <= 720 -> -2.2
            width <= 900 -> -1.8
            width <= 1100 -> -1.4
            else -> DEFAULT_ZOOM_LEVEL
        }
        applyZoom(zoomLevel)
    }

    private fun applyZoom(zoomLevel: Double) {
        currentZoomLevel = zoomLevel
        browser?.zoomLevel = zoomLevel
        zoomLabel.text = "缩放 ${zoomPercent(zoomLevel)}%"
    }

    private fun injectSingleWindowScript(jcefBrowser: JBCefBrowser) {
        SwingUtilities.invokeLater {
            jcefBrowser.runJavaScript(
                """
                (function () {
                  if (window.__novelReaderSingleWindowPatched) return;
                  window.__novelReaderSingleWindowPatched = true;
                  const originalOpen = window.open;
                  window.open = function (url, target, features) {
                    if (url && typeof url === 'string' && url !== 'about:blank') {
                      window.location.href = url;
                    }
                    return window;
                  };
                  document.addEventListener('click', function (event) {
                    const link = event.target && event.target.closest ? event.target.closest('a[target="_blank"]') : null;
                    if (!link || !link.href) return;
                    event.preventDefault();
                    window.location.href = link.href;
                  }, true);
                })();
                """.trimIndent(),
                jcefBrowser.cefBrowser.url ?: NEAT_READER_WEB_APP_URL,
                0,
            )
        }
    }

    private fun zoomPercent(zoomLevel: Double): Int {
        return (Math.pow(ZOOM_BASE, zoomLevel) * 100).toInt()
    }

    override fun dispose() {
        browser = null
    }

    companion object {
        const val NEAT_READER_HOME_URL = "https://www.neat-reader.cn/"
        const val NEAT_READER_WEB_APP_URL = "https://www.neat-reader.cn/webapp"
        private const val DEFAULT_ZOOM_LEVEL = -1.2
        private const val MIN_ZOOM_LEVEL = -3.0
        private const val MAX_ZOOM_LEVEL = 1.0
        private const val ZOOM_STEP = 0.4
        private const val ZOOM_BASE = 1.2
    }
}
