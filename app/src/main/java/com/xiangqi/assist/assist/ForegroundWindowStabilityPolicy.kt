package com.xiangqi.assist.assist

/**
 * 前台应用切换的稳定确认器。
 *
 * Accessibility 窗口事件可能只在切换瞬间出现一次，因此服务会周期性重新观察当前全屏
 * application window，并用与画面稳定门相同的连续样本数确认目标。瞬时系统层和非全屏窗口
 * 不会打断正在累计的候选应用。
 */
object ForegroundWindowStabilityPolicy {
    data class State(
        val stablePackage: String? = null,
        val candidatePackage: String? = null,
        val candidateFrames: Int = 0,
    )

    sealed class Decision {
        data object IGNORE : Decision()
        data class PENDING(val packageName: String, val frames: Int, val required: Int) : Decision()
        data class BASELINE(val packageName: String) : Decision()
        data class CHANGED(val previous: String, val current: String) : Decision()
    }

    data class Result(val state: State, val decision: Decision)

    fun observe(
        ownerPackage: String,
        state: State,
        packageName: String?,
        isFullScreen: Boolean,
        requiredStableFrames: Int = FrameStabilityPolicy.REQUIRED_STABLE_FRAMES,
        observationAvailable: Boolean = true,
    ): Result {
        val required = requiredStableFrames.coerceAtLeast(1)
        if (!observationAvailable) {
            // Missing accessibility data is not a stable sample; never join counts across a gap.
            return Result(
                state.copy(candidatePackage = null, candidateFrames = 0),
                Decision.IGNORE,
            )
        }
        val base = ForegroundAppPolicy.observe(
            ownerPackage = ownerPackage,
            baselinePackage = state.stablePackage,
            packageName = packageName,
            isFullScreen = isFullScreen,
        )
        val pkg = packageName?.trim().orEmpty()
        if (pkg.isEmpty()) return Result(state, Decision.IGNORE)
        if (base is ForegroundAppPolicy.Decision.IGNORE) {
            // Seeing the already stable package confirms that a transient candidate was not
            // a real app switch. Other ignored system layers are simply transparent to state.
            return if (pkg == state.stablePackage) {
                Result(state.copy(candidatePackage = null, candidateFrames = 0), Decision.IGNORE)
            } else {
                Result(state, Decision.IGNORE)
            }
        }

        val nextFrames = if (state.candidatePackage == pkg) {
            state.candidateFrames + 1
        } else {
            1
        }
        val next = state.copy(candidatePackage = pkg, candidateFrames = nextFrames)
        if (nextFrames < required) {
            return Result(next, Decision.PENDING(pkg, nextFrames, required))
        }

        val committed = next.copy(
            stablePackage = pkg,
            candidatePackage = null,
            candidateFrames = 0,
        )
        return if (state.stablePackage == null) {
            Result(committed, Decision.BASELINE(pkg))
        } else {
            Result(committed, Decision.CHANGED(state.stablePackage, pkg))
        }
    }
}
