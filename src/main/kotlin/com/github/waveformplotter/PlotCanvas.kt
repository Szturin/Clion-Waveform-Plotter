package com.github.waveformplotter

import com.intellij.ui.JBColor
import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent
import javax.swing.JPanel
import javax.swing.Timer

enum class DisplayMode { TIME, FFT }

/**
 * 波形绘制画布
 * - 背景网格 + Y 轴自动缩放
 * - 多通道叠加显示
 * - 鼠标滚轮缩放 X/Y 轴（Shift+滚轮切换轴）
 * - 鼠标左键拖拽平移查看历史
 * - 鼠标悬停显示数值 tooltip
 * - FFT 频域显示模式（结果缓存，仅数据变化时重算）
 */
class PlotCanvas(private val dataBuffer: DataBuffer) : JPanel() {

    private var yScale = 1.0       // Y 轴缩放因子（>1 放大）
    private var yOffset = 0.0      // Y 轴平移偏移（数据单位）

    // 时域 X 轴（真实时间）
    private var xOffsetSec = 0.0       // 可见窗口左边缘时间（秒，相对 t=0）
    private var xScaleSPP = 0.001      // seconds-per-pixel（每像素多少秒）
    private var autoTrack = true       // 自动追踪最新数据（latest at right edge）
    private var userZoomed = false     // 用户是否手动缩放过（区分 auto-fit 和 sliding window）

    // FFT X 轴（保持原样，样本序号式）
    private var fftXScale = 1.0
    private var fftXOffset = 0

    // 时间单位（ms / μs）
    enum class TimeUnit(val label: String) { MS("ms"), US("μs") }
    var timeUnit = TimeUnit.MS

    // 鼠标拖拽状态
    private var dragStartX = 0
    private var dragStartY = 0
    private var dragStartXOffsetSec = 0.0
    private var dragStartFftXOffset = 0
    private var dragStartYOffset = 0.0
    private var isDragging = false

    // 鼠标悬停
    private var hoverX = -1
    private var hoverY = -1

    // 当前可见 Y 范围（用于拖拽计算）
    private var currentYMin = -1.0
    private var currentYMax = 1.0

    private val refreshTimer: Timer
    private lateinit var mouseHandler: MouseAdapter

    // 状态栏信息回调
    var onStatusUpdate: ((sampleCount: Int, yRange: String) -> Unit)? = null

    // 绘图常量
    private val margin = Insets(12, 62, 28, 12)
    private val gridColor = JBColor(Color(50, 50, 50, 60), Color(200, 200, 200, 40))
    private val textColor = JBColor(Color(100, 100, 100), Color(180, 180, 180))
    private val bgColor = JBColor(Color(0xF8F8F8), Color(0x2B2B2B))
    private val crosshairColor = JBColor(Color(150, 150, 150, 120), Color(150, 150, 150, 80))
    private val tooltipBgColor = JBColor(Color(255, 255, 255, 220), Color(50, 50, 50, 220))

    // 预缓存 Stroke 对象（避免每帧分配）
    private val dashedStroke = BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_BEVEL, 0f, floatArrayOf(4f, 4f), 0f)
    private val thinStroke = BasicStroke(1f)
    private var cachedLineStroke: BasicStroke? = null
    private var cachedLineWidth = -1f

    // 显示模式
    var displayMode = DisplayMode.TIME
    var sampleRateHz = 50.0  // Live Watch 采样率，用于 FFT 频率轴

    // 可配置参数（通过设置面板修改）
    var lineWidth = 2.0f
    var fontSize = 12

    // 动态字体（根据 fontSize 更新）
    private var fontGrid = Font("Monospaced", Font.PLAIN, fontSize)
    private var fontAxisLabel = Font("Monospaced", Font.PLAIN, fontSize - 1)
    private var fontTooltip = Font("Monospaced", Font.BOLD, fontSize + 1)
    private var fontHint = Font("SansSerif", Font.PLAIN, fontSize + 2)

    var refreshIntervalMs = 33

    // --- FFT 结果缓存 ---
    private class CachedSpectrum(
        val name: String,
        val color: Color,
        val magnitudes: DoubleArray,
        val fftN: Int
    )
    private var fftCache = emptyList<CachedSpectrum>()
    private var fftCacheDataVersion = -1L   // 数据版本号，检测变化
    private var fftCacheInputSize = 0
    // 采样缓冲复用
    private var fftSampleBuf = DoubleArray(1024)

    fun updateFonts() {
        fontGrid = Font("Monospaced", Font.PLAIN, fontSize)
        fontAxisLabel = Font("Monospaced", Font.PLAIN, fontSize - 1)
        fontTooltip = Font("Monospaced", Font.BOLD, fontSize + 1)
        fontHint = Font("SansSerif", Font.PLAIN, fontSize + 2)
        repaint()
    }

    fun updateRefreshRate() {
        refreshTimer.delay = refreshIntervalMs
    }

    private fun getLineStroke(): BasicStroke {
        if (cachedLineWidth != lineWidth) {
            cachedLineWidth = lineWidth
            cachedLineStroke = BasicStroke(lineWidth, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        }
        return cachedLineStroke!!
    }

    init {
        isOpaque = true
        preferredSize = Dimension(600, 300)

        mouseHandler = object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                if (e.button == MouseEvent.BUTTON1) {
                    isDragging = true
                    dragStartX = e.x
                    dragStartY = e.y
                    dragStartXOffsetSec = xOffsetSec
                    dragStartFftXOffset = fftXOffset
                    dragStartYOffset = yOffset
                    cursor = Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR)
                }
            }

            override fun mouseReleased(e: MouseEvent) {
                if (e.button == MouseEvent.BUTTON1) {
                    isDragging = false
                    cursor = Cursor.getDefaultCursor()
                }
            }

            override fun mouseClicked(e: MouseEvent) {
                if (e.button == MouseEvent.BUTTON3) {
                    // 右键：重置视图 + 恢复自动追踪
                    yScale = 1.0
                    yOffset = 0.0
                    xScaleSPP = 0.001
                    xOffsetSec = 0.0
                    autoTrack = true
                    userZoomed = false
                    fftXScale = 1.0
                    fftXOffset = 0
                    repaint()
                }
            }

            override fun mouseDragged(e: MouseEvent) {
                if (isDragging) {
                    val plotW = width - margin.left - margin.right
                    val plotH = height - margin.top - margin.bottom
                    if (plotW <= 0 || plotH <= 0) return

                    val dx = e.x - dragStartX

                    if (displayMode == DisplayMode.TIME) {
                        // 时域拖拽：像素差 × seconds-per-pixel
                        xOffsetSec = dragStartXOffsetSec - dx * xScaleSPP
                        if (xOffsetSec < 0) xOffsetSec = 0.0
                    } else {
                        // FFT 拖拽：保持原逻辑
                        val maxBins = fftCache.firstOrNull()?.magnitudes?.size ?: 0
                        if (maxBins > 0) {
                            val visibleBins = (maxBins / fftXScale).toInt().coerceAtLeast(2)
                            val pixelsPerBin = plotW.toDouble() / visibleBins
                            fftXOffset = (dragStartFftXOffset - (dx / pixelsPerBin).toInt())
                                .coerceIn(0, (maxBins - visibleBins).coerceAtLeast(0))
                        }
                    }

                    // Y 轴拖拽（向上拖 = yOffset 增大 = 波形下移）
                    val dy = e.y - dragStartY
                    val yRange = currentYMax - currentYMin
                    yOffset = dragStartYOffset + (dy.toDouble() / plotH) * yRange

                    repaint()
                }
            }

            override fun mouseMoved(e: MouseEvent) {
                hoverX = e.x
                hoverY = e.y
            }

            override fun mouseExited(e: MouseEvent) {
                hoverX = -1
                hoverY = -1
            }

            override fun mouseWheelMoved(e: MouseWheelEvent) {
                val factor = if (e.wheelRotation < 0) 1.15 else 1.0 / 1.15
                if (e.isShiftDown) {
                    if (displayMode == DisplayMode.TIME) {
                        // Shift+滚轮：缩放时间轴，保持 autoTrack（滑动窗口模式）
                        val zoomFactor = if (e.wheelRotation < 0) 1.0 / 1.2 else 1.2
                        if (!autoTrack) {
                            // 手动模式：以鼠标位置为锚点缩放
                            val mouseRelPx = (e.x - margin.left).toDouble()
                            val mouseTime = xOffsetSec + mouseRelPx * xScaleSPP
                            xScaleSPP = (xScaleSPP * zoomFactor).coerceIn(1e-9, 100.0)
                            xOffsetSec = (mouseTime - mouseRelPx * xScaleSPP).coerceAtLeast(0.0)
                        } else {
                            xScaleSPP = (xScaleSPP * zoomFactor).coerceIn(1e-9, 100.0)
                        }
                        userZoomed = true
                    } else {
                        // FFT 模式保持原逻辑
                        fftXScale = (fftXScale * factor).coerceIn(0.1, 100.0)
                        val maxBins = fftCache.firstOrNull()?.magnitudes?.size ?: 0
                        val visibleBins = (maxBins / fftXScale).toInt().coerceAtLeast(2)
                        fftXOffset = fftXOffset.coerceIn(0, (maxBins - visibleBins).coerceAtLeast(0))
                    }
                } else {
                    yScale = (yScale * factor).coerceIn(0.01, 1000.0)
                }
                repaint()
            }
        }
        addMouseListener(mouseHandler)
        addMouseMotionListener(mouseHandler)
        addMouseWheelListener(mouseHandler)

        // 30fps 刷新
        refreshTimer = Timer(33) { repaint() }
        refreshTimer.start()
    }

    fun dispose() {
        refreshTimer.stop()
        removeMouseListener(mouseHandler)
        removeMouseMotionListener(mouseHandler)
        removeMouseWheelListener(mouseHandler)
    }

    /** 重置视图（Clear 时调用） */
    fun resetView() {
        xOffsetSec = 0.0
        xScaleSPP = 0.001
        autoTrack = true
        userZoomed = false
        yScale = 1.0
        yOffset = 0.0
        fftXScale = 1.0
        fftXOffset = 0
        fftCacheDataVersion = -1L  // 强制 FFT 重算
    }

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        val g2 = g as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

        // 背景
        g2.color = bgColor
        g2.fillRect(0, 0, width, height)

        val plotW = width - margin.left - margin.right
        val plotH = height - margin.top - margin.bottom
        if (plotW <= 0 || plotH <= 0) return

        val channels = dataBuffer.getChannels()
        if (channels.isEmpty()) {
            drawEmptyHint(g2)
            onStatusUpdate?.invoke(0, "N/A")
            return
        }

        val maxPoints = channels.maxOf { it.size }
        if (maxPoints < 1) {
            drawEmptyHint(g2)
            return
        }

        when (displayMode) {
            DisplayMode.TIME -> paintTimeDomain(g2, plotW, plotH, channels, maxPoints)
            DisplayMode.FFT -> paintFFT(g2, plotW, plotH, channels, maxPoints)
        }
    }

    private fun paintTimeDomain(
        g2: Graphics2D, plotW: Int, plotH: Int,
        channels: List<ChannelData>, maxPoints: Int
    ) {
        // 自动追踪
        if (autoTrack && dataBuffer.tsSize > 1) {
            val totalDuration = dataBuffer.getTimeSeconds(dataBuffer.tsSize - 1)
            if (totalDuration > 0) {
                if (!userZoomed) {
                    // 未手动缩放：auto-fit 所有数据
                    xScaleSPP = totalDuration * 1.05 / plotW
                    xOffsetSec = 0.0
                } else {
                    // 手动缩放过：sliding window，最新数据在右边缘
                    val visibleDuration = xScaleSPP * plotW
                    xOffsetSec = (totalDuration - visibleDuration * 0.95).coerceAtLeast(0.0)
                }
            }
        }

        // 时间范围（秒）
        val visibleDuration = xScaleSPP * plotW
        val tStart = xOffsetSec
        val tEnd = xOffsetSec + visibleDuration

        // 找出在可见时间范围内的样本索引
        val startIdx = findIndexAtTime(tStart)
        val endIdx = findIndexAtTime(tEnd) + 1

        // 计算 Y 轴范围（基于可见数据，自动缩放，跳过 NaN）
        var yMin = Double.MAX_VALUE
        var yMax = -Double.MAX_VALUE
        for (ch in channels) {
            for (i in startIdx until endIdx.coerceAtMost(ch.size)) {
                val v = ch.get(i)
                if (v.isNaN()) continue
                if (v < yMin) yMin = v
                if (v > yMax) yMax = v
            }
        }
        if (yMin == Double.MAX_VALUE) { yMin = -1.0; yMax = 1.0 }
        if (yMin == yMax) { yMin -= 1.0; yMax += 1.0 }

        // 应用 Y 轴缩放 + 偏移
        val yCenter = (yMin + yMax) / 2.0
        val yRange = (yMax - yMin) / yScale
        yMin = yCenter - yRange / 2.0 + yOffset
        yMax = yCenter + yRange / 2.0 + yOffset
        currentYMin = yMin
        currentYMax = yMax

        // 显示偏移：最新样本 = 0，旧样本 = 负值（示波器标准）
        val latestTimeSec = if (dataBuffer.tsSize > 0)
            dataBuffer.getTimeSeconds(dataBuffer.tsSize - 1) else 0.0

        // 绘制网格（时间轴）
        drawTimeGrid(g2, plotW, plotH, yMin, yMax, tStart, tEnd, latestTimeSec)

        // 绘制波形
        g2.clip = Rectangle(margin.left, margin.top, plotW, plotH)
        val stroke = getLineStroke()
        for (ch in channels) {
            val chEnd = endIdx.coerceAtMost(ch.size)
            if (chEnd - startIdx < 2) continue
            g2.color = ch.color
            g2.stroke = stroke

            var prevX = -1
            var prevY = -1
            for (i in startIdx until chEnd) {
                val v = ch.get(i)
                if (v.isNaN()) {
                    prevX = -1
                    continue
                }
                val t = dataBuffer.getTimeSeconds(i)
                val x = margin.left + ((t - tStart) / visibleDuration * plotW).toInt()
                val yNorm = (v - yMin) / (yMax - yMin)
                val y = margin.top + plotH - (yNorm * plotH).toInt()
                if (prevX >= 0) {
                    g2.drawLine(prevX, prevY, x, y)
                }
                prevX = x
                prevY = y
            }
        }
        g2.clip = null

        // 悬停十字线 + 数值
        if (hoverX in margin.left..(margin.left + plotW) &&
            hoverY in margin.top..(margin.top + plotH)
        ) {
            drawTimeCrosshair(g2, plotW, plotH, channels, tStart, visibleDuration, latestTimeSec)
        }

        // 状态栏更新
        onStatusUpdate?.invoke(maxPoints, "[${formatValue(yMin)}, ${formatValue(yMax)}]")
    }

    /** 二分查找：找到 >= targetTimeSec 的第一个样本索引 */
    private fun findIndexAtTime(targetTimeSec: Double): Int {
        val n = dataBuffer.tsSize
        if (n == 0) return 0
        var lo = 0
        var hi = n - 1
        while (lo < hi) {
            val mid = (lo + hi) / 2
            if (dataBuffer.getTimeSeconds(mid) < targetTimeSec) lo = mid + 1 else hi = mid
        }
        return lo
    }

    /**
     * 计算 FFT 频谱（带缓存：仅数据版本变化时重算）
     */
    private fun computeFFTSpectra(channels: List<ChannelData>, maxPoints: Int): List<CachedSpectrum> {
        val currentVersion = dataBuffer.version
        if (fftCacheDataVersion == currentVersion && fftCache.isNotEmpty()) {
            return fftCache
        }

        val fftInputSize = 1024.coerceAtMost(maxPoints)

        // 确保采样缓冲足够大
        if (fftSampleBuf.size < fftInputSize) {
            fftSampleBuf = DoubleArray(fftInputSize)
        }

        val results = ArrayList<CachedSpectrum>(channels.size)
        for (ch in channels) {
            if (ch.size < 2) continue
            val n = fftInputSize.coerceAtMost(ch.size)
            val offset = ch.size - n
            for (i in 0 until n) {
                val v = ch.get(offset + i)
                fftSampleBuf[i] = if (v.isNaN()) 0.0 else v
            }
            // 使用复用版本
            val inputSlice = if (n == fftSampleBuf.size) fftSampleBuf else fftSampleBuf.copyOf(n)
            val mag = FFT.magnitudeSpectrum(inputSlice)
            results.add(CachedSpectrum(ch.name, ch.color, mag, FFT.fftSize(n)))
        }

        fftCache = results
        fftCacheDataVersion = currentVersion
        fftCacheInputSize = fftInputSize
        return results
    }

    private fun paintFFT(
        g2: Graphics2D, plotW: Int, plotH: Int,
        channels: List<ChannelData>, maxPoints: Int
    ) {
        val spectra = computeFFTSpectra(channels, maxPoints)
        if (spectra.isEmpty()) {
            drawEmptyHint(g2)
            return
        }

        val halfN = spectra[0].magnitudes.size
        val freqResolution = sampleRateHz / spectra[0].fftN  // Hz per bin

        // 可见频率范围（应用 X 缩放/偏移）
        val visibleBins = (halfN / fftXScale).toInt().coerceIn(2, halfN)
        val startBin = fftXOffset.coerceIn(0, (halfN - visibleBins).coerceAtLeast(0))
        val endBin = (startBin + visibleBins).coerceAtMost(halfN)

        // Y 轴范围 (dB)
        var yMin = Double.MAX_VALUE
        var yMax = -Double.MAX_VALUE
        for (sp in spectra) {
            for (i in startBin until endBin.coerceAtMost(sp.magnitudes.size)) {
                val v = sp.magnitudes[i]
                if (v < -200) continue
                if (v < yMin) yMin = v
                if (v > yMax) yMax = v
            }
        }
        if (yMin == Double.MAX_VALUE) { yMin = -120.0; yMax = 0.0 }
        if (yMin == yMax) { yMin -= 10.0; yMax += 10.0 }

        // 应用 Y 轴缩放 + 偏移
        val yCenter = (yMin + yMax) / 2.0
        val yRange = (yMax - yMin) / yScale
        yMin = yCenter - yRange / 2.0 + yOffset
        yMax = yCenter + yRange / 2.0 + yOffset
        currentYMin = yMin
        currentYMax = yMax

        // 绘制 FFT 网格
        drawFFTGrid(g2, plotW, plotH, yMin, yMax, startBin, endBin, freqResolution)

        // 绘制频谱曲线
        g2.clip = Rectangle(margin.left, margin.top, plotW, plotH)
        val stroke = getLineStroke()
        for (sp in spectra) {
            val spEnd = endBin.coerceAtMost(sp.magnitudes.size)
            if (spEnd - startBin < 2) continue
            g2.color = sp.color
            g2.stroke = stroke

            var prevX = -1
            var prevY = -1
            for (i in startBin until spEnd) {
                val v = sp.magnitudes[i]
                val x = margin.left + ((i - startBin).toDouble() / (visibleBins - 1) * plotW).toInt()
                val yNorm = (v - yMin) / (yMax - yMin)
                val y = margin.top + plotH - (yNorm * plotH).toInt()
                if (prevX >= 0) {
                    g2.drawLine(prevX, prevY, x, y)
                }
                prevX = x
                prevY = y
            }
        }
        g2.clip = null

        // 悬停十字线 + 频率/幅度 tooltip
        if (hoverX in margin.left..(margin.left + plotW) &&
            hoverY in margin.top..(margin.top + plotH)
        ) {
            drawFFTCrosshair(g2, plotW, plotH, spectra, startBin, visibleBins, freqResolution)
        }

        // 状态栏
        onStatusUpdate?.invoke(fftCacheInputSize, "FFT ${spectra[0].fftN}pt | [${formatValue(yMin)}, ${formatValue(yMax)}] dB")
    }

    private fun drawFFTGrid(
        g2: Graphics2D, plotW: Int, plotH: Int,
        yMin: Double, yMax: Double, startBin: Int, endBin: Int,
        freqResolution: Double
    ) {
        g2.color = gridColor
        g2.stroke = thinStroke

        // 水平网格线（Y 轴 = dB）
        val hLines = 5
        for (i in 0..hLines) {
            val y = margin.top + (plotH.toDouble() * i / hLines).toInt()
            g2.drawLine(margin.left, y, margin.left + plotW, y)

            val value = yMax - (yMax - yMin) * i / hLines
            g2.color = textColor
            g2.font = fontGrid
            g2.drawString("${formatValue(value)}dB", 2, y + 4)
            g2.color = gridColor
        }

        // 垂直网格线（X 轴 = 频率 Hz）
        val vLines = 5
        g2.font = fontAxisLabel
        for (i in 0..vLines) {
            val x = margin.left + (plotW.toDouble() * i / vLines).toInt()
            g2.drawLine(x, margin.top, x, margin.top + plotH)

            val bin = startBin + ((endBin - startBin).toDouble() * i / vLines).toInt()
            val freq = bin * freqResolution
            g2.color = textColor
            g2.drawString(formatFreq(freq), x + 2, margin.top + plotH + 12)
            g2.color = gridColor
        }

        g2.drawRect(margin.left, margin.top, plotW, plotH)
    }

    private fun drawFFTCrosshair(
        g2: Graphics2D, plotW: Int, plotH: Int,
        spectra: List<CachedSpectrum>, startBin: Int, visibleBins: Int,
        freqResolution: Double
    ) {
        g2.color = crosshairColor
        g2.stroke = dashedStroke
        g2.drawLine(hoverX, margin.top, hoverX, margin.top + plotH)
        g2.drawLine(margin.left, hoverY, margin.left + plotW, hoverY)

        val relX = (hoverX - margin.left).toDouble() / plotW
        val bin = startBin + (relX * (visibleBins - 1)).toInt()
        val freq = bin * freqResolution

        g2.font = fontTooltip
        val fm = g2.fontMetrics
        val lineH = fm.height + 2

        val tooltipLines = mutableListOf<Pair<Color, String>>()
        for (sp in spectra) {
            if (bin < 0 || bin >= sp.magnitudes.size) continue
            val mag = sp.magnitudes[bin]
            tooltipLines.add(sp.color to "${sp.name}: ${formatValue(freq)}Hz, ${formatValue(mag)}dB")
        }
        if (tooltipLines.isEmpty()) return

        drawTooltipBox(g2, fm, lineH, tooltipLines, plotW, plotH)
    }

    private fun drawTimeCrosshair(
        g2: Graphics2D, plotW: Int, plotH: Int,
        channels: List<ChannelData>, tStart: Double, visibleDuration: Double,
        latestTimeSec: Double = 0.0
    ) {
        g2.color = crosshairColor
        g2.stroke = dashedStroke
        g2.drawLine(hoverX, margin.top, hoverX, margin.top + plotH)
        g2.drawLine(margin.left, hoverY, margin.left + plotW, hoverY)

        // 像素→时间→最近样本索引
        val relX = (hoverX - margin.left).toDouble() / plotW
        val hoverTime = tStart + relX * visibleDuration
        val dataIdx = findIndexAtTime(hoverTime)

        // 收集 tooltip 内容
        g2.font = fontTooltip
        val fm = g2.fontMetrics
        val lineH = fm.height + 2
        val tooltipLines = mutableListOf<Pair<Color, String>>()
        // 时间标注
        tooltipLines.add(textColor to "t = ${formatTime(hoverTime - latestTimeSec)}")
        for (ch in channels) {
            if (dataIdx < 0 || dataIdx >= ch.size) continue
            val value = ch.get(dataIdx)
            tooltipLines.add(ch.color to "${ch.name}: ${formatValue(value)}")
        }
        if (tooltipLines.size <= 1) return

        drawTooltipBox(g2, fm, lineH, tooltipLines, plotW, plotH)
    }

    /** 绘制 tooltip 背景框 + 文字（时域/频域共用） */
    private fun drawTooltipBox(
        g2: Graphics2D, fm: FontMetrics, lineH: Int,
        lines: List<Pair<Color, String>>, plotW: Int, plotH: Int
    ) {
        val maxTextW = lines.maxOf { fm.stringWidth(it.second) }
        val tooltipW = maxTextW + 16
        val tooltipH = lines.size * lineH + 8
        val tx = if (hoverX + tooltipW + 12 > margin.left + plotW) hoverX - tooltipW - 8 else hoverX + 10
        val ty = (hoverY - tooltipH / 2).coerceIn(margin.top, margin.top + plotH - tooltipH)

        g2.color = tooltipBgColor
        g2.fillRoundRect(tx, ty, tooltipW, tooltipH, 6, 6)
        g2.color = crosshairColor
        g2.stroke = thinStroke
        g2.drawRoundRect(tx, ty, tooltipW, tooltipH, 6, 6)

        var textY = ty + fm.ascent + 4
        for ((color, text) in lines) {
            g2.color = color
            g2.drawString(text, tx + 8, textY)
            textY += lineH
        }
    }

    private fun drawTimeGrid(
        g2: Graphics2D, plotW: Int, plotH: Int,
        yMin: Double, yMax: Double, tStart: Double, tEnd: Double,
        latestTimeSec: Double = 0.0
    ) {
        g2.color = gridColor
        g2.stroke = thinStroke

        // 水平网格线（5条）
        val hLines = 5
        for (i in 0..hLines) {
            val y = margin.top + (plotH.toDouble() * i / hLines).toInt()
            g2.drawLine(margin.left, y, margin.left + plotW, y)

            val value = yMax - (yMax - yMin) * i / hLines
            g2.color = textColor
            g2.font = fontGrid
            g2.drawString(formatValue(value), 2, y + 4)
            g2.color = gridColor
        }

        // 垂直网格线（时间轴，自适应友好间距）
        val duration = tEnd - tStart
        val gridStep = pickFriendlyTimeStep(duration)
        val firstGrid = Math.ceil(tStart / gridStep) * gridStep

        g2.font = fontAxisLabel
        var t = firstGrid
        while (t <= tEnd) {
            val x = margin.left + ((t - tStart) / duration * plotW).toInt()
            if (x in margin.left..(margin.left + plotW)) {
                g2.color = gridColor
                g2.stroke = thinStroke
                g2.drawLine(x, margin.top, x, margin.top + plotH)
                g2.color = textColor
                g2.drawString(formatTime(t - latestTimeSec), x + 2, margin.top + plotH + 12)
            }
            t += gridStep
        }

        g2.color = gridColor
        g2.stroke = thinStroke
        g2.drawRect(margin.left, margin.top, plotW, plotH)
    }

    /** 示波器风格的友好时间间距（1-2-5 序列） */
    private fun pickFriendlyTimeStep(visibleDuration: Double): Double {
        // 目标：4-8 条网格线
        val target = visibleDuration / 6.0
        if (target <= 0) return 1.0

        // 1-2-5 序列
        val steps = doubleArrayOf(1.0, 2.0, 5.0)
        val exp = Math.floor(Math.log10(target)).toInt()
        val base = Math.pow(10.0, exp.toDouble())
        for (s in steps) {
            val step = s * base
            if (step >= target * 0.7) return step
        }
        return 10.0 * base
    }

    private fun drawEmptyHint(g2: Graphics2D) {
        g2.color = textColor
        g2.font = fontHint
        val msg = "Select variables and start recording to see waveforms"
        val fm = g2.fontMetrics
        g2.drawString(msg, (width - fm.stringWidth(msg)) / 2, height / 2)
    }

    private fun formatValue(v: Double): String {
        return when {
            v == 0.0 -> "0"
            Math.abs(v) >= 1000 -> String.format("%.0f", v)
            Math.abs(v) >= 1 -> String.format("%.2f", v)
            Math.abs(v) >= 0.01 -> String.format("%.4f", v)
            else -> String.format("%.2e", v)
        }
    }

    private fun formatFreq(hz: Double): String {
        return when {
            hz >= 1000 -> String.format("%.1fkHz", hz / 1000)
            hz >= 1 -> String.format("%.1fHz", hz)
            else -> String.format("%.2fHz", hz)
        }
    }

    /** 固定单位时间格式化（ms 或 μs） */
    private fun formatTime(seconds: Double): String {
        return when (timeUnit) {
            TimeUnit.MS -> {
                val ms = seconds * 1000.0
                when {
                    Math.abs(ms) >= 100 -> String.format("%.0fms", ms)
                    Math.abs(ms) >= 1 -> String.format("%.1fms", ms)
                    else -> String.format("%.2fms", ms)
                }
            }
            TimeUnit.US -> {
                val us = seconds * 1_000_000.0
                when {
                    Math.abs(us) >= 100 -> String.format("%.0fμs", us)
                    Math.abs(us) >= 1 -> String.format("%.1fμs", us)
                    else -> String.format("%.2fμs", us)
                }
            }
        }
    }
}
