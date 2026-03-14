package com.github.waveformplotter

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import javax.swing.SwingUtilities

/**
 * 编辑器右键菜单 Action: "Add to Waveform Plotter"
 *
 * 用户在代码中选中变量名 → 右键 → Add to Waveform Plotter
 * 自动将变量添加到波形绘制面板并激活 tool window
 */
class AddToPlotterAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR)
        val hasSelection = editor?.selectionModel?.hasSelection() == true
        e.presentation.isEnabledAndVisible = hasSelection
    }

    override fun actionPerformed(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val project = e.project ?: return

        // 获取选中文本（变量名）
        val selectedText = editor.selectionModel.selectedText?.trim() ?: return
        if (selectedText.isEmpty() || selectedText.contains('\n')) return

        // 获取或激活 WaveformPanel
        SwingUtilities.invokeLater {
            val panel = WaveformToolWindowFactory.getPanel(project) ?: return@invokeLater
            panel.addVariableByName(selectedText)
        }
    }
}
