package qilin.ucfs.banchmark

import qilin.ucfs.graph.CiPag
import qilin.ucfs.graph.CutShortcut
import qilin.ucfs.QilinSession
import qilin.ucfs.graph.TypedGraph
import qilin.ucfs.graph.describe

import org.junit.BeforeClass
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Shared run for the M18 tests: B with the container pattern, production solver (`--solver=rsm --rsm=v2+s+nf --reduce --reach-only`). */
internal object ContainerRun {
    val PATTERNS = CutShortcut.parse("field,local,container")

    class Run(val checks: List<AliasChecks.Check>, val notSubset: List<String>, val stats: Map<String, Int>)

    fun run(main: String, assert: String): Run {
        val pta = QilinSession.runSpark(main)
        val pag = CiPag.build(pta)
        val spark = SparkCompare.sparkSets(pta, pag)
        val cs = CutShortcut.apply(pta, pag, TypedGraph.build(pta, pag), PATTERNS)
        val pts = RsmSolve.solve(cs.graph, "v2+s+nf", pag.graph.varIds(), reduce = true, reachOnly = true, profile = false, heap = false).pointsTo
        val root = SparkCompare.rootId(pta, pag)
        val notSubset = pag.graph.varIds().filter { v -> !(spark[v] ?: emptySet()).containsAll((pts[v] ?: emptySet()) - root) }
            .map { describe(pag.nodes[it]) }
        return Run(AliasChecks.evaluate(pta, pag, pts, assert), notSubset, cs.stats)
    }

    fun assertSound(name: String, r: Run) {
        assertTrue(r.checks.any { it.may }, "$name: has mayAlias")
        for (c in r.checks.filter { it.may }) assertTrue(c.holds, "$name: mayAlias must hold: ${c.where}")
        assertEquals(emptyList(), r.notSubset, "$name: subset of Spark")
    }
}

/**
 * M18 acceptance D18.1: the container access pattern of Cut-Shortcut (Ma et al. PLDI'23 §3.3) as a rewrite of the typed graph.
 *  (a) Qilin's microbenchmarks `context.collections` (not written by us): every `mayAlias` holds, the result is a subset of
 *      Spark, and some `notAlias` hold (none hold without the container pattern, M14);
 *  (b) our programs: on ContainerFlow (only the described API) every `notAlias` holds; on ContainerEscape (copy constructor,
 *      addAll, a wrapper, a user subclass, Map.Entry.setValue) every `mayAlias` holds; both are subsets of Spark.
 */
class CutShortcutContainerTest {
    companion object {
        const val PKG = "qilin.ucfs.banchmark.csprogs"
        const val CHECK = "$PKG.Check"

        @JvmStatic
        @BeforeClass
        fun setUp() = CutShortcutTest.setUp()
    }

    @Test
    fun ourProgramsWithContainers() {
        val flow = ContainerRun.run("$PKG.ContainerFlow", CHECK)
        ContainerRun.assertSound("ContainerFlow", flow)
        for (c in flow.checks.filter { !it.may }) assertTrue(c.holds, "ContainerFlow: notAlias must hold: ${c.where}")
        ContainerRun.assertSound("ContainerEscape", ContainerRun.run("$PKG.ContainerEscape", CHECK))
        // D67: the methods added after the comparison with the authors' specification
        val more = ContainerRun.run("$PKG.ContainerMore", CHECK)
        ContainerRun.assertSound("ContainerMore", more)
        for (c in more.checks.filter { !it.may }) assertTrue(c.holds, "ContainerMore: notAlias must hold: ${c.where}")
        // D65: counterexamples of the M18 critic (custom subclasses of java.util skeletons, entries passed to callbacks, direct writes)
        ContainerRun.assertSound("ContainerCustom", ContainerRun.run("$PKG.ContainerCustom", CHECK))
        ContainerRun.assertSound("ContainerArray", ContainerRun.run("$PKG.ContainerArray", CHECK))
        ContainerRun.assertSound("ContainerHoles", ContainerRun.run("$PKG.ContainerHoles", CHECK))
    }
}

/** M18 acceptance D18.1 (a), see [CutShortcutContainerTest]: Qilin's `context.collections` microbenchmarks (one Qilin session per JVM). */
class CutShortcutContainerMicrobenTest {
    companion object {
        @JvmStatic
        @BeforeClass
        fun setUp() = CorePagTest.setUp()

        val COLLECTIONS = listOf("ArrayList0", "HashMap0", "HashSet0", "HashTable0", "LinkedList0", "PriorityQueue0", "Stack0", "TreeMap0",
            "TreeSet0", "Vector0").map { "qilin.microben.context.collections.$it" }
    }

    @Test
    fun collectionsMicrobenchmarks() {
        var held = 0; var total = 0
        for (mc in COLLECTIONS) {
            val r = ContainerRun.run(mc, CutShortcutMicrobenTest.ASSERT)
            ContainerRun.assertSound(mc, r)
            val nots = r.checks.filter { !it.may }
            total += nots.size; held += nots.count { it.holds }
            println("$mc notAlias ${nots.count { it.holds }}/${nots.size} cs=${r.stats}"); System.out.flush()
        }
        println("CONTAINER-MICROBEN notAlias held $held/$total")
        // the DoD asks for more than 0/10; 7/10 is what the pattern reaches (M18 critic: keep the level reached)
        assertTrue(held >= 7, "at least 7 notAlias of context.collections hold under the container pattern")
    }
}
