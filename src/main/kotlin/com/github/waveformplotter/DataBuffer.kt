package com.github.waveformplotter

import java.awt.Color

/**
 * 单通道数据: 变量名 + 环形缓冲区 + 颜色
 */
class ChannelData(
    val name: String,
    val color: Color,
    private val capacity: Int = DEFAULT_CAPACITY
) {
    private val data = DoubleArray(capacity)
    private var head = 0   // 下一个写入位置
    var size = 0           // 当前有效数据量
        private set

    fun push(value: Double) {
        data[head] = value
        head = (head + 1) % capacity
        if (size < capacity) size++
    }

    /** 获取第 i 个数据点（0 = 最旧） */
    fun get(i: Int): Double {
        if (i < 0 || i >= size) return 0.0
        val idx = if (size < capacity) i else (head + i) % capacity
        return data[idx]
    }

    fun clear() {
        head = 0
        size = 0
    }

    companion object {
        const val DEFAULT_CAPACITY = 10000
    }
}

/** 多通道数据缓冲管理器 */
class DataBuffer {
    private val channels = mutableListOf<ChannelData>()
    private val lock = Any()

    /** 共享时间戳环形缓冲区（纳秒精度，所有通道同步采样） */
    private val timestamps = LongArray(ChannelData.DEFAULT_CAPACITY)
    private var tsHead = 0
    var tsSize = 0
        private set

    /** 首个样本的时间戳（纳秒），作为 t=0 基准 */
    @Volatile
    var baseTimestampNs = 0L
        private set

    /** 数据版本号，每次 pushAll/clearAll 递增，用于 FFT 缓存失效检测 */
    @Volatile
    var version = 0L
        private set

    val channelCount: Int get() = synchronized(lock) { channels.size }

    fun addChannel(name: String): ChannelData? {
        synchronized(lock) {
            if (channels.size >= MAX_CHANNELS) return null
            if (channels.any { it.name == name }) return null
            val color = COLORS[channels.size % COLORS.size]
            val ch = ChannelData(name, color)
            channels.add(ch)
            return ch
        }
    }

    fun removeChannel(name: String) {
        synchronized(lock) { channels.removeAll { it.name == name } }
    }

    fun getChannels(): List<ChannelData> = synchronized(lock) { channels.toList() }

    fun pushAll(values: Map<String, Double>, timestampNs: Long = System.nanoTime()) {
        synchronized(lock) {
            if (tsSize == 0) baseTimestampNs = timestampNs
            timestamps[tsHead] = timestampNs
            tsHead = (tsHead + 1) % timestamps.size
            if (tsSize < timestamps.size) tsSize++
            for (ch in channels) {
                val v = values[ch.name]
                if (v != null) ch.push(v)
            }
            version++
        }
    }

    /** 获取第 i 个时间戳（0 = 最旧），返回纳秒 */
    fun getTimestamp(i: Int): Long {
        if (i < 0 || i >= tsSize) return 0L
        val idx = if (tsSize < timestamps.size) i else (tsHead + i) % timestamps.size
        return timestamps[idx]
    }

    /** 获取第 i 个样本相对 t=0 的时间（秒） */
    fun getTimeSeconds(i: Int): Double {
        return (getTimestamp(i) - baseTimestampNs) / 1_000_000_000.0
    }

    fun clearAll() {
        synchronized(lock) {
            channels.forEach { it.clear() }
            tsHead = 0
            tsSize = 0
            baseTimestampNs = 0L
            version++
        }
    }

    companion object {
        const val MAX_CHANNELS = 8
        val COLORS = arrayOf(
            Color(0x4FC3F7), // 浅蓝
            Color(0xEF5350), // 红
            Color(0x66BB6A), // 绿
            Color(0xFFA726), // 橙
            Color(0xAB47BC), // 紫
            Color(0x26C6DA), // 青
            Color(0xFFEE58), // 黄
            Color(0xEC407A), // 粉
        )
    }
}
