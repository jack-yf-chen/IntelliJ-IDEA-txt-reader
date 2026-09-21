package com.chen.reader.ui

import com.chen.reader.model.FootnoteHotSpot
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.event.KeyEvent
import javax.swing.AbstractAction
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTextArea
import javax.swing.KeyStroke
import javax.swing.event.AncestorEvent
import javax.swing.event.AncestorListener

/**
 * 注释弹窗（T64）。
 *
 * 弹窗**数据自足**：`FootnoteHotSpot.body` 已经内嵌了注释正文，所以不需要做任何跳转，
 * 也不需要在章末的注释区里"回到原位"——这正是弹窗方案取代跳转方案的原因。
 *
 * 标题用 `FootnoteHotSpot.label`（书里原本的标记，如 "[1]"）保留书中原貌；
 * 插件自己的编号 `[注N]` 放在标题后面，方便"书里编号 vs 插件编号"对不上时排查。
 *
 * ## T67：弹层不得引起阅读区重排
 *
 * 用 `JBPopup` 而不是往阅读区里塞子组件：弹层是**独立的轻量级窗口**，
 * 不进入 `JBScrollPane` 的视图树，因此不会改变 viewport 尺寸、
 * 不会触发 `ReaderPanel` 的 `componentResized` → 恢复回路 → 写回持久化那条链
 * （0.3.2「阅读记忆被覆盖」的坑）。
 */
internal object FootnotePopup {
    /**
     * 在 `owner` 中央弹出注释。
     *
     * **为什么不用 `RelativePoint` 贴着点击位置弹**：本 SDK（2026.1.3）的插件编译类路径里
     * 取不到 `RelativePoint`，而 `JBPopup` 只暴露了 `show(Component)` /
     * `showInCenterOf(Component)` / `showUnderneathOf(Component)`。居中弹层位置可预期、
     * 也不必自己做坐标系换算，代价是弹层不贴着点击处——对读注释这件事可以接受。
     *
     * @param onClosed 弹层关闭后的回调（用于把焦点还给阅读区），在 EDT 上回调。
     */
    fun show(owner: JComponent, spot: FootnoteHotSpot, onClosed: () -> Unit) {
        val body = JTextArea(spot.body).apply {
            isEditable = false
            lineWrap = true
            wrapStyleWord = true
            caretPosition = 0
            border = JBUI.Borders.empty(4)
            isOpaque = false
        }
        val scrollPane = JBScrollPane(body).apply {
            border = JBUI.Borders.empty()
            preferredSize = Dimension(JBUI.scale(420), JBUI.scale(160))
        }

        val content = JPanel(BorderLayout(0, JBUI.scale(8))).apply {
            border = JBUI.Borders.empty(10)
            isOpaque = true
            add(scrollPane, BorderLayout.CENTER)
        }

        val popup: JBPopup = JBPopupFactory.getInstance()
            .createComponentPopupBuilder(content, body)
            .setTitle(titleFor(spot))
            .setMovable(true)
            .setResizable(true)
            .setRequestFocus(true)
            .setCancelOnClickOutside(true)
            .setCancelOnOtherWindowOpen(true)
            .setMinSize(Dimension(JBUI.scale(260), JBUI.scale(120)))
            .createPopup()

        installEscapeClose(content, popup)
        installClosedCallback(content, onClosed)
        popup.showInCenterOf(owner)
    }

    private fun titleFor(spot: FootnoteHotSpot): String {
        val label = spot.label.trim()
        return if (label.isBlank()) {
            "注释 ${spot.number}"
        } else {
            "$label　（插件编号：注${spot.number}）"
        }
    }

    /** Esc 关闭：JBPopup 默认对部分场景不响应 Esc，显式绑一次更稳。 */
    private fun installEscapeClose(content: JComponent, popup: JBPopup) {
        val closeAction = object : AbstractAction() {
            override fun actionPerformed(event: java.awt.event.ActionEvent?) {
                popup.cancel()
            }
        }
        content.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
            .put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), CLOSE_ACTION_KEY)
        content.actionMap.put(CLOSE_ACTION_KEY, closeAction)
    }

    /**
     * 关闭回调：用 `AncestorListener` 而不是 `JBPopupListener`，
     * 因为前者是稳定的 Swing API，不会受 IntelliJ 各版本弹层包路径迁移影响。
     */
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

    private const val CLOSE_ACTION_KEY = "novel-reader-close-footnote-popup"
}
