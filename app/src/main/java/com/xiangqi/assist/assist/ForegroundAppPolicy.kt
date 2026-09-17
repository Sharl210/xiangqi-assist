package com.xiangqi.assist.assist

/**
 * 前台窗口切换保护的纯逻辑规则。
 *
 * 只有全屏窗口才参与前台应用判断；浮窗、小窗、画中画、状态栏和输入法等瞬时窗口
 * 不改变基准应用，避免用户查看通知或浮层时被错误判定为切换。
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
        val pkg = packageName?.trim().orEmpty()
        if (pkg.isEmpty() || !isFullScreen || isTransientSystemOverlay(pkg)) {
            return Decision.IGNORE
        }
        // 首次观察发生在本应用的悬浮窗辅助页时不把它当作棋盘基准；等用户切到真实棋盘应用再建立。
        if (baselinePackage == null) {
            return if (pkg == ownerPackage) Decision.IGNORE else Decision.BASELINE(pkg)
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
