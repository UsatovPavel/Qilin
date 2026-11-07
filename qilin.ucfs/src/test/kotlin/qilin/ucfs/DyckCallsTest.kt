package qilin.ucfs

import org.junit.BeforeClass
import org.junit.Test
import org.junit.Assume.assumeTrue
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * M15 acceptance D15.0, D15.1 (and E1 on the test programs): variant A — call-site Dyck reachability with a context-insensitive
 * heap — on our own programs (`src/csprogs`).
 *  (0) the reference encoding cfl-idr heap-CI+pb keeps a flow through a static field between two calling contexts;
 *  (1) A: every `mayAlias` holds; the `notAlias` of the programs whose distinction needs only call-site parentheses hold
 *      (the heap is context-insensitive, so programs that need it — FieldAccess, HeapCross, NestedCtor — check `mayAlias` only);
 *  (2) A equals cfl-idr heap-CI+pb (under, parity) pair by pair on every test program.
 */
class DyckCallsTest {
    companion object {
        const val PKG = "qilin.ucfs.csprogs"
        const val CHECK = "$PKG.Check"

        @JvmStatic
        @BeforeClass
        fun setUp() = CutShortcutTest.setUp()

        val CALL_PRECISE = listOf("LocalFlow", "SelfReturn", "RecursionCtx", "StaticCross", "PartialBalance")
        // StaticSetter distinguishes x1/x2 only through the fields of b1/b2 (heap): A cannot (D49)
        val HEAP_MERGED = listOf("FieldAccess", "HeapCross", "NestedCtor", "StaticSetter")
        val jar: File? get() = System.getProperty("cflidr.jar")?.takeIf { it.isNotBlank() }?.let(::File)
    }

    private fun idrUnder(pta: qilin.core.PTA, pag: CiPag): Map<Int, Set<Int>> {
        val referenceJar = jar
        assumeTrue("Set -PcflidrJar or CFLIDR_JAR to run the cfl-idr comparison", referenceJar?.isFile == true)
        val idr = IdrExport.build(pta, pag, IdrExport.Encoding.HEAPCIPB)
        val dir = kotlin.io.path.createTempDirectory("idr-m15").toFile()
        val r = IdrRun.run(idr, checkNotNull(referenceJar), "parity", dir, "4g", 600)
        return IdrRun.pointsTo(idr, r.under)
    }

    private fun norm(pta: qilin.core.PTA, pag: CiPag, m: Map<Int, Set<Int>>): Map<Int, Set<Int>> {
        val root = SparkCompare.rootId(pta, pag)
        return pag.graph.varIds().associateWith { (m[it] ?: emptySet()) - root }.filterValues { it.isNotEmpty() }
    }

    @Test
    fun heapCiPbKeepsFlowsThroughStaticFields() {
        val pta = QilinSession.runSpark("$PKG.StaticCross")
        val pag = CiPag.build(pta)
        val checks = AliasChecks.evaluate(pta, pag, idrUnder(pta, pag), CHECK)
        assertTrue(checks.any { it.may })
        for (c in checks.filter { it.may }) assertTrue(c.holds, "cfl-idr heap-CI+pb: mayAlias must hold: ${c.where}")
    }

    @Test
    fun variantAMeetsTheExpectations() {
        for (p in CALL_PRECISE + HEAP_MERGED) {
            val pta = QilinSession.runSpark("$PKG.$p")
            val pag = CiPag.build(pta)
            val pts = DyckCalls.build(pta, pag).solve().pointsTo
            val checks = AliasChecks.evaluate(pta, pag, pts, CHECK)
            assertTrue(checks.any { it.may }, "$p: has mayAlias")
            for (c in checks.filter { it.may }) assertTrue(c.holds, "$p: mayAlias must hold under A: ${c.where}")
            if (p in CALL_PRECISE) for (c in checks.filter { !it.may }) assertTrue(c.holds, "$p: notAlias must hold under A: ${c.where}")
        }
    }

    @Test
    fun variantAEqualsCflIdrHeapCiPb() {
        for (p in CALL_PRECISE + HEAP_MERGED) {
            val pta = QilinSession.runSpark("$PKG.$p")
            val pag = CiPag.build(pta)
            val a = norm(pta, pag, DyckCalls.build(pta, pag).solve().pointsTo)
            val idr = norm(pta, pag, idrUnder(pta, pag))
            assertEquals(idr, a, "$p: A vs cfl-idr heap-CI+pb (under)")
        }
    }
}
