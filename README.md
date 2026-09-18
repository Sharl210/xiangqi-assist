# 象棋辅助

象棋辅助是一款开源的 Android 中国象棋学习与分析工具。它既保留了完整的本地对弈、棋谱与引擎分析能力，也提供屏幕棋盘识别、悬浮窗建议和可选自动落子，适合日常练棋、复盘和研究局面。

> 本项目仅用于个人学习、技术研究与文化交流。使用屏幕识别、悬浮窗或自动落子功能时，请遵守所在平台规则以及当地法律法规。

## 主要特色

- **本地象棋对弈**：内置 Pikafish 与 NNUE，可进行人机对弈和局面分析。
- **棋谱管理**：支持打谱、复盘、变着浏览以及常见棋谱导入导出。
- **屏幕棋盘识别**：通过 Android 屏幕录制读取其他象棋应用的棋盘画面，并用 YOLO/TensorFlow Lite 在本机识别。
- **八帧稳定判定**：录屏流保持每秒 4 个样本；只有连续 8 个样本画面稳定，才选择其中较清晰的一帧识别，减少动画和移动过程造成的误判。
- **明确启停**：一键准备只完成权限与录屏环境，默认保持停止；必须点击绿色“一键启动”才运行，运行后可用红色“一键关闭”立即停止。
- **统一视觉**：主页和控制页采用与 Logo 一致的深蓝、金色与朱红主题，状态栏和导航栏使用沉浸式透明处理。
- **悬浮窗辅助**：显示识别棋盘、当前状态、候选走法、评分和主变，可切换红黑方、思考方式与候选数量。
- **多种工作模式**：支持指导、半自动、手动摆子和自动走子，模式之间互斥，避免状态混乱。
- **可选自动落子**：通过无障碍手势执行点击或拖动，并在落子前后进行局面和通道检查；首次工作模式为自动，也可关闭自动走子开关只看建议。未点击“一键启动”时不会执行任何识别或落子。
- **root 辅助启用**：设备具备 root 时，可尝试自动启用或恢复本应用的无障碍服务；没有 root 时可在系统设置中手动开启。
- **隐私友好**：识别和计算在设备本地完成，不要求把棋盘截图上传到服务器。
- **双引擎变体**：同时提供通用 ARMv8 版和适合支持 dotprod 指令设备的 ARMv8 dotprod 版。

## 版本选择

| APK | 适用设备 |
|---|---|
| `象棋辅助_1.1_通用ARMv8版.apk` | 通用 64 位 ARM Android 设备，兼容性优先 |
| `象棋辅助_1.1_ARMv8点积优化版.apk` | 支持 ARMv8.2 dotprod 指令的较新设备，优先发挥对应引擎优化 |

不确定设备能力时，建议先安装“通用 ARMv8 版”。两个版本的应用功能相同，主要区别是内置 Pikafish 引擎指令集。

## 使用概览

1. 安装与设备匹配的 APK，打开“象棋辅助”。
2. 使用本地对弈、打谱或复盘时，可直接进入相应功能。
3. 使用屏幕辅助时，进入“悬浮窗辅助”，完成一键准备后再点击一键启动。
4. 切换到目标象棋应用，等待连续 8 帧稳定后完成识别和计算。
5. 在悬浮窗中查看建议；如需自动落子，再启用无障碍服务和自动走子。

更详细的操作说明见 [悬浮窗辅助说明](docs/assist-helper.md)。

## 权限说明

- **屏幕录制**：获取当前屏幕画面，用于本地棋盘识别。
- **悬浮窗**：在其他应用上方展示棋盘、状态和走法建议。
- **无障碍服务**：仅在用户使用自动落子时发送坐标手势；基础识别和建议功能不依赖它。
- **root（可选）**：用于尝试自动启用或恢复本应用无障碍服务，不是安装和基础使用的必要条件。

## 从源码构建

项目要求 JDK 17、Android SDK 34，并使用 Gradle Wrapper 构建。

```bash
# 通用 ARMv8 Release
./gradlew :app:assembleArmv8-Release

# ARMv8 dotprod Release
./gradlew :app:assembleArmv8-dotprod-Release

# 单元测试
./gradlew :app:testArmv8-ReleaseUnitTest
```

Release 构建支持通过 `-PreleaseStoreFile`、`-PreleaseStorePassword`、`-PreleaseKeyAlias`、`-PreleaseKeyPassword`（或对应环境变量）指定自己的签名；公开源码不包含私钥。未提供私钥时，源码仍可构建，但会使用本地 Android debug 签名，仅适合测试，不应直接作为正式分发签名。

## 项目结构

- `app/`：Android 主应用、对弈、棋谱、识别、悬浮窗和自动落子。
- `filepicker/`：棋谱文件选择组件。
- `tinypinyin/`：拼音检索相关组件。
- `docs/`：使用说明、架构记录、技术报告和发布记录。
- `release/`：本地构建后的版本归档；GitHub 下载请以 Releases 页面为准。

## 参考项目与鸣谢

感谢以下开源项目、作者和社区提供的基础能力与参考：

- [zfdang/chinese-chess-android](https://github.com/zfdang/chinese-chess-android)：本项目二次开发所基于的 Android 中国象棋工程。
- [official-pikafish/Pikafish](https://github.com/official-pikafish/Pikafish)：中国象棋引擎与 NNUE 支持。
- [peterosterlund2/droidfish](https://github.com/peterosterlund2/droidfish)：Android 棋类应用架构和引擎交互的重要基础。
- [Vincentzyx/VinXiangQi](https://github.com/Vincentzyx/VinXiangQi)：棋子检测模型与识别方案参考。
- [walker8088/cchess](https://github.com/walker8088/cchess)：中国象棋规则与工具参考。
- [PhilJay/MPAndroidChart](https://github.com/PhilJay/MPAndroidChart)：图表展示组件。
- TensorFlow Lite、YOLO 及 Android 开源生态的维护者与贡献者。

项目中各第三方组件仍遵循其各自许可证和署名要求。

## 开源许可

本项目以 [GNU General Public License v3.0](LICENSE) 开源。修改和再分发时，请遵守 GPL-3.0 以及项目所包含第三方组件的许可条款。

## 文档

- [悬浮窗辅助说明](docs/assist-helper.md)
- [1.1 发布说明](docs/RELEASE_1.1.md)
- [Logo 设计记录](docs/LOGO.md)
