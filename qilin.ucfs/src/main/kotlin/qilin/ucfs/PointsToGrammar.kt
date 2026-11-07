package qilin.ucfs

import org.ucfs.input.TerminalInputLabel
import org.ucfs.rsm.RsmState
import org.ucfs.rsm.symbol.Nonterminal
import org.ucfs.rsm.symbol.Term

/**
 * Field-sensitive context-insensitive points-to as CFL-reachability (Andersen-style "flowsTo",
 * Sridharan et al. OOPSLA'05; in the inverse "pointsTo" form used by UCFS PR #49 `PointsToGrammar`):
 *
 *   PointsTo -> ( assign | load_f Alias store_f )* alloc        (for every field f)
 *   FlowsTo  -> alloc_r ( assign_r | store_f_r Alias load_f_r )*
 *   Alias    -> PointsTo FlowsTo
 *
 * Graph orientation (from a Qilin value-flow edge src -> dst):
 *   ALLOC o->x  : x -alloc-> o,  o -alloc_r-> x
 *   ASSIGN y->x : x -assign-> y, y -assign_r-> x
 *   STORE y->b.f: b -store_f-> y, y -store_f_r-> b
 *   LOAD b.f->x : x -load_f-> b,  b -load_f_r-> x
 * so PointsTo(x, o) holds iff object o may flow into variable x.
 *
 * The RSM is built by hand (one box state per field) instead of with the Grammar DSL:
 * the DSL left-folds thousands of alternatives and overflows the stack (skill ucfs, gotcha 6).
 */
class PointsToGrammar(val fieldCount: Int) {
    val pointsTo = Nonterminal("PointsTo")
    val flowsTo = Nonterminal("FlowsTo")
    val alias = Nonterminal("Alias")
    private val fictive = Nonterminal("fictiveStart")

    val alloc = Term("alloc")
    val allocR = Term("alloc_r")
    val assign = Term("assign")
    val assignR = Term("assign_r")
    private val loads = Array(fieldCount) { Term("load_$it") }
    private val loadsR = Array(fieldCount) { Term("load_${it}_r") }
    private val stores = Array(fieldCount) { Term("store_$it") }
    private val storesR = Array(fieldCount) { Term("store_${it}_r") }

    fun load(f: Int) = loads[f]
    fun loadR(f: Int) = loadsR[f]
    fun store(f: Int) = stores[f]
    fun storeR(f: Int) = storesR[f]

    /** Fictive start state; pass it to `Gll.gll`. */
    val startState: RsmState

    init {
        // PointsTo box
        val p0 = RsmState(pointsTo, isStart = true)
        val pFinal = RsmState(pointsTo, isFinal = true)
        pointsTo.startState = p0
        p0.addEdge(assign, p0)
        p0.addEdge(alloc, pFinal)
        for (f in 0 until fieldCount) {
            val afterLoad = RsmState(pointsTo)
            val afterAlias = RsmState(pointsTo)
            p0.addEdge(loads[f], afterLoad)
            afterLoad.addEdge(alias, afterAlias)
            afterAlias.addEdge(stores[f], p0)
        }
        // FlowsTo box
        val f0 = RsmState(flowsTo, isStart = true)
        val f1 = RsmState(flowsTo, isFinal = true)
        flowsTo.startState = f0
        f0.addEdge(allocR, f1)
        f1.addEdge(assignR, f1)
        for (f in 0 until fieldCount) {
            val afterStore = RsmState(flowsTo)
            val afterAlias = RsmState(flowsTo)
            f1.addEdge(storesR[f], afterStore)
            afterStore.addEdge(alias, afterAlias)
            afterAlias.addEdge(loadsR[f], f1)
        }
        // Alias box
        val a0 = RsmState(alias, isStart = true)
        val a1 = RsmState(alias)
        val a2 = RsmState(alias, isFinal = true)
        alias.startState = a0
        a0.addEdge(pointsTo, a1)
        a1.addEdge(flowsTo, a2)
        // fictive start: fictiveStart -> PointsTo (Gll.gll takes outgoingEdges[0] as the final state)
        val s0 = RsmState(fictive, isStart = true)
        val s1 = RsmState(fictive, isFinal = true)
        fictive.startState = s0
        s0.addEdge(pointsTo, s1)
        startState = s0
    }

    /** Interned input labels (one object per terminal). */
    private val labelCache = HashMap<Term<String>, TerminalInputLabel>()
    fun label(t: Term<String>): TerminalInputLabel = labelCache.getOrPut(t) { TerminalInputLabel(t) }
}
