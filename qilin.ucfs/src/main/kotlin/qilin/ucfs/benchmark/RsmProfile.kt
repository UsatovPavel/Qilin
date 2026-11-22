package qilin.ucfs.benchmark

import qilin.ucfs.ucfs.RSM_Provider
import org.ucfs.descriptors.Descriptor
import org.ucfs.descriptors.DescriptorsStorage
import org.ucfs.parser.Gll
import org.ucfs.sppf.SppfStorage

/**
 * Where GLL spends its work for a given RSM design (M13): handled descriptors per RSM state role, GSS nodes per
 * nonterminal, GSS edges, popped results and SPPF range nodes. Read by reflection after parse(); UCFS is not changed.
 * Printed as `PROFILE ...` lines.
 */
object RsmProfile {
    fun report(gll: Gll<Int, *>, d: RSM_Provider.TypedDesign) {
        val ctx = gll.ctx
        val hf = DescriptorsStorage::class.java.getDeclaredField("handledDescriptors").apply { isAccessible = true }
        val storage = hf.get(ctx.descriptors)
        val handled = if (storage is Iterable<*>) storage.asSequence() else {
            // Current UCFS uses InternTable: occupied slots are the canonical descriptors.
            val slots = storage.javaClass.getDeclaredField("slots").apply { isAccessible = true }
            (slots.get(storage) as Array<*>).asSequence().filterNotNull()
        }
        val byRole = HashMap<String, Long>()
        val scansByRole = HashMap<String, Long>()
        var edgeScans = 0L; var rsmEdgeHits = 0L
        var descriptors = 0L
        for (value in handled) {
            @Suppress("UNCHECKED_CAST")
            val desc = value as Descriptor<Int>
            descriptors++
            val r = d.role[desc.rsmState] ?: desc.rsmState.id
            byRole.merge(r, 1L, Long::plus)
            // IntersectionEngine scans every input edge of the descriptor's vertex and looks its terminal up in the state
            val es = ctx.input.getEdges(desc.inputPosition)
            edgeScans += es.size; scansByRole.merge(r, es.size.toLong(), Long::plus)
            for (e in es) { val term = e.label.terminal; if (term != null && desc.rsmState.getTerminalEdge(term) != null) rsmEdgeHits++ }
        }
        val gssByNt = HashMap<String, Long>()
        var gssEdges = 0L; var popped = 0L
        for (n in ctx.gss.nodes.keys) {
            val nt = (n.rsm.nonterminal.name ?: "?").substringBefore('_')
            gssByNt.merge(nt, 1L, Long::plus)
            gssEdges += n.outgoingEdges.size; popped += n.popped.size
        }
        val sf = SppfStorage::class.java.getDeclaredField("createdSppfNodes").apply { isAccessible = true }
        val sppf = (sf.get(ctx.sppfStorage) as Map<*, *>).size
        println("PROFILE-SCANS edgeScans=$edgeScans terminalMatches=$rsmEdgeHits")
        for ((r, c) in scansByRole.entries.sortedByDescending { it.value }.take(8)) println("PROFILE-SCANS-STATE $r $c")
        println("PROFILE descriptors=$descriptors gssNodes=${ctx.gss.nodes.size} gssEdges=$gssEdges popped=$popped sppfNodes=$sppf rsmStates=${d.states}")
        for ((r, c) in byRole.entries.sortedByDescending { it.value }) println("PROFILE-STATE $r $c")
        for ((nt, c) in gssByNt.entries.sortedByDescending { it.value }) println("PROFILE-GSS $nt $c")
        System.out.flush()
    }
}
