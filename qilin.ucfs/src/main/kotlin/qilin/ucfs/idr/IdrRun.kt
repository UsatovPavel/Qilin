package qilin.ucfs.idr

import qilin.ucfs.model.IdrResult
import qilin.ucfs.graph.describe

import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Runs cfl-idr-with-gll (CLI jar, as a separate process on the same UCFS build) on an [IdrExport] and reads its
 * pair sets back (M7). The CLI is called with `-under-paths` (branch local/M6-under-paths), so the output holds
 * the under-approximation list, the over-approximation list (non on-demand grammars) and the on-demand list (parityD).
 */
object IdrRun {


    fun run(idr: IdrExport, jar: File, grammar: String, dir: File, xmx: String = "16g", timeoutSec: Long = 3600): IdrResult {
        dir.mkdirs()
        val dot = File(dir, "graph.dot")
        idr.writeDot(dot)
        val out = File(dir, "$grammar.out")
        val java = File(System.getProperty("java.home"), "bin/java").path
        val cmd = listOf(java, "-Xmx$xmx", "-Xss64m", "-jar", jar.path, "-under-paths", "-o", out.path, dot.path, grammar)
        val t0 = System.nanoTime()
        val p = ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(File(dir, "$grammar.log")).start()
        if (!p.waitFor(timeoutSec, TimeUnit.SECONDS)) { p.destroyForcibly(); error("cfl-idr timeout after $timeoutSec s") }
        val ms = (System.nanoTime() - t0) / 1_000_000
        check(p.exitValue() == 0) { "cfl-idr exit ${p.exitValue()}: " + File(dir, "$grammar.log").readText().takeLast(2000) }
        checkMap(idr, File(dir, "graph.map.tsv"))
        return parse(out.readText(), ms)
    }

    /** The map written with the graph must describe the same CI vertices (guards against an id mix-up). */
    fun checkMap(idr: IdrExport, map: File) {
        val lines = map.readLines().drop(1)
        check(lines.size == idr.vertexCount) { "map has ${lines.size} vertices, export ${idr.vertexCount}" }
        for ((i, line) in lines.withIndex()) {
            val (id, kind, desc) = line.split("\t", limit = 3)
            check(id.toInt() == i) { "map line $i has id $id" }
            if (i < idr.pag.nodes.size) {
                val n = idr.pag.nodes[i]
                check(kind == (if (idr.pag.graph.isAlloc[i]) "alloc" else "var")) { "map line $i: kind $kind" }
                check(desc == describe(n).replace('\t', ' ').replace('\n', ' ')) { "map line $i: '$desc' vs ${describe(n)}" }
            } else {
                val j = i - idr.pag.nodes.size
                check(kind == idr.extraKind[j] && desc.toInt() == idr.extraBase[j]) { "map line $i: $kind/$desc vs ${idr.extraKind[j]}/${idr.extraBase[j]}" }
            }
        }
    }

    fun parse(text: String, millis: Long = -1): IdrResult {
        val lines = text.lines()
        fun section(prefix: String): Set<Pair<Int, Int>>? {
            val i = lines.indexOfFirst { it.startsWith(prefix) }
            if (i < 0) return null
            val res = HashSet<Pair<Int, Int>>()
            for (l in lines.drop(i + 1)) {
                if (l.isBlank()) break
                val (a, b) = l.trim().split(Regex("\\s+"))
                res.add(a.toInt() to b.toInt())
            }
            return res
        }
        val underCount = Regex("Under approximation paths: (\\d+)").find(text)!!.groupValues[1].toInt()
        val overCount = Regex("Over approximation paths \\([^)]*\\): (\\d+)").find(text)!!.groupValues[1].toInt()
        val under = section("Under approximation path list:") ?: error("no under list (cfl-idr without -under-paths?)")
        check(under.size == underCount) { "under list ${under.size} != count $underCount" }
        val onDemand = section("On-Demand paths:")
        val over = if (onDemand != null) null else section("Over approximation paths (").also {
            check(it == null || it.size == overCount) { "over list ${it?.size} != count $overCount" }
        }
        return IdrResult(under, over, onDemand, underCount, overCount, millis)
    }

    /** Points-to sets (variable -> objects) of one pair set, mapped through the encoding. */
    fun pointsTo(idr: IdrExport, pairs: Set<Pair<Int, Int>>): Map<Int, Set<Int>> {
        val res = HashMap<Int, MutableSet<Int>>()
        for ((u, v) in pairs) idr.pointsToFact(u, v)?.let { (x, o) -> res.getOrPut(x) { HashSet() }.add(o) }
        return res
    }
}
