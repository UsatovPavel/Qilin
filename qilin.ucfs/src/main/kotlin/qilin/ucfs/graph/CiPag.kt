package qilin.ucfs.graph

import qilin.core.PTA
import qilin.core.PTAScene
import qilin.core.pag.AllocNode
import qilin.core.pag.ContextAllocNode
import qilin.core.pag.FieldRefNode
import qilin.core.pag.GlobalVarNode
import qilin.core.pag.LocalVarNode
import qilin.core.pag.Node
import qilin.core.pag.ValNode
import qilin.core.pag.VarNode
import qilin.CoreConfig
import qilin.util.PTAUtils
import soot.RefLikeType
import soot.SootMethod
import soot.jimple.AssignStmt
import soot.jimple.InstanceInvokeExpr
import soot.jimple.NullConstant
import soot.jimple.spark.pag.SparkField

/** Edge kinds of the context-insensitive constraint graph, in value-flow direction (as in Qilin's PAG). */
enum class EdgeKind {
    /** `o -> x`: x = new ... (object o flows into x). */
    ALLOC,

    /** `y -> x`: x = y (casts, statics, parameter/return/receiver passing). */
    ASSIGN,

    /** `y -> b.f`: b.f = y.  src = y (value), dst = b (base). */
    STORE,

    /** `b.f -> x`: x = b.f.  src = b (base), dst = x. */
    LOAD,
}

/** Where an edge comes from in Qilin (see docs/ai/coursework/notes/M2-qilin-core.md §5). */
enum class EdgeOrigin { INTRA, ROOT, FINALIZER, CALL_ARG, CALL_RET, RECV_THIS }

data class CiEdge(val kind: EdgeKind, val src: Int, val dst: Int, val field: Int, val origin: EdgeOrigin)

/** Framework-independent constraint graph: vertices 0 until [vertexCount], fields 0 until [fieldCount]. */
data class ConstraintGraph(
    val vertexCount: Int,
    val isAlloc: BooleanArray,
    val fieldCount: Int,
    val edges: List<CiEdge>,
) {
    fun varIds(): List<Int> = (0 until vertexCount).filter { !isAlloc[it] }
    fun allocIds(): List<Int> = (0 until vertexCount).filter { isAlloc[it] }
    fun count(kind: EdgeKind) = edges.count { it.kind == kind }
    override fun toString() = "ConstraintGraph(n=$vertexCount, fields=$fieldCount, edges=$edges)"
}

/**
 * The context-insensitive constraint graph of a solved Qilin analysis, built from program
 * statements only: MethodPAG edges of every reachable method plus interprocedural edges
 * for every edge of the final CI call graph, exactly as Qilin's own `Solver.addToPAG` and
 * `CallGraphBuilder.processCallAssign` would add them, except that the receiver is passed
 * to `this` as a variable-to-variable edge (Spark passes only dispatched objects).
 * Field edges `y -> o.f`, `o.f -> x` (computed by Spark from points-to sets) are NOT part of it.
 *
 * Vertices are dense ints: [nodes]`[i]` is a Qilin [AllocNode] or [VarNode].
 */
class CiPag private constructor(
    val nodes: List<Node>,
    val fields: List<SparkField>,
    val edges: List<CiEdge>,
) {
    val graph = ConstraintGraph(nodes.size, BooleanArray(nodes.size) { nodes[it] is AllocNode }, fields.size, edges)
    private val index: Map<Node, Int> = HashMap<Node, Int>().also { m -> nodes.forEachIndexed { i, n -> m[n] = i } }

    /** Vertex id of a Qilin node, or -1 if the node is not in the graph. */
    fun idOf(n: Node): Int = index[n] ?: -1

    fun count(kind: EdgeKind) = graph.count(kind)

    companion object {
        fun build(pta: PTA): CiPag = Builder(pta).build()
    }

    private class Builder(val pta: PTA) {
        val pag = pta.pag
        val nodeIds = LinkedHashMap<Node, Int>()
        val fieldIds = LinkedHashMap<SparkField, Int>()
        val edgeSet = LinkedHashSet<CiEdge>()

        fun id(n: Node): Int = nodeIds.getOrPut(n) { nodeIds.size }
        fun fid(f: SparkField): Int = fieldIds.getOrPut(f) { fieldIds.size }

        /** Same classification as `PAG.addEdgeIntenal`. */
        fun add(from: Node?, to: Node?, origin: EdgeOrigin) {
            if (from == null || to == null) return
            val e = when {
                from is ValNode && to is ValNode -> CiEdge(EdgeKind.ASSIGN, id(from), id(to), -1, origin)
                from is ValNode && to is FieldRefNode ->
                    CiEdge(EdgeKind.STORE, id(from), id(to.base), fid(to.field), origin)
                from is FieldRefNode && to is VarNode ->
                    CiEdge(EdgeKind.LOAD, id(from.base), id(to), fid(from.field), origin)
                from is AllocNode && to is VarNode -> CiEdge(EdgeKind.ALLOC, id(from), id(to), -1, origin)
                else -> error("unexpected PAG edge ${from.javaClass.simpleName} -> ${to.javaClass.simpleName}: $from -> $to")
            }
            edgeSet.add(e)
        }

        fun build(): CiPag {
            check(!CoreConfig.v().ptaConfig.preciseExceptions) { "-pe (precise exceptions) is not supported by the export" }
            // CallGraphBuilder.getEntryPoints: ROOT -> FakeMain.this
            val fakeMain = PTAScene.v().fakeMainMethod
            // getRootNode() is already parameterized (ContextAllocNode with the empty context): use the CI node
            val root: Node = pta.rootNode.let { if (it is ContextAllocNode) it.base() else it }
            add(root, pag.getMethodPAG(fakeMain).nodeFactory().caseThis(), EdgeOrigin.ROOT)

            // Solver.processStmts/addToPAG: MethodPAG edges of every reachable non-phantom method
            val methods: List<SootMethod> = pta.nakedReachableMethods.filter { !it.isPhantom }.sortedBy { it.signature }
            for (m in methods) {
                val reader = pag.getMethodPAG(m).internalReader.clone()
                while (reader.hasNext()) {
                    var from = reader.next()
                    val to = reader.next()
                    if (from is AllocNode) from = pta.heapAbstractor().abstractHeap(from)
                    if (from is AllocNode && to !is GlobalVarNode && PTAUtils.supportFinalize(from)) {
                        // Solver.handleImplicitCallToFinalizerRegister
                        val register = PTAScene.v().getMethod("<java.lang.ref.Finalizer: void register(java.lang.Object)>")
                        add(from, pag.getMethodPAG(register).nodeFactory().caseParm(0), EdgeOrigin.FINALIZER)
                    }
                    add(from, to, EdgeOrigin.INTRA)
                }
            }

            // CallGraphBuilder.processCallAssign (+ receiver -> this of CallGraphBuilder.addVirtualEdge)
            for (e in pta.callGraph) {
                val srcnf = pag.getMethodPAG(e.src()).nodeFactory()
                val tgtmpag = pag.getMethodPAG(e.tgt())
                val tgtnf = tgtmpag.nodeFactory()
                val tgt = tgtmpag.method
                val s = e.srcStmt()
                val ie = s.invokeExpr
                for (i in 0 until ie.argCount) {
                    val arg = ie.getArg(i)
                    if (arg.type !is RefLikeType || arg is NullConstant) continue
                    if (tgt.getParameterType(i) !is RefLikeType) continue
                    add(srcnf.getNode(arg), tgtnf.caseParm(i), EdgeOrigin.CALL_ARG)
                }
                if (s is AssignStmt) {
                    val dest = s.leftOp
                    if (dest.type is RefLikeType && tgt.returnType is RefLikeType) {
                        add(tgtnf.caseRet(), srcnf.getNode(dest), EdgeOrigin.CALL_RET)
                    }
                }
                if (ie is InstanceInvokeExpr) {
                    add(srcnf.getNode(ie.base), tgtnf.caseThis(), EdgeOrigin.RECV_THIS)
                }
            }
            val nodes = ArrayList<Node>(nodeIds.size)
            nodeIds.keys.forEach { nodes.add(it) }
            return CiPag(nodes, fieldIds.keys.toList(), edgeSet.toList())
        }
    }
}

/** Short, stable textual description of a node (for dumps and examples). */
fun describe(n: Node): String = when (n) {
    is AllocNode -> "alloc[${n.newExpr}]@${n.method?.signature ?: "-"}"
    is LocalVarNode -> "var[${n.variable}]@${n.method.signature}"
    is GlobalVarNode -> "global[${n.variable}]"
    else -> n.toString()
}
