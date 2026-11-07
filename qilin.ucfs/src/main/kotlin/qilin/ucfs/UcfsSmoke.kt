package qilin.ucfs

import org.ucfs.grammar.combinator.Grammar
import org.ucfs.grammar.combinator.extension.StringExtension.times
import org.ucfs.grammar.combinator.regexp.Nt
import org.ucfs.grammar.combinator.regexp.Option
import org.ucfs.input.InputGraph
import org.ucfs.input.TerminalInputLabel
import org.ucfs.parser.Gll
import org.ucfs.rsm.symbol.Term

/** Grammar a^n b^n, used only to check that UCFS is wired into the Qilin build. */
class AnBnGrammar : Grammar() {
    val S by Nt().asStart()

    init {
        S /= "a" * Option(S) * "b"
    }
}

/** Runs UCFS on a labelled edge list with every vertex as a start vertex; returns reachable (from, to) pairs. */
object UcfsSmoke {
    fun reachablePairs(grammar: Grammar, edges: List<Triple<Int, String, Int>>): Set<Pair<Int, Int>> {
        val graph = InputGraph<Int, TerminalInputLabel>()
        for ((from, label, to) in edges) {
            graph.addVertex(from)
            graph.addVertex(to)
            graph.addEdge(from, TerminalInputLabel(Term(label)), to)
        }
        for ((from, _, to) in edges) {
            graph.addStartVertex(from)
            graph.addStartVertex(to)
        }
        return Gll.gll(grammar.rsm, graph).parse()
            .mapNotNull { it.inputRange }
            .map { it.from to it.to }
            .toSet()
    }
}
