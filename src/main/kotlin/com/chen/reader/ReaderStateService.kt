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
    var fontSize: Int = 18
}
