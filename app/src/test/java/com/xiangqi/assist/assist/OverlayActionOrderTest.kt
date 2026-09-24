package com.xiangqi.assist.assist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayActionOrderTest {
    @Test
    fun `overlay XML keeps board region last and reset before simulation`() {
        val xml = java.io.File("src/main/res/layout/overlay_panel.xml").readText()
        assertTrue(xml.indexOf("overlay_btn_stop") < xml.indexOf("overlay_btn_sim"))
        assertTrue(xml.indexOf("overlay_btn_board_region") > xml.indexOf("overlay_btn_hash"))
    }

    @Test
    fun `variation button is green only while next move request is armed`() {
        val source = java.io.File("src/main/java/com/xiangqi/assist/assist/ui/OverlayPanelView.kt").readText()
        assertTrue(source.contains("btnChange.setTextColor(if (model.nextVariationPending) ON_COLOR else normalColor)"))
        assertTrue(source.contains("private const val ON_COLOR = 0xFF69F0AE.toInt()"))
        assertTrue(source.contains("private val normalColor: Int = Color.WHITE"))
    }

    @Test
    fun `second variation tap clears the stored intent before normal processing`() {
        val source = java.io.File("src/main/java/com/xiangqi/assist/assist/ScreenAssistService.kt").readText()
        val start = source.indexOf("private fun cycleCandidate()")
        val cancel = source.indexOf("if (nextVariationArmed)", start)
        val clear = source.indexOf("clearNextVariationRequest()", cancel)
        val cancelReturn = source.indexOf("return", clear)
        val arm = source.indexOf("nextVariationArmed = NextVariationPolicy.toggleArmed", start)
        assertTrue(start >= 0)
        assertTrue(cancel > start)
        assertTrue(clear > cancel)
        assertTrue(cancelReturn > clear)
        assertTrue(arm > cancelReturn)
        assertTrue(source.contains("已取消下一手变招"))
    }

    @Test
    fun `variation tap can arm while service has no board yet`() {
        val source = java.io.File("src/main/java/com/xiangqi/assist/assist/ScreenAssistService.kt").readText()
        val start = source.indexOf("private fun cycleCandidate()")
        val armAt = source.indexOf("nextVariationArmed = NextVariationPolicy.toggleArmed", start)
        val noFenAt = source.indexOf("if (currentFen.isEmpty() || currentRedGo != mySideIsRed())", armAt)
        assertTrue(armAt > start)
        assertTrue(noFenAt > armAt)
        assertTrue(!source.contains("变招只适用于当前我方待落子"))
    }

    @Test
    fun `capture analysis crops never escape the configured region`() {
        val region = doubleArrayOf(200.0, 200.0, 800.0, 800.0)
        assertEquals(region.toList(), BoardRegionGeometry.intersectCrop(null, region)!!.toList())
        val intersecting = BoardRegionGeometry.intersectCrop(
            doubleArrayOf(100.0, 300.0, 400.0, 700.0), region,
        )!!
        assertEquals(200.0, intersecting[0], 0.0001)
        assertEquals(300.0, intersecting[1], 0.0001)
        assertEquals(400.0, intersecting[2], 0.0001)
        assertEquals(700.0, intersecting[3], 0.0001)

        val disjoint = BoardRegionGeometry.intersectCrop(
            doubleArrayOf(0.0, 0.0, 100.0, 100.0), region,
        )!!
        assertEquals(region.toList(), disjoint.toList())
    }

    @Test
    fun `home entry order puts overlay assistance first`() {
        val xml = java.io.File("src/main/res/layout/activity_main.xml").readText()
        assertTrue(xml.indexOf("button_link") < xml.indexOf("button_play"))
        assertTrue(xml.indexOf("Widget.XiangqiAssist.Button.Primary", xml.indexOf("button_link")) >= 0)
        assertTrue(xml.indexOf("Widget.XiangqiAssist.Button.Secondary", xml.indexOf("button_play")) >= 0)
    }
}
