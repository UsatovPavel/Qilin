package qilin.ucfs.benchmark

import qilin.ucfs.ucfs.RsmSolve
import qilin.ucfs.graph.ImportedGraph
import qilin.ucfs.graph.CutShortcutOptimizedGraph
import qilin.ucfs.QilinSession
import qilin.ucfs.graph.TypedGraph
import qilin.ucfs.ucfs.TypedPointsToShared
import qilin.ucfs.graph.describe

import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * M14 acceptance D14.1 (a), (b): Cut-Shortcut patterns on our own programs (`src/csprogs`, Ma et al. PLDI'23 Fig. 1, 3, 5 and
 * negative cases). Every `mayAlias` must hold (soundness), the `notAlias` of the positive programs must hold (the pattern
 * applies), and the result is a subset of Spark for every variable.
 */
class CutShortcutTest {
    companion object {
        const val PKG = "qilin.ucfs.benchmark.csprogs"
        const val CHECK = "$PKG.Check"

        @JvmStatic
        @BeforeClass
        fun setUp() {
            val root = File("..").canonicalFile
            val dir = System.getProperty("csprogs.dir") ?: error("csprogs.dir not set (see build.gradle)")
            QilinSession.setUp(
                listOf(
                    "-apppath", dir, "-mainclass", "$PKG.FieldAccess",
                    "-se", "-jre=" + File(root, "artifact/benchmarks/JREs/jre1.6.0_45").path, "-clinit=ONFLY",
                )
            )
        }

        val POSITIVE = listOf("FieldAccess", "NestedCtor", "LocalFlow", "StaticSetter", "SelfReturn")
        val NEGATIVE = listOf("Redefined", "Recursive", "TwoTargets", "NullArg")
        val ALL = CutShortcutOptimizedGraph.parse("field,local")
    }

    private class Run(val checks: List<AliasChecks.Check>, val subsetOfSpark: List<String>, val stats: Map<String, Int>)

    /** The production configuration (`--solver=rsm --rsm=v2+s+nf --reduce --reach-only`) or the plain v2 solver. */
    private var rsm = false

    private fun run(prog: String): Run {
        val pta = QilinSession.runSpark("$PKG.$prog")
        val pag = ImportedGraph.build(pta)
        val spark = SparkCompare.sparkSets(pta, pag)
        val cs = CutShortcutOptimizedGraph.apply(pta, pag, TypedGraph.build(pta, pag), ALL)
        val pts = if (rsm) RsmSolve.solve(cs.graph, "v2+s+nf", pag.graph.varIds(), reduce = true, reachOnly = true, profile = false, heap = false).pointsTo
            else TypedPointsToShared.solve(cs.graph).pointsTo
        val root = SparkCompare.rootId(pta, pag)
        val notSubset = pag.graph.varIds().filter { v -> !(spark[v] ?: emptySet()).containsAll((pts[v] ?: emptySet()) - root) }
            .map { describe(pag.nodes[it]) }
        return Run(AliasChecks.evaluate(pta, pag, pts, CHECK), notSubset, cs.stats)
    }

    private fun assertSound(prog: String, r: Run) {
        assertTrue(r.checks.any { it.may }, "$prog: has mayAlias assertions")
        for (c in r.checks.filter { it.may }) assertTrue(c.holds, "$prog: mayAlias must hold (soundness): ${c.where}")
        assertEquals(emptyList(), r.subsetOfSpark, "$prog: result is a subset of Spark")
    }

    @Test
    fun positivePatternsAreSoundAndPrecise() {
        for (p in POSITIVE) {
            val r = run(p)
            assertSound(p, r)
            assertTrue(r.checks.any { !it.may }, "$p: has notAlias assertions")
            for (c in r.checks.filter { !it.may }) assertTrue(c.holds, "$p: notAlias must hold with Cut-Shortcut: ${c.where} (stats ${r.stats})")
        }
    }

    @Test
    fun productionRsmSolverGivesTheSameVerdicts() {
        rsm = true
        try {
            for (p in POSITIVE + NEGATIVE) {
                val r = run(p)
                assertSound("$p (rsm v2+s+nf, reduce)", r)
                for (c in r.checks.filter { !it.may }) assertTrue(c.holds, "$p (rsm): notAlias must hold: ${c.where}")
            }
        } finally { rsm = false }
    }

    @Test
    fun negativeCasesStaySound() {
        for (p in NEGATIVE) {
            val r = run(p)
            assertSound(p, r)
            for (c in r.checks.filter { !it.may }) assertTrue(c.holds, "$p: notAlias must hold: ${c.where}")
        }
    }

    @Test
    fun callOnlyParamsExcludeTheRootEntry() {
        val pta = QilinSession.runSpark("$PKG.FieldAccess")
        val pag = ImportedGraph.build(pta)
        val only = CutShortcutOptimizedGraph.callOnlyParams(pag)
        val names = only.map { describe(pag.nodes[it]) }
        assertTrue(names.none { it.contains("FakeMain") && it.contains("THIS_NODE") }, "FakeMain.this has the ROOT input: $names")
        assertTrue(names.any { it.contains("Box: void setItem") && it.contains("THIS_NODE") }, "Box.setItem this: $names")
        assertTrue(names.any { it.contains("Box: void setItem") && it.contains("Parm 0") }, "Box.setItem item: $names")
    }
}
