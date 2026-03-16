# Waveform Plotter v1.3.2 — CLion Embedded Debug Plugin

**中文** | [English](README_EN.md)

CLion 嵌入式调试插件，提供实时波形绘制 + FFT 频谱分析 + RTT 高速数据流功能。

专为 **OpenOCD** 用户设计（支持 CMSIS-DAP / DAP-Link / ST-Link / J-Link）。
CLion 原生 Live Watches 仅支持 J-Link/ST-Link —— 本插件填补了 DAP-Link 等调试器的空白。

## 功能

### 被动模式（Passive Recording）
- 监听调试器暂停事件（断点/单步/手动暂停）
- 自动采集 Watch 变量数值并绘制波形
- 适合配合 "Evaluate and Log" 断点使用

### Live Watch 实时监控

- **非侵入式**：通过 SWD MEM-AP 直读 MCU 内存，CPU 不停
- **零暂停启动**：自动从 ELF 符号表解析变量地址，无需暂停 MCU
- 支持 1-2000Hz 采样频率（默认 50Hz，HSS 高速采样模式）
- 通过 OpenOCD Telnet 端口直连（绕过 GDB all-stop 限制）
- 变量实时显示数据类型和当前数值
- 支持数据类型：int8/16/32, uint8/16/32, float, double

### RTT 高速数据流（v1.3.2 新增）

- **SEGGER RTT 协议**：通过 RTT 通道接收固件端 `printf` 数据，极低延迟
- **OpenOCD 自动初始化**：一键启动，自动执行 `rtt setup/start/server start`
- **多 RAM 区域自动搜索**：自动扫描 DTCM/AXI SRAM/SRAM1 等区域，兼容 STM32 全系列
- **区域缓存加速**：记住上次成功的 RAM 区域，二次启动秒开
- **兼容多种 RTT Server**：OpenOCD RTT / J-Link RTT Viewer / pyOCD（关闭自动初始化直连 TCP）
- **高吞吐**：RTT polling interval 1ms，回调节流 60fps，支持 kHz 级采样
- **完全解耦**：不依赖 Debug Session / ELF / GDB，纯 TCP 文本流

### FFT 频谱分析（v1.2 新增）

- **一键切换**：Time / FFT 按钮切换时域/频域显示
- **Cooley-Tukey FFT**：取最后 1024 点，自动补零到 2 的幂
- **Hanning 窗**：抑制频谱泄漏
- **dB 幅度谱**：Y 轴显示 dB，X 轴显示频率 (Hz)
- **性能优化**：FFT 结果缓存（仅数据变化时重算）、窗函数预计算、工作数组复用
- 复用通道颜色、缩放/拖拽/tooltip 交互

### 波形显示

- **真实时间轴**：X 轴显示真实时间（示波器风格，最新 = 0，旧 = 负值）
- **自适应时间单位**：ms / μs 一键切换，1-2-5 友好网格间距
- **智能自动追踪**：默认全览模式，缩放后自动切换为滑动窗口
- 多通道叠加显示（最多 8 通道，10000 点缓冲）
- **万向自由拖拽**（鼠标左键上下左右任意方向平移）
- Y 轴自动缩放 + 手动缩放（滚轮）
- X 轴缩放（Shift+滚轮，以鼠标位置为锚点）
- 鼠标悬停十字线 + 带背景框的数值 tooltip（含时间标注）
- NaN 自动断线
- CSV 导出

### 可定制 UI

- 齿轮设置按钮：字体大小、波形线宽、刷新率（30/60fps）
- 设置自动持久化

## 支持的调试环境

| 调试器 | 被动模式 | Live Watch | RTT |
|--------|---------|------------|-----|
| OpenOCD（含 DAP-Link, ST-Link, J-Link） | ✅ | ✅ Telnet `mdw` | ✅ 自动初始化 |
| J-Link GDB Server | ✅ | ✅ Telnet `memU32` | ✅ J-Link RTT Viewer |
| pyOCD | ✅ | ⚠️ 需测试 | ✅ 直连 TCP |
| 其他 GDB Server | ✅ | ⚠️ 需测试 | ⚠️ 需 RTT Server |

## 快速开始

**插件本身无任何外部依赖**，下载 ZIP 即装即用：

1. 从 [Releases](https://github.com/Szturin/Clion-Waveform-Plotter/releases) 下载最新 `.zip`
2. CLion → Settings → Plugins → ⚙ 齿轮图标 → **Install Plugin from Disk**
3. 选择下载的 ZIP 文件 → 重启 CLion
4. 底部出现 **Waveform Plotter** 面板，即安装成功

> 如果你使用 OpenOCD + ARM 工具链进行嵌入式开发，那你已经具备了所有条件。
> 插件会自动检测 CLion 的调试配置，无需额外设置。

## 环境要求

插件运行只需要你的 CLion 嵌入式开发环境已正常工作：

- **CLion 2024.3 ~ 2025.3**
- **ARM 工具链**（`arm-none-eabi-gcc`, `arm-none-eabi-nm` 等）
- **OpenOCD**（Telnet 端口默认 4444）

> **推荐**：安装 [STM32CubeCLT](https://www.st.com/en/development-tools/stm32cubeclt.html)（STM32Cube Command Line Tools），一键包含 arm-none-eabi 工具链 + OpenOCD + STM32Programmer，省去单独配置。
>
> 也可以手动安装：
> - [ARM GNU Toolchain](https://developer.arm.com/downloads/-/arm-gnu-toolchain-downloads)（arm-none-eabi-gcc）
> - [OpenOCD](https://openocd.org/)（或通过包管理器安装）

确保 `arm-none-eabi-nm` 和 `openocd` 在系统 PATH 中，CLion 能够找到即可。

## 使用方法

### 被动模式
1. 打开底部 **Waveform Plotter** 面板
2. 输入变量名（GDB 表达式），点 **+ Add**
3. 勾选要监控的变量
4. 点 **Record**，然后正常调试（断点/单步）

### Live Watch 模式
1. 添加并勾选变量
2. 启动调试（插件自动从 ELF 加载符号表）
3. 点 **▶ Live** 按钮
4. MCU 运行时波形实时更新

> **ELF 优先解析**：如果 ELF 符号表中能找到变量地址，无需暂停 MCU。
> 对于结构体成员、复杂表达式等 ELF 无法解析的情况，会自动回退到 GDB 解析（需 MCU 暂停一次）。

> Live Watch 只能监控有固定地址的变量（全局变量、静态变量）。
> 建议监控变量加 `volatile` 关键字，避免 D-Cache 导致读到旧值。

### RTT 模式
1. **固件端**：使用 SEGGER RTT 输出数据，格式为逗号分隔数值 + 换行
   ```c
   SEGGER_RTT_printf(0, "%.4f,%.4f\n", voltage, current);
   ```
2. 在插件中添加对应变量名（顺序与 printf 一致）
3. ⚙ 设置中选择数据源 **RTT**，配置端口（默认 9090）
4. 启动调试 → 点 **▶ Live** → 自动初始化 RTT 并开始采集

> **OpenOCD 用户**：保持"自动初始化"勾选，插件会自动通过 Telnet 执行 `rtt setup/start/server start`。
>
> **J-Link / pyOCD 用户**：取消"自动初始化"，手动启动 RTT Server，插件直连 TCP 端口。
>
> **RAM 区域**：默认自动搜索多个 Cortex-M 常见区域（DTCM/AXI SRAM 等）。如需指定，在设置中填写 RAM Start 和 Size。

### FFT 频谱分析
1. 采集波形数据（被动模式或 Live Watch）
2. 点控制栏 **FFT** 按钮切换到频域视图
3. X 轴显示频率 (Hz)，Y 轴显示幅度 (dB)
4. 点 **Time** 按钮切回时域，波形不受影响
5. 频率分辨率 = 采样率 / FFT 点数（如 50Hz/1024 ≈ 0.049Hz）

### 交互操作
- **左键拖拽**：万向平移（上下左右任意方向）
- **滚轮**：缩放 Y 轴
- **Shift+滚轮**：缩放 X 轴
- **右键单击**：重置视图
- **右键变量**：删除变量
- **⚙ 设置**：字体大小、线宽、刷新率

## 构建

```bash
./gradlew buildPlugin
```

要求：JDK 21, CLion 2024.3 ~ 2025.3

## 项目结构

```
src/main/kotlin/com/github/waveformplotter/
├── WaveformToolWindowFactory.kt  # 工具窗口入口
├── WaveformPanel.kt              # 主面板 UI
├── PlotCanvas.kt                 # 波形绘制引擎（时域 + FFT 频域）
├── FFT.kt                        # Cooley-Tukey FFT 算法（窗函数缓存 + 数组复用）
├── LiveWatchService.kt           # Live Watch 实时采集服务（Telnet）
├── RttService.kt                 # RTT 数据源（TCP 连接 RTT Server，CSV 解析）
├── ElfSymbolResolver.kt          # ELF 符号表解析（零暂停地址解析）
├── AddToPlotterAction.kt         # 编辑器右键菜单 Action
├── WatchVariableCollector.kt     # 被动模式变量采集
├── DebugSessionListener.kt       # 调试会话生命周期管理
├── DataBuffer.kt                 # 环形缓冲区（含版本号用于缓存失效）
└── WaveformConfigService.kt      # 配置持久化
```

## 技术原理

### Live Watch 架构
```
┌─ 地址解析（二选一，自动优先 ELF）─────────────────────┐
│ ① ELF: arm-none-eabi-nm → 符号表 → 地址（无需暂停）    │
│ ② GDB: print &var → 地址（需 MCU halted，自动回退）     │
└──────────────────────────────────────────────────────┘
                         ↓
┌─ 实时采集 ──────────────────────────────────────────┐
│ TCP → OpenOCD Telnet:4444 → mdw/mdh/mdb → 解析 → 绘图 │
│ （绕过 GDB all-stop 限制，MCU 运行时持续采样）          │
└──────────────────────────────────────────────────────┘
```

### FFT 频谱分析
```
时域数据（最后 1024 点）→ Hanning 窗 → 补零到 2^N → Cooley-Tukey FFT → 单边幅度谱 (dB)
```
- 结果缓存：仅 DataBuffer 版本号变化时重算，30/60fps 刷新零重复计算
- 工作数组复用：FFT 内部 re/im 数组和窗函数系数跨帧复用，减少 GC 压力

### RTT 数据流架构
```
┌─ 自动初始化（OpenOCD Telnet） ──────────────────────────┐
│ rtt setup <RAM_START> <SIZE> "SEGGER RTT"                │
│ rtt start → 自动扫描多个 RAM 区域（DTCM/AXI SRAM/...）    │
│ rtt server start <PORT> 0                                │
│ rtt polling_interval 1  (1ms 高速轮询)                    │
└──────────────────────────────────────────────────────────┘
                         ↓
┌─ 数据采集 ─────────────────────────────────────────────┐
│ TCP → RTT Server:9090 → BufferedReader → 逐行读取        │
│ "1.234,5.678\n" → split(",") → parseDouble → DataBuffer  │
│ 回调节流: 16ms 间隔 (~60fps)，避免 EDT 过载              │
└──────────────────────────────────────────────────────────┘
```
- 协议约定：固件端 `SEGGER_RTT_printf(0, "%.4f,%.4f\n", v1, v2)`
- 完全解耦：不依赖 Debug Session / ELF / GDB，兼容任何 RTT Server

Live Watch 利用 ARM Cortex-M 调试架构中的 MEM-AP（Memory Access Port），
通过 SWD 调试口作为独立总线主控读取 MCU 内存，与 CPU 并行工作，零侵入。

## License

本软件以**非商业许可**开源发布。

**允许**：出于个人学习、教育、非商业目的自由使用、复制、修改和分发本软件。

**禁止**：未经作者书面许可，不得将本软件的全部或部分用于任何商业用途。

如需商业授权，请在 GitHub 提交 Issue 联系。
