package com.github.waveformplotter

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.xdebugger.XDebugSession
import com.intellij.xdebugger.evaluation.XDebuggerEvaluator
import com.intellij.xdebugger.frame.XFullValueEvaluator
import com.intellij.xdebugger.frame.XValue
import com.intellij.xdebugger.frame.XValueNode
import com.intellij.xdebugger.frame.XValuePlace
import com.intellij.xdebugger.frame.presentation.XValuePresentation
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.swing.Icon

/**
 * Watch 变量采集器 — 被动模式
 *
 * 当调试器暂停时，从当前栈帧求值用户勾选的变量表达式，
 * 将数值写入 DataBuffer。
 *
 * 不主动暂停/恢复 MCU。
 */
class WatchVariableCollector(
    private val dataBuffer: DataBuffer,
    private val onDataCollected: () -> Unit
) {
    private val log = Logger.getInstance(WatchVariableCollector::class.java)

    /** 当前正在追踪（勾选）的变量名集合 */
    val trackedVariables = CopyOnWriteArraySet<String>()

    /** 是否正在录制 */
    @Volatile
    var recording = false

    /** 采样计数 */
    @Volatile
    var sampleCount = 0L
        private set

    fun resetSampleCount() {
        sampleCount = 0
    }

    /**
     * 由 DebugSessionListener.sessionPaused() 调用
     * 在调试器暂停时采集一次数据
     */
    fun collectFromSession(session: XDebugSession) {
        if (!recording) return
        if (trackedVariables.isEmpty()) return

        val frame = session.currentStackFrame ?: return
        val evaluator = frame.evaluator ?: return

        val tracked = trackedVariables.toList()
        val values = mutableMapOf<String, Double>()
        val latch = CountDownLatch(tracked.size)

        for (varName in tracked) {
            evaluator.evaluate(
                varName,
                object : XDebuggerEvaluator.XEvaluationCallback {
                    override fun evaluated(result: XValue) {
                        extractDoubleFromXValue(result) { value ->
                            if (value != null) {
                                synchronized(values) { values[varName] = value }
                            }
                            latch.countDown()
                        }
                    }

                    override fun errorOccurred(errorMessage: String) {
                        log.debug("Eval error for '$varName': $errorMessage")
                        latch.countDown()
                    }
                },
                null
            )
        }

        // 等待所有求值完成（最多 500ms，复杂表达式/远程调试需要更多时间）
        latch.await(500, TimeUnit.MILLISECONDS)

        if (values.isNotEmpty()) {
            // 确保所有被跟踪通道存在
            for (name in tracked) {
                if (dataBuffer.getChannels().none { it.name == name }) {
                    dataBuffer.addChannel(name)
                }
            }
            // 对缺失值填 NaN，保持各通道时间对齐
            val aligned = mutableMapOf<String, Double>()
            for (name in tracked) {
                aligned[name] = values[name] ?: Double.NaN
            }
            dataBuffer.pushAll(aligned, System.nanoTime())
            sampleCount++
            onDataCollected()
        }
    }

    /**
     * 从 XValue 的 presentation 中提取数值
     */
    private fun extractDoubleFromXValue(value: XValue, callback: (Double?) -> Unit) {
        value.computePresentation(
            object : XValueNode {
                override fun setPresentation(
                    icon: Icon?,
                    type: String?,
                    value: String,
                    hasChildren: Boolean
                ) {
                    callback(parseDouble(value))
                }

                override fun setPresentation(
                    icon: Icon?,
                    presentation: XValuePresentation,
                    hasChildren: Boolean
                ) {
                    val sb = StringBuilder()
                    presentation.renderValue(object : XValuePresentation.XValueTextRenderer {
                        override fun renderValue(value: String) { sb.append(value) }
                        override fun renderValue(value: String, key: TextAttributesKey) { sb.append(value) }
                        override fun renderStringValue(value: String) { sb.append(value) }
                        override fun renderStringValue(value: String, additionalChars: String?, maxLength: Int) { sb.append(value) }
                        override fun renderNumericValue(value: String) { sb.append(value) }
                        override fun renderKeywordValue(value: String) { sb.append(value) }
                        override fun renderComment(comment: String) {}
                        override fun renderSpecialSymbol(symbol: String) {}
                        override fun renderError(error: String) {}
                    })
                    callback(parseDouble(sb.toString()))
                }

                override fun setFullValueEvaluator(fullValueEvaluator: XFullValueEvaluator) {}
            },
            XValuePlace.TREE
        )
    }

    /** 解析 GDB 返回的各种数值格式 */
    private fun parseDouble(s: String): Double? {
        val trimmed = s.trim()
            .removeSuffix("f")
            .removeSuffix("F")
            .removeSuffix("L")
            .removeSuffix("l")
            .replace("'", "")  // C++ digit separator
        return try {
            when {
                trimmed.equals("true", ignoreCase = true) -> 1.0
                trimmed.equals("false", ignoreCase = true) -> 0.0
                trimmed.startsWith("0x") || trimmed.startsWith("0X") ->
                    java.lang.Long.decode(trimmed).toDouble()
                else -> trimmed.toDouble()
            }
        } catch (_: NumberFormatException) {
            null
        }
    }
}
