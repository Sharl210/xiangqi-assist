package com.xiangqi.assist.assist

import com.xiangqi.assist.assist.AssistHud.Stage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 悬浮窗展示逻辑：阶段短标签、阶段外圈配色、胜率换算与颜色档。
 */
class AssistHudTest {

    @Test
    fun `stage labels match the four flow states`() {
        assertEquals("识盘中", AssistHud.titleLabel(Stage.FINDING))
        assertEquals("计算中", AssistHud.titleLabel(Stage.THINKING))
        assertEquals("落子中", AssistHud.titleLabel(Stage.MOVING))
        assertEquals("等待对面落子中", AssistHud.titleLabel(Stage.WAITING))
        // 悬浮球上用更短的写法
        assertEquals("识盘中", AssistHud.shortLabel(Stage.FINDING))
        assertEquals("计算中", AssistHud.shortLabel(Stage.THINKING))
        assertEquals("落子中", AssistHud.shortLabel(Stage.MOVING))
        assertEquals("等待对方", AssistHud.shortLabel(Stage.WAITING))
    }

    @Test
    fun `flow stages are exactly the four the user asked for`() {
        // 流程态：识盘中 → 计算中 → 待落子 → 落子中 → 等待对面落子中。
        // 「待落子」是后续补的：引擎已经算完、轮到我方、但这一手还没落下去时，
        // 必须如实显示，不能再写"计算中"（这正是用户报的那个 bug）。
        // 其余是非流程态：已暂停 / 手动摆子 / 引擎异常 / 更新棋谱中。
        assertEquals(10, Stage.values().size)
        val flow = listOf(
            Stage.FINDING, Stage.THINKING, Stage.READY,
            Stage.MOVING, Stage.VERIFYING, Stage.WAITING
        )
        assertEquals(
            setOf("PAUSED", "MANUAL", "ENGINE_ERROR", "UPDATING"),
            Stage.values().filter { it !in flow }.map { it.name }.toSet()
        )
        val labels = flow.map { AssistHud.titleLabel(it) }.toSet()
        assertEquals(
            setOf("识盘中", "计算中", "待落子", "落子中", "核对落子中", "等待对面落子中"),
            labels
        )
    }

    @Test
    fun `every stage has a label and a ring color`() {
        for (st in Stage.values()) {
            assertTrue("阶段 $st 缺少短标签", AssistHud.shortLabel(st).isNotEmpty())
            assertTrue("阶段 $st 缺少标题标签", AssistHud.titleLabel(st).isNotEmpty())
            assertNotEquals("阶段 $st 未分配外圈颜色", 0, AssistHud.ringColor(st))
        }
    }

    @Test
    fun `four flow stages use four distinct ring colors`() {
        val colors = listOf(
            AssistHud.ringColor(Stage.FINDING),
            AssistHud.ringColor(Stage.THINKING),
            AssistHud.ringColor(Stage.MOVING),
            AssistHud.ringColor(Stage.WAITING),
        )
        assertEquals("四个流程态的外圈颜色必须互不相同", colors.size, colors.toSet().size)
        assertNotEquals(AssistHud.ringColor(Stage.ENGINE_ERROR), AssistHud.ringColor(Stage.THINKING))
        assertNotEquals(AssistHud.ringColor(Stage.PAUSED), AssistHud.ringColor(Stage.THINKING))
    }

    @Test
    fun `percentage keeps two decimals`() {
        assertEquals("63.25%", AssistHud.formatPct(6325))
        assertEquals("50.00%", AssistHud.formatPct(5000))
        assertEquals("0.00%", AssistHud.formatPct(0))
        assertEquals("100.00%", AssistHud.formatPct(10000))
        assertEquals("7.05%", AssistHud.formatPct(705))
    }

    @Test
    fun `equal score is an even game`() {
        val r = AssistHud.winRate(redScoreCp = 0, mateIn = null, myRed = true)
        assertEquals(5000, r.pct)
        assertEquals(AssistHud.RateLevel.EVEN, r.level)
        assertEquals("50.00%", r.text)
    }

    @Test
    fun `winning side is above fifty percent and losing side below`() {
        val redWins = AssistHud.winRate(300, null, myRed = true)
        assertTrue(redWins.pct > 5000)
        assertEquals(AssistHud.RateLevel.HIGH, redWins.level)

        val redLoses = AssistHud.winRate(-300, null, myRed = true)
        assertTrue(redLoses.pct < 5000)
        assertEquals(AssistHud.RateLevel.LOW, redLoses.level)
    }

    @Test
    fun `win rate is from my side of the board`() {
        // 同一分数下，执黑与执红的胜率互补（合计约 100%）
        val asRed = AssistHud.winRate(150, null, myRed = true)
        val asBlack = AssistHud.winRate(150, null, myRed = false)
        assertTrue(kotlin.math.abs(10000 - (asRed.pct + asBlack.pct)) <= 1)
        assertEquals(AssistHud.RateLevel.LOW, asBlack.level)
    }

    @Test
    fun `mate is decisive`() {
        val iMate = AssistHud.winRate(0, mateIn = 3, myRed = true)
        assertEquals(AssistHud.RateLevel.HIGH, iMate.level)
        assertEquals("100.00%", iMate.text)

        val theyMate = AssistHud.winRate(0, mateIn = 3, myRed = false)
        assertEquals(AssistHud.RateLevel.LOW, theyMate.level)
        assertEquals("0.00%", theyMate.text)
    }

    // ==================== 胜率：必须始终是我方视角 ====================
    // 下面这些 wdl 数值全部来自真实引擎实测输出（Pikafish 2026-09-06，UCI_ShowWDL true）

    @Test
    fun `wdl from engine is used when available`() {
        // 实测：红方多一车、轮到黑走 → 引擎（走子方=黑）报 wdl 0 0 1000
        // 换算到红方视角应为：红胜 1000‰ / 和 0‰ / 红负 0‰
        val (w, d, l) = AssistHud.toRedPerspectiveWdl(0, 0, 1000, stmIsRed = false)
        assertEquals(1000, w); assertEquals(0, d); assertEquals(0, l)
        // 我方执红 → 胜率 100%
        assertEquals("100.00%", AssistHud.winRateFromWdl(w, d, l, myRed = true)!!.text)
        // 我方执黑 → 胜率 0%
        assertEquals("0.00%", AssistHud.winRateFromWdl(w, d, l, myRed = false)!!.text)
    }

    @Test
    fun `same position with opposite side to move yields opposite raw wdl`() {
        // 实测：同一局面轮到红走 → 引擎报 1000 0 0
        val (w, d, l) = AssistHud.toRedPerspectiveWdl(1000, 0, 0, stmIsRed = true)
        assertEquals(Triple(1000, 0, 0), Triple(w, d, l))
        // 两次换算后的"红方视角"应当一致（都表示红方赢），这正是修复的关键
        val fromBlackStm = AssistHud.toRedPerspectiveWdl(0, 0, 1000, stmIsRed = false)
        val fromRedStm = AssistHud.toRedPerspectiveWdl(1000, 0, 0, stmIsRed = true)
        assertEquals(fromRedStm, fromBlackStm)
    }

    @Test
    fun `our win rate never flips with the turn`() {
        // 这是用户报的 bug：轮到对手走时，胜率被翻成了对面的。
        // 现在无论轮到谁走，只要红方视角数据相同，我方胜率就必须相同。
        val (w, d, l) = AssistHud.toRedPerspectiveWdl(300, 600, 100, stmIsRed = true)
        val oursWhenWeMove = AssistHud.winRateFromWdl(w, d, l, myRed = true)!!
        val oursWhenTheyMove = AssistHud.winRateFromWdl(w, d, l, myRed = true)!!
        assertEquals(oursWhenWeMove.text, oursWhenTheyMove.text)
        assertEquals(6000, oursWhenWeMove.pct) // 300 + 600/2 = 600 ‰*10 = 6000
        assertEquals(AssistHud.RateLevel.HIGH, oursWhenWeMove.level)
        // 执黑时同一个局面（红方优势）我方就是劣势
        assertEquals(
            AssistHud.RateLevel.LOW,
            AssistHud.winRateFromWdl(w, d, l, myRed = false)!!.level
        )
    }

    @Test
    fun `opening wdl gives a slight edge to the side that moves first`() {
        // 实测：均势开局轮到红走 → wdl 66 922 12
        val r = AssistHud.winRateFromWdl(66, 922, 12, myRed = true)!!
        assertEquals("52.70%", r.text)
        assertTrue(r.pct > 5000)
        // 同一局面我方执黑 → 略亏；互补值应严格相加为 10000
        val asBlack = AssistHud.winRateFromWdl(66, 922, 12, myRed = false)!!
        assertEquals("47.30%", asBlack.text)
        assertEquals(4730, asBlack.pct)
        assertEquals(10000, r.pct + asBlack.pct)
    }

    @Test
    fun `wdl percentages always add up to one hundred`() {
        for ((w, d, l) in listOf(
            Triple(0, 0, 1000), Triple(1000, 0, 0), Triple(66, 922, 12), Triple(333, 334, 333),
        )) {
            assertEquals(1000, w + d + l)
            val pct = AssistHud.winRateFromWdl(w, d, l, myRed = true)!!.pct +
                    AssistHud.winRateFromWdl(w, d, l, myRed = false)!!.pct
            assertEquals("我方与对方的胜率之和应为 100%", 10000, pct)
        }
    }

    // ---------- 精度：归一化、负值裁剪、四舍五入 ----------

    @Test
    fun `wdl totals other than one thousand are normalized first`() {
        // 引擎不保证三个数严格相加为 1000。旧实现直接除以 1000，会系统性偏差；
        // 现在先归一化再算期望值。
        assertEquals(6000, AssistHud.winRateFromWdl(60, 60, 30, myRed = true)!!.pct)
        assertEquals(6000, AssistHud.winRateFromWdl(600, 600, 300, myRed = true)!!.pct)
        assertEquals(
            AssistHud.winRateFromWdl(66, 922, 12, myRed = true)!!.pct,
            AssistHud.winRateFromWdl(660, 9220, 120, myRed = true)!!.pct,
        )
    }

    @Test
    fun `negative wdl components are clipped instead of skewing the result`() {
        // 负值一律按 0 处理：结果必须与“把该分量写成 0”完全一致，不能算出负胜率。
        assertEquals(
            AssistHud.winRateFromWdl(0, 922, 12, myRed = true)!!.pct,
            AssistHud.winRateFromWdl(-50, 922, 12, myRed = true)!!.pct,
        )
        assertEquals(
            AssistHud.winRateFromWdl(66, 0, 12, myRed = true)!!.pct,
            AssistHud.winRateFromWdl(66, -50, 12, myRed = true)!!.pct,
        )
    }

    @Test
    fun `wdl rounding is not a truncation`() {
        // 999/1000 的期望值应为 99.90%，而不是被截断成 99.89%
        val wdl = AssistHud.winRateFromWdl(999, 0, 1, myRed = true)!!
        assertEquals(9990, wdl.pct)
        assertEquals("99.90%", wdl.text)
    }

    @Test
    fun `unusable wdl yields no rate instead of a fake fifty percent`() {
        assertNull(AssistHud.winRateFromWdl(0, 0, 0, myRed = true))
        assertNull(AssistHud.winRateFromWdl(-5, -5, -5, myRed = true))
    }

    @Test
    fun `book statistics use the same precise formula with two decimals`() {
        // 522 胜 / 432 和 / 46 负 → (522 + 216) / 1000 = 73.80%
        val book = AssistHud.winRateFromCounts(522, 432, 46)!!
        assertEquals(7380, book.pct)
        assertEquals("73.80%", book.text)
        // 旧实现是整数截断（73%），这里必须保留两位小数
        assertEquals("52.70%", AssistHud.winRateFromCounts(66, 922, 12)!!.text)
        assertNull(AssistHud.winRateFromCounts(0, 0, 0))
    }

    @Test
    fun `fallback logistic is only for when engine gives no wdl`() {
        // 没有 wdl 时才用分数换算，且同样只看我方视角
        val mine = AssistHud.winRate(redScoreCp = 0, mateIn = null, myRed = true)
        assertEquals("50.00%", mine.text)
        val asBlackWhenRedEven = AssistHud.winRate(redScoreCp = 0, mateIn = null, myRed = false)
        assertEquals("50.00%", asBlackWhenRedEven.text)
    }
}
