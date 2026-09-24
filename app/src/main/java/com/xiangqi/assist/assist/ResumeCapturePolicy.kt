package com.xiangqi.assist.assist

import android.content.Intent

/** Pure policy for holding a one-shot MediaProjection grant until the original app returns. */
object ResumeCapturePolicy {
    class Grant(resultCode: Int, data: Intent) {
        val resultCode: Int = resultCode
        val data: Intent = Intent(data)
    }

    fun shouldStartCapture(
        grant: Grant?,
        targetPackage: String?,
        currentPackage: String?,
    ): Boolean {
        if (grant == null) return false
        val target = targetPackage?.trim().orEmpty()
        val current = currentPackage?.trim().orEmpty()
        return target.isNotEmpty() && target == current
    }
}
