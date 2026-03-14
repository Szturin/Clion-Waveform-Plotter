# Waveform Plotter v1.2 — CLion Plugin

## 项目概述
CLion 嵌入式调试波形绘制插件，支持被动采集、Live Watch 实时监控、FFT 频谱分析。
专为 OpenOCD / DAP-Link 用户设计（CLion 原生 Live Watches 仅支持 J-Link/ST-Link）。

## 技术栈
- **语言**: Kotlin 1.9 + JDK 21
- **平台**: IntelliJ Platform SDK (CLion 2024.3+)
- **构建**: Gradle + intellij-platform-plugin 2.2.1
- **API**: XDebugger API (被动模式) + CIDR Debugger API (地址解析) + TCP Socket (实时采集)

## 关键依赖
- `com.intellij.clion` — CLion 平台
- `com.intellij.nativeDebug` — CIDR 调试器 API（CidrDebugProcess, DebuggerDriver）
- `com.intellij.modules.xdebugger` — 通用调试器框架

## 架构

### 数据流

```
被动模式: sessionPaused → XDebuggerEvaluator.evaluate() → DataBuffer → PlotCanvas

Live Watch:
  地址解析: ① ELF 符号表 (arm-none-eabi-nm，无需暂停)
            ② GDB print &var (自动回退，需 MCU halted)
  实时采集: TCP → OpenOCD Telnet:4444 → mdw/mdh/mdb → DataBuffer → PlotCanvas

FFT: DataBuffer(last 1024pts) → Hanning窗 → 补零2^N → FFT → dB幅度谱 → PlotCanvas
     结果缓存：仅 DataBuffer.version 变化时重算
```

### 关键设计决策
- **为什么不走 GDB monitor 命令**: GDB 在 all-stop 模式下 target running 时阻塞所有命令，OpenOCD 不支持 non-stop
- **为什么走 Telnet**: OpenOCD Telnet 端口独立于 GDB，target running 时也能执行命令
- **为什么 ELF 解析**: 避免必须暂停 MCU 才能获取变量地址，对简单全局变量实现零暂停启动
- **FFT 缓存策略**: DataBuffer.version（@Volatile long）在每次 pushAll/clearAll 时递增，PlotCanvas 比对版本号决定是否重算 FFT，避免 30/60fps 下每帧重复计算
- **FFT 数组复用**: FFT 内部 re/im 工作数组和 Hanning 窗系数跨帧复用，减少 GC 压力

### CIDR API 要点
- `CidrDebugProcess.VoidDebuggerCommand` 是**接口**（不是类），用 `object : ... {` 不带 `()`
- `executeInterpreterCommand()` 返回 String；`executeConsoleCommand()` 返回 void
- GDB 命令仅在 MCU halted 时可用（all-stop 模式限制）

### 文件职责
| 文件 | 职责 |
|------|------|
| `WaveformPanel.kt` | 主 UI 面板，组装控件，协调各组件，Time/FFT 切换 |
| `PlotCanvas.kt` | 波形绘制 (Graphics2D)，时域+FFT频域，万向拖拽，FFT 结果缓存 |
| `FFT.kt` | Cooley-Tukey radix-2 FFT 算法，Hanning 窗，工作数组复用 |
| `LiveWatchService.kt` | Live Watch 核心：地址解析、Telnet 通信、数据类型转换 |
| `ElfSymbolResolver.kt` | ELF 符号表解析：arm-none-eabi-nm 解析变量地址（零暂停方案） |
| `AddToPlotterAction.kt` | 编辑器右键菜单 Action：选中变量名 → Add to Waveform Plotter |
| `WatchVariableCollector.kt` | 被动模式：断点暂停时通过 XDebuggerEvaluator 采集 |
| `DebugSessionListener.kt` | 调试会话生命周期，暴露 started/paused/resumed 回调 |
| `DataBuffer.kt` | 环形缓冲区，多通道，线程安全，含 version 用于 FFT 缓存失效 |
| `WaveformConfigService.kt` | 项目级配置持久化 (PersistentStateComponent) |
| `WaveformToolWindowFactory.kt` | 工具窗口入口，注册 panel 引用供 Action 访问 |

## 构建命令
```bash
./gradlew buildPlugin       # 构建插件 ZIP
./gradlew compileKotlin     # 仅编译检查
```

输出: `build/distributions/clion-waveform-plotter-<version>.zip`

## 注意事项
- Telnet 命令不需要 `monitor` 前缀（直接 `mdw 0xADDR 1`）
- ELF 解析在 debug 会话首次暂停时自动加载（通过 GDB `info files` 获取 ELF 路径）
- ELF 无法区分 int32 和 float（都是 4 字节），默认推断为 float
- 结构体成员、数组元素等复杂表达式需要 GDB 回退解析
- 只能监控有固定内存地址的变量（全局/静态），不支持局部变量
- FFT 采样率取自 Live Watch 的 freqSpinner 值，被动模式采样率不规则时频率轴仅供参考
