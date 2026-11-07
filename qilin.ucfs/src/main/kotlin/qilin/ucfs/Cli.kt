package qilin.ucfs

import java.io.File
import java.lang.management.ManagementFactory
import kotlin.system.exitProcess

/**
 * Command-line runner: Qilin Spark -> CI constraint graph -> UCFS points-to, with per-phase timings.
 *
 *   qilin.ucfs [--solver=plain|typed|typed-subset|ref|classify|none] [--out=<dir>] [--check-spark] [--metrics] -- <Qilin options...>
 *
 *  - plain    : UCFS, untyped field-sensitive points-to grammar (M3); `ucfs` is an alias
 *  - typed    : UCFS over the typed graph (Spark's filters as gates, M4), grammar v2 [TypedPointsToShared]
 *  - typed-subset : the same with grammar v1 [TypedPointsToGrammar] (one PT state per set of admitted tags)
 *  - ref      : worklist Andersen without type filter (reference for plain)
 *  - classify : plain + typed with each single Spark filter switched off; classifies plain's extra pairs
 *  - none     : Spark and export only
 *  --export-idr=<f.dot> : also write the interleaved-Dyck graph for cfl-idr-with-gll ([IdrExport]) and <f>.map.tsv
 *  --dump-pts=<file>    : write the solver's pairs as "var<TAB>alloc" vertex ids
 *  --reach-only  : UCFS reachability-only mode (only SPPF range nodes; same pairs, less memory)
 *  --reduce      : (typed/classify ablations) exact graph reduction before UCFS, see [TypedGraph.reduced]
 *  --solver=idr --idr-jar=<cfl-idr jar> [--idr-encoding=vf|vfpb|heappb] [--idr-grammar=parity|se|parityD|...]
 *               [--idr-set=under|over|ondemand] [--idr-xmx=16g] [--idr-andersen] : cfl-idr-with-gll on the exported graph (M7)
 *  --cs-oracle=<pattern> : (idr) also run Qilin's own analysis <pattern> (e.g. 2c) and classify the IDR pairs missing vs Spark
 *  --inject      : import the result into Qilin's PAG (timed phase `injectMs`, no metrics)
 *  --off=F1,F2   : (typed) switch off these Spark filters, see [TypedGraph.Filter]
 *  --cut-shortcut=field,local : (typed, rsm) Cut-Shortcut rewrite of the typed graph before solving, see [CutShortcut]
 *  --solver=dyck [--dyck-and-spark] [--heap] [--dyck-select=zipper|all|none] : variant A (A′ with a selection), [DyckCalls]
 *               [--dyck-typed] [--dyck-reduce] : with Spark's filters (product graph vertex × tag) and the exact reduction (M17)
 *  --check-spark : per-variable oracle against Spark (`reachingObjects`) + call graph derived from the result
 *  --metrics     : import the result into Qilin's PAG and print Qilin's SimplifiedEvaluator metrics
 *
 * Prints one `STATS {json}` line to stdout (and writes `<out>/stats.json`, `<out>/diffs.tsv` if --out is given).
 * Times are wall-clock milliseconds of this JVM; peak RSS is measured outside (`/usr/bin/time -v`).
 */
fun main(args: Array<String>) {
    val sep = args.indexOf("--")
    require(sep >= 0) { "usage: qilin.ucfs [--solver=plain|typed|typed-subset|ref|classify|none] [--out=<dir>] [--check-spark] [--metrics] -- <Qilin options>" }
    val own = args.take(sep)
    val qilinArgs = args.drop(sep + 1)
    val solver = (own.firstOrNull { it.startsWith("--solver=") }?.substringAfter("=") ?: "plain").let { if (it == "ucfs") "plain" else it }
    val out = own.firstOrNull { it.startsWith("--out=") }?.substringAfter("=")?.let { File(it) }
    val checkSpark = "--check-spark" in own || solver == "classify"
    val metrics = "--metrics" in own
    // --cs-oracle=<pattern> resets Qilin's scene to run a second analysis: everything that reads Spark's state runs before it
    val csOracle = own.any { it.startsWith("--cs-oracle=") }
    val reachOnly = "--reach-only" in own // UCFS keeps only SPPF range nodes (same results, less memory)
    val reduce = "--reduce" in own // exact offline variable substitution before UCFS-typed (TypedGraph.reduced)
    // --off=ALLOC,DISPATCH,... : typed solver with these Spark filters switched off (ablation in its own process)
    val off = own.firstOrNull { it.startsWith("--off=") }?.substringAfter("=")?.split(",")?.filter { it.isNotBlank() }
        ?.map { TypedGraph.Filter.valueOf(it) }?.toSet() ?: emptySet()

    val stats = LinkedHashMap<String, Any>()
    val diffLines = ArrayList<String>()
    stats["solver"] = solver
    stats["off"] = off.sorted().joinToString("+")
    stats["reduce"] = reduce.toString()
    stats["reachOnly"] = reachOnly.toString()
    stats["qilinArgs"] = qilinArgs.joinToString(" ")
    fun <T> phase(name: String, body: () -> T): T {
        val t0 = System.nanoTime()
        val r = body()
        stats["${name}Ms"] = (System.nanoTime() - t0) / 1_000_000
        println("PHASE ${name}Ms=${stats["${name}Ms"]}") // printed at once: survives a later OOM
        System.out.flush()
        return r
    }
    fun emit() { // may be called twice (partial + final): files are overwritten
        val heap = ManagementFactory.getMemoryMXBean().heapMemoryUsage
        stats["heapUsedMbAtEnd"] = heap.used / (1 shl 20)
        val json = stats.entries.joinToString(",", "{", "}") { (k, v) ->
            "\"$k\":" + if (v is Number) v.toString() else "\"" + v.toString().replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        }
        println("STATS $json")
        out?.let {
            it.mkdirs()
            File(it, "stats.json").writeText(json + "\n")
            File(it, "diffs.tsv").writeText((listOf("kind\tclass\tvar\tobject") + diffLines).joinToString("\n") + "\n")
        }
    }

    phase("sootSetup") { QilinSession.setUp(qilinArgs) }
    // --dyck-select=zipper (M16) needs call details recorded during Spark (Zipper's pre-analysis)
    val dyckSelect = own.firstOrNull { it.startsWith("--dyck-select=") }?.substringAfter("=")
    val pta = phase("spark") { QilinSession.runSpark(callDetails = dyckSelect == "zipper") }
    val pag = phase("export") { CiPag.build(pta) }
    val g = pag.graph
    stats["vertices"] = g.vertexCount
    stats["vars"] = g.varIds().size
    stats["allocs"] = g.allocIds().size
    stats["fields"] = g.fieldCount
    stats["edges"] = g.edges.size
    for (k in EdgeKind.values()) stats["edges_$k"] = g.count(k)
    stats["reachableMethods"] = pta.nakedReachableMethods.size
    stats["ciCallEdges"] = pta.callGraph.size()
    own.firstOrNull { it.startsWith("--export-idr=") }?.substringAfter("=")?.let { path ->
        val idr = phase("idrExport") { IdrExport.build(pta, pag).also { it.writeDot(File(path)) } }
        stats["idrEdges"] = idr.edges.size; stats["idrCallSites"] = idr.callSites
        for (p in listOf("normal", "op--", "cp--", "ob--", "cb--")) stats["idr_" + p.trimEnd('-')] = idr.count(p)
        stats["idrAlphaKinds"] = idr.edges.filter { it.label[1] == 'p' }.map { it.label }.toSet().size
        stats["idrBetaKinds"] = idr.edges.filter { it.label[1] == 'b' }.map { it.label }.toSet().size
    }
    println("GRAPH " + stats.filterKeys { it != "qilinArgs" }) // printed before solving: survives a later OOM
    System.out.flush()
    val sparkMetrics = if (metrics) phase("sparkMetrics") { PtsImport.qilinMetrics(pta) } else emptyMap()
    // Spark's sets are read once, before anything is imported into Qilin's PAG: every later comparison uses this snapshot
    // (reading reachingObjects after an import would compare a result with itself)
    val sparkSnap: Map<Int, Set<Int>> = if (checkSpark || csOracle) SparkCompare.sparkSets(pta, pag) else emptyMap()
    for ((k, v) in sparkMetrics) stats["spark " + k.trimEnd(':')] = v

    val variant = if (solver == "typed-subset") TypedPointsTo.Variant.SUBSET else TypedPointsTo.Variant.SHARED
    // --cut-shortcut=field,local[,container]: Cut-Shortcut as a rewrite of the typed graph before solving (M14, CutShortcut)
    val cutShortcut = own.firstOrNull { it.startsWith("--cut-shortcut=") }?.substringAfter("=")?.let { CutShortcut.parse(it) }
    fun rewrite(tg: TypedGraph, prefix: String): TypedGraph {
        if (cutShortcut == null) return tg
        val r = phase("${prefix}cutShortcut") { CutShortcut.apply(pta, pag, tg, cutShortcut) }
        stats["${prefix}cutShortcut"] = cutShortcut.sorted().joinToString("+")
        for ((k, v) in r.stats) stats["${prefix}cs_$k"] = v
        return r.graph
    }
    fun typed(off: Set<TypedGraph.Filter>, prefix: String): Map<Int, Set<Int>> {
        val tg = rewrite(phase("${prefix}typedExport") { TypedGraph.build(pta, pag, off) }, prefix)
        stats["${prefix}typedVertices"] = tg.vertexCount
        stats["${prefix}typedEdges"] = tg.edges.size
        stats["${prefix}tags"] = tg.tagCount
        stats["${prefix}gates"] = tg.gates.size
        stats["${prefix}droppedAllocs"] = tg.droppedAllocs
        val r = TypedPointsTo.solve(tg, variant = variant, reduce = reduce, reachOnly = reachOnly)
        if (reduce) { stats["${prefix}reducedStarts"] = r.reducedStarts; stats["${prefix}reducedEdges"] = r.reducedEdges }
        stats["${prefix}rsmStates"] = r.constraintStates
        stats["${prefix}ucfsGraphMs"] = r.graphMillis
        stats["${prefix}ucfsSolveMs"] = r.solveMillis
        stats["${prefix}sppfRoots"] = r.roots
        return r.pointsTo
    }
    fun plain(): Map<Int, Set<Int>> {
        val gr = PointsToGrammar(g.fieldCount)
        val input = phase("ucfsGraph") { UcfsPointsTo.inputGraph(g, gr, g.varIds()) }
        val roots = phase("ucfsSolve") { org.ucfs.parser.Gll.gll(gr.startState, input, reachabilityOnly = reachOnly).parse() }
        stats["sppfRoots"] = roots.size
        return UcfsPointsTo.pairsOf(roots, g)
    }

    val root = SparkCompare.rootId(pta, pag)
    val ignore = setOf(root)
    var andersen: Map<Int, Set<Int>>? = null
    if (solver == "classify") phase("classify") {
        // 1) typed with each single Spark filter off and with every filter off (= Andersen, grammar v2): these are
        //    cheap thanks to GSS sharing; their stats are written before the M3-grammar plain solve, which may OOM
        val spark = sparkSnap
        val ablated = LinkedHashMap<TypedGraph.Filter, Map<Int, Set<Int>>>()
        for (f in TypedGraph.Filter.values()) ablated[f] = typed(setOf(f), "no${f}_")
        val allOff = typed(TypedGraph.Filter.values().toSet(), "allOff_")
        andersen = allOff
        stats.putAll(SparkCompare.compare(spark, allOff, ignore).stats("andersen_"))
        val classes = SparkCompare.classify(spark, allOff, ablated, ignore)
        for ((cls, pairs) in classes) {
            stats["class_$cls"] = pairs.size
            for ((v, o) in pairs.take(5)) diffLines.add("extra\t$cls\t${describe(pag.nodes[v])}\t${describe(pag.nodes[o])}")
        }
        for (f in TypedGraph.Filter.values()) {
            val cf = SparkCompare.compare(spark, ablated.getValue(f), ignore)
            stats["no${f}_extraPairs"] = cf.diffs.sumOf { it.solverOnly.size }
            stats["no${f}_missingPairs"] = cf.diffs.sumOf { it.sparkOnly.size }
        }
        stats["plainM3"] = "pending"
        emit() // partial result, survives an OOM of the plain solve below
    }

    /**
     * --cs-oracle=<p1>[,<p2>...]: runs Qilin's own analyses (each resets the scene, so Spark's state must be read before) and
     * classifies every pair that a result set misses (or adds) w.r.t. Spark by whether those analyses miss (have) it too.
     * Verdicts are per oracle: a missing pair counts as "in Cs" if an oracle with a known verdict for the variable has it,
     * as "also missing" if every oracle with a known verdict lacks it, as unknown if no oracle knows the variable;
     * `missingInAllCs` — every oracle has it.
     * All missing pairs that some oracle keeps are written to `<out>/cs-missing.tsv` (var, object, oracles that keep it).
     */
    fun csClassify(pats: String, sets: Map<String, Map<Int, Set<Int>>>, withMetrics: Boolean = metrics, vars: Collection<Int> = g.varIds()) {
        val spark = sparkSnap
        val root = SparkCompare.rootId(pta, pag)
        val byNew = HashMap<Any, Int>()
        for (o in g.allocIds()) (pag.nodes[o] as? qilin.core.pag.AllocNode)?.newExpr?.let { byNew[it] = o }
        val metricsBefore = LinkedHashMap<String, Map<String, String>>()
        if (withMetrics) for ((name, m) in sets) { PtsImport.inject(pta, pag, m); metricsBefore[name] = PtsImport.qilinMetrics(pta) }
        // a variable of our graph without a node in an oracle's PAG, or an oracle object without a counterpart in our graph,
        // makes that oracle's verdict unknown for that variable (never "absent in the oracle"); verdicts are kept per oracle
        val unknownBy = LinkedHashMap<String, Set<Int>>()
        val oracles = LinkedHashMap<String, Map<Int, Set<Int>>>()
        for (pat in pats.split(",").filter { it.isNotBlank() }) {
            val pta2 = phase("csOracle_$pat") { QilinSession.runPattern(pat) }
            val cs = HashMap<Int, Set<Int>>()
            val unknown = HashSet<Int>()
            var unmappedVars = 0; var unmappedObjs = 0
            for (v in g.varIds()) {
                val n = pag.nodes[v] as? qilin.core.pag.VarNode ?: continue
                val n2 = pta2.pag.findValNode(n.variable)
                if (n2 == null) { unmappedVars++; unknown.add(v); continue }
                val objs = HashSet<Int>()
                for (a in pta2.reachingObjects(n2).toCIPointsToSet().toCollection()) {
                    val o = byNew[a.newExpr]
                    if (o == null) { unmappedObjs++; unknown.add(v) } else objs.add(o)
                }
                cs[v] = objs
            }
            stats["csOracle"] = pats; stats["cs_${pat}_unmappedVars"] = unmappedVars; stats["cs_${pat}_unmappedObjs"] = unmappedObjs
            stats["cs_${pat}_unknownVars"] = unknown.size
            if (pats == pat) { stats["csUnmappedVars"] = unmappedVars; stats["csUnmappedObjs"] = unmappedObjs } // M7 names
            oracles[pat] = cs; unknownBy[pat] = unknown
        }
        val unknownVars = unknownBy.values.fold(null as Set<Int>?) { acc, u -> acc?.intersect(u) ?: u } ?: emptySet() // unknown for every oracle
        stats["csUnknownVars"] = unknownVars.size
        for ((pat, cs) in oracles) stats.putAll(SparkCompare.compare(spark.filterKeys { it !in unknownBy.getValue(pat) }, cs.mapValues { it.value - root }, setOf(root))
            .stats(if (oracles.size == 1) "cs_vsSpark_" else "cs_${pat}_vsSpark_"))
        val tsv = ArrayList<String>()
        for ((name, m) in sets) {
            // per missing pair: kept by some oracle with a known verdict / dropped by every oracle with a known verdict / no known verdict
            var missAlsoCs = 0; var missNotCs = 0; var missAll = 0; var extraInCs = 0; var extraNotCs = 0; var missUnknown = 0; var extraUnknown = 0
            for (v in vars) {
                val sp = spark[v] ?: emptySet(); val mine = (m[v] ?: emptySet()) - root
                val known = oracles.keys.filter { v !in unknownBy.getValue(it) }
                if (known.isEmpty()) { missUnknown += (sp - mine).size; extraUnknown += (mine - sp).size; continue }
                for (o in sp - mine) {
                    val keep = known.filter { o in (oracles.getValue(it)[v] ?: emptySet()) }
                    if (keep.isEmpty()) { missAlsoCs++; if (missAlsoCs <= 5) diffLines.add("missing-also-in-cs\t$name\t${describe(pag.nodes[v])}\t${describe(pag.nodes[o])}") }
                    else {
                        missNotCs++; if (keep.size == oracles.size) missAll++
                        tsv.add("$name\t${describe(pag.nodes[v])}\t${describe(pag.nodes[o])}\t${keep.joinToString(",")}\t${known.joinToString(",")}")
                    }
                }
                for (o in mine - sp) { if (known.any { o in (oracles.getValue(it)[v] ?: emptySet()) }) extraInCs++ else extraNotCs++ }
            }
            stats["${name}_missingAlsoMissingInCs"] = missAlsoCs; stats["${name}_missingButInCs"] = missNotCs; stats["${name}_missingInAllCs"] = missAll
            stats["${name}_extraInCs"] = extraInCs; stats["${name}_extraNotInCs"] = extraNotCs
            stats["${name}_missingCsUnknown"] = missUnknown; stats["${name}_extraCsUnknown"] = extraUnknown
            // direct comparison with each oracle on its known variables, within Spark (M15: A ∩ Spark vs kc+0h, 2c, ...)
            for ((pat, cs) in oracles) {
                var onlyMine = 0; var onlyCs = 0
                for (v in vars) {
                    if (v in unknownBy.getValue(pat)) continue
                    val sp = spark[v] ?: emptySet(); val mine = ((m[v] ?: emptySet()) - root).intersect(sp); val c = (cs[v] ?: emptySet()).intersect(sp)
                    onlyMine += (mine - c).size; onlyCs += (c - mine).size
                }
                stats["${name}_vs_${pat}_onlyMine"] = onlyMine; stats["${name}_vs_${pat}_onlyCs"] = onlyCs
            }
            for ((pat, cs) in oracles) stats[if (oracles.size == 1) "${name}_vsCs_varsEqual" else "${name}_vs_${pat}_varsEqual"] =
                g.varIds().count { v -> v !in unknownBy.getValue(pat) && ((m[v] ?: emptySet<Int>()) - root) == (cs[v] ?: emptySet<Int>()) }
        }
        out?.let { File(it, "cs-missing.tsv").also { f -> f.parentFile.mkdirs() }.writeText((listOf("set\tvar\tobject\tkeptBy\tknownBy") + tsv).joinToString("\n") + "\n") }
        for ((name, mm) in metricsBefore) for ((k, v) in mm) stats["$name " + k.trimEnd(':')] = v
    }

    // after Qilin's own analysis ran (--cs-oracle) the scene is reset: everything reading Spark's state runs before it
    val oracleRan = csOracle && solver == "idr"
    var queryVars: Set<Int>? = null
    val pts: Map<Int, Set<Int>>? = when (solver) {
        "plain" -> plain()
        "classify" -> if ("--skip-m3plain" in own) null else plain().also { stats["plainM3"] = "done" }
        "typed", "typed-subset" -> typed(off, "")
        // M13: hand-built RSM designs over the same typed graph (--rsm=v2|ft, --profile prints GLL work per RSM state)
        "rsm" -> {
            val design = own.firstOrNull { it.startsWith("--rsm=") }?.substringAfter("=") ?: "v2"
            val tg = rewrite(phase("typedExport") { TypedGraph.build(pta, pag, off) }, "")
            stats["typedVertices"] = tg.vertexCount; stats["typedEdges"] = tg.edges.size
            stats["tags"] = tg.tagCount; stats["gates"] = tg.gates.size; stats["rsm"] = design
            // --query=casts|receivers|sample:N: start only at the client's variables (demand-driven, advisor's point 6)
            val qv = own.firstOrNull { it.startsWith("--query=") }?.substringAfter("=")?.let { q ->
                phase("querySet") { QuerySets.of(q, pta, pag) }.also { stats["query"] = q; stats["queryVars"] = it.size }
            }
            queryVars = qv?.toSet()
            val r = RsmSolve.solve(tg, design, qv ?: tg.base.graph.varIds(), reduce, reachOnly, "--profile" in own, "--heap" in own)
            stats["rsmStates"] = r.states; stats["ucfsGraphMs"] = r.graphMillis; stats["ucfsSolveMs"] = r.solveMillis
            stats["sppfRoots"] = r.roots
            stats["ucfsSolveCpuMs"] = r.solveCpuMillis; stats["liveHeapMb"] = r.liveHeapMb
            println("PHASE ucfsSolveMs=${r.solveMillis}")
            println("PHASE ucfsSolveCpuMs=${r.solveCpuMillis}")
            r.pointsTo
        }
        "idr" -> {
            // export -> cfl-idr-with-gll (separate JVM) -> pairs -> points-to sets (M7)
            fun opt(k: String, d: String? = null) = own.firstOrNull { it.startsWith("--$k=") }?.substringAfter("=") ?: d ?: error("--$k required")
            val enc = IdrExport.Encoding.valueOf(opt("idr-encoding", "vf").uppercase())
            val grammar = opt("idr-grammar", "parity")
            val set = opt("idr-set", "under")
            val dir = out ?: kotlin.io.path.createTempDirectory("idr").toFile()
            val idr = phase("idrExport") { IdrExport.build(pta, pag, enc) }
            stats["idrEncoding"] = enc.name; stats["idrGrammar"] = grammar; stats["idrSet"] = set
            stats["idrVertices"] = idr.vertexCount; stats["idrEdges"] = idr.edges.size; stats["idrCallSites"] = idr.callSites
            stats["idrAlphaKinds"] = idr.edges.filter { it.label[1] == 'p' }.map { it.label }.toSet().size
            stats["idrBetaKinds"] = idr.edges.filter { it.label[1] == 'b' }.map { it.label }.toSet().size
            println("GRAPH-IDR " + stats.filterKeys { it.startsWith("idr") }); System.out.flush()
            val r = phase("idrSolve") { IdrRun.run(idr, File(opt("idr-jar")), grammar, dir, opt("idr-xmx", "16g")) }
            val under = IdrRun.pointsTo(idr, r.under)
            val over = r.over?.let { IdrRun.pointsTo(idr, it) }
            val od = r.onDemand?.let { IdrRun.pointsTo(idr, it) }
            fun n(m: Map<Int, Set<Int>>?) = m?.values?.sumOf { it.size } ?: -1
            stats["idrUnderPairs"] = r.underCount; stats["idrOverPairs"] = r.overCount; stats["idrOnDemandPairs"] = r.onDemand?.size ?: -1
            stats["idrUnderPts"] = n(under); stats["idrOverPts"] = n(over); stats["idrOnDemandPts"] = n(od)
            stats["idrUnderSubOver"] = r.over?.let { r.under.all { p -> p in it } }?.toString() ?: "-"
            stats["idrUnderSubOnDemand"] = r.onDemand?.let { r.under.all { p -> p in it } }?.toString() ?: "-"
            val sets = linkedMapOf("under" to under).apply { over?.let { put("over", it) }; od?.let { put("ondemand", it) } }
            val and = if ("--idr-andersen" in own) phase("refSolve") { ReferenceAndersenSolver.solve(g) } else null
            if (and != null) {
                fun sub(m: Map<Int, Set<Int>>) = m.all { (v, os) -> (and[v] ?: emptySet()).containsAll(os) }
                stats["idrUnderSubAndersen"] = sub(under).toString()
                over?.let { stats["idrOverSubAndersen"] = sub(it).toString() }
                od?.let { stats["idrOnDemandSubAndersen"] = sub(it).toString() }
                stats["andersenPts"] = n(and)
            }
            if (checkSpark) {
                val spark = sparkSnap
                val root = SparkCompare.rootId(pta, pag)
                for ((name, m) in sets) {
                    val c = SparkCompare.compare(spark, m, setOf(root))
                    stats.putAll(c.stats("${name}_vsSpark_"))
                    for (dd in c.diffs.take(8)) {
                        for (o in dd.sparkOnly.take(3)) diffLines.add("missing\t$name-vs-spark\t${describe(pag.nodes[dd.v])}\t${describe(pag.nodes[o])}")
                        for (o in dd.solverOnly.take(3)) diffLines.add("extra\t$name-vs-spark\t${describe(pag.nodes[dd.v])}\t${describe(pag.nodes[o])}")
                    }
                    if (and != null) {
                        val ca = SparkCompare.compare(g.varIds().associateWith { (and[it] ?: emptySet()) - root }, m, setOf(root))
                        stats.putAll(ca.stats("${name}_vsAndersen_"))
                    }
                }
            }
            // --cs-oracle=<pattern>: Qilin's own context-sensitive analysis (CI-projected) as a second oracle; every pair
            // that the IDR set misses w.r.t. Spark is classified by whether that analysis misses it too
            own.firstOrNull { it.startsWith("--cs-oracle=") }?.substringAfter("=")?.let { pat -> csClassify(pat, sets) }
            if (metrics && !csOracle) for ((name, m) in sets) {
                PtsImport.inject(pta, pag, m)
                for ((k, v) in PtsImport.qilinMetrics(pta)) stats["$name " + k.trimEnd(':')] = v
            }
            when (set) { "under" -> under; "over" -> over ?: error("no over list for $grammar"); "ondemand" -> od ?: error("ondemand needs parityD"); else -> error("--idr-set") }
        }
        // M15 variant A: call-site Dyck reachability over a context-insensitive heap (one CFL); --dyck-and-spark: report A ∩ Spark
        "dyck" -> {
            val sel: ((soot.SootMethod) -> Boolean)? = when (dyckSelect) {
                null, "all" -> null
                "none" -> { _ -> false }
                "zipper" -> phase("zipper") { DyckCalls.zipperSelection(pta) }.let { z -> stats["dyckSelected"] = z.size; { m: soot.SootMethod -> m in z } }
                else -> error("--dyck-select=$dyckSelect (zipper|all|none)")
            }
            stats["dyckSelect"] = dyckSelect ?: "all"
            stats["dyckTyped"] = "--dyck-typed" in own; stats["dyckReduce"] = "--dyck-reduce" in own
            val d0 = phase("dyckExport") { if ("--dyck-typed" in own) DyckCalls.buildTyped(pta, pag, sel) else DyckCalls.build(pta, pag, sel) }
            stats["dyckVertices"] = d0.vertexCount; stats["dyckEdges"] = d0.edges.size; stats["dyckSites"] = d0.sites
            val d = if ("--dyck-reduce" in own) phase("dyckReduce") { d0.reduced() }.also {
                stats["dyckReducedVertices"] = it.activeVertices(); stats["dyckReducedEdges"] = it.edges.size } else d0
            println("GRAPH-DYCK " + stats.filterKeys { it.startsWith("dyck") }); System.out.flush()
            val r = d.solve(reachOnly = true, heap = "--heap" in own)
            stats["rsmStates"] = r.states; stats["ucfsGraphMs"] = r.graphMillis; stats["ucfsSolveMs"] = r.solveMillis
            stats["sppfRoots"] = r.roots; stats["liveHeapMb"] = r.liveHeapMb
            println("PHASE ucfsSolveMs=${r.solveMillis}")
            if ("--dyck-and-spark" in own) r.pointsTo.mapValues { (v, os) -> os.filterTo(HashSet()) { it in (sparkSnap[v] ?: emptySet()) || it == root } }
            else r.pointsTo
        }
        "ref" -> phase("refSolve") { ReferenceAndersenSolver.solve(g) }
        "none" -> null
        else -> error("unknown solver $solver")
    }
    if (pts != null) stats["pairs"] = pts.values.sumOf { it.size }
    // --dump-pts=<file>: "var<TAB>alloc" vertex-id pairs of the solver's result (for external comparisons, M6/M7)
    own.firstOrNull { it.startsWith("--dump-pts=") }?.substringAfter("=")?.let { path ->
        File(path).bufferedWriter().use { w -> pts?.forEach { (v, os) -> os.forEach { o -> w.write("$v\t$o\n") } } }
    }
    // --dump-pts-desc=<file>: the same pairs with node descriptions (debugging, M14), only for the queried variables if any
    own.firstOrNull { it.startsWith("--dump-pts-desc=") }?.substringAfter("=")?.let { path ->
        File(path).bufferedWriter().use { w ->
            pts?.forEach { (v, os) -> if (queryVars == null || v in queryVars!!) os.forEach { o -> w.write("${describe(pag.nodes[v])}\t${describe(pag.nodes[o])}\n") } }
        }
    }
    if (andersen != null && pts != null) {
        fun norm(m: Map<Int, Set<Int>>) = m.mapValues { it.value - root }.filterValues { it.isNotEmpty() }
        stats["allOffEqualsPlain"] = norm(pts) == norm(andersen!!)
    }
    if (checkSpark && pts != null && !oracleRan) phase("compare") {
        val spark = queryVars?.let { q -> sparkSnap.filterKeys { it in q } } ?: sparkSnap // demand mode: compare the queried variables
        val c = SparkCompare.compare(spark, pts, ignore)
        stats.putAll(c.stats("oracle_"))
        // examples: up to 20 differing variables with their differing objects
        for (d in c.diffs.take(20)) {
            for (o in d.sparkOnly.take(if (queryVars != null) Int.MAX_VALUE else 5)) diffLines.add("missing\t-\t${describe(pag.nodes[d.v])}\t${describe(pag.nodes[o])}")
            for (o in d.solverOnly.take(5)) diffLines.add("extra\t-\t${describe(pag.nodes[d.v])}\t${describe(pag.nodes[o])}")
        }
        if (queryVars == null) {
        val cg = SparkCompare.derivedCallGraph(pta, pag, pts)
        stats["cg_sites"] = cg.sites; stats["cg_sparkEdges"] = cg.sparkEdges; stats["cg_derivedEdges"] = cg.derivedEdges
        stats["cg_missing"] = cg.missing; stats["cg_extra"] = cg.extra; stats["cg_sitesDiffer"] = cg.sitesDiffer
        stats["cg_sparkPoly"] = cg.sparkPoly; stats["cg_derivedPoly"] = cg.derivedPoly
        }

    }
    // --inject: import the result into Qilin's PAG without computing metrics (timed phase for benchmarks, M5)
    if ("--inject" in own && pts != null && !oracleRan) phase("inject") { PtsImport.inject(pta, pag, pts) }
    if (metrics && pts != null && !oracleRan) phase("import") { // after --cs-oracle the scene is reset: metrics were taken before
        PtsImport.inject(pta, pag, pts)
        for ((k, v) in PtsImport.qilinMetrics(pta)) stats["$solver " + k.trimEnd(':')] = v
    }
    // typed/rsm (M14): the result is classified against Qilin's own analysis after everything else has read Spark's state
    if (csOracle && !oracleRan && pts != null) own.firstOrNull { it.startsWith("--cs-oracle=") }?.substringAfter("=")?.let { pat ->
        csClassify(pat, mapOf(solver to pts), withMetrics = false, vars = queryVars ?: g.varIds())
    }
    emit()
    exitProcess(0) // Qilin/Soot may leave non-daemon threads
}
