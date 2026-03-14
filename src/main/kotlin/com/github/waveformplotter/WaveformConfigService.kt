package com.github.waveformplotter

import com.intellij.openapi.components.*
import com.intellij.openapi.project.Project

/**
 * 项目级配置持久化: 变量列表 + 勾选状态
 */
@Service(Service.Level.PROJECT)
@State(
    name = "WaveformPlotterConfig",
    storages = [Storage("waveformPlotter.xml")]
)
class WaveformConfigService : PersistentStateComponent<WaveformConfigService.State> {

    data class State(
        var variableNames: MutableList<String> = mutableListOf(),
        var trackedVariables: MutableList<String> = mutableListOf(),
        var liveWatchFrequency: Int = 50,
        var telnetPort: Int = 4444,
        var resolvedAddresses: MutableMap<String, String> = mutableMapOf()
    )

    private var myState = State()

    override fun getState(): State = myState

    override fun loadState(state: State) {
        myState = state
    }

    companion object {
        fun getInstance(project: Project): WaveformConfigService {
            return project.getService(WaveformConfigService::class.java)
        }
    }
}
