# Waveform Plotter — CLion Embedded Debug Plugin

CLion 插件，为嵌入式调试提供实时波形绘制功能。

## 功能

### 被动模式（Passive Recording）
- 监听调试器暂停事件（断点/单步/手动暂停）
- 自动采集 Watch 变量数值并绘制波形
- 适合配合 "Evaluate and Log" 断点使用

### Live Watch 实时监控
- **非侵入式**：通过 SWD MEM-AP 直读 MCU 内存，CPU 不停
- 支持 1-100Hz 采样频率（默认 50Hz）
- 自动探测 GDB Server 类型（OpenOCD / J-Link）
- 变量地址自动解析，支持全局变量、结构体成员、数组元素
- 支持数据类型：int8/16/32, uint8/16/32, float, double

### 波形显示
- 多通道叠加显示（最多 8 通道）
- Y 轴自动缩放 + 手动缩放（滚轮）
- X 轴缩放（Shift+滚轮）+ 拖拽平移
- 鼠标悬停显示数值
- NaN 自动断线
- CSV 导出

## 支持的调试环境

| 调试器 | 被动模式 | Live Watch |
|--------|---------|------------|
| OpenOCD（含 DAP-Link, ST-Link, J-Link） | ✅ | ✅ `monitor mdw` |
| J-Link GDB Server | ✅ | ✅ `monitor memU32` |
| 其他 GDB Server | ✅ | ⚠️ 需测试 |

## 使用方法

### 安装
1. `./gradlew buildPlugin`
2. CLion → Settings → Plugins → 齿轮图标 → Install Plugin from Disk
3. 选择 `build/distributions/clion-waveform-plotter-0.1.0.zip`
4. 重启 CLion

### 被动模式
1. 打开底部 **Waveform Plotter** 面板
2. 输入变量名（GDB 表达式），点 **+ Add**
3. 勾选要监控的变量
4. 点 **Record**，然后正常调试（断点/单步）

### Live Watch 模式
1. 添加并勾选变量
2. 启动调试，设断点让 MCU **暂停一次**（用于地址解析）
3. 点 **Live** 按钮
4. 恢复 MCU 运行，波形开始实时更新

> Live Watch 只能监控有固定地址的变量（全局变量、静态变量）。
> 建议监控变量加 `volatile` 关键字，避免 D-Cache 导致读到旧值。

### 交互操作
- **滚轮**：缩放 Y 轴
- **Shift+滚轮**：缩放 X 轴
- **左键拖拽**：平移查看历史
- **右键单击**：重置视图
- **右键变量**：删除变量

## 构建

```bash
./gradlew buildPlugin
```

要求：JDK 21, CLion 2024.3+

## 项目结构

```
src/main/kotlin/com/github/waveformplotter/
├── WaveformToolWindowFactory.kt  # 工具窗口入口
├── WaveformPanel.kt              # 主面板 UI
├── PlotCanvas.kt                 # 波形绘制引擎
├── LiveWatchService.kt           # Live Watch 实时采集服务
├── WatchVariableCollector.kt     # 被动模式变量采集
├── DebugSessionListener.kt       # 调试会话生命周期管理
├── DataBuffer.kt                 # 环形缓冲区
└── WaveformConfigService.kt      # 配置持久化
```

## 技术原理

Live Watch 利用 ARM Cortex-M 调试架构中的 MEM-AP（Memory Access Port），
通过 SWD 调试口作为独立总线主控读取 MCU 内存，与 CPU 并行工作，零侵入。

```
CLion Plugin → GDB monitor cmd → GDB Server → SWD → DAP (MEM-AP) → MCU Memory Bus
```

## License

MIT
