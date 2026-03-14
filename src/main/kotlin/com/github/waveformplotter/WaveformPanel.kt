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
        SwingUtilities.invokeLater { plotCanvas.repaint(); updateStatusBar() }
    }
    private val liveWatchService = LiveWatchService(dataBuffer) {
        SwingUtilities.invokeLater { plotCanvas.repaint(); updateStatusBar() }
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
    private val freqSpinner = JSpinner(SpinnerNumberModel(50, 1, 100, 5)).apply {
        preferredSize = Dimension(60, preferredSize.height)
        toolTipText = "Sampling frequency (Hz)"
    }
    private val freqLabel = JLabel("Hz")

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

        // --- Live Watch 控件 ---
        controlBar.add(Box.createHorizontalStrut(16))
        controlBar.add(JSeparator(SwingConstants.VERTICAL).apply {
            preferredSize = Dimension(2, 20)
        })
        controlBar.add(Box.createHorizontalStrut(8))

        liveWatchBtn.foreground = colorIdle
        liveWatchBtn.toolTipText = "Start/Stop Live Watch (non-invasive memory monitoring)"
        liveWatchBtn.addActionListener { toggleLiveWatch() }
        controlBar.add(liveWatchBtn)
        controlBar.add(freqSpinner)
        controlBar.add(freqLabel)

        controlBar.add(Box.createHorizontalGlue())
        controlBar.add(sessionStatusLabel)

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
        statusLabel.font = Font("Monospaced", Font.PLAIN, 11)
        statusLabel.foreground = JBColor(Color(100, 100, 100), Color(160, 160, 160))
        liveStatusLabel.font = Font("Monospaced", Font.PLAIN, 11)
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
        cb.addActionListener {
            if (cb.isSelected) {
                collector.trackedVariables.add(name)
            } else {
                collector.trackedVariables.remove(name)
            }
            saveConfig()
        }
        // 右键删除
        cb.addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(e: java.awt.event.MouseEvent) {
                if (e.button == java.awt.event.MouseEvent.BUTTON3) {
                    removeVariable(name)
                }
            }
        })

        if (checked) {
            collector.trackedVariables.add(name)
        }

        channelCheckboxes[name] = cb
        channelCheckPanel.add(cb)
        channelCheckPanel.revalidate()
        channelCheckPanel.repaint()
    }

    private fun removeVariable(name: String) {
        collector.trackedVariables.remove(name)
        dataBuffer.removeChannel(name)
        channelCheckboxes.remove(name)?.let { cb ->
            channelCheckPanel.remove(cb)
            channelCheckPanel.revalidate()
            channelCheckPanel.repaint()
        }
        saveConfig()
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

        // 先解析地址（需要 MCU 暂停），然后启动采集
        liveWatchBtn.text = "\u23F3 Resolving..."
        liveWatchBtn.isEnabled = false
        liveStatusLabel.text = "Resolving addresses...  "
        liveStatusLabel.foreground = colorIdle

        liveWatchService.resolveVariables(session, trackedVars) { resolvedCount ->
            SwingUtilities.invokeLater {
                if (resolvedCount == 0) {
                    liveWatchBtn.text = "\u25B6 Live"
                    liveWatchBtn.isEnabled = true
                    liveWatchBtn.foreground = colorError
                    liveStatusLabel.text = "Failed to resolve variables (MCU must be paused first)  "
                    liveStatusLabel.foreground = colorError
                    return@invokeLater
                }

                liveWatchService.startLiveWatch(session, freq)
                liveWatchBtn.text = "\u25A0 Live"
                liveWatchBtn.isEnabled = true
                liveWatchBtn.foreground = colorRunning
                freqSpinner.isEnabled = false
                updateLiveStatus()
            }
        }
    }

    private fun stopLiveWatch() {
        liveWatchService.stopLiveWatch()
        liveWatchBtn.text = "\u25B6 Live"
        liveWatchBtn.foreground = colorIdle
        freqSpinner.isEnabled = true
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
            val mode = liveWatchService.watchMode.name.lowercase()
            val server = liveWatchService.serverType.name.lowercase()
            val freq = freqSpinner.value
            liveStatusLabel.text = "Live: $server/$mode@${freq}Hz (${liveWatchService.sampleCount})  "
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

    private fun updateStatusBar() {
        // 状态栏在 plotCanvas.onStatusUpdate 中更新
    }

    private fun saveConfig() {
        val config = WaveformConfigService.getInstance(project)
        val s = config.state
        s.variableNames = channelCheckboxes.keys.toMutableList()
        s.trackedVariables = collector.trackedVariables.toMutableList()
        s.liveWatchFrequency = freqSpinner.value as Int
    }

    private fun restoreConfig() {
        val config = WaveformConfigService.getInstance(project)
        val s = config.state
        val tracked = s.trackedVariables.toSet()
        for (name in s.variableNames) {
            addChannelCheckbox(name, checked = tracked.contains(name))
        }
        freqSpinner.value = s.liveWatchFrequency.coerceIn(1, 100)
    }

    private fun csvField(s: String): String {
        return if (s.contains(',') || s.contains('"')) "\"${s.replace("\"", "\"\"")}\"" else s
    }

    override fun dispose() {
        liveWatchService.stopLiveWatch()
        saveConfig()
        plotCanvas.dispose()
        sessionListener.dispose()
    }
}
