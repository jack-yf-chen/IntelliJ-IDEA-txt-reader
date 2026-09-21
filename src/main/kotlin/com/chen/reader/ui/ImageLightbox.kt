package com.chen.reader.ui

import com.chen.reader.book.BookResources
import com.chen.reader.model.ImageHotSpot
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Point
import java.awt.RenderingHints
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent
import java.awt.image.BufferedImage
import javax.swing.AbstractAction
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.KeyStroke
import javax.swing.SwingUtilities
import javax.swing.event.AncestorEvent
import javax.swing.event.AncestorListener

/**
 * 图片灯箱（T66）。
 *
 * 交互：滚轮缩放、按住拖拽平移、"1:1" 原始尺寸、"适配" 回到适应窗口、Esc / 关闭按钮退出。
 *
 * ## 两级解码（都在后台线程，回调只 repaint）
 *
 * 1. **首屏**：优先复用阅读区已经解码好的小图（`preview`），立刻出画面；
 * 2. **高清**：后台再按原图尺寸（`targetWidth = 0`）解一次，解码完成后**只替换位图并 repaint**，
 *    绝不触发阅读区重排。
 *
 * 这样首屏不卡 EDT，也不违反设计文档 §8「解码必须在后台」的纪律。
 */
internal object ImageLightbox {
    /** 首屏预览宽：够看清内容，又不至于等太久 */
    private const val PREVIEW_WIDTH = 1200

    /** 原图尺寸解码：`BookResources.rasterize` 约定 `targetWidth <= 0` 表示按原始尺寸返回 */
    private const val ORIGINAL_WIDTH = 0

    fun show(
        owner: JComponent,
        spot: ImageHotSpot,
        resources: BookResources,
        preview: BufferedImage?,
        onClosed: () -> Unit,
    ) {
        val panel = LightboxPanel(spot, resources, preview)
        val content = JPanel(BorderLayout(0, JBUI.scale(6))).apply {
            border = JBUI.Borders.empty(8)
            add(panel, BorderLayout.CENTER)
            add(panel.createToolbar(), BorderLayout.SOUTH)
        }

        val popup = JBPopupFactory.getInstance()
            .createComponentPopupBuilder(content, panel)
            .setTitle(spot.alt.takeIf { it.isNotBlank() } ?: "图片")
            .setMovable(true)
            .setResizable(true)
            .setRequestFocus(true)
            .setCancelOnClickOutside(true)
            .setCancelOnOtherWindowOpen(true)
            .setMinSize(Dimension(JBUI.scale(360), JBUI.scale(260)))
            .createPopup()

        panel.bindPopup(popup)
        panel.installEscapeClose(content, popup)
        installClosedCallback(content) {
            panel.dispose()
            onClosed()
        }
        popup.showInCenterOf(owner)
        panel.loadHighResolution()
    }

    private fun installClosedCallback(content: JComponent, onClosed: () -> Unit) {
        var notified = false
        content.addAncestorListener(object : AncestorListener {
            override fun ancestorAdded(event: AncestorEvent?) = Unit

            override fun ancestorRemoved(event: AncestorEvent?) {
                if (notified) {
                    return
                }
                notified = true
                onClosed()
            }

            override fun ancestorMoved(event: AncestorEvent?) = Unit
        })
    }

    /**
     * 灯箱画布。
     *
     * 缩放/平移是**纯绘制变换**：只改 `scale` 与 `origin` 两个字段后 repaint，
     * 不碰 `preferredSize`，因此不会引起任何布局变化。
     */
    private class LightboxPanel(
        private val spot: ImageHotSpot,
        private val resources: BookResources,
        preview: BufferedImage?,
    ) : JPanel() {
        private val lock = Any()

        @Volatile
        private var image: BufferedImage? = preview

        private var scale = 1.0
        private var originX = 0
        private var originY = 0
        private var dragStart: Point? = null
        private var dragOriginX = 0
        private var dragOriginY = 0
        private var popup: JBPopup? = null

        @Volatile
        private var disposed = false

        init {
            background = Color(0x2B2B2B)
            foreground = Color(0xBBBBBB)
            isOpaque = true
            preferredSize = Dimension(JBUI.scale(760), JBUI.scale(520))
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            if (preview == null) {
                requestDecode(PREVIEW_WIDTH, replace = false)
            }
            installInteractions()
        }

        fun bindPopup(popup: JBPopup) {
            this.popup = popup
        }

        fun dispose() {
            disposed = true
        }

        /** 后台请求高清原图；完成后只替换位图 + repaint。 */
        fun loadHighResolution() {
            requestDecode(ORIGINAL_WIDTH, replace = true)
        }

        private fun requestDecode(targetWidth: Int, replace: Boolean) {
            val application = ApplicationManager.getApplication()
            application.executeOnPooledThread {
                val decoded = try {
                    resources.rasterize(spot.resourceId, targetWidth)
                } catch (_: Throwable) {
                    null
                }
                // 只更新位图，**不改 preferredSize / 不 revalidate**（§8 + T67）。
                val shouldApply = when {
                    decoded == null -> false
                    !replace -> synchronized(lock) { image == null }
                    else -> true
                }
                if (!shouldApply) {
                    return@executeOnPooledThread
                }
                synchronized(lock) {
                    if (disposed) {
                        return@executeOnPooledThread
                    }
                    image = decoded
                }
                application.invokeLater { fitToPanel(); repaint() }
            }
        }

        private fun installInteractions() {
            val adapter = object : MouseAdapter() {
                override fun mousePressed(event: MouseEvent) {
                    if (!SwingUtilities.isLeftMouseButton(event)) {
                        return
                    }
                    dragStart = event.point
                    dragOriginX = originX
                    dragOriginY = originY
                }

                override fun mouseReleased(event: MouseEvent) {
                    dragStart = null
                }

                override fun mouseDragged(event: MouseEvent) {
                    val start = dragStart ?: return
                    originX = dragOriginX + (event.x - start.x)
                    originY = dragOriginY + (event.y - start.y)
                    repaint()
                }

                override fun mouseWheelMoved(event: MouseWheelEvent) {
                    val current = synchronized(lock) { image } ?: return
                    val factor = if (event.wheelRotation < 0) ZOOM_IN_FACTOR else 1.0 / ZOOM_IN_FACTOR
                    val nextScale = (scale * factor).coerceIn(MIN_SCALE, MAX_SCALE)
                    if (nextScale == scale) {
                        return
                    }
                    // 以鼠标位置为锚点缩放，体验接近看图软件。
                    val anchorX = event.x
                    val anchorY = event.y
                    val relativeX = (anchorX - originX) / scale
                    val relativeY = (anchorY - originY) / scale
                    scale = nextScale
                    originX = (anchorX - relativeX * scale).toInt()
                    originY = (anchorY - relativeY * scale).toInt()
                    repaint()
                    val unused = current
                }
            }
            addMouseListener(adapter)
            addMouseMotionListener(adapter)
            addMouseWheelListener(adapter)
        }

        fun createToolbar(): JComponent {
            val toolbar = JPanel(FlowLayout(FlowLayout.CENTER, JBUI.scale(8), 0))
            toolbar.isOpaque = false

            val zoomIn = JButton("放大")
            val zoomOut = JButton("缩小")
            val actual = JButton("1:1")
            val fit = JButton("适配")
            val close = JButton("关闭")

            zoomIn.addActionListener { zoomBy(ZOOM_IN_FACTOR) }
            zoomOut.addActionListener { zoomBy(1.0 / ZOOM_IN_FACTOR) }
            actual.addActionListener { scale = 1.0; centerImage(); repaint() }
            fit.addActionListener { fitToPanel(); repaint() }
            close.addActionListener { popup?.cancel() }

            val sizeLabel = JLabel(spot.resourceId)
            sizeLabel.foreground = Color(0x888888)
            sizeLabel.border = BorderFactory.createEmptyBorder(0, JBUI.scale(8), 0, 0)

            toolbar.add(zoomIn)
            toolbar.add(zoomOut)
            toolbar.add(actual)
            toolbar.add(fit)
            toolbar.add(close)
            toolbar.add(sizeLabel)
            return toolbar
        }

        private fun zoomBy(factor: Double) {
            val nextScale = (scale * factor).coerceIn(MIN_SCALE, MAX_SCALE)
            if (nextScale == scale) {
                return
            }
            val centerX = width / 2.0
            val centerY = height / 2.0
            val relativeX = (centerX - originX) / scale
            val relativeY = (centerY - originY) / scale
            scale = nextScale
            originX = (centerX - relativeX * scale).toInt()
            originY = (centerY - relativeY * scale).toInt()
            repaint()
        }

        /** 缩放到适应面板并居中 */
        private fun fitToPanel() {
            val current = synchronized(lock) { image } ?: return
            val panelWidth = width.takeIf { it > 0 } ?: preferredSize.width
            val panelHeight = height.takeIf { it > 0 } ?: preferredSize.height
            scale = minOf(
                panelWidth.toDouble() / current.width.toDouble(),
                panelHeight.toDouble() / current.height.toDouble(),
            ).coerceIn(MIN_SCALE, MAX_SCALE)
            centerImage()
        }

        private fun centerImage() {
            val current = synchronized(lock) { image } ?: return
            val panelWidth = width.takeIf { it > 0 } ?: preferredSize.width
            val panelHeight = height.takeIf { it > 0 } ?: preferredSize.height
            originX = ((panelWidth - current.width * scale) / 2).toInt()
            originY = ((panelHeight - current.height * scale) / 2).toInt()
        }

        override fun paintComponent(graphics: Graphics) {
            super.paintComponent(graphics)
            val current = synchronized(lock) { image }
            val g = graphics.create() as Graphics2D
            try {
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
                if (current == null) {
                    g.color = foreground
                    val label = "正在解码图片…"
                    val metrics = g.fontMetrics
                    g.drawString(label, (width - metrics.stringWidth(label)) / 2, height / 2)
                    return
                }
                val drawWidth = (current.width * scale).toInt().coerceAtLeast(1)
                val drawHeight = (current.height * scale).toInt().coerceAtLeast(1)
                g.drawImage(current, originX, originY, originX + drawWidth, originY + drawHeight, null)
                // 缩放比例提示
                g.color = Color(0x888888)
                g.drawString("${(scale * 100).toInt()}%", JBUI.scale(8), height - JBUI.scale(8))
            } finally {
                g.dispose()
            }
        }

        /** Esc 关闭：绑在 content 上，用 `WHEN_ANCESTOR_OF_FOCUSED_COMPONENT` 覆盖整个弹层。 */
        fun installEscapeClose(content: JComponent, popup: JBPopup) {
            val closeAction = object : AbstractAction() {
                override fun actionPerformed(event: java.awt.event.ActionEvent?) {
                    popup.cancel()
                }
            }
            content.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
                .put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), CLOSE_ACTION_KEY)
            content.actionMap.put(CLOSE_ACTION_KEY, closeAction)
            // 兜底：直接按在画布上时也要能 Esc。
            getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), CLOSE_ACTION_KEY)
            actionMap.put(CLOSE_ACTION_KEY, closeAction)
        }
    }

    private const val ZOOM_IN_FACTOR = 1.15
    private const val MIN_SCALE = 0.05
    private const val MAX_SCALE = 20.0
    private const val CLOSE_ACTION_KEY = "novel-reader-close-lightbox"
}
