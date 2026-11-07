package qilin.ucfs

import org.junit.BeforeClass
import org.junit.Test
import qilin.core.PTA
import qilin.core.PointsToAnalysis
import qilin.core.pag.AllocNode
import qilin.core.pag.ContextAllocNode
import qilin.core.pag.ContextField
import qilin.core.pag.ContextVarNode
import qilin.core.pag.LocalVarNode
import qilin.core.pag.Node
import qilin.core.pag.Parm
import qilin.core.pag.VarNode
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * M2 acceptance: Qilin's core (Spark, `insens`) driven from Kotlin, and the CI constraint graph
 * exported by [CiPag] checked against Spark's own solved PAG.
 */
class CorePagTest {
    companion object {
        const val FS1 = "qilin.microben.core.field.FieldSensitivity1"

        @JvmStatic
        @BeforeClass
        fun setUp() {
            val root = File("..").canonicalFile
            QilinSession.setUp(
                listOf(
                    "-apppath", File(root, "qilin.microben/build/classes/java/main").path,
                    "-mainclass", FS1,
                    "-se", "-jre=" + File(root, "artifact/benchmarks/JREs/jre1.6.0_45").path,
                    "-clinit=ONFLY",
                )
            )
        }
    }

    private val fs1Main = "<$FS1: void main(java.lang.String[])>"
    private val fs1Init = "<$FS1\$A: void <init>(java.lang.Object,java.lang.Object)>"

    private fun inMethod(n: Node, sig: String) = n is LocalVarNode && n.method.signature == sig
    private fun isThisOf(n: Node, sig: String) =
        inMethod(n, sig) && (n as LocalVarNode).variable.let { it is Parm && it.index == PointsToAnalysis.THIS_NODE }

    @Test
    fun sparkRunsThroughSessionAndReachesMainAndConstructor() {
        val pta = QilinSession.runSpark(FS1)
        val sigs = pta.nakedReachableMethods.map { it.signature }.toSet()
        assertTrue(fs1Main in sigs, "main reachable")
        assertTrue(fs1Init in sigs, "A.<init> reachable")
    }

    @Test
    fun exportedGraphHasTheStatementsOfFieldSensitivity1() {
        val pag = CiPag.build(QilinSession.runSpark(FS1))
        // Jimple: `this := @this; this.f = o1` -- the store base is the local that receives the `this` parameter
        val thisLocals = pag.edges.filter { it.kind == EdgeKind.ASSIGN && isThisOf(pag.nodes[it.src], fs1Init) }.map { it.dst }.toSet()
        assertEquals(1, thisLocals.size, "one local receives @this in A.<init>")
        val stores = pag.edges.filter { it.kind == EdgeKind.STORE && it.dst in thisLocals }
        assertEquals(2, stores.size, "this.f = o1; this.g = o2 in A.<init>: $stores")
        assertEquals(2, stores.map { it.field }.toSet().size, "two distinct fields f and g")
        assertTrue(pag.edges.any { it.origin == EdgeOrigin.CALL_ARG && inMethod(pag.nodes[it.src], fs1Main) }, "args of new A(o1, o2)")
        assertTrue(pag.edges.any { it.origin == EdgeOrigin.RECV_THIS && isThisOf(pag.nodes[it.dst], fs1Init) }, "receiver -> this")
    }

    /** Spark's solved global PAG reduced to CI program edges (see M2-qilin-core.md §5). */
    private fun sparkProgramEdges(pta: PTA, pag: CiPag): Set<Triple<EdgeKind, Pair<Int, Int>, Int>> {
        val g = pta.pag
        fun ci(n: Node): Node = when (n) {
            is ContextVarNode -> n.base()
            is ContextAllocNode -> n.base()
            else -> n
        }
        fun id(n: Node): Int = pag.idOf(ci(n)).also { check(it >= 0) { "Spark node missing in CiPag: ${describe(ci(n))}" } }
        fun fid(f: Any): Int = pag.fields.indexOf(f).also { check(it >= 0) { "field missing in CiPag: $f" } }
        val rootThis = g.getMethodPAG(qilin.core.PTAScene.v().fakeMainMethod).nodeFactory().caseThis()
        val out = HashSet<Triple<EdgeKind, Pair<Int, Int>, Int>>()
        for ((o, vars) in g.alloc) for (v in vars) {
            val target = ci(v)
            val isReceiverToThis = target is LocalVarNode && target.variable.let { it is Parm && it.index == PointsToAnalysis.THIS_NODE } && target != rootThis
            if (!isReceiverToThis) out.add(Triple(EdgeKind.ALLOC, id(o) to id(v), -1))
        }
        for ((s, ts) in g.simple) for (t in ts) {
            if (s is ContextField || t is ContextField) continue // derived by the solver from points-to sets
            out.add(Triple(EdgeKind.ASSIGN, id(s) to id(t), -1))
        }
        for ((fr, vars) in g.load) for (v in vars) out.add(Triple(EdgeKind.LOAD, id(fr.base) to id(v), fid(fr.field)))
        for ((fr, vars) in g.storeInv) for (v in vars) out.add(Triple(EdgeKind.STORE, id(v) to id(fr.base), fid(fr.field)))
        return out
    }

    private fun checkExportEqualsSpark(mainClass: String) {
        val pta = QilinSession.runSpark(mainClass)
        val pag = CiPag.build(pta)
        val exported = pag.edges.filter { it.origin != EdgeOrigin.RECV_THIS }.map { Triple(it.kind, it.src to it.dst, it.field) }.toSet()
        val spark = sparkProgramEdges(pta, pag)
        for (k in EdgeKind.values()) {
            val a = exported.filter { it.first == k }.toSet()
            val b = spark.filter { it.first == k }.toSet()
            fun show(s: Set<Triple<EdgeKind, Pair<Int, Int>, Int>>) =
                s.take(5).map { "${describe(pag.nodes[it.second.first])} -> ${describe(pag.nodes[it.second.second])} f=${it.third}" }
            assertEquals(b.size, a.size, "$mainClass $k: only in export ${show(a - b)}; only in Spark ${show(b - a)}")
            assertEquals(b, a, "$mainClass $k")
        }
        assertTrue(pag.count(EdgeKind.ALLOC) > 0 && pag.count(EdgeKind.ASSIGN) > 0)
    }

    @Test
    fun exportEqualsSparkConstraintGraphOnMicrobenchmarks() {
        for (mc in listOf(FS1, "qilin.microben.core.assign.Receiver2This", "qilin.microben.core.call.VirtualCall1", "qilin.microben.core.array.ArrayCopy")) {
            checkExportEqualsSpark(mc)
        }
    }
}
