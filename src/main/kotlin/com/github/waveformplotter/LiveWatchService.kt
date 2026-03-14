package com.github.waveformplotter

import com.intellij.openapi.diagnostic.Logger
import com.jetbrains.cidr.execution.debugger.CidrDebugProcess
import com.jetbrains.cidr.execution.debugger.backend.DebuggerDriver
import com.intellij.xdebugger.XDebugSession
import java.util.Timer
import java.util.TimerTask
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Live Watch 实时内存监控服务
 *
 * 数据链路: GDB monitor 命令 → SWD MEM-AP → MCU 内存（非侵入式）
 *
 * 支持的 GDB Server:
 * - OpenOCD:            monitor mdw/mdh/mdb <addr>
 * - J-Link GDB Server:  monitor memU32/memU16/memU8 <addr> 1
 *
 * 双模式:
 * - Monitor 模式（默认）: MCU 不停，通过 monitor 命令直读内存
 * - Halt-Resume 模式（后备）: 暂停→采样→恢复
 */
class LiveWatchService(
    private val dataBuffer: DataBuffer,
    private val onDataCollected: () -> Unit
) {
    private val log = Logger.getInstance(LiveWatchService::class.java)

    /** 已解析的变量 → 地址/类型映射 */
    private val watchEntries = ConcurrentHashMap<String, WatchEntry>()

    /** 采集定时器 */
    private var timer: Timer? = null

    /** 当前 GDB Server 类型 */
    @Volatile
    var serverType: GdbServerType = GdbServerType.UNKNOWN
        private set

    /** 当前运行模式 */
    @Volatile
    var watchMode: WatchMode = WatchMode.MONITOR
        private set

    /** 是否正在运行 */
    val isRunning = AtomicBoolean(false)

    /** 最近一次错误信息 */
    @Volatile
    var lastError: String? = null
        private set

    /** 采样计数 */
    @Volatile
    var sampleCount = 0L
        private set

    // ─── 数据类 ───

    data class WatchEntry(
        val name: String,
        val address: Long,
        val dataType: DataType,
        val byteSize: Int
    )

    enum class DataType {
        INT8, UINT8, INT16, UINT16, INT32, UINT32, FLOAT, DOUBLE;

        val byteSize: Int
            get() = when (this) {
                INT8, UINT8 -> 1
                INT16, UINT16 -> 2
                INT32, UINT32, FLOAT -> 4
                DOUBLE -> 8
            }
    }

    enum class GdbServerType {
        OPENOCD, JLINK, UNKNOWN
    }

    enum class WatchMode {
        MONITOR, HALT_RESUME
    }

    // ─── 地址解析（需 MCU halted）───

    /**
     * 在 MCU 暂停时解析变量地址和类型，结果缓存
     */
    fun resolveVariable(session: XDebugSession, varName: String, callback: (WatchEntry?) -> Unit) {
        watchEntries[varName]?.let { callback(it); return }

        val cidrProcess = session.debugProcess as? CidrDebugProcess
        if (cidrProcess == null) {
            log.warn("Not a CIDR debug process, cannot resolve variable")
            callback(null)
            return
        }

        cidrProcess.postCommand(object : CidrDebugProcess.VoidDebuggerCommand {
            override fun run(driver: DebuggerDriver) {
                try {
                    val addrResult = driver.executeInterpreterCommand("print/x (unsigned long)&$varName").trim()
                    val address = parseAddressFromPrint(addrResult)
                    if (address == null) {
                        log.warn("Cannot resolve address for '$varName': $addrResult")
                        callback(null)
                        return
                    }

                    val typeResult = driver.executeInterpreterCommand("ptype $varName").trim()
                    val sizeResult = driver.executeInterpreterCommand("print (int)sizeof($varName)").trim()
                    val dataType = inferDataType(typeResult, sizeResult)

                    val entry = WatchEntry(varName, address, dataType, dataType.byteSize)
                    watchEntries[varName] = entry
                    log.info("Resolved '$varName' -> 0x${address.toString(16)}, type=$dataType, size=${dataType.byteSize}")
                    callback(entry)
                } catch (e: Exception) {
                    log.warn("Failed to resolve '$varName'", e)
                    callback(null)
                }
            }
        })
    }

    /**
     * 批量解析多个变量
     */
    fun resolveVariables(session: XDebugSession, varNames: List<String>, callback: (Int) -> Unit) {
        if (varNames.isEmpty()) { callback(0); return }

        val latch = CountDownLatch(varNames.size)
        var resolved = 0

        for (name in varNames) {
            resolveVariable(session, name) { entry ->
                if (entry != null) synchronized(this) { resolved++ }
                latch.countDown()
            }
        }

        Thread({
            latch.await(5, TimeUnit.SECONDS)
            callback(resolved)
        }, "LiveWatch-Resolve").start()
    }

    // ─── 启动/停止 ───

    fun startLiveWatch(session: XDebugSession, frequencyHz: Int) {
        if (isRunning.getAndSet(true)) return
        lastError = null
        sampleCount = 0

        val cidrProcess = session.debugProcess as? CidrDebugProcess
        if (cidrProcess == null) {
            lastError = "Not a CIDR debug process"
            isRunning.set(false)
            return
        }

        detectServerType(cidrProcess) {
            val intervalMs = (1000.0 / frequencyHz).toLong().coerceAtLeast(10)
            log.info("Starting Live Watch: ${frequencyHz}Hz (${intervalMs}ms), server=$serverType, mode=$watchMode")

            timer = Timer("LiveWatch-Sampler", true).apply {
                scheduleAtFixedRate(object : TimerTask() {
                    override fun run() {
                        if (!isRunning.get()) { cancel(); return }
                        sample(cidrProcess)
                    }
                }, intervalMs, intervalMs)
            }
        }
    }

    fun stopLiveWatch() {
        if (!isRunning.getAndSet(false)) return
        timer?.cancel()
        timer = null
        log.info("Live Watch stopped, $sampleCount samples collected")
    }

    fun clearResolvedEntries() {
        watchEntries.clear()
    }

    fun getResolvedEntries(): Map<String, WatchEntry> = watchEntries.toMap()

    // ─── 采样核心 ───

    private fun sample(cidrProcess: CidrDebugProcess) {
        val entries = watchEntries.values.toList()
        if (entries.isEmpty()) return

        // 使用 canExecuteWhileRunning = true 的命令，允许在 MCU running 时执行
        cidrProcess.postCommand(object : CidrDebugProcess.VoidDebuggerCommand {
            override fun canExecuteWhileRunning(): Boolean = true

            override fun run(driver: DebuggerDriver) {
                try {
                    val values = when (watchMode) {
                        WatchMode.MONITOR -> readViaMonitor(driver, entries)
                        WatchMode.HALT_RESUME -> readViaHaltResume(driver, entries)
                    }

                    if (values.isNotEmpty()) {
                        for (name in values.keys) {
                            if (dataBuffer.getChannels().none { it.name == name }) {
                                dataBuffer.addChannel(name)
                            }
                        }
                        dataBuffer.pushAll(values)
                        sampleCount++
                        onDataCollected()
                        lastError = null
                    }
                } catch (e: Exception) {
                    lastError = e.message
                    log.debug("Sample failed: ${e.message}")
                }
            }
        })
    }

    // ─── Monitor 模式：MCU 不停 ───

    private fun readViaMonitor(driver: DebuggerDriver, entries: List<WatchEntry>): Map<String, Double> {
        val result = mutableMapOf<String, Double>()
        for (entry in entries) {
            try {
                val cmd = buildMonitorReadCommand(entry)
                val response = driver.executeInterpreterCommand(cmd).trim()
                val rawValue = parseMonitorResponse(response, entry)
                if (rawValue != null) {
                    result[entry.name] = interpretBytes(rawValue, entry.dataType)
                }
            } catch (e: Exception) {
                log.debug("Monitor read failed for '${entry.name}': ${e.message}")
            }
        }
        return result
    }

    private fun buildMonitorReadCommand(entry: WatchEntry): String {
        val addr = "0x${entry.address.toString(16)}"
        return when (serverType) {
            GdbServerType.OPENOCD -> {
                when (entry.dataType) {
                    DataType.INT8, DataType.UINT8 -> "monitor mdb $addr 1"
                    DataType.INT16, DataType.UINT16 -> "monitor mdh $addr 1"
                    DataType.INT32, DataType.UINT32, DataType.FLOAT -> "monitor mdw $addr 1"
                    DataType.DOUBLE -> "monitor mdw $addr 2"
                }
            }
            GdbServerType.JLINK -> {
                when (entry.dataType) {
                    DataType.INT8, DataType.UINT8 -> "monitor memU8 $addr 1"
                    DataType.INT16, DataType.UINT16 -> "monitor memU16 $addr 1"
                    DataType.INT32, DataType.UINT32, DataType.FLOAT -> "monitor memU32 $addr 1"
                    DataType.DOUBLE -> "monitor memU32 $addr 2"
                }
            }
            GdbServerType.UNKNOWN -> {
                when (entry.dataType) {
                    DataType.INT8, DataType.UINT8 -> "monitor mdb $addr 1"
                    DataType.INT16, DataType.UINT16 -> "monitor mdh $addr 1"
                    DataType.INT32, DataType.UINT32, DataType.FLOAT -> "monitor mdw $addr 1"
                    DataType.DOUBLE -> "monitor mdw $addr 2"
                }
            }
        }
    }

    /**
     * 解析 monitor 命令返回值
     *
     * OpenOCD 格式: "0x20000100: 3f800000" 或 "0x20000100: 3f800000 40000000"
     * J-Link 格式:  "= 3F800000" 或 "0x20000100 = 3F800000"
     */
    fun parseMonitorResponse(response: String, entry: WatchEntry): Long? {
        if (response.isBlank()) return null

        // OpenOCD: "0xADDR: HEXVALUE [HEXVALUE2]"
        val openocdMatch = Regex("""0x[0-9a-fA-F]+:\s+([0-9a-fA-F]+)(?:\s+([0-9a-fA-F]+))?""")
            .find(response)
        if (openocdMatch != null) {
            val word1 = java.lang.Long.parseUnsignedLong(openocdMatch.groupValues[1], 16)
            if (entry.dataType == DataType.DOUBLE && openocdMatch.groupValues[2].isNotEmpty()) {
                val word2 = java.lang.Long.parseUnsignedLong(openocdMatch.groupValues[2], 16)
                return (word2 shl 32) or word1
            }
            return word1
        }

        // J-Link: "= HEXVALUE"
        val jlinkMatch = Regex("""=\s*([0-9a-fA-F]+)""").find(response)
        if (jlinkMatch != null) {
            return java.lang.Long.parseUnsignedLong(jlinkMatch.groupValues[1], 16)
        }

        // 兜底
        val hexMatch = Regex("""([0-9a-fA-F]{2,16})""").findAll(response).lastOrNull()
        return hexMatch?.let {
            try { java.lang.Long.parseUnsignedLong(it.groupValues[1], 16) } catch (_: Exception) { null }
        }
    }

    // ─── Halt-Resume 模式 ───

    private fun readViaHaltResume(driver: DebuggerDriver, entries: List<WatchEntry>): Map<String, Double> {
        val result = mutableMapOf<String, Double>()
        try {
            driver.executeInterpreterCommand("monitor halt")
            for (entry in entries) {
                try {
                    val cmd = buildMonitorReadCommand(entry)
                    val response = driver.executeInterpreterCommand(cmd).trim()
                    val rawValue = parseMonitorResponse(response, entry)
                    if (rawValue != null) {
                        result[entry.name] = interpretBytes(rawValue, entry.dataType)
                    }
                } catch (e: Exception) {
                    log.debug("Halt-resume read failed for '${entry.name}': ${e.message}")
                }
            }
            driver.executeInterpreterCommand("monitor resume")
        } catch (e: Exception) {
            log.warn("Halt-resume cycle failed", e)
            try { driver.executeInterpreterCommand("monitor resume") } catch (_: Exception) {}
        }
        return result
    }

    // ─── 字节解释 ───

    fun interpretBytes(rawValue: Long, dataType: DataType): Double {
        return when (dataType) {
            DataType.UINT8 -> (rawValue and 0xFF).toDouble()
            DataType.INT8 -> (rawValue and 0xFF).toByte().toDouble()
            DataType.UINT16 -> (rawValue and 0xFFFF).toDouble()
            DataType.INT16 -> (rawValue and 0xFFFF).toShort().toDouble()
            DataType.UINT32 -> (rawValue and 0xFFFFFFFFL).toDouble()
            DataType.INT32 -> (rawValue and 0xFFFFFFFFL).toInt().toDouble()
            DataType.FLOAT -> Float.fromBits((rawValue and 0xFFFFFFFFL).toInt()).toDouble()
            DataType.DOUBLE -> Double.fromBits(rawValue)
        }
    }

    // ─── GDB Server 探测 ───

    private fun detectServerType(cidrProcess: CidrDebugProcess, onDone: () -> Unit) {
        cidrProcess.postCommand(object : CidrDebugProcess.VoidDebuggerCommand {
            override fun canExecuteWhileRunning(): Boolean = true

            override fun run(driver: DebuggerDriver) {
                // 尝试 OpenOCD 特征命令
                try {
                    val versionResult = driver.executeInterpreterCommand("monitor version").trim()
                    if (versionResult.contains("Open On-Chip Debugger", ignoreCase = true)) {
                        serverType = GdbServerType.OPENOCD
                        watchMode = WatchMode.MONITOR
                        log.info("Detected OpenOCD server")
                        onDone()
                        return
                    }
                } catch (_: Exception) {}

                // 尝试 J-Link 特征命令
                try {
                    val jlinkResult = driver.executeInterpreterCommand("monitor showinfo").trim()
                    if (jlinkResult.contains("J-Link", ignoreCase = true) ||
                        jlinkResult.contains("SEGGER", ignoreCase = true)) {
                        serverType = GdbServerType.JLINK
                        watchMode = WatchMode.MONITOR
                        log.info("Detected J-Link GDB Server")
                        onDone()
                        return
                    }
                } catch (_: Exception) {}

                serverType = GdbServerType.UNKNOWN
                watchMode = WatchMode.MONITOR
                log.info("Unknown GDB server, defaulting to monitor mode")
                onDone()
            }
        })
    }

    // ─── 辅助解析 ───

    private fun parseAddressFromPrint(result: String): Long? {
        val hexMatch = Regex("""\$\d+\s*=\s*0x([0-9a-fA-F]+)""").find(result)
        if (hexMatch != null) {
            return java.lang.Long.parseUnsignedLong(hexMatch.groupValues[1], 16)
        }
        val decMatch = Regex("""\$\d+\s*=\s*(\d+)""").find(result)
        if (decMatch != null) {
            return decMatch.groupValues[1].toLongOrNull()
        }
        return null
    }

    private fun inferDataType(ptypeResult: String, sizeResult: String): DataType {
        val sizeMatch = Regex("""\$\d+\s*=\s*(\d+)""").find(sizeResult)
        val byteSize = sizeMatch?.groupValues?.get(1)?.toIntOrNull() ?: 4

        val typeStr = ptypeResult.lowercase()
        val isFloat = typeStr.contains("float")
        val isDouble = typeStr.contains("double")
        val isUnsigned = typeStr.contains("unsigned")

        return when {
            isDouble || byteSize == 8 -> DataType.DOUBLE
            isFloat -> DataType.FLOAT
            byteSize == 1 -> if (isUnsigned) DataType.UINT8 else DataType.INT8
            byteSize == 2 -> if (isUnsigned) DataType.UINT16 else DataType.INT16
            else -> if (isUnsigned) DataType.UINT32 else DataType.INT32
        }
    }
}
