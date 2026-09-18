# 象棋辅助 1.1 下载说明

正式安装包由 `v1.1` 标签触发 GitHub Actions，使用仓库加密发布密钥重新构建并上传到公开 Release：

https://github.com/Sharl210/xiangqi-assist/releases/tag/v1.1

Release 提供以下文件：

- `xiangqi-assist-1.1-armv8.apk`：通用 ARMv8 版，兼容性优先。
- `xiangqi-assist-1.1-armv8-dotprod.apk`：适用于支持 ARMv8.2 dotprod 指令的设备。
- `SHA256SUMS.txt`：上述两个公开 APK 的实时 SHA-256 校验值。

两个 APK 均应显示应用名“象棋辅助”、包名 `com.xiangqi.assist`、版本 `1.1`、版本代码 `2`，并通过 APK Signature Scheme v2 校验。

仓库源码归档不内嵌 APK 二进制；请以 Release 页面及其同批生成的 `SHA256SUMS.txt` 为准。
