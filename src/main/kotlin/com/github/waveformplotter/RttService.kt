package com.github.waveformplotter

import com.intellij.openapi.diagnostic.Logger
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * RTT 数据源：连接 RTT Server TCP 端口，读取文本行，解析 CSV 数值推送到 DataBuffer。
 *
 * 协议约定：
 *   固件端: SEGGER_RTT_printf(0, "%.4f,%.4f,%.4f\n", v1, v2, v3);
 *   插件端: 按行读取 → split(",") → parseDouble → dataBuffer.pushAll()
 *
 * 完全解耦：不依赖 Debug Session、ELF 解析、GDB。
 * 兼容任何 RTT Server（OpenOCD rtt server / J-Link RTT / pyOCD 等）。
 */
class RttService(
    private val dataBuffer: DataBuffer,
    private val onDataCollected: () -> Unit
) {
    private val log = Logger.getInstance(RttService::class.java)

    val isRunning = AtomicBoolean(false)
    var sampleCount: Long = 0
        private set
    var lastError: String? = null

    /** 上次成功找到 RTT 控制块的 RAM 区域，下次优先搜索 */
    var lastFoundRegion: Pair<String, String>? = null

    private var socket: Socket? = null
    private var reader: BufferedReader? = null
    private var readerThread: Thread? = null
    private var channelNames: List<String> = emptyList()
    private var lastCallbackNs: Long = 0

    // Cortex-M 常见 RAM 区域
    private val defaultRamRegions = listOf(
        "0x20000000" to "0x10000",   // 64KB — STM32F/G/L 通用 SRAM / H7 DTCM
        "0x24000000" to "0x18000",   // 96KB — STM32H7 AXI SRAM
        "0x20010000" to "0x10000",   // 64KB — 较大 SRAM 后段
        "0x30000000" to "0x10000",   // 64KB — STM32H7 SRAM1/2
    )

    // ── Telnet 命令通信 ──

    /** 发送命令，主动轮询等待响应，比固定 sleep 快得多 */
    private fun sendCmd(writer: PrintWriter, input: java.io.InputStream, cmd: String, timeoutMs: Int = 500): String {
        writer.println(cmd)
        val deadline = System.currentTimeMillis() + timeoutMs
        while (input.available() == 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(8)
        }
        Thread.sleep(50)  // 让完整多行响应到达
        return readAvailable(input)
    }

    private fun readAvailable(input: java.io.InputStream): String {
        val sb = StringBuilder()
        while (input.available() > 0) {
            sb.append(input.read().toChar())
        }
        return sb.toString()
    }

    // ── OpenOCD RTT 初始化 ──

    fun initOpenOcdRtt(telnetPort: Int, rttPort: Int, ramStart: String = "", ramSize: String = ""): Boolean {
        try {
            val s = Socket("localhost", telnetPort)
            s.soTimeout = 3000
            s.tcpNoDelay = true
            val writer = PrintWriter(s.getOutputStream(), true)
            val input = s.getInputStream()

            // 消耗欢迎信息
            Thread.sleep(80)
            while (input.available() > 0) input.read()

            // 停止已有的 RTT
            sendCmd(writer, input, "rtt stop", 150)

            // 构建搜索区域列表（优先级：用户指定 > 上次成功 > 默认列表）
            val regions = when {
                ramStart.isNotBlank() && ramSize.isNotBlank() ->
                    listOf(ramStart.trim() to ramSize.trim())
                else -> {
                    val list = mutableListOf<Pair<String, String>>()
                    // 上次成功的区域排最前
                    lastFoundRegion?.let { list.add(it) }
                    for (r in defaultRamRegions) {
                        if (r != lastFoundRegion) list.add(r)
                    }
                    list
                }
            }

            var found = false
            for ((start, size) in regions) {
                sendCmd(writer, input, "rtt setup $start $size \"SEGGER RTT\"", 150)

                val startResp = sendCmd(writer, input, "rtt start", 600)
                log.info("rtt start ($start/$size): $startResp")

                if (startResp.contains("not found", ignoreCase = true) ||
                    startResp.contains("Error", ignoreCase = true)) {
                    sendCmd(writer, input, "rtt stop", 150)
                    continue
                }

                // 检查通道数
                val channelsResp = sendCmd(writer, input, "rtt channels", 150)
                log.info("rtt channels ($start): $channelsResp")

                if (channelsResp.contains("up=0")) {
                    // 假阳性或 Init 还没跑，重试
                    log.info("RTT channels=0 at $start, retrying...")
                    sendCmd(writer, input, "rtt stop", 150)
                    Thread.sleep(300)

                    sendCmd(writer, input, "rtt start", 600)
                    val retryChannels = sendCmd(writer, input, "rtt channels", 150)
                    log.info("rtt channels retry ($start): $retryChannels")

                    if (retryChannels.contains("up=0")) {
                        sendCmd(writer, input, "rtt stop", 150)
                        continue
                    }
                }

                found = true
                lastFoundRegion = start to size
                log.info("RTT control block found in region $start/$size")
                break
            }

            if (!found) {
                lastError = "RTT control block not found in any RAM region"
                s.close()
                return false
            }

            // 启动 RTT TCP Server
            sendCmd(writer, input, "rtt server start $rttPort 0", 200)

            // 提高轮询频率
            sendCmd(writer, input, "rtt polling_interval 1", 100)

            s.close()
            log.info("OpenOCD RTT initialized, server=tcp:$rttPort")
            return true
        } catch (e: Exception) {
            lastError = "OpenOCD RTT init failed: ${e.message}"
            log.warn(lastError)
            return false
        }
    }

    fun stopOpenOcdRtt(telnetPort: Int) {
        try {
            val s = Socket("localhost", telnetPort)
            s.soTimeout = 1000
            s.tcpNoDelay = true
            val writer = PrintWriter(s.getOutputStream(), true)
            Thread.sleep(50)
            writer.println("rtt stop")
            Thread.sleep(50)
            s.close()
        } catch (_: Exception) {}
    }

    // ── RTT 数据读取 ──

    fun startRtt(host: String, port: Int, channelNames: List<String>) {
        if (isRunning.get()) return
        this.channelNames = channelNames
        sampleCount = 0
        lastError = null
        lastCallbackNs = 0

        try {
            val s = Socket(host, port)
            s.soTimeout = 2000
            s.tcpNoDelay = true
            socket = s
            reader = BufferedReader(InputStreamReader(s.getInputStream()), 8192)
        } catch (e: Exception) {
            lastError = "RTT connect failed: ${e.message}"
            log.warn(lastError)
            return
        }

        isRunning.set(true)

        readerThread = Thread({
            log.info("RTT reader started on $host:$port, channels=${channelNames}")
            try {
                while (isRunning.get()) {
                    val line = try {
                        reader?.readLine()
                    } catch (e: java.net.SocketTimeoutException) {
                        continue
                    }

                    if (line == null) {
                        lastError = "RTT connection closed"
                        break
                    }

                    parseLine(line)
                }
            } catch (e: Exception) {
                if (isRunning.get()) {
                    lastError = "RTT read error: ${e.message}"
                    log.warn(lastError)
                }
            } finally {
                isRunning.set(false)
                log.info("RTT reader stopped, $sampleCount samples collected")
            }
        }, "RTT-Reader").apply {
            isDaemon = true
            start()
        }
    }

    private fun parseLine(line: String) {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return

        val parts = trimmed.split(",")
        val values = mutableMapOf<String, Double>()

        for (i in parts.indices) {
            if (i >= channelNames.size) break
            val v = parts[i].trim().toDoubleOrNull()
            if (v != null) {
                values[channelNames[i]] = v
            }
        }

        if (values.isEmpty()) return

        // 确保通道存在
        for (name in values.keys) {
            if (dataBuffer.getChannels().none { it.name == name }) {
                dataBuffer.addChannel(name)
            }
        }

        dataBuffer.pushAll(values, System.nanoTime())
        sampleCount++

        // 节流回调：最多 60 次/秒，避免 1kHz+ 数据淹没 EDT
        val now = System.nanoTime()
        if (now - lastCallbackNs >= 16_000_000L) {  // 16ms = ~60fps
            lastCallbackNs = now
            onDataCollected()
        }
    }

    fun stopRtt() {
        if (!isRunning.compareAndSet(true, false)) return
        val t = readerThread
        val r = reader
        val s = socket
        readerThread = null
        reader = null
        socket = null
        try { r?.close() } catch (_: Exception) {}
        try { s?.close() } catch (_: Exception) {}
        t?.join(1000)  // 等待线程退出，避免孤儿线程
        log.info("RTT stopped, total samples: $sampleCount")
    }
}
