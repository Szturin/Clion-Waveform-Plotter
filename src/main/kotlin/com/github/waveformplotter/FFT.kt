package com.github.waveformplotter

import kotlin.math.*

/**
 * Cooley-Tukey radix-2 FFT
 * - Hanning 窗函数（抑制频谱泄漏）
 * - 自动补零到 2 的幂
 * - 输出单边幅度谱 (dB)
 * - 工作数组复用，减少 GC 压力
 */
object FFT {

    // 窗函数缓存（按原始信号长度缓存）
    private var cachedWindowSize = 0
    private var cachedWindow = DoubleArray(0)

    // 工作数组复用（按 FFT 点数缓存）
    private var workN = 0
    private var workRe = DoubleArray(0)
    private var workIm = DoubleArray(0)

    /**
     * 对实数信号做 FFT，返回单边幅度谱 (dB)，长度 = N/2
     * 结果写入 output（如果长度匹配则复用，否则新建）
     */
    fun magnitudeSpectrum(input: DoubleArray, output: DoubleArray? = null): DoubleArray {
        val n = nextPowerOf2(input.size)
        val half = n / 2

        // 复用或分配工作数组
        if (workN != n) {
            workN = n
            workRe = DoubleArray(n)
            workIm = DoubleArray(n)
        }
        val re = workRe
        val im = workIm

        // 获取/缓存窗函数
        val window = getWindow(input.size)

        // 加 Hanning 窗 + 补零
        for (i in input.indices) {
            re[i] = input[i] * window[i]
        }
        for (i in input.size until n) {
            re[i] = 0.0
        }
        im.fill(0.0)

        fft(re, im)

        // 单边幅度谱 (dB)
        val mag = if (output != null && output.size == half) output else DoubleArray(half)
        val invN = 2.0 / n
        for (i in 0 until half) {
            val amp = sqrt(re[i] * re[i] + im[i] * im[i]) * invN
            mag[i] = if (amp > 1e-12) 20.0 * log10(amp) else -240.0
        }
        return mag
    }

    /** 返回 FFT 使用的点数（补零后的 2 的幂） */
    fun fftSize(inputSize: Int): Int = nextPowerOf2(inputSize)

    /** 获取指定大小的 Hanning 窗（缓存） */
    private fun getWindow(size: Int): DoubleArray {
        if (cachedWindowSize == size) return cachedWindow
        cachedWindowSize = size
        cachedWindow = DoubleArray(size) { i ->
            0.5 * (1.0 - cos(2.0 * PI * i / (size - 1)))
        }
        return cachedWindow
    }

    /** 向上取最近的 2 的幂 */
    private fun nextPowerOf2(n: Int): Int {
        var v = 1
        while (v < n) v = v shl 1
        return v
    }

    /** in-place Cooley-Tukey radix-2 FFT */
    private fun fft(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        // bit-reversal permutation
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                var tmp = re[i]; re[i] = re[j]; re[j] = tmp
                tmp = im[i]; im[i] = im[j]; im[j] = tmp
            }
        }
        // butterfly
        var len = 2
        while (len <= n) {
            val halfLen = len shr 1
            val angle = -2.0 * PI / len
            val wRe = cos(angle)
            val wIm = sin(angle)
            var i = 0
            while (i < n) {
                var curRe = 1.0
                var curIm = 0.0
                for (k in 0 until halfLen) {
                    val idx = i + k + halfLen
                    val tRe = curRe * re[idx] - curIm * im[idx]
                    val tIm = curRe * im[idx] + curIm * re[idx]
                    re[idx] = re[i + k] - tRe
                    im[idx] = im[i + k] - tIm
                    re[i + k] += tRe
                    im[i + k] += tIm
                    val newRe = curRe * wRe - curIm * wIm
                    curIm = curRe * wIm + curIm * wRe
                    curRe = newRe
                }
                i += len
            }
            len = len shl 1
        }
    }
}
