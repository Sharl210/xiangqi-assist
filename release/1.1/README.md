# 象棋辅助 1.1 APK

本目录保存两个可安装的 Release APK；两个文件均对应当前源码的 `com.xiangqi.assist` 应用身份。

| 文件 | 大小 | SHA-256 |
|---|---:|---|
| `象棋辅助_1.1_通用ARMv8版.apk` | 97090435 字节 | `8b59855b4ab48ec768915812cc367826d4e9e6c482599a2471d296c3f22ddd1f` |
| `象棋辅助_1.1_ARMv8点积优化版.apk` | 97090375 字节 | `02a50c0258892ee080a0942cd56f97b8cc7413e47a6add8d89d3d49fbd3b5222` |

两者均为：应用名“象棋辅助”、包名 `com.xiangqi.assist`、版本 `1.1`、版本代码 `2`，并通过 APK Signature Scheme v2 校验。

通用 ARMv8 版优先兼容性；点积优化版适用于支持相应 ARMv8.2 dotprod 指令的设备。功能代码一致，主要区别是内置引擎变体。

本地工作区未提供私有发布密钥，这两个复核 APK 使用 Android debug keystore，适合安装验证；正式公开发布应使用 GitHub 工作流配置的发布密钥重新生成资产。当前文件已通过 ZIP 完整性、包信息和 APK v2 签名校验。
