package com.xiangqi.assist.assist

/**
 * 前台窗口切换保护的纯逻辑规则。
 *
 * 只有系统报告的真实全屏应用窗口才参与判断；浮窗、小窗、画中画、状态栏、输入法、
 * 通知面板和权限对话框都不改变基准应用。应用自己的全屏控制页仍属于真实切出目标，
 * 这样自动手势不会误落到本应用界面；首次观察本应用时不建立棋盘基准。
 */
object ForegroundAppPolicy {
    sealed class Decision {
        data object IGNORE : Decision()
        data class BASELINE(val packageName: String) : Decision()
        data class CHANGED(val previous: String, val current: String) : Decision()
    }

    fun observe(
        ownerPackage: String,
        baselinePackage: String?,
        packageName: String?,
        isFullScreen: Boolean,
    ): Decision {
        val owner = ownerPackage.trim()
        val pkg = packageName?.trim().orEmpty()
        // 服务自己的悬浮窗不是 application window，不会走到这里；空包名、非全屏
        // 和瞬时系统层都不构成切出。
        if (pkg.isEmpty() || !isFullScreen || isTransientSystemOverlay(pkg)) {
            return Decision.IGNORE
        }
        // 首次观察发生在本应用控制页时不把它当作棋盘基准；等用户切到真实棋盘应用再建立。
        if (baselinePackage == null) {
            return if (pkg == owner) Decision.IGNORE else Decision.BASELINE(pkg)
        }
        return if (baselinePackage == pkg) Decision.IGNORE
        else Decision.CHANGED(baselinePackage, pkg)
    }

    private fun isTransientSystemOverlay(packageName: String): Boolean = when {
        packageName == "android" -> true
        packageName.startsWith("com.android.systemui") -> true
        packageName.startsWith("com.google.android.permissioncontroller") -> true
        packageName.startsWith("com.android.permissioncontroller") -> true
        packageName == "com.google.android.inputmethod.latin" -> true
        packageName == "com.android.inputmethod.latin" -> true
        packageName == "com.samsung.android.honeyboard" -> true
        packageName.contains("inputmethod", ignoreCase = true) -> true
        packageName.contains("keyboard", ignoreCase = true) -> true
        else -> false
    }
}
