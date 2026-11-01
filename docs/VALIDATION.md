# 3.0-beta4 验证记录

最新版本的能力选择与测试范围见 [beta6 验证记录](VALIDATION-BETA6.md)。以下保留 beta4 的历史记录。

测试日期：2026-10-31。交付整理：2026-11-01。

## 环境

| 设备 | 系统 | root | 适配方式 |
| --- | --- | --- | --- |
| 小米 MIX 2 / chiron | 当前 Android 15 / API 35 固件 | KernelSU 3.2.0 | AOSP 运行时角色属性及 fabricated overlay |
| 小米 10S / thyme | MIUI Android 11 / API 30 | Magisk 28.1 | 普通资源辅助 APK、root idmap2 映射及高通运行时属性 |

两台设备均使用同一个管理 APK。10S 没有安装 `bt_speaker_thyme` 模块，适配不创建模块目录，也不修改系统分区。

## 配置方式

MIX 2 使用 `bluetooth.profile.a2dp.*` 和 `bluetooth.profile.avrcp.*` 角色属性。10S 使用 `persist.vendor.service.bt.a2dp.sink` 和 `persist.vendor.bt.a2dp.sink_conn`。

10S 首次切换时，APK 从自身 assets 提取签名的资源辅助 APK，以 root 安装到 `/data/app`。`idmap2` 为系统蓝牙 APK 生成资源映射，放在 `/data/resource-cache`；通过 OverlayManager 启用，并逐一检查接收、媒体控制和自动音频焦点资源。

10S 的映射由 root 使用 `--ignore-overlayable` 创建，适配明确限定在已检查接口和资源的 `thyme` / API 30 环境。该办法不是普通未 root APK 的权限，也不能泛化为所有 Android 11 固件均可用。

## 验证内容

- 管理 APK 和内置资源 APK 构建、签名校验及覆盖安装。
- 完整检测返回当前机型、系统、蓝牙包、原生库、root 方案和适配后端。
- 检测前后的角色属性、覆盖状态、蓝牙开关与调试设置快照一致。
- 发送／接收模式对应的 A2DP 服务和本机服务 UUID 切换。
- 蓝牙关闭时切换角色保持关闭；再次开启后使用目标角色。
- 默认模式的恢复函数与重复调用保护。
- 原属性及 10S 组件启用状态的备份、恢复和重复恢复。
- 重复开启、重复恢复前后的蓝牙开关记录保持一致，两台设备的集成测试均通过。
- 设备列表保留已连接设备，并兼容手机／电脑的旧蓝牙服务缓存。
- 连接、音频会话与实际活动音轨分别显示。

## beta4 优化

- Magisk 属性工具支持固定路径和 `magisk --path` 回退定位，在单次辅助进程中缓存结果。
- 已经开启且角色、资源均正确时跳过重新开启，减少重复改写资源映射。
- 已恢复的配置再次恢复时不重启蓝牙；10S 本轮首次恢复约 8.0 秒，再次恢复约 2.2 秒，均已检查蓝牙开关记录。
- 开关失败时按操作前的属性、组件、覆盖、开关状态和开机设置回滚。
- 手动切换后记录当前启动周期，延迟的开机任务不会覆盖刚选择的模式。
- 本地 `127.0.0.1:7778` 编译、原签名升级和 APK SHA-256 一致性检查通过。

## 实际音频观察

10S 切到无模块接收模式后，连接的发送端为 MacBook Pro。完整检测观察到来自蓝牙进程的活动音轨：

- 采样率：44100 Hz。
- 格式：PCM 16 位，双声道。
- 输出设备：`0x2 (AUDIO_DEVICE_OUT_SPEAKER)`。
- `playbackObserved=true`，同时 `legacyModule=false`。

这是本次运行状态的观察，未进行现场听感确认。观察摘要保存在 [mi10s-beta4-playback.json](evidence/mi10s-beta4-playback.json)。MIX 2 的控制逻辑回归已通过，这次音频观察的发送端是 Mac。

2026-11-01 收尾只读复核通过，10S 已安装 APK 的 SHA-256 与交付文件一致，仍为接收模式，系统 `A2dpSinkService` 已启动。该次复核时没有连接的音频源，也没有活动音轨；上述活动音轨记录属于前一轮观察。Android 11 的控制 Binder 在管理界面绑定后可用，独立 CLI 查询可能显示 `profileReady=false`，不等同于接收服务未启动。

交付文件：`dist/Android-A2DP-Sink-3.0-beta4.apk`。

SHA-256：

```text
32950cd0e860499e6afa2d3cd07511dfb0744f9f3547e0defeb24bfca28e82e4
```

## 限制

启动恢复函数的测试不等同于整机重启测试。本轮未重启设备；MIUI 自启动限制、低内存和取消 root 授权后的行为仍需现场验证。

未测量音频延迟，未进行长期稳定性测试，也未承诺其他固件兼容。发现资源或原生代码标识本身不等于音频已经正常播放。

2.0 历史验证见 [VALIDATION-V2.md](VALIDATION-V2.md)。
