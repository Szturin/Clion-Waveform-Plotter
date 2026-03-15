# Waveform Plotter v1.3 — CLion Embedded Debug Plugin

[中文](README.md) | **English**

A CLion plugin for real-time waveform plotting + FFT spectrum analysis during embedded debugging.

Designed for **OpenOCD** users (supports CMSIS-DAP / DAP-Link / ST-Link / J-Link).
CLion's built-in Live Watches only supports J-Link/ST-Link — this plugin fills the gap for DAP-Link and other debuggers.

## Features

### Passive Recording
- Listens to debugger pause events (breakpoints / step / manual halt)
- Automatically captures Watch variable values and plots waveforms
- Works great with "Evaluate and Log" breakpoints

### Live Watch — Real-time Monitoring

- **Non-invasive**: Reads MCU memory directly via SWD MEM-AP — CPU keeps running
- **Zero-halt startup**: Automatically resolves variable addresses from ELF symbol table — no MCU pause needed
- Supports 1–2000 Hz sampling rate (default 50 Hz, HSS high-speed sampling mode)
- Direct connection via OpenOCD Telnet port (bypasses GDB all-stop limitation)
- Real-time display of data type and current value per variable
- Supported types: int8/16/32, uint8/16/32, float, double

### FFT Spectrum Analysis (New in v1.2)

- **One-click toggle**: Switch between Time / FFT domain via toolbar buttons
- **Cooley-Tukey FFT**: Uses last 1024 samples, auto zero-padded to power of 2
- **Hanning window**: Suppresses spectral leakage
- **dB magnitude spectrum**: Y-axis in dB, X-axis in frequency (Hz)
- **Performance optimized**: FFT result caching (recompute only on data change), window precomputation, work array reuse
- Reuses channel colors, zoom/pan/tooltip interactions

### Waveform Display

- Multi-channel overlay (up to 8 channels)
- **Free-form panning** (drag in any direction with left mouse button)
- Y-axis auto-scale + manual zoom (scroll wheel)
- X-axis zoom (Shift + scroll wheel)
- Crosshair tooltip with value display on hover
- Automatic NaN gap handling
- CSV export

### Customizable UI

- Settings button: font size, line width, refresh rate (30/60 fps)
- Settings auto-persisted

## Supported Debug Environments

| Debugger | Passive Mode | Live Watch |
|----------|-------------|------------|
| OpenOCD (DAP-Link, ST-Link, J-Link) | ✅ | ✅ Telnet `mdw` |
| J-Link GDB Server | ✅ | ✅ Telnet `memU32` |
| Other GDB Servers | ✅ | ⚠️ Untested |

## Quick Start

**The plugin has zero external dependencies** — just download the ZIP and install:

1. Download the latest `.zip` from [Releases](https://github.com/Szturin/Clion-Waveform-Plotter/releases)
2. CLion → Settings → Plugins → ⚙ Gear icon → **Install Plugin from Disk**
3. Select the downloaded ZIP → Restart CLion
4. The **Waveform Plotter** panel appears at the bottom — you're all set

> If you already have OpenOCD + ARM toolchain for embedded development, you have everything you need.
> The plugin auto-detects CLion's debug configuration — no extra setup required.

## Prerequisites

The plugin only requires your CLion embedded development environment to be working:

- **CLion 2024.3 ~ 2025.3**
- **ARM toolchain** (`arm-none-eabi-gcc`, `arm-none-eabi-nm`, etc.)
- **OpenOCD** (Telnet port defaults to 4444)

> **Recommended**: Install [STM32CubeCLT](https://www.st.com/en/development-tools/stm32cubeclt.html) (STM32Cube Command Line Tools) — bundles the arm-none-eabi toolchain + OpenOCD + STM32Programmer in one package.
>
> Or install manually:
> - [ARM GNU Toolchain](https://developer.arm.com/downloads/-/arm-gnu-toolchain-downloads) (arm-none-eabi-gcc)
> - [OpenOCD](https://openocd.org/) (or via your package manager)

Make sure `arm-none-eabi-nm` and `openocd` are on your system PATH so CLion can find them.

## Usage

### Passive Mode
1. Open the **Waveform Plotter** panel at the bottom
2. Enter variable name (GDB expression), click **+ Add**
3. Check the variables to monitor
4. Click **Record**, then debug as usual (breakpoints / stepping)

### Live Watch Mode
1. Add and check variables
2. Start debugging (plugin auto-loads ELF symbol table)
3. Click the **▶ Live** button
4. Waveforms update in real-time while MCU is running

> **ELF-first resolution**: If the variable address is found in the ELF symbol table, no MCU halt is needed.
> For struct members, complex expressions, etc. that ELF cannot resolve, it automatically falls back to GDB resolution (requires one MCU halt).

> Live Watch can only monitor variables with fixed memory addresses (global / static variables).
> It's recommended to mark monitored variables as `volatile` to avoid stale reads due to D-Cache.

### FFT Spectrum Analysis
1. Capture waveform data (Passive mode or Live Watch)
2. Click the **FFT** button in the toolbar to switch to frequency domain
3. X-axis shows frequency (Hz), Y-axis shows magnitude (dB)
4. Click **Time** to switch back — waveform data is preserved
5. Frequency resolution = sample rate / FFT size (e.g., 50 Hz / 1024 ≈ 0.049 Hz)

### Controls
- **Left-click drag**: Pan in any direction
- **Scroll wheel**: Zoom Y-axis
- **Shift + scroll**: Zoom X-axis
- **Right-click**: Reset view
- **Right-click variable**: Delete variable
- **⚙ Settings**: Font size, line width, refresh rate

## Build

```bash
./gradlew buildPlugin
```

Requirements: JDK 21, CLion 2024.3 ~ 2025.3

## Project Structure

```
src/main/kotlin/com/github/waveformplotter/
├── WaveformToolWindowFactory.kt  # Tool window entry point
├── WaveformPanel.kt              # Main panel UI
├── PlotCanvas.kt                 # Waveform rendering engine (time domain + FFT)
├── FFT.kt                        # Cooley-Tukey FFT (window caching + array reuse)
├── LiveWatchService.kt           # Live Watch sampling service (Telnet)
├── ElfSymbolResolver.kt          # ELF symbol resolution (zero-halt address lookup)
├── AddToPlotterAction.kt         # Editor right-click menu action
├── WatchVariableCollector.kt     # Passive mode variable collection
├── DebugSessionListener.kt       # Debug session lifecycle management
├── DataBuffer.kt                 # Ring buffer (with version for cache invalidation)
└── WaveformConfigService.kt      # Configuration persistence
```

## How It Works

### Live Watch Architecture
```
┌─ Address Resolution (auto-selects, ELF preferred) ──────┐
│ ① ELF: arm-none-eabi-nm → symbol table → address        │
│         (no MCU halt needed)                             │
│ ② GDB: print &var → address                             │
│         (requires MCU halted, automatic fallback)        │
└──────────────────────────────────────────────────────────┘
                          ↓
┌─ Real-time Sampling ────────────────────────────────────┐
│ TCP → OpenOCD Telnet:4444 → mdw/mdh/mdb → parse → plot  │
│ (bypasses GDB all-stop, samples while MCU runs)          │
└──────────────────────────────────────────────────────────┘
```

### FFT Spectrum Analysis
```
Time-domain data (last 1024 pts) → Hanning window → zero-pad to 2^N → Cooley-Tukey FFT → one-sided magnitude spectrum (dB)
```
- Result caching: Recomputes only when DataBuffer version changes — zero redundant computation at 30/60 fps
- Array reuse: FFT internal re/im arrays and window coefficients reused across frames, reducing GC pressure

Live Watch leverages the MEM-AP (Memory Access Port) in ARM Cortex-M debug architecture,
reading MCU memory via SWD as an independent bus master — runs in parallel with the CPU, fully non-invasive.

## License

This software is open source and released under a **non-commercial license**.

**You are free to**: use, copy, modify, and distribute this software for personal, educational, and non-commercial purposes.

**You may NOT**: use this software, in whole or in part, for any commercial purpose without explicit written permission from the author.

For commercial licensing inquiries, please open an issue on GitHub.
