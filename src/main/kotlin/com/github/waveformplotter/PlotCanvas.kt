package com.github.waveformplotter

import com.intellij.ui.JBColor
import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent
import javax.swing.JPanel
import javax.swing.Timer

/**
 * 波形绘制画布
 * - 背景网格 + Y 轴自动缩放
 * - 多通道叠加显示
 * - 鼠标滚轮缩放 X/Y 轴（Shift+滚轮切换轴）
 * - 鼠标左键拖拽平移查看历史
 * - 鼠标悬停显示数值 tooltip
 */
class PlotCanvas(private val dataBuffer: DataBuffer) : JPanel() {

    private var yScale = 1.0       // Y 轴缩放因子（>1 放大）
    private var xScale = 1.0       // X 轴缩放因子（>1 放大，显示更少点）
    private var xOffset = 0        // X 轴平移偏移（数据点数）

    // 鼠标拖拽状态
    private var dragStartX = 0
    private var dragStartOffset = 0
    private var isDragging = false

    // 鼠标悬停
    private var hoverX = -1
    private var hoverY = -1

    private val refreshTimer: Timer
    private lateinit var mouseHandler: MouseAdapter

    // 状态栏信息回调
    var onStatusUpdate: ((sampleCount: Int, yRange: String) -> Unit)? = null

    // 绘图常量
    private val margin = Insets(10, 55, 25, 10)
    private val gridColor = JBColor(Color(50, 50, 50, 60), Color(200, 200, 200, 40))
    private val textColor = JBColor(Color(100, 100, 100), Color(180, 180, 180))
    private val bgColor = JBColor(Color(0xF8F8F8), Color(0x2B2B2B))
    private val crosshairColor = JBColor(Color(150, 150, 150, 120), Color(150, 150, 150, 80))

    // 缓存 Font 避免每帧创建
    private val fontGrid = Font("Monospaced", Font.PLAIN, 10)
    private val fontAxisLabel = Font("Monospaced", Font.PLAIN, 9)
    private val fontTooltip = Font("Monospaced", Font.PLAIN, 11)
    private val fontHint = Font("SansSerif", Font.PLAIN, 13)

    init {
        isOpaque = true
        preferredSize = Dimension(600, 300)

        mouseHandler = object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                if (e.button == MouseEvent.BUTTON1) {
                    isDragging = true
                    dragStartX = e.x
                    dragStartOffset = xOffset
                    cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
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
                    // 右键：重置视图
                    xScale = 1.0
                    yScale = 1.0
                    xOffset = 0
                    repaint()
                }
            }

            override fun mouseDragged(e: MouseEvent) {
                if (isDragging) {
                    val plotW = width - margin.left - margin.right
                    if (plotW <= 0) return
                    val maxPoints = dataBuffer.getChannels().maxOfOrNull { it.size } ?: return
                    val visiblePoints = (maxPoints / xScale).toInt().coerceAtLeast(2)
                    val pixelsPerPoint = plotW.toDouble() / visiblePoints
                    val dx = e.x - dragStartX
                    xOffset = (dragStartOffset - (dx / pixelsPerPoint).toInt())
                        .coerceIn(0, (maxPoints - visiblePoints).coerceAtLeast(0))
                    repaint()
                }
            }

            override fun mouseMoved(e: MouseEvent) {
                hoverX = e.x
                hoverY = e.y
                repaint()
            }

            override fun mouseExited(e: MouseEvent) {
                hoverX = -1
                hoverY = -1
                repaint()
            }

            override fun mouseWheelMoved(e: MouseWheelEvent) {
                val factor = if (e.wheelRotation < 0) 1.15 else 1.0 / 1.15
                if (e.isShiftDown) {
                    // Shift+滚轮：缩放 X 轴
                    xScale = (xScale * factor).coerceIn(0.1, 100.0)
                    // 修正 xOffset 防止越界
                    val maxPoints = dataBuffer.getChannels().maxOfOrNull { it.size } ?: 0
                    val visiblePoints = (maxPoints / xScale).toInt().coerceAtLeast(2)
                    xOffset = xOffset.coerceIn(0, (maxPoints - visiblePoints).coerceAtLeast(0))
                } else {
                    // 普通滚轮：缩放 Y 轴
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
        xOffset = 0
        xScale = 1.0
        yScale = 1.0
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

        // 计算可见数据范围
        val maxPoints = channels.maxOf { it.size }
        if (maxPoints < 1) {
            drawEmptyHint(g2)
            return
        }

        val visiblePoints = (maxPoints / xScale).toInt().coerceIn(2, maxPoints)
        val startIdx = xOffset.coerceIn(0, (maxPoints - visiblePoints).coerceAtLeast(0))
        val endIdx = (startIdx + visiblePoints).coerceAtMost(maxPoints)

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

        // 应用 Y 轴缩放
        val yCenter = (yMin + yMax) / 2.0
        val yRange = (yMax - yMin) / yScale
        yMin = yCenter - yRange / 2.0
        yMax = yCenter + yRange / 2.0

        // 绘制网格
        drawGrid(g2, plotW, plotH, yMin, yMax, startIdx, endIdx)

        // 绘制波形
        g2.clip = Rectangle(margin.left, margin.top, plotW, plotH)
        for (ch in channels) {
            val chEnd = endIdx.coerceAtMost(ch.size)
            if (chEnd - startIdx < 2) continue
            g2.color = ch.color
            g2.stroke = BasicStroke(1.5f)

            var prevX = -1
            var prevY = -1
            for (i in startIdx until chEnd) {
                val v = ch.get(i)
                if (v.isNaN()) {
                    // NaN 断线：下一个有效点重新起笔
                    prevX = -1
                    continue
                }
                val x = margin.left + ((i - startIdx).toDouble() / (visiblePoints - 1) * plotW).toInt()
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
            drawCrosshair(g2, plotW, plotH, channels, startIdx, visiblePoints, yMin, yMax)
        }

        // 状态栏更新
        onStatusUpdate?.invoke(maxPoints, "[${formatValue(yMin)}, ${formatValue(yMax)}]")

    }

    private fun drawCrosshair(
        g2: Graphics2D, plotW: Int, plotH: Int,
        channels: List<ChannelData>, startIdx: Int, visiblePoints: Int,
        yMin: Double, yMax: Double
    ) {
        // 竖线
        g2.color = crosshairColor
        g2.stroke = BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_BEVEL, 0f, floatArrayOf(4f, 4f), 0f)
        g2.drawLine(hoverX, margin.top, hoverX, margin.top + plotH)

        // 计算对应的数据索引
        val relX = (hoverX - margin.left).toDouble() / plotW
        val dataIdx = startIdx + (relX * (visiblePoints - 1)).toInt()

        // 显示各通道在此位置的数值
        g2.font = fontTooltip
        var tooltipY = margin.top + 14
        for (ch in channels) {
            if (dataIdx < 0 || dataIdx >= ch.size) continue
            val value = ch.get(dataIdx)
            val text = "${ch.name}: ${formatValue(value)}"
            g2.color = ch.color
            g2.drawString(text, hoverX + 8, tooltipY)
            tooltipY += 14
        }
    }

    private fun drawGrid(
        g2: Graphics2D, plotW: Int, plotH: Int,
        yMin: Double, yMax: Double, startIdx: Int, endIdx: Int
    ) {
        g2.color = gridColor
        g2.stroke = BasicStroke(1f)

        // 水平网格线（5条）
        val hLines = 5
        for (i in 0..hLines) {
            val y = margin.top + (plotH.toDouble() * i / hLines).toInt()
            g2.drawLine(margin.left, y, margin.left + plotW, y)

            // Y 轴标签
            val value = yMax - (yMax - yMin) * i / hLines
            g2.color = textColor
            g2.font = fontGrid
            g2.drawString(formatValue(value), 2, y + 4)
            g2.color = gridColor
        }

        // 垂直网格线（5条）
        val vLines = 5
        for (i in 0..vLines) {
            val x = margin.left + (plotW.toDouble() * i / vLines).toInt()
            g2.drawLine(x, margin.top, x, margin.top + plotH)

            // X 轴标签（数据点序号）
            val idx = startIdx + ((endIdx - startIdx).toDouble() * i / vLines).toInt()
            g2.color = textColor
            g2.font = fontAxisLabel
            g2.drawString("#$idx", x + 2, margin.top + plotH + 12)
            g2.color = gridColor
        }

        // 绘图区域边框
        g2.drawRect(margin.left, margin.top, plotW, plotH)
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
}
