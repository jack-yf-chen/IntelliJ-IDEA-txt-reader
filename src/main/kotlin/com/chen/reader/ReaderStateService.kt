package com.chen.reader

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project

@State(
    name = "NovelReaderState",
    storages = [Storage("novelReader.xml")],
)
@Service(Service.Level.PROJECT)
class ReaderStateService : PersistentStateComponent<ReaderState> {
    private var state = ReaderState()

    override fun getState(): ReaderState = state

    override fun loadState(state: ReaderState) {
        this.state = state
    }

    companion object {
        fun getInstance(project: Project): ReaderStateService = project.service()
    }
}

class ReaderState {
    var filePath: String? = null
    var charsetName: String? = null
    var chapterIndex: Int = 0
    var scrollValue: Int = 0
    var globalOffset: Int = 0
    var anchorText: String = ""
    var progressInChapterPermille: Int = 0
    var fontSize: Int = 18
    var fontFamily: String? = null
    var boldText: Boolean = false
    var textColorName: String = "跟随主题"
    var lineSpacingPercent: Int = 20
    var themeName: String = "跟随 IDE"
    var widthMode: String = "舒适"
    var hideCursor: Boolean = false
    var buttonStyle: String = "文字"
}
