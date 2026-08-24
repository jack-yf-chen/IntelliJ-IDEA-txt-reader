package com.chen.reader

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent

class ToggleButtonStyleAction : AnAction() {
    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        ReaderPanel.toggleButtonStyle(project)
    }

    override fun update(event: AnActionEvent) {
        val project = event.project
        val style = project
            ?.let { ReaderStateService.getInstance(it).state.buttonStyle }
            ?: "文字"
        event.presentation.text = if (style == "图标") {
            "切换为文字按钮"
        } else {
            "切换为图标按钮"
        }
    }
}

