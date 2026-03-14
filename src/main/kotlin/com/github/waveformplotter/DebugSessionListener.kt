package com.github.waveformplotter

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.util.messages.MessageBusConnection
import com.intellij.xdebugger.XDebugProcess
import com.intellij.xdebugger.XDebugSession
import com.intellij.xdebugger.XDebugSessionListener
import com.intellij.xdebugger.XDebuggerManager
import com.intellij.xdebugger.XDebuggerManagerListener

/**
 * 调试会话生命周期 + 暂停事件监听
 *
 * 被动模式：不主动暂停/恢复 MCU，只在调试器已暂停时采集数据。
 * 典型使用场景：用户设 "Evaluate and Log" 断点，每次命中时插件记录一个数据点。
 */
class DebugSessionListener(
    private val project: Project,
    private val collector: WatchVariableCollector,
    private val onSessionActive: (Boolean) -> Unit
) {
    private val log = Logger.getInstance(DebugSessionListener::class.java)
    private var currentSession: XDebugSession? = null
    private var sessionListener: XDebugSessionListener? = null
    private var busConnection: MessageBusConnection? = null

    /** MCU 暂停时回调（用于 Live Watch 地址解析时机） */
    var onSessionPaused: ((XDebugSession) -> Unit)? = null

    /** MCU 恢复运行时回调（用于 Live Watch 启动采集） */
    var onSessionResumed: ((XDebugSession) -> Unit)? = null

    /** 获取当前活跃的调试会话 */
    fun getCurrentSession(): XDebugSession? = currentSession

    fun init() {
        val connection = project.messageBus.connect()
        busConnection = connection
        connection.subscribe(
            XDebuggerManager.TOPIC,
            object : XDebuggerManagerListener {
                override fun processStarted(debugProcess: XDebugProcess) {
                    log.info("Debug session started")
                    attachToSession(debugProcess.session)
                    onSessionActive(true)
                }

                override fun processStopped(debugProcess: XDebugProcess) {
                    log.info("Debug session stopped")
                    detachFromSession()
                    onSessionActive(false)
                }
            }
        )

        // 如果插件加载时已有活跃会话，立即附加
        val existing = XDebuggerManager.getInstance(project).currentSession
        if (existing != null) {
            attachToSession(existing)
            onSessionActive(true)
        }
    }

    private fun attachToSession(session: XDebugSession) {
        detachFromSession()
        currentSession = session

        val listener = object : XDebugSessionListener {
            override fun sessionPaused() {
                // 调试器暂停（断点命中/单步/手动暂停）— 采集数据点
                collector.collectFromSession(session)
                onSessionPaused?.invoke(session)
            }

            override fun sessionResumed() {
                onSessionResumed?.invoke(session)
            }

            override fun sessionStopped() {
                detachFromSession()
                onSessionActive(false)
            }
        }
        sessionListener = listener
        session.addSessionListener(listener)
        log.info("Attached pause listener to debug session")
    }

    private fun detachFromSession() {
        sessionListener?.let { listener ->
            currentSession?.removeSessionListener(listener)
        }
        sessionListener = null
        currentSession = null
    }

    fun dispose() {
        detachFromSession()
        busConnection?.disconnect()
        busConnection = null
    }
}
