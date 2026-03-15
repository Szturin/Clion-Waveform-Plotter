package com.github.waveformplotter

import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.ui.JBColor
import java.awt.*
import java.io.File
import javax.swing.*
import javax.swing.event.ChangeListener

/**
 * 主面板: 被动监听 + Live Watch 实时监控
 *
 * ┌───────────────────────────────────────────────────────────────┐
 * │ [● Record] [■ Stop] [⟳ Clear] [Export CSV] │ [▶ Live] [50▼Hz] │ ← 控制栏
 * ├───────────────────────────────────────────────────────────────┤
 * │ Variable: [expression         ] [+ Add]                       │ ← 变量输入
 * │ ☑ speed_pid.Output   ☑ actual_rads   ☐ output               │ ← 勾选框
 * ├───────────────────────────────────────────────────────────────┤
 * │              ~~~~ 波形 ~~~~                                    │ ← PlotCanvas
 * │ #120  Y: [-10.0, 50.0]  |  Live: monitor@50Hz (1234 samples) │ ← 状态栏
 * └───────────────────────────────────────────────────────────────┘
 */
class WaveformPanel(private val project: Project) : JPanel(BorderLayout()), Disposable {

    private val dataBuffer = DataBuffer()
    private val plotCanvas = PlotCanvas(dataBuffer)
    private val collector = WatchVariableCollector(dataBuffer) {
        SwingUtilities.invokeLater { updateChannelValues(); updateStatusBar() }
    }
    private val liveWatchService = LiveWatchService(dataBuffer) {
        SwingUtilities.invokeLater { updateChannelValues(); updateStatusBar() }
    }
    private val sessionListener = DebugSessionListener(project, collector) { active ->
        SwingUtilities.invokeLater { updateSessionState(active) }
    }

    // 控制按钮
    private val recordBtn = JButton("\u25CF Record")
    private val stopBtn = JButton("\u25A0 Stop")
    private val clearBtn = JButton("\u27F3 Clear")
    private val exportBtn = JButton("Export CSV")

    // Live Watch 控件
    private val liveWatchBtn = JButton("\u25B6 Live")
    private val freqSpinner = JSpinner(SpinnerNumberModel(50, 1, 2000, 50)).apply {
        preferredSize = Dimension(60, preferredSize.height)
        toolTipText = "Sampling frequency (Hz)"
    }
    private val freqLabel = JLabel("Hz")
    private val portSpinner = JSpinner(SpinnerNumberModel(4444, 1, 65535, 1)).apply {
        preferredSize = Dimension(70, preferredSize.height)
        toolTipText = "OpenOCD Telnet port"
    }
    private val portLabel = JLabel("Port:")

    // 时域/频域切换按钮
    private val timeModeBtn = JButton("Time").apply {
        toolTipText = "Time domain waveform"
        margin = Insets(2, 8, 2, 8)
    }
    private val fftModeBtn = JButton("FFT").apply {
        toolTipText = "FFT frequency spectrum"
        margin = Insets(2, 8, 2, 8)
    }

    // 设置按钮
    private val settingsBtn = JButton("\u2699").apply {
        toolTipText = "Display Settings"
        font = Font("SansSerif", Font.PLAIN, 16)
        margin = Insets(2, 6, 2, 6)
    }

    // 变量输入
    private val varField = JTextField(18)
    private val addBtn = JButton("+ Add")

    // 通道勾选框面板
    private val channelCheckPanel = JPanel(FlowLayout(FlowLayout.LEFT, 6, 2))

    // 状态栏
    private val statusLabel = JLabel(" ")
    private val sessionStatusLabel = JLabel("No debug session")
    private val liveStatusLabel = JLabel("")

    // 勾选框列表
    private val channelCheckboxes = mutableMapOf<String, JCheckBox>()
    // 变量实时值标签
    private val channelValueLabels = mutableMapOf<String, JLabel>()

    private val log = Logger.getInstance(WaveformPanel::class.java)
    private var hasActiveSession = false

    // Live Watch 按钮颜色
    private val colorIdle = JBColor(Color(0x616161), Color(0xBDBDBD))
    private val colorRunning = JBColor(Color(0x2E7D32), Color(0x81C784))
    private val colorError = JBColor(Color(0xB71C1C), Color(0xEF9A9A))

    init {
        buildUI()
        restoreConfig()
        setupLiveWatchCallbacks()
        sessionListener.init()
        updateButtonStates()

        plotCanvas.onStatusUpdate = { sampleCount, yRange ->
            SwingUtilities.invokeLater {
                val base = "  #${collector.sampleCount} samples | $sampleCount pts | Y: $yRange"
                statusLabel.text = base
                if (liveWatchService.isRunning.get()) {
                    updateLiveStatus()
                }
            }
        }

        Disposer.register(project, this)
    }

    private fun setupLiveWatchCallbacks() {
        // Debug 会话首次暂停：自动获取 ELF 路径并加载符号（静默/隐藏）
        sessionListener.onSessionStarted = { session ->
            autoLoadElfSymbols(session)
        }

        // MCU 暂停时：如果 Live Watch 开启且有未解析的变量，自动解析
        sessionListener.onSessionPaused = { session ->
            if (liveWatchService.isRunning.get()) {
                val unresolved = collector.trackedVariables.filter { name ->
                    liveWatchService.getResolvedEntries()[name] == null
                }
                if (unresolved.isNotEmpty()) {
                    liveWatchService.resolveVariables(session, unresolved) { count ->
                        log.info("Auto-resolved $count variables during pause")
                    }
                }
            }
        }

        // MCU 恢复时：自动重启采集（如果 Live Watch 按钮已激活）
        sessionListener.onSessionResumed = { _ ->
            // 采集在 timer 中自动进行，无需额外操作
        }
    }

    private fun buildUI() {
        // --- 控制栏 ---
        val controlBar = JPanel(FlowLayout(FlowLayout.LEFT, 4, 2))
        recordBtn.addActionListener { startRecording() }
        stopBtn.addActionListener { stopRecording() }
        clearBtn.addActionListener { clearAll() }
        exportBtn.addActionListener { exportCsv() }

        controlBar.add(recordBtn)
        controlBar.add(stopBtn)
        controlBar.add(clearBtn)
        controlBar.add(Box.createHorizontalStrut(8))
        controlBar.add(exportBtn)

        // --- 时域/频域切换 ---
        controlBar.add(Box.createHorizontalStrut(8))
        controlBar.add(JSeparator(SwingConstants.VERTICAL).apply {
            preferredSize = Dimension(2, 20)
        })
        controlBar.add(Box.createHorizontalStrut(8))
        timeModeBtn.addActionListener { setDisplayMode(DisplayMode.TIME) }
        fftModeBtn.addActionListener { setDisplayMode(DisplayMode.FFT) }
        controlBar.add(timeModeBtn)
        controlBar.add(fftModeBtn)
        updateModeButtons()

        // --- Live Watch 控件 ---
        controlBar.add(Box.createHorizontalStrut(16))
        controlBar.add(JSeparator(SwingConstants.VERTICAL).apply {
            preferredSize = Dimension(2, 20)
        })
        controlBar.add(Box.createHorizontalStrut(8))

        liveWatchBtn.foreground = colorIdle
        liveWatchBtn.toolTipText = "Start/Stop Live Watch (non-invasive memory monitoring via OpenOCD Telnet)"
        liveWatchBtn.addActionListener { toggleLiveWatch() }
        controlBar.add(liveWatchBtn)
        controlBar.add(freqSpinner)
        controlBar.add(freqLabel)
        controlBar.add(Box.createHorizontalStrut(8))
        controlBar.add(portLabel)
        controlBar.add(portSpinner)

        controlBar.add(Box.createHorizontalGlue())
        controlBar.add(sessionStatusLabel)
        controlBar.add(Box.createHorizontalStrut(8))
        settingsBtn.addActionListener { showSettingsDialog() }
        controlBar.add(settingsBtn)

        // --- 变量输入栏 ---
        val inputBar = JPanel(FlowLayout(FlowLayout.LEFT, 4, 2))
        inputBar.add(JLabel("Variable:"))
        inputBar.add(varField)
        addBtn.addActionListener { addVariable() }
        varField.addActionListener { addVariable() }
        inputBar.add(addBtn)

        // --- 通道勾选框（可滚动） ---
        val checkScroll = JScrollPane(channelCheckPanel).apply {
            preferredSize = Dimension(0, 34)
            minimumSize = Dimension(0, 28)
            border = BorderFactory.createEmptyBorder()
            horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED
            verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_NEVER
        }

        // --- 顶部面板 ---
        val topPanel = JPanel()
        topPanel.layout = BoxLayout(topPanel, BoxLayout.Y_AXIS)
        topPanel.add(controlBar)
        topPanel.add(inputBar)
        topPanel.add(checkScroll)

        // --- 状态栏 ---
        statusLabel.font = Font("Monospaced", Font.PLAIN, 12)
        statusLabel.foreground = JBColor(Color(100, 100, 100), Color(160, 160, 160))
        liveStatusLabel.font = Font("Monospaced", Font.PLAIN, 12)
        val statusBar = JPanel(BorderLayout())
        statusBar.add(statusLabel, BorderLayout.WEST)
        statusBar.add(liveStatusLabel, BorderLayout.EAST)

        // --- 组装 ---
        add(topPanel, BorderLayout.NORTH)
        add(plotCanvas, BorderLayout.CENTER)
        add(statusBar, BorderLayout.SOUTH)
    }

    private fun addVariable() {
        val name = varField.text.trim()
        if (name.isEmpty()) return
        if (channelCheckboxes.containsKey(name)) {
            varField.selectAll()
            return
        }

        addChannelCheckbox(name, checked = true)
        varField.text = ""
        saveConfig()
    }

    /** 外部调用：添加变量到绘图器（从右键菜单等） */
    fun addVariableByName(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty() || channelCheckboxes.containsKey(trimmed)) return
        addChannelCheckbox(trimmed, checked = true)
        saveConfig()
    }

    private fun addChannelCheckbox(name: String, checked: Boolean) {
        if (channelCheckboxes.containsKey(name)) return

        // 确保通道存在于 DataBuffer，颜色由 DataBuffer 统一分配
        if (dataBuffer.getChannels().none { it.name == name }) {
            dataBuffer.addChannel(name)
        }
        val channel = dataBuffer.getChannels().find { it.name == name } ?: return
        val color = channel.color

        val cb = JCheckBox(name).apply {
            isSelected = checked
            foreground = color
            font = Font("Monospaced", Font.BOLD, 12)
            toolTipText = "Right-click to remove"
        }

        // 实时数值标签
        val valueLabel = JLabel("").apply {
            foreground = color
            font = Font("Monospaced", Font.PLAIN, 11)
        }

        cb.addActionListener {
            if (cb.isSelected) {
                collector.trackedVariables.add(name)
            } else {
                collector.trackedVariables.remove(name)
            }
            saveConfig()
        }
        // 右键删除
        val rightClickListener = object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(e: java.awt.event.MouseEvent) {
                if (e.button == java.awt.event.MouseEvent.BUTTON3) {
                    removeVariable(name)
                }
            }
        }
        cb.addMouseListener(rightClickListener)
        valueLabel.addMouseListener(rightClickListener)

        if (checked) {
            collector.trackedVariables.add(name)
        }

        channelCheckboxes[name] = cb
        channelValueLabels[name] = valueLabel

        // 组合: [checkbox] [value_label] | 下一个变量...
        val itemPanel = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
            isOpaque = false
            add(cb)
            add(valueLabel)
        }
        channelCheckPanel.add(itemPanel)
        channelCheckPanel.revalidate()
        channelCheckPanel.repaint()
    }

    private fun removeVariable(name: String) {
        collector.trackedVariables.remove(name)
        dataBuffer.removeChannel(name)
        channelCheckboxes.remove(name)
        channelValueLabels.remove(name)
        // 移除包含该变量的 itemPanel
        val components = channelCheckPanel.components.toList()
        for (comp in components) {
            if (comp is JPanel) {
                val hasTarget = comp.components.any { it is JCheckBox && (it as JCheckBox).text == name }
                if (hasTarget) {
                    channelCheckPanel.remove(comp)
                    break
                }
            }
        }
        channelCheckPanel.revalidate()
        channelCheckPanel.repaint()
        saveConfig()
    }

    private fun setDisplayMode(mode: DisplayMode) {
        plotCanvas.displayMode = mode
        plotCanvas.sampleRateHz = (freqSpinner.value as Int).toDouble()
        plotCanvas.resetView()
        plotCanvas.repaint()
        updateModeButtons()
    }

    private fun updateModeButtons() {
        val isTime = plotCanvas.displayMode == DisplayMode.TIME
        timeModeBtn.isEnabled = !isTime
        fftModeBtn.isEnabled = isTime
        // 高亮当前模式
        timeModeBtn.font = Font("SansSerif", if (isTime) Font.BOLD else Font.PLAIN, 12)
        fftModeBtn.font = Font("SansSerif", if (!isTime) Font.BOLD else Font.PLAIN, 12)
    }

    private fun toggleLiveWatch() {
        if (liveWatchService.isRunning.get()) {
            stopLiveWatch()
        } else {
            startLiveWatch()
        }
    }

    private fun startLiveWatch() {
        val session = sessionListener.getCurrentSession()
        if (session == null) {
            liveWatchBtn.foreground = colorError
            liveStatusLabel.text = "No debug session  "
            liveStatusLabel.foreground = colorError
            return
        }

        val trackedVars = collector.trackedVariables.toList()
        if (trackedVars.isEmpty()) {
            liveStatusLabel.text = "No variables selected  "
            liveStatusLabel.foreground = colorError
            return
        }

        val freq = freqSpinner.value as Int
        val port = portSpinner.value as Int

        liveWatchBtn.text = "\u23F3 Resolving..."
        liveWatchBtn.isEnabled = false
        liveStatusLabel.text = "Resolving addresses...  "
        liveStatusLabel.foreground = colorIdle

        // 1. 先尝试 ELF 符号表解析（无需暂停 MCU）
        val elfResolved = if (liveWatchService.elfResolver.isLoaded()) {
            val elfPath = liveWatchService.elfResolver.lastElfPath ?: ""
            liveWatchService.resolveFromElf(elfPath, trackedVars)
        } else 0

        val unresolvedVars = trackedVars.filter { name ->
            liveWatchService.getResolvedEntries()[name] == null
        }

        if (unresolvedVars.isEmpty()) {
            // 全部通过 ELF 解析成功 — 无需暂停 MCU
            log.info("All ${trackedVars.size} variables resolved via ELF (no pause needed)")
            launchLiveWatch(port, freq)
        } else {
            // 有未解析变量 — 回退 GDB（需 MCU 暂停）
            log.info("$elfResolved resolved via ELF, ${unresolvedVars.size} need GDB fallback")
            liveWatchService.resolveVariables(session, unresolvedVars) { gdbResolved ->
                SwingUtilities.invokeLater {
                    val total = elfResolved + gdbResolved
                    if (total == 0) {
                        liveWatchBtn.text = "\u25B6 Live"
                        liveWatchBtn.isEnabled = true
                        liveWatchBtn.foreground = colorError
                        liveStatusLabel.text = "Failed to resolve variables (MCU must be paused first)  "
                        liveStatusLabel.foreground = colorError
                        return@invokeLater
                    }
                    launchLiveWatch(port, freq)
                }
            }
        }
    }

    private fun launchLiveWatch(port: Int, freq: Int) {
        liveWatchService.startLiveWatch(port, freq)

        if (liveWatchService.lastError != null) {
            liveWatchBtn.text = "\u25B6 Live"
            liveWatchBtn.isEnabled = true
            liveWatchBtn.foreground = colorError
            liveStatusLabel.text = liveWatchService.lastError + "  "
            liveStatusLabel.foreground = colorError
            return
        }

        liveWatchBtn.text = "\u25A0 Live"
        liveWatchBtn.isEnabled = true
        liveWatchBtn.foreground = colorRunning
        freqSpinner.isEnabled = false
        portSpinner.isEnabled = false
        updateLiveStatus()
    }

    private fun stopLiveWatch() {
        liveWatchService.stopLiveWatch()
        liveWatchBtn.text = "\u25B6 Live"
        liveWatchBtn.foreground = colorIdle
        freqSpinner.isEnabled = true
        portSpinner.isEnabled = true
        liveStatusLabel.text = ""
    }

    private fun updateLiveStatus() {
        if (!liveWatchService.isRunning.get()) return

        val error = liveWatchService.lastError
        if (error != null) {
            liveStatusLabel.text = "Live: error  "
            liveStatusLabel.foreground = colorError
            liveStatusLabel.toolTipText = error
        } else {
            val freq = freqSpinner.value
            val port = portSpinner.value
            liveStatusLabel.text = "Live: telnet:$port@${freq}Hz (${liveWatchService.sampleCount})  "
            liveStatusLabel.foreground = colorRunning
            liveStatusLabel.toolTipText = null
        }
    }

    private fun startRecording() {
        collector.recording = true
        updateButtonStates()
    }

    private fun stopRecording() {
        collector.recording = false
        updateButtonStates()
    }

    private fun clearAll() {
        dataBuffer.clearAll()
        collector.resetSampleCount()
        liveWatchService.clearResolvedEntries()
        plotCanvas.resetView()
        plotCanvas.repaint()
        updateStatusBar()
    }

    private fun exportCsv() {
        val channels = dataBuffer.getChannels()
        if (channels.isEmpty()) return

        val descriptor = FileSaverDescriptor("Export Waveform Data", "Save as CSV file", "csv")
        val dialog = FileChooserFactory.getInstance().createSaveFileDialog(descriptor, project)
        val wrapper = dialog.save("waveform_data.csv") ?: return
        val file = wrapper.file

        try {
            file.bufferedWriter().use { writer ->
                // 表头（含逗号的名称加双引号）
                writer.write("index")
                for (ch in channels) {
                    writer.write(",")
                    writer.write(csvField(ch.name))
                }
                writer.newLine()

                // 数据行
                val maxSize = channels.maxOf { it.size }
                for (i in 0 until maxSize) {
                    writer.write("$i")
                    for (ch in channels) {
                        writer.write(",")
                        if (i < ch.size) {
                            val v = ch.get(i)
                            if (!v.isNaN()) writer.write("$v")
                        }
                    }
                    writer.newLine()
                }
            }
        } catch (e: Exception) {
            JOptionPane.showMessageDialog(this, "Export failed: ${e.message}", "Error", JOptionPane.ERROR_MESSAGE)
        }
    }

    private fun updateButtonStates() {
        recordBtn.isEnabled = !collector.recording
        stopBtn.isEnabled = collector.recording
        liveWatchBtn.isEnabled = hasActiveSession || liveWatchService.isRunning.get()
    }

    private fun updateSessionState(active: Boolean) {
        hasActiveSession = active
        sessionStatusLabel.text = if (active) "Debug session active" else "No debug session"
        sessionStatusLabel.foreground = if (active)
            JBColor(Color(0x2E7D32), Color(0x81C784))
        else
            JBColor(Color(0xB71C1C), Color(0xEF9A9A))

        if (!active && liveWatchService.isRunning.get()) {
            stopLiveWatch()
        }
        updateButtonStates()
    }

    /**
     * 更新变量值标签 — 显示数据类型和最新数值
     */
    private fun updateChannelValues() {
        val entries = liveWatchService.getResolvedEntries()
        for ((name, label) in channelValueLabels) {
            val ch = dataBuffer.getChannels().find { it.name == name }
            if (ch != null && ch.size > 0) {
                val lastValue = ch.get(ch.size - 1)
                val typeStr = entries[name]?.dataType?.name?.lowercase() ?: "?"
                label.text = " ($typeStr) ${formatCompactValue(lastValue)}"
            }
        }
    }

    private fun formatCompactValue(v: Double): String {
        if (v.isNaN()) return "NaN"
        return when {
            v == 0.0 -> "0"
            Math.abs(v) >= 10000 -> String.format("%.0f", v)
            Math.abs(v) >= 1 -> String.format("%.3f", v)
            Math.abs(v) >= 0.001 -> String.format("%.5f", v)
            else -> String.format("%.2e", v)
        }
    }

    private fun updateStatusBar() {
        // 状态栏在 plotCanvas.onStatusUpdate 中更新
    }

    private fun saveConfig() {
        val config = WaveformConfigService.getInstance(project)
        val s = config.state
        s.variableNames = channelCheckboxes.keys.toMutableList()
        s.trackedVariables = collector.trackedVariables.toMutableList()
        s.liveWatchFrequency = freqSpinner.value as Int
        s.telnetPort = portSpinner.value as Int
        s.fontSize = plotCanvas.fontSize
        s.lineWidth = plotCanvas.lineWidth
        s.refreshFps = if (plotCanvas.refreshIntervalMs <= 17) 60 else 30
    }

    private fun restoreConfig() {
        val config = WaveformConfigService.getInstance(project)
        val s = config.state
        val tracked = s.trackedVariables.toSet()
        for (name in s.variableNames) {
            addChannelCheckbox(name, checked = tracked.contains(name))
        }
        freqSpinner.value = s.liveWatchFrequency.coerceIn(1, 2000)
        portSpinner.value = s.telnetPort.coerceIn(1, 65535)
        // 恢复 UI 设置
        plotCanvas.fontSize = s.fontSize.coerceIn(8, 20)
        plotCanvas.lineWidth = s.lineWidth.coerceIn(0.5f, 5.0f)
        plotCanvas.refreshIntervalMs = if (s.refreshFps >= 60) 16 else 33
        plotCanvas.updateFonts()
        plotCanvas.updateRefreshRate()
    }

    private fun csvField(s: String): String {
        return if (s.contains(',') || s.contains('"')) "\"${s.replace("\"", "\"\"")}\"" else s
    }

    /**
     * 静默获取 ELF 路径并加载符号表（用户不可见）
     * 通过 GDB `info files` 命令获取当前加载的可执行文件路径
     */
    private fun autoLoadElfSymbols(session: com.intellij.xdebugger.XDebugSession) {
        val cidrProcess = session.debugProcess as? com.jetbrains.cidr.execution.debugger.CidrDebugProcess ?: return
        cidrProcess.postCommand(object : com.jetbrains.cidr.execution.debugger.CidrDebugProcess.VoidDebuggerCommand {
            override fun run(driver: com.jetbrains.cidr.execution.debugger.backend.DebuggerDriver) {
                try {
                    val result = driver.executeInterpreterCommand("info files").trim()
                    // 解析 "Symbols from ..." 或 "Local exec file:" 行
                    val elfPath = parseElfPathFromInfoFiles(result)
                    if (elfPath != null) {
                        val count = liveWatchService.elfResolver.loadSymbols(elfPath)
                        if (count) {
                            log.info("Auto-loaded ${liveWatchService.elfResolver.getSymbolCount()} ELF symbols from $elfPath")
                        }
                    }
                } catch (e: Exception) {
                    log.debug("Auto ELF load failed: ${e.message}")
                }
            }
        })
    }

    private fun parseElfPathFromInfoFiles(output: String): String? {
        // 格式: 'Symbols from "/path/to/firmware.elf".'
        val symbolsMatch = Regex("""Symbols from "(.+?)\"""").find(output)
        if (symbolsMatch != null) return symbolsMatch.groupValues[1]

        // 格式: 'Local exec file:\n    `/path/to/firmware.elf\''
        val localMatch = Regex("""`(.+?\.elf)'""").find(output)
        if (localMatch != null) return localMatch.groupValues[1]

        return null
    }

    private fun showSettingsDialog() {
        val panel = JPanel(GridBagLayout())
        val gbc = GridBagConstraints().apply {
            insets = Insets(4, 8, 4, 8)
            anchor = GridBagConstraints.WEST
        }

        // 字体大小
        val fontSizeSpinner = JSpinner(SpinnerNumberModel(plotCanvas.fontSize, 8, 20, 1))
        gbc.gridx = 0; gbc.gridy = 0
        panel.add(JLabel("Font Size:"), gbc)
        gbc.gridx = 1
        panel.add(fontSizeSpinner, gbc)

        // 波形线宽
        val lineWidthSpinner = JSpinner(SpinnerNumberModel(plotCanvas.lineWidth.toDouble(), 0.5, 5.0, 0.5))
        gbc.gridx = 0; gbc.gridy = 1
        panel.add(JLabel("Line Width:"), gbc)
        gbc.gridx = 1
        panel.add(lineWidthSpinner, gbc)

        // 刷新率
        val refreshRates = arrayOf("30 fps", "60 fps")
        val refreshCombo = JComboBox(refreshRates).apply {
            selectedIndex = if (plotCanvas.refreshIntervalMs <= 17) 1 else 0
        }
        gbc.gridx = 0; gbc.gridy = 2
        panel.add(JLabel("Refresh Rate:"), gbc)
        gbc.gridx = 1
        panel.add(refreshCombo, gbc)

        val result = JOptionPane.showConfirmDialog(
            this, panel, "Display Settings",
            JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE
        )
        if (result == JOptionPane.OK_OPTION) {
            plotCanvas.fontSize = fontSizeSpinner.value as Int
            plotCanvas.lineWidth = (lineWidthSpinner.value as Double).toFloat()
            plotCanvas.refreshIntervalMs = if (refreshCombo.selectedIndex == 1) 16 else 33
            plotCanvas.updateFonts()
            plotCanvas.updateRefreshRate()
            saveConfig()
        }
    }

    override fun dispose() {
        liveWatchService.stopLiveWatch()
        saveConfig()
        plotCanvas.dispose()
        sessionListener.dispose()
    }
}
