package qilin.ucfs.benchmark

import qilin.ucfs.graph.ImportedGraph
import qilin.ucfs.idr.DyckCalls
import qilin.ucfs.QilinSession

import org.junit.BeforeClass
import org.junit.Test
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * M16 acceptance D16.1: variant A′ (parentheses only for calls of selected methods, other calls are plain flow) on our programs.
 *  (a) selecting every method gives exactly A; (b) any selection gives a superset of A per variable; (c) selecting no method gives a
 *  superset of A and a subset of the filter-less Andersen solution of the same graph, and on LocalFlow it no longer separates the
 *  two `select` calls (so it is not A); (d) the Zipper selection lies between A and `none`.
 */
class DyckSelectiveTest {
    companion object {
        const val PKG = "qilin.ucfs.benchmark.csprogs"

        @JvmStatic
        @BeforeClass
        fun setUp() = CutShortcutTest.setUp()

        val PROGRAMS = DyckCallsTest.CALL_PRECISE + DyckCallsTest.HEAP_MERGED + listOf("TwoTargets", "Redefined", "Recursive", "NullArg")
    }

    private fun sub(a: Map<Int, Set<Int>>, b: Map<Int, Set<Int>>) = a.all { (v, os) -> (b[v] ?: emptySet()).containsAll(os) }

    @Test
    fun selectionsAreBetweenAAndNone() {
        for (p in PROGRAMS) {
            val pta = QilinSession.runSpark("$PKG.$p", callDetails = true)
            val pag = ImportedGraph.build(pta)
            val root = SparkCompare.rootId(pta, pag)
            fun solve(sel: ((soot.SootMethod) -> Boolean)?) = DyckCalls.build(pta, pag, sel).solve().pointsTo.mapValues { it.value - root }.filterValues { it.isNotEmpty() }
            val a = solve(null)
            val all = solve { true }
            val none = solve { false }
            val zs = DyckCalls.zipperSelection(pta)
            val z = solve { it in zs }
            val andersen = ReferenceAndersenSolver.solve(pag.graph).mapValues { it.value - root }
            assertTrue(a == all, "$p: selecting every method gives A")
            assertTrue(sub(a, none) && sub(a, z), "$p: A ⊆ A′")
            assertTrue(sub(z, none), "$p: zipper selection ⊆ none")
            assertTrue(sub(none, andersen), "$p: none ⊆ Andersen without filters")
        }
        val pta = QilinSession.runSpark("$PKG.LocalFlow", callDetails = true)
        val pag = ImportedGraph.build(pta)
        assertNotEquals(DyckCalls.build(pta, pag) { false }.solve().pointsTo, DyckCalls.build(pta, pag).solve().pointsTo,
            "LocalFlow: without parentheses the two select calls merge")
    }
}
