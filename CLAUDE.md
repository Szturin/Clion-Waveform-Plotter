# Waveform Plotter — CLion Plugin

## 项目概述
CLion 嵌入式调试波形绘制插件，支持被动采集和 Live Watch 实时监控两种模式。

## 技术栈
- **语言**: Kotlin 1.9 + JDK 21
- **平台**: IntelliJ Platform SDK (CLion 2024.3+)
- **构建**: Gradle + intellij-platform-plugin 2.2.1
- **API**: XDebugger API (被动模式) + CIDR Debugger API (Live Watch)

## 关键依赖
- `com.intellij.clion` — CLion 平台
- `com.intellij.nativeDebug` — CIDR 调试器 API（CidrDebugProcess, DebuggerDriver）
- `com.intellij.modules.xdebugger` — 通用调试器框架

## 架构

### 数据流
```
被动模式: sessionPaused → XDebuggerEvaluator.evaluate() → DataBuffer → PlotCanvas
Live Watch: Timer → postCommand(VoidDebuggerCommand) → executeInterpreterCommand("monitor mdw") → DataBuffer → PlotCanvas
```

### CIDR API 要点
- `CidrDebugProcess.VoidDebuggerCommand` 是**接口**（不是类），用 `object : ... {` 不带 `()`
- `executeInterpreterCommand()` 返回 String；`executeConsoleCommand()` 返回 void
- `canExecuteWhileRunning() = true` 允许在 MCU running 时发送 monitor 命令
- `postCommand()` 返回 `CompletableFuture`

### 文件职责
| 文件 | 职责 |
|------|------|
| `WaveformPanel.kt` | 主 UI 面板，组装控件，协调各组件 |
| `PlotCanvas.kt` | 波形绘制 (Graphics2D)，30fps 刷新 |
| `LiveWatchService.kt` | Live Watch 核心：地址解析、monitor 命令、数据类型转换 |
| `WatchVariableCollector.kt` | 被动模式：断点暂停时通过 XDebuggerEvaluator 采集 |
| `DebugSessionListener.kt` | 调试会话生命周期，暴露 paused/resumed 回调 |
| `DataBuffer.kt` | 环形缓冲区，多通道，线程安全 |
| `WaveformConfigService.kt` | 项目级配置持久化 (PersistentStateComponent) |

## 构建命令
```bash
./gradlew buildPlugin       # 构建插件 ZIP
./gradlew compileKotlin     # 仅编译检查
```

输出: `build/distributions/clion-waveform-plotter-<version>.zip`

## 注意事项
- monitor 命令格式因 GDB Server 不同而异：OpenOCD 用 `mdw`，J-Link 用 `memU32`
- Live Watch 变量地址解析需要 MCU 处于暂停状态
- 只能监控有固定内存地址的变量（全局/静态），不支持局部变量
