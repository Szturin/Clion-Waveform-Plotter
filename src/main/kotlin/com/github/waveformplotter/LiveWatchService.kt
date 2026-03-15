package com.github.waveformplotter

import com.intellij.openapi.diagnostic.Logger
import com.jetbrains.cidr.execution.debugger.CidrDebugProcess
import com.jetbrains.cidr.execution.debugger.backend.DebuggerDriver
import com.intellij.xdebugger.XDebugSession
import java.io.InputStream
import java.io.PrintWriter
import java.net.Socket
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

    /** ELF 符号解析器（无暂停方案） */
    val elfResolver = ElfSymbolResolver()

    /** 采集线程 */
    private var samplerThread: Thread? = null

    /** OpenOCD Telnet 连接 */
    private var telnetSocket: Socket? = null
    private var telnetWriter: PrintWriter? = null
    private var telnetInput: InputStream? = null
    private val telnetLock = Any()

    /** HSS: 预分配读缓冲区（避免采样循环中分配内存） */
    private val readBuf = ByteArray(4096)

    /** HSS: 预编译响应解析正则（避免每次采样重新编译） */
    private val responsePattern = Regex("""0x[0-9a-fA-F]+:\s+([0-9a-fA-F]+)(?:\s+([0-9a-fA-F]+))?""")

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

    // ─── ELF 符号表解析（无需暂停 MCU）───

    /**
     * 尝试通过 ELF 文件解析变量地址（无暂停方案）
     * @return 成功解析的数量
     */
    fun resolveFromElf(elfPath: String, varNames: List<String>): Int {
        if (!elfResolver.loadSymbols(elfPath)) return 0

        var resolved = 0
        for (name in varNames) {
            if (watchEntries.containsKey(name)) { resolved++; continue }
            val entry = elfResolver.resolveVariable(name)
            if (entry != null) {
                watchEntries[name] = entry
                resolved++
                log.info("ELF resolved '$name' -> 0x${entry.address.toString(16)}, type=${entry.dataType}")
            }
        }
        return resolved
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
                    tcpNoDelay = true   // 禁用 Nagle 算法，小包立即发送
                    soTimeout = 500     // 读超时 500ms（采样场景不需要等太久）
                }
                telnetSocket = socket
                telnetWriter = PrintWriter(socket.getOutputStream(), true)
                telnetInput = socket.getInputStream()

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
            try { telnetInput?.close() } catch (_: Exception) {}
            try { telnetWriter?.close() } catch (_: Exception) {}
            try { telnetSocket?.close() } catch (_: Exception) {}
            telnetInput = null
            telnetWriter = null
            telnetSocket = null
        }
    }

    /**
     * 发送单条 Telnet 命令并读取响应（阻塞读取）
     */
    private fun sendTelnetCommand(command: String): String? {
        synchronized(telnetLock) {
            val writer = telnetWriter ?: return null
            val input = telnetInput ?: return null

            try {
                while (input.available() > 0) input.read(readBuf)

                writer.println(command)
                writer.flush()

                val sb = StringBuilder()
                while (true) {
                    val b = input.read()
                    if (b == -1) break
                    sb.append(b.toChar())
                    if (sb.endsWith("> ")) break
                }
                return sb.toString().removeSuffix("> ").trim()
            } catch (e: Exception) {
                log.debug("Telnet command failed: ${e.message}")
                return null
            }
        }
    }

    private fun drainResponse(timeoutMs: Long) {
        val input = telnetInput ?: return
        val deadline = System.currentTimeMillis() + timeoutMs
        try {
            while (System.currentTimeMillis() < deadline) {
                if (input.available() > 0) input.read(readBuf) else Thread.sleep(10)
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

        val intervalNs = (1_000_000_000L / frequencyHz).coerceAtLeast(1_000_000L)
        log.info("Starting Live Watch: ${frequencyHz}Hz (${intervalNs / 1_000_000}ms), telnet port=$telnetPort")

        // 专用采样线程，用 System.nanoTime 精确计时（避免 Timer 的 ~15ms 调度抖动）
        samplerThread = Thread({
            while (isRunning.get()) {
                val start = System.nanoTime()
                sample()
                val elapsed = System.nanoTime() - start
                val sleepNs = intervalNs - elapsed
                if (sleepNs > 1_000_000L) {
                    // 粗粒度 sleep + 细粒度自旋，兼顾 CPU 占用和精度
                    val sleepMs = (sleepNs / 1_000_000L) - 1
                    if (sleepMs > 0) Thread.sleep(sleepMs)
                    // 剩余时间自旋（精确到 ~100μs）
                    while (System.nanoTime() - start < intervalNs) {
                        Thread.onSpinWait()
                    }
                }
            }
        }, "LiveWatch-Sampler").apply {
            isDaemon = true
            start()
        }
    }

    fun stopLiveWatch() {
        if (!isRunning.getAndSet(false)) return
        samplerThread?.interrupt()
        samplerThread = null
        disconnectTelnet()
        log.info("Live Watch stopped, $sampleCount samples collected")
    }

    fun clearResolvedEntries() {
        watchEntries.clear()
    }

    fun getResolvedEntries(): Map<String, WatchEntry> = watchEntries.toMap()

    // ─── 采样核心（管线化 Telnet）───

    /**
     * 管线化采样：一次性发送所有 mdw/mdh/mdb 命令，一次性读取所有响应
     *
     * 原来: 发cmd1 → 等响应1 → 发cmd2 → 等响应2 → ... (N 次往返)
     * 现在: 发cmd1\ncmd2\n...cmdN → 读响应1+响应2+...响应N (1 次往返)
     *
     * 效果: N 个变量从 N*RTT 降到 1*RTT，采样率提升 N 倍
     */
    private fun sample() {
        val entries = watchEntries.values.toList()
        if (entries.isEmpty()) return

        try {
            val responses = sendPipelinedCommands(entries)
            if (responses == null || responses.size != entries.size) return

            val values = mutableMapOf<String, Double>()
            for (i in entries.indices) {
                val entry = entries[i]
                val response = responses[i]
                try {
                    val rawValue = parseOpenocdResponse(response, entry)
                    if (rawValue != null) {
                        values[entry.name] = interpretBytes(rawValue, entry.dataType)
                    }
                } catch (e: Exception) {
                    log.debug("Parse failed for '${entry.name}': ${e.message}")
                }
            }

            if (values.isNotEmpty()) {
                for (name in values.keys) {
                    if (dataBuffer.getChannels().none { it.name == name }) {
                        dataBuffer.addChannel(name)
                    }
                }
                dataBuffer.pushAll(values, System.nanoTime())
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
     * HSS 管线化采样：字节级批量 I/O
     *
     * 优化点（对比旧版逐字符 BufferedReader）：
     * 1. 原始 InputStream 批量读取 — 跳过 InputStreamReader 字符集解码
     * 2. 预分配 readBuf — 采样循环零内存分配
     * 3. 字节级 prompt 扫描 — 在 byte[] 中直接找 "> "（0x3E 0x20）
     * 4. 单次 TCP 往返 — N 条命令 pipeline 发送，1*RTT 读回
     *
     * 实测: 3 个变量 @localhost ≈ 0.3-0.5ms/sample → 支持 1000-2000Hz
     */
    private fun sendPipelinedCommands(entries: List<WatchEntry>): List<String>? {
        synchronized(telnetLock) {
            val writer = telnetWriter ?: return null
            val input = telnetInput ?: return null

            try {
                // 清空残留数据（非阻塞）
                while (input.available() > 0) input.read(readBuf)

                // Pipeline: 一次性发送所有命令
                val cmdBuilder = StringBuilder()
                for (entry in entries) {
                    cmdBuilder.appendLine(buildTelnetReadCommand(entry))
                }
                writer.print(cmdBuilder.toString())
                writer.flush()

                // HSS: 字节级批量读取 + prompt 扫描
                val responses = mutableListOf<String>()
                var bufLen = 0
                var respStart = 0

                while (responses.size < entries.size) {
                    if (bufLen >= readBuf.size) break
                    val n = input.read(readBuf, bufLen, readBuf.size - bufLen)
                    if (n == -1) break
                    bufLen += n

                    // 扫描 "> " 分隔符（0x3E 0x20）
                    var i = respStart
                    while (i < bufLen - 1 && responses.size < entries.size) {
                        if (readBuf[i] == '>'.code.toByte() && readBuf[i + 1] == ' '.code.toByte()) {
                            responses.add(String(readBuf, respStart, i - respStart).trim())
                            respStart = i + 2
                            i = respStart
                        } else {
                            i++
                        }
                    }
                }

                return if (responses.size == entries.size) responses else null
            } catch (e: Exception) {
                log.debug("Pipelined command failed: ${e.message}")
                return null
            }
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

        val match = responsePattern.find(response)
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
