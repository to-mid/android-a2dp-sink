# 蓝牙小音箱 2.0

作者：橘橙的记忆。

使用 Android 系统中已有的蓝牙音频接收实现，在普通蓝牙发送与蓝牙音箱接收之间切换。管理 APK 内置 Magisk 模块。

## 当前支持范围

- 小米 10S，设备代号 `thyme`。
- Android 11，API 30；已在 MIUI 12.5.8 验证。
- root 与 Magisk；已使用 Magisk 28.1 验证。

安装脚本会拒绝其他设备或 Android 版本。当前不是通用模块，不能仅删除机型判断就认定其他系统兼容。设备检测和其他机型的适配不包含在本次 2.0 提交中。

## 功能

- **普通发送**：使用 A2DP Source 和 AVRCP Target，向耳机、音箱输出媒体音频。
- **音箱接收**：使用 A2DP Sink 和 AVRCP Controller，接收其他手机或电脑的音频。
- **开机默认**：普通发送、音箱接收，或记住上次成功切换的模式。
- 模式切换只重启蓝牙，保留配对记录和原来的蓝牙开关状态。
- 根据角色显示已配对的音频源或耳机／音箱。
- 配对可发现窗口、媒体音量、名称修改和蓝牙重启。
- 从 APK 安装内置模块；挂载的覆盖 APK 完全相同时，已有安装可在线更新脚本。

## 构建

在 Linux x86_64 上使用：

```sh
bash bootstrap-toolchain.sh
bash build.sh
```

工具链会放在仓库相邻的 `toolchain/`，包括 OpenJDK 17、Android SDK Platform 30、Build Tools 30.0.3。不需要 Gradle。

已有工具链时，可通过 `TOOLS` 指定路径：

```sh
TOOLS=/absolute/path/to/toolchain bash build.sh
```

输出：

```text
dist/Bluetooth-Speaker.apk
dist/Mi10S-Bluetooth-Speaker-Magisk.zip
```

构建先生成模块 ZIP，再把同一个 ZIP 放入 APK 的 `assets/bluetooth-speaker-module.zip`。

签名私钥不包含在仓库中。首次构建会生成 `signing/local-speaker.jks`，开发签名别名为 `local`，密码为 `android`。覆盖升级现有 APK 和系统覆盖更新时，需要沿用自己的原签名文件；该目录已被 Git 忽略。

## 安装与使用

1. 安装 APK，打开“蓝牙小音箱”，允许 root。
2. 点击“安装内置模块”。首次安装需要重启，以便系统扫描覆盖包。
3. 在“当前模式”中选择普通发送或音箱接收。
4. 在“开机默认”中保存下次开机的模式。首次默认普通发送。
5. 使用接收模式时点击“开始配对”，在发送端搜索本机并确认配对，再选择本机作为媒体输出。

切换会断开当前蓝牙连接。当前模式与开机默认相互独立；保存开机默认不会立即切换。

## 模块行为

- `post-fs-data.sh` 读取启动模式，清理自身覆盖包的解析缓存，为本次启动设置蓝牙接收角色属性。
- `service.sh` 在系统启动完成后调用 `mode.sh`，并记录结果。
- `mode.sh` 切换覆盖资源和角色，按需重启蓝牙。失败时尝试恢复原模式。
- 由于 Shamiko 白名单模式可能隐藏模块挂载，接收覆盖采用系统包加同签名系统应用更新的方式。
- 组件启用优先使用 `DONT_KILL_APP`。

应用和模块不修改 USB 调试、开发者选项或网络 ADB 端口。日常使用只需要 root，不依赖调试开关。

## 卸载

恢复普通使用只需选择“普通发送”，无需卸载。

彻底卸载时，先切普通发送，再在 Magisk 中移除模块并重启。卸载脚本恢复保存的蓝牙属性，并安排启动后清理覆盖更新与组件设置。仅停用模块不会完整清理系统覆盖更新。

## 代码结构

```text
app/          中文管理界面、root 辅助进程、内置模块安装逻辑
overlay/      蓝牙接收模式资源覆盖
module/       Magisk 模块与模式切换脚本
build.sh      编译、签名、打包与内置模块
bootstrap-toolchain.sh
docs/         验证记录
```

验证范围与限制见 [docs/VALIDATION.md](docs/VALIDATION.md)。
