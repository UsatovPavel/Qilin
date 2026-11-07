package qilin.ucfs

import org.junit.Test
import org.ucfs.grammar.combinator.Grammar
import org.ucfs.grammar.combinator.extension.StringExtension.or
import org.ucfs.grammar.combinator.extension.StringExtension.times
import org.ucfs.grammar.combinator.regexp.Nt
import org.ucfs.grammar.combinator.regexp.many
import org.ucfs.parser.Gll
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Same language as [PointsToGrammar] with 2 fields, written with the UCFS DSL (cf. UCFS PR #49). */
class DslPointsTo2 : Grammar() {
    val PointsTo by Nt().asStart()
    val FlowsTo by Nt()
    val Alias by Nt()

    init {
        PointsTo /= many("assign" or ("load_0" * Alias * "store_0") or ("load_1" * Alias * "store_1")) * "alloc"
        FlowsTo /= "alloc_r" * many("assign_r" or ("store_0_r" * Alias * "load_0_r") or ("store_1_r" * Alias * "load_1_r"))
        Alias /= PointsTo * FlowsTo
    }
}

class GrammarEquivalenceTest {
    /** Random constraint graph: [allocs] allocation vertices, [vars] variables, [fields] fields. */
    private fun randomGraph(rnd: Random, allocs: Int, vars: Int, fields: Int, edges: Int): ConstraintGraph {
        val n = allocs + vars
        val isAlloc = BooleanArray(n) { it < allocs }
        val list = ArrayList<CiEdge>()
        fun v() = allocs + rnd.nextInt(vars)
        repeat(allocs) { list.add(CiEdge(EdgeKind.ALLOC, it, v(), -1, EdgeOrigin.INTRA)) }
        repeat(edges) {
            list.add(
                when (rnd.nextInt(4)) {
                    0 -> CiEdge(EdgeKind.ALLOC, rnd.nextInt(allocs), v(), -1, EdgeOrigin.INTRA)
                    1 -> CiEdge(EdgeKind.ASSIGN, v(), v(), -1, EdgeOrigin.INTRA)
                    2 -> CiEdge(EdgeKind.STORE, v(), v(), rnd.nextInt(fields), EdgeOrigin.INTRA)
                    else -> CiEdge(EdgeKind.LOAD, v(), v(), rnd.nextInt(fields), EdgeOrigin.INTRA)
                }
            )
        }
        return ConstraintGraph(n, isAlloc, fields, list.distinct())
    }

    @Test
    fun handBuiltRsmEqualsReferenceAndersenOnRandomGraphs() {
        val rnd = Random(20261018)
        var nonTrivial = 0
        repeat(60) { round ->
            val g = randomGraph(rnd, allocs = 1 + rnd.nextInt(5), vars = 3 + rnd.nextInt(15), fields = 1 + rnd.nextInt(3), edges = 5 + rnd.nextInt(30))
            val ucfs = UcfsPointsTo.solve(g).pointsTo
            val ref = ReferenceAndersen.solve(g)
            assertEquals(ref, ucfs, "round $round: $g")
            if (ref.values.sumOf { it.size } > g.edges.count { it.kind == EdgeKind.ALLOC }) nonTrivial++
        }
        assertTrue(nonTrivial >= 20, "random graphs must exercise field/assign propagation, got $nonTrivial")
    }

    @Test
    fun handBuiltRsmEqualsDslGrammarOnRandomGraphs() {
        val rnd = Random(7)
        repeat(40) { round ->
            val g = randomGraph(rnd, allocs = 1 + rnd.nextInt(4), vars = 3 + rnd.nextInt(12), fields = 2, edges = 5 + rnd.nextInt(25))
            val hand = UcfsPointsTo.solve(g).pointsTo
            val grammar = PointsToGrammar(2)
            val roots = Gll.gll(DslPointsTo2().rsm, UcfsPointsTo.inputGraph(g, grammar, g.varIds())).parse()
            assertEquals(hand, UcfsPointsTo.pairsOf(roots, g), "round $round")
        }
    }

    @Test
    fun fieldSensitivityDistinguishesFields() {
        // o0 -> a ; o1 -> x ; o2 -> y ; a.f0 = x ; a.f1 = y ; b = a ; r = b.f0   => r -> {o1} only
        val (o0, o1, o2, a, x, y, b, r) = (0..7).toList()
        val g = ConstraintGraph(
            8, BooleanArray(8) { it < 3 }, 2,
            listOf(
                CiEdge(EdgeKind.ALLOC, o0, a, -1, EdgeOrigin.INTRA),
                CiEdge(EdgeKind.ALLOC, o1, x, -1, EdgeOrigin.INTRA),
                CiEdge(EdgeKind.ALLOC, o2, y, -1, EdgeOrigin.INTRA),
                CiEdge(EdgeKind.STORE, x, a, 0, EdgeOrigin.INTRA),
                CiEdge(EdgeKind.STORE, y, a, 1, EdgeOrigin.INTRA),
                CiEdge(EdgeKind.ASSIGN, a, b, -1, EdgeOrigin.INTRA),
                CiEdge(EdgeKind.LOAD, b, r, 0, EdgeOrigin.INTRA),
            )
        )
        val pts = UcfsPointsTo.solve(g).pointsTo
        assertEquals(setOf(o1), pts[r])
        assertEquals(setOf(o0), pts[b])
    }

    private operator fun <T> List<T>.component6() = this[5]
    private operator fun <T> List<T>.component7() = this[6]
    private operator fun <T> List<T>.component8() = this[7]
}
