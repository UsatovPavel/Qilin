package qilin.ucfs.banchmark

import qilin.ucfs.graph.CiPag
import qilin.ucfs.graph.EdgeKind
import qilin.ucfs.graph.EdgeOrigin
import qilin.ucfs.idr.IdrExport
import qilin.ucfs.QilinSession

import org.junit.BeforeClass
import org.junit.Test
import qilin.core.pag.LocalVarNode
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * M6 acceptance (D6.2): export of the CI constraint graph to the interleaved-Dyck input of cfl-idr-with-gll.
 * Value-flow orientation; `normal` for intraprocedural ALLOC/ASSIGN, `op--c`/`cp--c` for argument/receiver passing and
 * return at call site c, `ob--f`/`cb--f` for store/load of field f.
 */
class IdrExportTest {
    companion object {
        @JvmStatic
        @BeforeClass
        fun setUp() = CorePagTest.setUp()

        val PROGRAMS = listOf(
            "qilin.microben.core.field.FieldSensitivity1",
            "qilin.microben.core.call.VirtualCall1",
            "qilin.microben.core.assign.ReturnValue2",
            "qilin.microben.core.array.ArrayCopy",
        )
        private val LABEL = Regex("normal|[oc][pb]--\\d+")
    }

    private fun pairs(pag: CiPag, kinds: Set<EdgeKind>, origins: Set<EdgeOrigin>) =
        pag.graph.edges.filter { it.kind in kinds && it.origin in origins }.map { it.src to it.dst }.toSet()

    @Test
    fun edgeKindsCorrespondToTheConstraintGraph() {
        val intra = setOf(EdgeOrigin.INTRA, EdgeOrigin.ROOT, EdgeOrigin.FINALIZER)
        for (mc in PROGRAMS) {
            val pta = QilinSession.runSpark(mc)
            val pag = CiPag.build(pta)
            val idr = IdrExport.build(pta, pag)
            for (e in idr.edges) assertTrue(LABEL.matches(e.label), "$mc: label ${e.label}")
            fun sel(p: (String) -> Boolean) = idr.edges.filter { p(it.label) }.map { it.src to it.dst }.toSet()
            assertEquals(pairs(pag, setOf(EdgeKind.ALLOC, EdgeKind.ASSIGN), intra), sel { it == "normal" }, "$mc: normal")
            assertEquals(pairs(pag, setOf(EdgeKind.STORE), EdgeOrigin.values().toSet()), sel { it.startsWith("ob--") }, "$mc: ob = store y -> b")
            assertEquals(pairs(pag, setOf(EdgeKind.LOAD), EdgeOrigin.values().toSet()), sel { it.startsWith("cb--") }, "$mc: cb = load b -> x")
            assertEquals(pairs(pag, setOf(EdgeKind.ASSIGN), setOf(EdgeOrigin.CALL_ARG, EdgeOrigin.RECV_THIS)), sel { it.startsWith("op--") }, "$mc: op = arg/receiver passing")
            assertEquals(pairs(pag, setOf(EdgeKind.ASSIGN), setOf(EdgeOrigin.CALL_RET)), sel { it.startsWith("cp--") }, "$mc: cp = return")
            // labels, not only endpoints (D23): field brackets carry the field of the constraint edge ...
            fun withField(k: EdgeKind) = pag.graph.edges.filter { it.kind == k }.map { Triple(it.src, it.dst, it.field) }.toSet()
            fun labelled(p: String) = idr.edges.filter { it.label.startsWith(p) }.map { Triple(it.src, it.dst, it.label.substringAfter("--").toInt()) }.toSet()
            assertEquals(withField(EdgeKind.STORE), labelled("ob--"), "$mc: ob--f carries the stored field")
            assertEquals(withField(EdgeKind.LOAD), labelled("cb--"), "$mc: cb--f carries the loaded field")
            // ... and a parenthesis id denotes exactly one call statement: the caller side is a variable of the statement's
            // method, the callee side a variable of one of the statement's targets in Spark's call graph
            for (e in idr.edges.filter { it.label[1] == 'p' }) {
                val c = e.label.substringAfter("--").toInt()
                assertTrue(c in 0 until idr.callSites, "$mc: call-site id $c in range")
                val stmt = idr.siteStmts[c]
                val cgEdges = pta.callGraph.edgesOutOf(stmt).asSequence().toList()
                assertTrue(cgEdges.isNotEmpty(), "$mc: id $c is a call statement of the call graph")
                val (callerV, calleeV) = if (e.label[0] == 'o') e.src to e.dst else e.dst to e.src
                val caller = pag.nodes[callerV]; val callee = pag.nodes[calleeV]
                if (caller is LocalVarNode) assertTrue(cgEdges.all { it.src() == caller.method }, "$mc: caller of $e is the method of statement $c")
                if (callee is LocalVarNode) assertTrue(cgEdges.any { it.tgt() == callee.method }, "$mc: callee of $e is a target of statement $c")
            }
            assertEquals(idr.siteStmts.size, idr.siteStmts.toSet().size, "$mc: one id per statement")
            // operands (D24): the expected parenthesis edges computed from Spark's call graph and the statements' operands
            val siteOf = idr.siteStmts.withIndex().associate { (i, st) -> st to i }
            val expected = HashSet<Triple<Int, Int, String>>()
            for (cg in pta.callGraph) {
                val st = cg.srcStmt(); val c = siteOf.getValue(st)
                val srcnf = pta.pag.getMethodPAG(cg.src()).nodeFactory(); val tnf = pta.pag.getMethodPAG(cg.tgt()).nodeFactory()
                val ie = st.invokeExpr
                for (i in 0 until ie.argCount) {
                    val a = ie.getArg(i)
                    if (a.type !is soot.RefLikeType || a is soot.jimple.NullConstant || cg.tgt().getParameterType(i) !is soot.RefLikeType) continue
                    expected.add(Triple(pag.idOf(srcnf.getNode(a)), pag.idOf(tnf.caseParm(i)), "op--$c"))
                }
                if (ie is soot.jimple.InstanceInvokeExpr) expected.add(Triple(pag.idOf(srcnf.getNode(ie.base)), pag.idOf(tnf.caseThis()), "op--$c"))
                if (st is soot.jimple.AssignStmt && st.leftOp.type is soot.RefLikeType && cg.tgt().returnType is soot.RefLikeType)
                    expected.add(Triple(pag.idOf(tnf.caseRet()), pag.idOf(srcnf.getNode(st.leftOp)), "cp--$c"))
            }
            val actual = idr.edges.filter { it.label[1] == 'p' }.map { Triple(it.src, it.dst, it.label) }
            assertEquals(expected, actual.toSet(), "$mc: every op/cp edge is an operand of its call statement, and all are exported")
            // counts per kind: no duplicates, one exported edge per constraint edge (normal, ob, cb) / per operand (op, cp)
            assertEquals(actual.size, actual.toSet().size, "$mc: no duplicate op/cp edges")
            assertEquals(pairs(pag, setOf(EdgeKind.ALLOC, EdgeKind.ASSIGN), intra).size, idr.count("normal"), "$mc: count normal")
            assertEquals(withField(EdgeKind.STORE).size, idr.count("ob--"), "$mc: count ob")
            assertEquals(withField(EdgeKind.LOAD).size, idr.count("cb--"), "$mc: count cb")
            // store/load ids are field ids of the constraint graph
            assertTrue(idr.edges.filter { it.label[1] == 'b' }.all { it.label.substringAfter("--").toInt() in 0 until pag.graph.fieldCount })
        }
    }

    @Test
    fun fieldSensitivity1ConcreteEdges() {
        val pta = QilinSession.runSpark(CorePagTest.FS1)
        val pag = CiPag.build(pta)
        val idr = IdrExport.build(pta, pag)
        val init = "<${CorePagTest.FS1}\$A: void <init>(java.lang.Object,java.lang.Object)>"
        fun inInit(v: Int) = pag.nodes[v].let { it is LocalVarNode && it.method.signature == init }
        // A.<init>: this.f = o1; this.g = o2 -> two ob edges into the same base with two different field ids
        val obInInit = idr.edges.filter { it.label.startsWith("ob--") && inInit(it.dst) }
        assertEquals(2, obInInit.size, "two stores in A.<init>: $obInInit")
        assertEquals(1, obInInit.map { it.dst }.toSet().size, "same base (this)")
        assertEquals(2, obInInit.map { it.label }.toSet().size, "different fields")
        // main calls new A(o1, o2): the receiver and both arguments enter A.<init> under the same call-site id
        val opIntoInit = idr.edges.filter { it.label.startsWith("op--") && inInit(it.dst) }
        assertEquals(3, opIntoInit.size, "receiver + 2 arguments: $opIntoInit")
        assertEquals(1, opIntoInit.map { it.label }.toSet().size, "one call site")
        // main: o1 = a.f; o1 = a.g -> two cb edges from the same base, with exactly the fields stored in A.<init>
        val main = "<${CorePagTest.FS1}: void main(java.lang.String[])>"
        val cbInMain = idr.edges.filter { it.label.startsWith("cb--") && pag.nodes[it.dst].let { n -> n is LocalVarNode && n.method.signature == main } }
        assertEquals(2, cbInMain.size, "two loads in main: $cbInMain")
        assertEquals(1, cbInMain.map { it.src }.toSet().size, "same base a")
        assertEquals(obInInit.map { it.label.substringAfter("--") }.toSet(), cbInMain.map { it.label.substringAfter("--") }.toSet(), "loaded fields = stored fields")
    }

    @Test
    fun dotRoundTripsThroughUcfsParser() {
        val pta = QilinSession.runSpark(CorePagTest.FS1)
        val pag = CiPag.build(pta)
        val idr = IdrExport.build(pta, pag)
        val f = File.createTempFile("idr", ".dot")
        idr.writeDot(f)
        val g = org.ucfs.input.DotParser().parseDot(f.readText())
        val n = g.edges.values.sumOf { it.size }
        assertEquals(idr.edges.size, n, "edge count after DotParser")
        assertTrue(File(f.path.removeSuffix(".dot") + ".map.tsv").readLines().size > pag.nodes.size, "map file: header + one line per vertex")
    }
}
