package com.github.waveformplotter

import com.intellij.openapi.diagnostic.Logger
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * ELF 符号解析器 — 从 .elf 文件直接解析变量地址和大小
 *
 * 使用 arm-none-eabi-nm 解析符号表，无需暂停 MCU。
 * 作为 GDB 地址解析的替代方案，支持 Live Watch 零暂停启动。
 *
 * 限制:
 *   - 仅支持全局/静态变量（符号表中有记录的）
 *   - 无法获取精确数据类型（只有大小），需要用户选择或启发式推断
 *   - 局部变量、寄存器变量不在符号表中
 */
class ElfSymbolResolver {

    private val log = Logger.getInstance(ElfSymbolResolver::class.java)

    /** 缓存: 符号名(demangled) → 符号信息 */
    private val symbolCache = ConcurrentHashMap<String, SymbolInfo>()

    /** 短名索引: 变量短名 → 完整符号名列表（用于函数内 static 变量模糊匹配） */
    private val shortNameIndex = ConcurrentHashMap<String, MutableList<String>>()

    /** 上次解析的 ELF 文件路径 */
    @Volatile
    var lastElfPath: String? = null
        private set

    /** 上次解析的 ELF 文件修改时间 */
    @Volatile
    private var lastElfModified: Long = 0

    data class SymbolInfo(
        val name: String,
        val address: Long,
        val size: Int,
        val section: String   // "B" = BSS, "D" = Data, "R" = Read-only, etc.
    )

    // ─── 符号解析 ───

    /**
     * 从 ELF 文件解析所有全局符号
     * @param elfPath .elf 文件路径
     * @return 是否成功
     */
    fun loadSymbols(elfPath: String): Boolean {
        val elfFile = File(elfPath)
        if (!elfFile.exists()) {
            log.warn("ELF file not found: $elfPath")
            return false
        }

        // 如果 ELF 文件没变，使用缓存
        if (elfPath == lastElfPath && elfFile.lastModified() == lastElfModified && symbolCache.isNotEmpty()) {
            log.info("Using cached symbols from $elfPath (${symbolCache.size} symbols)")
            return true
        }

        // 尝试找 arm-none-eabi-nm
        val nmPath = findNmTool() ?: run {
            log.warn("arm-none-eabi-nm not found in PATH")
            return false
        }

        return try {
            // -C: demangle C++ 名（函数内 static 变量会从 _ZZ...E8sawtooth 变成 func()::sawtooth）
            // 不用 -g（排除 static）和 --size-sort（丢弃无 size 符号）
            val process = ProcessBuilder(nmPath, "-C", "--print-size", elfPath)
                .redirectErrorStream(true)
                .start()

            val output = process.inputStream.bufferedReader().readText()
            val exited = process.waitFor(10, TimeUnit.SECONDS)
            if (!exited || process.exitValue() != 0) {
                log.warn("nm failed: exit=${if (exited) process.exitValue() else "timeout"}")
                return false
            }

            symbolCache.clear()
            shortNameIndex.clear()
            parseNmOutput(output)
            buildShortNameIndex()
            lastElfPath = elfPath
            lastElfModified = elfFile.lastModified()
            log.info("Loaded ${symbolCache.size} symbols from $elfPath")
            true
        } catch (e: Exception) {
            log.warn("Failed to run nm on $elfPath", e)
            false
        }
    }

    /**
     * 解析单个变量
     * @return WatchEntry，如果找不到返回 null
     */
    fun resolveVariable(varName: String): LiveWatchService.WatchEntry? {
        // 1. 精确匹配（全局变量、文件作用域 static）
        val symbol = symbolCache[varName]
        if (symbol != null) {
            val dataType = inferDataTypeFromSize(symbol.size)
            return LiveWatchService.WatchEntry(varName, symbol.address, dataType, dataType.byteSize)
        }

        // 2. 短名模糊匹配（函数内 static 变量）
        //    用户输入 "sawtooth" → 匹配 "chassis_task_proc()::sawtooth"
        val candidates = shortNameIndex[varName]
        if (candidates != null && candidates.isNotEmpty()) {
            val fullName = if (candidates.size == 1) {
                candidates[0]
            } else {
                // 多个同名变量（不同函数内），选第一个并警告
                log.warn("Multiple matches for '$varName': $candidates, using first")
                candidates[0]
            }
            val matched = symbolCache[fullName]
            if (matched != null) {
                val dataType = inferDataTypeFromSize(matched.size)
                log.info("Fuzzy matched '$varName' → '$fullName'")
                return LiveWatchService.WatchEntry(varName, matched.address, dataType, dataType.byteSize)
            }
        }

        // 3. 结构体成员等复杂表达式 → GDB 回退
        if (varName.contains('.') || varName.contains("->") || varName.contains('[')) {
            log.info("Complex expression '$varName' requires GDB resolution")
            return null
        }

        log.info("Symbol '$varName' not found in ELF (neither global nor function-local static)")
        return null
    }

    /**
     * 批量解析
     */
    fun resolveVariables(varNames: List<String>): Map<String, LiveWatchService.WatchEntry> {
        val result = mutableMapOf<String, LiveWatchService.WatchEntry>()
        for (name in varNames) {
            resolveVariable(name)?.let { result[name] = it }
        }
        return result
    }

    fun isLoaded(): Boolean = symbolCache.isNotEmpty()

    fun getSymbolCount(): Int = symbolCache.size

    fun clear() {
        symbolCache.clear()
        shortNameIndex.clear()
        lastElfPath = null
        lastElfModified = 0
    }

    // ─── nm 输出解析 ───

    /**
     * 解析 arm-none-eabi-nm --print-size 的输出
     *
     * 带 size: "20000100 00000004 B my_variable"
     *           address  size     type name
     *
     * 无 size: "20000100 B my_variable"
     *           address  type name
     */
    private fun parseNmOutput(output: String) {
        // 带 size 的行: addr size type name
        val withSize = Regex("""^([0-9a-fA-F]+)\s+([0-9a-fA-F]+)\s+([A-Za-z])\s+(\S+)$""", RegexOption.MULTILINE)
        // 不带 size 的行: addr type name
        val withoutSize = Regex("""^([0-9a-fA-F]+)\s+([A-Za-z])\s+(\S+)$""", RegexOption.MULTILINE)

        // 数据段符号类型（大小写均可）:
        // B/b=BSS, D/d=Data, G/g=Small data, S/s=Small BSS, R/r=Read-only, C=Common
        val dataSections = setOf("B", "b", "D", "d", "G", "g", "S", "s", "R", "r", "C")
        // 排除: T/t=代码, U=未定义, W/w=弱符号(无定义), N=调试, A=绝对地址

        for (match in withSize.findAll(output)) {
            val addr = java.lang.Long.parseUnsignedLong(match.groupValues[1], 16)
            val size = match.groupValues[2].toInt(16)
            val section = match.groupValues[3]
            val name = match.groupValues[4]

            if (section in dataSections && size > 0) {
                symbolCache[name] = SymbolInfo(name, addr, size, section)
            }
        }

        // 处理没有 size 信息的符号（假设 4 字节）
        for (match in withoutSize.findAll(output)) {
            val addr = java.lang.Long.parseUnsignedLong(match.groupValues[1], 16)
            val section = match.groupValues[2]
            val name = match.groupValues[3]

            if (section in dataSections) {
                if (!symbolCache.containsKey(name)) {
                    symbolCache[name] = SymbolInfo(name, addr, 4, section)
                }
            }
        }
    }

    /**
     * 构建短名索引
     * "chassis_task_proc()::sawtooth" → shortName "sawtooth"
     * "main()::counter" → shortName "counter"
     * "chassis_vx" → shortName "chassis_vx"（直接匹配，不需要索引）
     */
    private fun buildShortNameIndex() {
        for (fullName in symbolCache.keys) {
            // 提取 :: 后面的短名（C++ 函数内 static）
            val colonIdx = fullName.lastIndexOf("::")
            if (colonIdx >= 0) {
                val shortName = fullName.substring(colonIdx + 2)
                shortNameIndex.getOrPut(shortName) { mutableListOf() }.add(fullName)
            }
        }
        if (shortNameIndex.isNotEmpty()) {
            log.info("Built short name index: ${shortNameIndex.size} entries (function-local statics)")
        }
    }

    // ─── 辅助方法 ───

    /**
     * 根据变量大小推断数据类型
     * 无法区分 int32 和 float（都是 4 字节），默认选 FLOAT（嵌入式场景更常见）
     */
    private fun inferDataTypeFromSize(size: Int): LiveWatchService.DataType {
        return when (size) {
            1 -> LiveWatchService.DataType.UINT8
            2 -> LiveWatchService.DataType.INT16
            4 -> LiveWatchService.DataType.FLOAT  // 嵌入式场景 4 字节多数是 float（PID 输出等）
            8 -> LiveWatchService.DataType.DOUBLE
            else -> LiveWatchService.DataType.INT32  // 其他大小默认 int32
        }
    }

    /**
     * 查找 arm-none-eabi-nm 工具
     */
    private fun findNmTool(): String? {
        val names = listOf("arm-none-eabi-nm", "arm-none-eabi-nm.exe")

        // 1. 直接在 PATH 中查找
        for (name in names) {
            try {
                val proc = ProcessBuilder(name, "--version")
                    .redirectErrorStream(true)
                    .start()
                proc.inputStream.bufferedReader().readText()
                if (proc.waitFor(3, TimeUnit.SECONDS) && proc.exitValue() == 0) {
                    return name
                }
            } catch (_: Exception) {}
        }

        // 2. 常见安装路径
        val commonPaths = listOf(
            "/usr/bin/arm-none-eabi-nm",
            "/usr/local/bin/arm-none-eabi-nm",
            "C:\\Program Files (x86)\\GNU Arm Embedded Toolchain\\bin\\arm-none-eabi-nm.exe",
            "C:\\Program Files\\GNU Arm Embedded Toolchain\\bin\\arm-none-eabi-nm.exe"
        )
        for (path in commonPaths) {
            if (File(path).exists()) return path
        }

        return null
    }
}
