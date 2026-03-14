package com.github.waveformplotter

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.content.ContentFactory
import javax.swing.SwingUtilities

class WaveformToolWindowFactory : ToolWindowFactory, DumbAware {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = WaveformPanel(project)
        val content = ContentFactory.getInstance().createContent(panel, "", false)
        toolWindow.contentManager.addContent(content)
        // 注册到 project 级别，供 Action 等外部访问
        project.putUserData(PANEL_KEY, panel)
    }

    companion object {
        val PANEL_KEY = com.intellij.openapi.util.Key.create<WaveformPanel>("WaveformPlotter.Panel")

        /** 获取当前 project 的 WaveformPanel（如果面板未打开则先激活） */
        fun getPanel(project: Project): WaveformPanel? {
            // 先尝试已缓存的
            project.getUserData(PANEL_KEY)?.let { return it }
            // 面板未创建，先激活 tool window
            val tw = ToolWindowManager.getInstance(project).getToolWindow("Waveform Plotter")
            tw?.activate(null)
            return project.getUserData(PANEL_KEY)
        }
    }
}
