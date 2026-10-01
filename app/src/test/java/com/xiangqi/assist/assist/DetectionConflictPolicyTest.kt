package com.xiangqi.assist.assist

import com.xiangqi.assist.gamelogic.Piece
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DetectionConflictPolicyTest {
    @Test fun `dominant same-color conflict is eligible for bounded retry`() {
        assertTrue(
            DetectionConflictPolicy.shouldTryDominantResolution(
                Piece.BPAO, Piece.BZU, 0.80, 0.54,
            )
        )
    }

    @Test fun `small margin is not silently resolved`() {
        assertFalse(
            DetectionConflictPolicy.shouldTryDominantResolution(
                Piece.BPAO, Piece.BZU, 0.68, 0.54,
            )
        )
    }

    @Test fun `opposite-color conflict is never resolved by score alone`() {
        assertFalse(
            DetectionConflictPolicy.shouldTryDominantResolution(
                Piece.BPAO, Piece.WPAO, 0.90, 0.20,
            )
        )
    }
}
