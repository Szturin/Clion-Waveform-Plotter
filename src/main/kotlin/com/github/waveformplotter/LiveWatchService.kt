package com.github.waveformplotter

import com.intellij.openapi.diagnostic.Logger
import com.jetbrains.cidr.execution.debugger.CidrDebugProcess
import com.jetbrains.cidr.execution.debugger.backend.DebuggerDriver
import com.intellij.xdebugger.XDebugSession
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.Socket
import java.util.Timer
import java.util.TimerTask
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Live Watch 实时内存监控服务
 *
 * 架构:
 *   地址解析: GDB (MCU halted 时) → print &var, ptype, sizeof
 *   实时采集: TCP 直连 OpenOCD Telnet 端口 (默认 4444) → mdw/mdh/mdb
 *
 * 为什么不走 GDB:
 *   GDB 在 target running 时阻塞所有命令（all-stop 模式），
 *   OpenOCD 不支持 non-stop 模式，所以 monitor 命令无法在 MCU 运行时发送。
 *   OpenOCD Telnet 端口独立于 GDB，target running 时也能执行命令。
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

    /** OpenOCD Telnet 连接 */
    private var telnetSocket: Socket? = null
    private var telnetWriter: PrintWriter? = null
    private var telnetReader: BufferedReader? = null
    private val telnetLock = Any()

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

    // ─── 地址解析（通过 GDB，需 MCU halted）───

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

    // ─── Telnet 连接管理 ───

    private fun connectTelnet(host: String, port: Int): Boolean {
        synchronized(telnetLock) {
            disconnectTelnet()
            try {
                val socket = Socket(host, port).apply {
                    soTimeout = 2000  // 读超时 2s
                }
                telnetSocket = socket
                telnetWriter = PrintWriter(socket.getOutputStream(), true)
                telnetReader = BufferedReader(InputStreamReader(socket.getInputStream()))

                // 读取 OpenOCD 的欢迎信息（消耗掉）
                drainResponse(500)

                log.info("Connected to OpenOCD Telnet at $host:$port")
                return true
            } catch (e: Exception) {
                log.warn("Failed to connect to OpenOCD Telnet at $host:$port", e)
                lastError = "Cannot connect to OpenOCD Telnet port $port"
                disconnectTelnet()
                return false
            }
        }
    }

    private fun disconnectTelnet() {
        synchronized(telnetLock) {
            try { telnetReader?.close() } catch (_: Exception) {}
            try { telnetWriter?.close() } catch (_: Exception) {}
            try { telnetSocket?.close() } catch (_: Exception) {}
            telnetReader = null
            telnetWriter = null
            telnetSocket = null
        }
    }

    /**
     * 发送 Telnet 命令并读取响应
     */
    private fun sendTelnetCommand(command: String): String? {
        synchronized(telnetLock) {
            val writer = telnetWriter ?: return null
            val reader = telnetReader ?: return null

            try {
                // 清空残留数据
                while (reader.ready()) { reader.read() }

                writer.println(command)
                writer.flush()

                // 读取响应（等待 OpenOCD 返回，以 ">" prompt 结束）
                val sb = StringBuilder()
                val deadline = System.currentTimeMillis() + 1000
                while (System.currentTimeMillis() < deadline) {
                    if (reader.ready()) {
                        val ch = reader.read()
                        if (ch == -1) break
                        sb.append(ch.toChar())
                        // OpenOCD telnet prompt 是 "> "
                        if (sb.endsWith("> ")) break
                    } else {
                        Thread.sleep(1)
                    }
                }
                return sb.toString().removeSuffix("> ").trim()
            } catch (e: Exception) {
                log.debug("Telnet command failed: ${e.message}")
                return null
            }
        }
    }

    private fun drainResponse(timeoutMs: Long) {
        val reader = telnetReader ?: return
        val deadline = System.currentTimeMillis() + timeoutMs
        try {
            while (System.currentTimeMillis() < deadline) {
                if (reader.ready()) { reader.read() } else { Thread.sleep(10) }
            }
        } catch (_: Exception) {}
    }

    // ─── 启动/停止 ───

    fun startLiveWatch(telnetPort: Int, frequencyHz: Int) {
        if (isRunning.getAndSet(true)) return
        lastError = null
        sampleCount = 0

        if (!connectTelnet("localhost", telnetPort)) {
            isRunning.set(false)
            return
        }

        val intervalMs = (1000.0 / frequencyHz).toLong().coerceAtLeast(10)
        log.info("Starting Live Watch: ${frequencyHz}Hz (${intervalMs}ms), telnet port=$telnetPort")

        timer = Timer("LiveWatch-Sampler", true).apply {
            scheduleAtFixedRate(object : TimerTask() {
                override fun run() {
                    if (!isRunning.get()) { cancel(); return }
                    sample()
                }
            }, intervalMs, intervalMs)
        }
    }

    fun stopLiveWatch() {
        if (!isRunning.getAndSet(false)) return
        timer?.cancel()
        timer = null
        disconnectTelnet()
        log.info("Live Watch stopped, $sampleCount samples collected")
    }

    fun clearResolvedEntries() {
        watchEntries.clear()
    }

    fun getResolvedEntries(): Map<String, WatchEntry> = watchEntries.toMap()

    // ─── 采样核心（通过 Telnet）───

    private fun sample() {
        val entries = watchEntries.values.toList()
        if (entries.isEmpty()) return

        try {
            val values = mutableMapOf<String, Double>()
            for (entry in entries) {
                try {
                    val cmd = buildTelnetReadCommand(entry)
                    val response = sendTelnetCommand(cmd)
                    if (response != null) {
                        val rawValue = parseOpenocdResponse(response, entry)
                        if (rawValue != null) {
                            values[entry.name] = interpretBytes(rawValue, entry.dataType)
                        }
                    }
                } catch (e: Exception) {
                    log.debug("Read failed for '${entry.name}': ${e.message}")
                }
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

    /**
     * 构建 OpenOCD Telnet 命令（不需要 "monitor" 前缀）
     */
    private fun buildTelnetReadCommand(entry: WatchEntry): String {
        val addr = "0x${entry.address.toString(16)}"
        return when (entry.dataType) {
            DataType.INT8, DataType.UINT8 -> "mdb $addr 1"
            DataType.INT16, DataType.UINT16 -> "mdh $addr 1"
            DataType.INT32, DataType.UINT32, DataType.FLOAT -> "mdw $addr 1"
            DataType.DOUBLE -> "mdw $addr 2"
        }
    }

    /**
     * 解析 OpenOCD Telnet 响应
     *
     * 格式: "0x20000100: 3f800000" 或 "0x20000100: 3f800000 40000000"
     */
    fun parseOpenocdResponse(response: String, entry: WatchEntry): Long? {
        if (response.isBlank()) return null

        val match = Regex("""0x[0-9a-fA-F]+:\s+([0-9a-fA-F]+)(?:\s+([0-9a-fA-F]+))?""")
            .find(response)
        if (match != null) {
            val word1 = java.lang.Long.parseUnsignedLong(match.groupValues[1], 16)
            if (entry.dataType == DataType.DOUBLE && match.groupValues[2].isNotEmpty()) {
                val word2 = java.lang.Long.parseUnsignedLong(match.groupValues[2], 16)
                // 小端序: word1 = 低 32 位, word2 = 高 32 位
                return (word2 shl 32) or word1
            }
            return word1
        }

        return null
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
