package qilin.ucfs.banchmark

import qilin.ucfs.graph.CiPag
import qilin.ucfs.idr.DyckCalls
import qilin.ucfs.QilinSession

import org.junit.BeforeClass
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * M17 acceptance D17.1: variants A and A′ with Spark's filters (typed product graph "vertex × tag") and the exact reduction.
 *  (a) typed, no parentheses = Spark per variable (the typed heap-CI graph without call-site labels is Spark's problem);
 *  (b) typed A ⊆ Spark and ⊆ untyped A per variable; on LocalFlow typed A is strictly more precise than Spark;
 *  (c) the reduction does not change the answer (typed / untyped × all / none / Zipper).
 */
class DyckTypedTest {
    companion object {
        const val PKG = "qilin.ucfs.banchmark.csprogs"

        @JvmStatic
        @BeforeClass
        fun setUp() = CutShortcutTest.setUp()

        val PROGRAMS = DyckSelectiveTest.PROGRAMS
    }

    private fun sub(a: Map<Int, Set<Int>>, b: Map<Int, Set<Int>>) = a.all { (v, os) -> (b[v] ?: emptySet()).containsAll(os) }

    private class Run(p: String) {
        val pta = QilinSession.runSpark("$PKG.$p", callDetails = true)
        val pag = CiPag.build(pta)
        val root = SparkCompare.rootId(pta, pag)
        val zs = DyckCalls.zipperSelection(pta)
        fun norm(m: Map<Int, Set<Int>>) = pag.graph.varIds().associateWith { (m[it] ?: emptySet()) - root }.filterValues { it.isNotEmpty() }
        val spark = norm(SparkCompare.sparkSets(pta, pag))
        fun solve(sel: ((soot.SootMethod) -> Boolean)?, typed: Boolean, reduce: Boolean): Map<Int, Set<Int>> {
            val d = if (typed) DyckCalls.buildTyped(pta, pag, sel) else DyckCalls.build(pta, pag, sel)
            return norm((if (reduce) d.reduced() else d).solve().pointsTo)
        }
    }

    @Test
    fun typedWithoutParenthesesIsSpark() {
        for (p in PROGRAMS) {
            val r = Run(p)
            assertEquals(r.spark, r.solve({ false }, typed = true, reduce = false), "$p: typed heap-CI without parentheses = Spark")
        }
    }

    @Test
    fun typedAIsBelowSparkAndUntypedA() {
        for (p in PROGRAMS) {
            val r = Run(p)
            val ta = r.solve(null, typed = true, reduce = false)
            assertTrue(sub(ta, r.spark), "$p: typed A ⊆ Spark")
            assertTrue(sub(ta, r.solve(null, typed = false, reduce = false)), "$p: typed A ⊆ untyped A")
        }
        val r = Run("LocalFlow")
        val ta = r.solve(null, typed = true, reduce = false)
        assertTrue(sub(ta, r.spark) && ta != r.spark, "LocalFlow: typed A is strictly more precise than Spark")
    }

    @Test
    fun reductionKeepsTheAnswer() {
        for (p in PROGRAMS) {
            val r = Run(p)
            for (typed in listOf(false, true)) for ((name, sel) in listOf<Pair<String, ((soot.SootMethod) -> Boolean)?>>(
                "all" to null, "none" to { _ -> false }, "zipper" to { m -> m in r.zs })) {
                assertEquals(r.solve(sel, typed, reduce = false), r.solve(sel, typed, reduce = true), "$p typed=$typed $name: reduction is exact")
            }
        }
    }
}
