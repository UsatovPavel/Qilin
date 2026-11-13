package qilin.ucfs.archive

import qilin.ucfs.graph.TypedGraph

import org.ucfs.input.InputGraph
import org.ucfs.input.TerminalInputLabel
import org.ucfs.rsm.RsmState
import org.ucfs.rsm.symbol.Nonterminal
import org.ucfs.rsm.symbol.Term
import java.util.BitSet

/**
 * Typed points-to grammar over a [TypedGraph] (Spark's filters as gates):
 *
 *   PointsTo_E -> assign PointsTo_E | Bridge PointsTo_E | gate_k PointsTo_{E ∩ P_k} | alloc_t      (t ∈ E)
 *   FlowsTo    -> alloc_r_t FT_t                                       (t not an empty array)
 *   FT_t       -> ε | assign_r FT_t | BridgeR FT_t | gate_r_k FT_t                   (t ∈ P_k)
 *   Alias      -> PointsTo_⊤ FlowsTo
 *   Bridge     -> load_f Alias store_f        BridgeR -> store_r_f Alias load_r_f    (every field f)
 *
 * E ranges over the sets of tags still admitted by the gates passed so far (start: all tags). The
 * walk of PointsTo goes against value flow from a variable to an object, so it cannot know the
 * object's tag in advance and carries the set of admissible tags in its state; FlowsTo starts at the
 * object and knows its tag. Each E and each FT_t is one state of a single RSM box (nonterminals
 * PointsTo / FlowsTo), so the RSM has O(#E + #tags + #fields) states.
 */
@Deprecated("Archived subset/v1 grammar. Do not run; use --solver=typed or RSM_Provider.typed instead.", level = DeprecationLevel.ERROR)
class TypedPointsToGrammar(tg: TypedGraph, maxConstraintStates: Int = 200_000) {
    val pointsTo = Nonterminal("PointsTo")
    val flowsTo = Nonterminal("FlowsTo")
    val alias = Nonterminal("Alias")
    val bridge = Nonterminal("Bridge")
    val bridgeR = Nonterminal("BridgeR")
    private val fictive = Nonterminal("fictiveStart")

    private val terms = HashMap<String, TerminalInputLabel>()
    fun label(s: String): TerminalInputLabel = terms.getOrPut(s) { TerminalInputLabel(Term(s)) }
    private fun t(s: String) = label(s).terminal

    val startState: RsmState
    val constraintStates: Int

    init {
        val tags = tg.tagCount
        // PointsTo box: one state per reachable constraint set
        val all = BitSet(tags).apply { set(0, tags) }
        val ptFinal = RsmState(pointsTo, isFinal = true)
        val states = HashMap<BitSet, RsmState>()
        val work = ArrayDeque<BitSet>()
        val ptStart = RsmState(pointsTo, isStart = true)
        pointsTo.startState = ptStart
        states[all] = ptStart
        work.add(all)
        while (work.isNotEmpty()) {
            val e = work.removeFirst()
            val q = states.getValue(e)
            q.addEdge(t("assign"), q)
            q.addEdge(bridge, q)
            for ((k, p) in tg.gates.withIndex()) {
                val inter = (e.clone() as BitSet).apply { and(p) }
                if (inter.isEmpty) continue
                val target = states.getOrPut(inter) {
                    check(states.size < maxConstraintStates) { "too many constraint states (> $maxConstraintStates)" }
                    work.add(inter); RsmState(pointsTo)
                }
                q.addEdge(t("gate_$k"), target)
            }
            var tag = e.nextSetBit(0)
            while (tag >= 0) { q.addEdge(t("alloc_$tag"), ptFinal); tag = e.nextSetBit(tag + 1) }
        }
        constraintStates = states.size

        // FlowsTo box
        val ftStart = RsmState(flowsTo, isStart = true)
        flowsTo.startState = ftStart
        for (tag in 0 until tags) {
            if (tg.tagEmptyArray[tag]) continue // Spark never stores to / loads from empty arrays
            val r = RsmState(flowsTo, isFinal = true)
            ftStart.addEdge(t("alloc_r_$tag"), r)
            r.addEdge(t("assign_r"), r)
            r.addEdge(bridgeR, r)
            for ((k, p) in tg.gates.withIndex()) if (p.get(tag)) r.addEdge(t("gate_r_$k"), r)
        }
        // Alias box
        val a0 = RsmState(alias, isStart = true)
        val a1 = RsmState(alias)
        val a2 = RsmState(alias, isFinal = true)
        alias.startState = a0
        a0.addEdge(pointsTo, a1)
        a1.addEdge(flowsTo, a2)
        // Bridge boxes
        val b0 = RsmState(bridge, isStart = true)
        val bF = RsmState(bridge, isFinal = true)
        bridge.startState = b0
        val c0 = RsmState(bridgeR, isStart = true)
        val cF = RsmState(bridgeR, isFinal = true)
        bridgeR.startState = c0
        for (f in 0 until tg.fieldCount) {
            val b1 = RsmState(bridge); val b2 = RsmState(bridge)
            b0.addEdge(t("load_$f"), b1); b1.addEdge(alias, b2); b2.addEdge(t("store_$f"), bF)
            val c1 = RsmState(bridgeR); val c2 = RsmState(bridgeR)
            c0.addEdge(t("store_r_$f"), c1); c1.addEdge(alias, c2); c2.addEdge(t("load_r_$f"), cF)
        }
        val s0 = RsmState(fictive, isStart = true)
        val s1 = RsmState(fictive, isFinal = true)
        fictive.startState = s0
        s0.addEdge(pointsTo, s1)
        startState = s0
    }

    fun inputGraph(tg: TypedGraph, startVars: Collection<Int>): InputGraph<Int, TerminalInputLabel> {
        val g = InputGraph<Int, TerminalInputLabel>()
        for (v in 0 until tg.vertexCount) g.addVertex(v)
        for (e in tg.edges) {
            val (fwd, bwd) = when (e.kind) {
                TypedGraph.L.ASSIGN -> "assign" to "assign_r"
                TypedGraph.L.GATE -> "gate_${e.arg}" to "gate_r_${e.arg}"
                TypedGraph.L.ALLOC -> "alloc_${e.arg}" to "alloc_r_${e.arg}"
                TypedGraph.L.LOAD -> "load_${e.arg}" to "load_r_${e.arg}"
                TypedGraph.L.STORE -> "store_${e.arg}" to "store_r_${e.arg}"
            }
            when (e.kind) {
                // PointsTo direction: against value flow; for STORE y -> b.f the PT edge is b -store_f-> y
                TypedGraph.L.STORE -> { g.addEdge(e.dst, label(fwd), e.src); g.addEdge(e.src, label(bwd), e.dst) }
                // LOAD b.f -> x: PT edge x -load_f-> b, FT edge b -load_r_f-> x
                else -> { g.addEdge(e.dst, label(fwd), e.src); g.addEdge(e.src, label(bwd), e.dst) }
            }
        }
        for (v in startVars) g.addStartVertex(v)
        return g
    }
}
