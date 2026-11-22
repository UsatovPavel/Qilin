package qilin.ucfs.graph

import qilin.core.PTA
import qilin.core.pag.AllocNode
import qilin.core.pag.LocalVarNode
import qilin.ucfs.graph.TypedGraph.L
import qilin.ucfs.graph.TypedGraph.TEdge
import qilin.util.PTAUtils
import soot.RefLikeType
import soot.RefType
import soot.Scene
import soot.SootMethod
import soot.Type
import soot.jimple.AssignStmt
import soot.jimple.InstanceInvokeExpr
import soot.jimple.NullConstant
import java.util.BitSet

/**
 * Container access pattern of Cut-Shortcut (Ma et al. PLDI'23 §3.3, §4.3; M18) as a rewrite of the typed graph.
 *
 * Hosts are the collection and map objects allocated outside `java.util`. The pointer-host map ptH (pairs host × view) is
 * computed on the context-insensitive graph with Spark's heap: a variable whose Spark set holds a host gets it ([ColHost],
 * [MapHost]); a Transfer call (`iterator()`, `keySet()`, `entrySet()`, ...) moves the hosts of the receiver to the result
 * with a new view; otherwise ptH flows along every value-flow edge except the return edges of Transfer calls ([PropHost]).
 * At an Exit call site `x = y.m()` (outside `java.util`) the return edges are cut and the elements of channel c of the hosts
 * of y flow into x through one vertex per (host, channel), filled by the matching arguments of the Entrance call sites of the
 * same host. The API is read from `cs-containers.tsv`.
 *
 * The paper's soundness rests on a complete specification. Here an exit site is rewritten only if (1) every object y may point
 * to is a host or an object that reaches code outside `java.util` only through Transfer calls on such objects, (2) every target
 * of the call is a described Exit, and (3) no host of y belongs to an excluded class: a class is excluded when some call on one
 * of its hosts (or on an object depending on one) has a target that is in no list (e.g. `clone`, `subList`, a method of a user
 * subclass), or when an `addAll`/`putAll`/copy constructor brings in an argument that is not completely described (D61). Calls inside `java.util` to non-public methods or on `this` are the implementation and are not
 * checked (the call that brought the values in is checked where it was made).
 * Every shortcut repeats Spark's filters (the parameter of the Entrance, the return and the target of the Exit).
 */
object ContainerOptimizedGraph {
    enum class View { COL, MAP, KEYS, VALUES, ENTRIES, ENTRY }
    enum class Chan { E, K, V }
    private enum class Kind { ENTRANCE, ENTRANCE_ALL, EXIT, TRANSFER, NEUTRAL }

    private class Rule(val kind: Kind, val views: Set<View>?, val subSig: String, val arg: String)

    private val rules: List<Rule> by lazy {
        val text = ContainerOptimizedGraph::class.java.getResourceAsStream("/qilin/ucfs/graph/cs-containers.tsv")!!.bufferedReader().readText()
        text.lines().filter { it.isNotBlank() && !it.startsWith("#") }.map { l ->
            val f = l.split("\t")
            Rule(Kind.valueOf(f[0]), if (f[1] == "*") null else f[1].split(",").map { View.valueOf(it) }.toSet(), f[2], f.getOrElse(3) { "" })
        }
    }

    private fun rulesFor(kind: Kind, subSig: String, view: View) = rules.filter { it.kind == kind && it.subSig == subSig && (it.views == null || view in it.views) }

    private fun elem(view: View): Chan? = when (view) { View.COL -> Chan.E; View.KEYS -> Chan.K; View.VALUES -> Chan.V; else -> null }
    private fun chan(token: String, view: View): Chan? = if (token == "ELEM") elem(view) else Chan.valueOf(token)

    private fun inJavaUtil(m: SootMethod?) = m != null && m.declaringClass.name.startsWith("java.util.")

    /** Per call graph edge: caller, receiver, destination and arguments as vertices of the CI graph (-1: none). */
    private class Call(val stmt: soot.Unit, val caller: SootMethod, val tgt: SootMethod, val recv: Int, val dest: Int, val args: IntArray)

    fun apply(pta: PTA, pag: ImportedGraph, tg: TypedGraph, stats: MutableMap<String, Int>): TypedGraph {
        fun count(k: String, d: Int = 1) { stats[k] = (stats[k] ?: 0) + d }
        val trace = System.getenv("CS_TRACE") != null // debugging: print the decisions
        val fh = Scene.v().orMakeFastHierarchy
        fun sub(t: Type, cls: String) = Scene.v().containsClass(cls) && fh.canStoreType(t, RefType.v(cls))
        val n0 = pag.nodes.size

        // objects: hosts (collections and maps allocated outside java.util) and dependent objects (allocated inside)
        val isHost = BooleanArray(n0); val isDep = BooleanArray(n0)
        for (o in pag.graph.allocIds()) {
            val a = pag.nodes[o] as? AllocNode ?: continue
            val t = a.type as? RefType ?: continue
            val colOrMap = sub(t, "java.util.Collection") || sub(t, "java.util.Map")
            // a host is an object of a java.util class allocated outside java.util ([CustomCont], `Yang2026Cut` appendix A; D65): a user
            // subclass of a java.util skeleton may keep its data in user code (AbstractMap.get -> this.entrySet()) or write it directly
            if (!inJavaUtil(a.method)) { if (colOrMap && t.sootClass.name.startsWith("java.util.")) isHost[o] = true }
            else if (colOrMap || sub(t, "java.util.Iterator") || sub(t, "java.util.Enumeration") || sub(t, "java.util.Map\$Entry")) isDep[o] = true
        }
        val baseView = { o: Int -> if (sub(pag.nodes[o].type, "java.util.Map")) View.MAP else View.COL }
        val ptsCache = HashMap<Int, IntArray>()
        fun pts(v: Int): IntArray = ptsCache.getOrPut(v) {
            pta.reachingObjects(pag.nodes[v]).toCIPointsToSet().toCollection().map { pag.idOf(it) }.filter { it >= 0 }.toIntArray()
        }

        // call graph edges
        val calls = ArrayList<Call>()
        for (e in pta.callGraph) {
            val s = e.srcStmt() ?: continue
            if (!s.containsInvokeExpr()) continue
            val ie = s.invokeExpr; val tgt = e.tgt()
            val nf = pta.pag.getMethodPAG(e.src()).nodeFactory()
            val args = IntArray(tgt.parameterCount) { -1 }
            for (i in 0 until minOf(ie.argCount, tgt.parameterCount)) {
                val a = ie.getArg(i)
                if (a.type is RefLikeType && a !is NullConstant && tgt.getParameterType(i) is RefLikeType) args[i] = pag.idOf(nf.getNode(a))
            }
            val recv = if (ie is InstanceInvokeExpr) pag.idOf(nf.getNode(ie.base)) else -1
            val dest = if (s is AssignStmt && s.leftOp.type is RefLikeType && tgt.returnType is RefLikeType) pag.idOf(nf.getNode(s.leftOp)) else -1
            calls.add(Call(s, e.src(), tgt, recv, dest, args))
        }
        fun retOf(m: SootMethod) = if (m.returnType !is RefLikeType) -1 else pag.idOf(pta.pag.getMethodPAG(m).nodeFactory().caseRet())
        fun transferRule(c: Call, view: View): Rule? =
            if (!inJavaUtil(c.tgt)) null else rulesFor(Kind.TRANSFER, c.tgt.subSignature, view).firstOrNull()
        fun isTransferSite(c: Call) = c.recv >= 0 && c.dest >= 0 && inJavaUtil(c.tgt) && rules.any { it.kind == Kind.TRANSFER && it.subSig == c.tgt.subSignature }

        // ptH: (host, view, carrier) ids, propagated over the CI graph with Spark's heap; transfer return edges excluded. The carrier
        // is the object that stands for the host at a variable: the host itself, or a dependent object (iterator, view, entry) that a
        // Transfer returned. An element reaches a variable only if the variable may point to its carrier (Spark's sets), and a call
        // concerns it only if the carrier's class has the target method (D60).
        class HV(val h: Int, val view: View, val carrier: Int)
        val hvIds = HashMap<Triple<Int, View, Int>, Int>(); val hvList = ArrayList<HV>()
        fun hv(h: Int, v: View, carrier: Int) = hvIds.getOrPut(Triple(h, v, carrier)) { hvList.add(HV(h, v, carrier)); hvList.size - 1 }
        val ptsSet = HashMap<Int, Set<Int>>()
        fun mayPoint(v: Int) = ptsSet.getOrPut(v) { pts(v).toHashSet() }
        fun reaches(e: HV, m: SootMethod) = fh.canStoreType(pag.nodes[e.carrier].type, m.declaringClass.type)
        var nv = n0
        val heapIds = HashMap<Pair<Int, Int>, Int>()
        fun heap(o: Int, f: Int) = heapIds.getOrPut(o to f) { nv++ }
        val succ = HashMap<Int, MutableList<Int>>()
        fun edge(u: Int, v: Int) { succ.getOrPut(u) { ArrayList() }.add(v) }
        val transferRet = HashSet<Pair<Int, Int>>()
        for (c in calls) if (isTransferSite(c)) { val r = retOf(c.tgt); if (r >= 0) transferRet.add(r to c.dest) }
        for (e in pag.graph.edges) when (e.kind) {
            EdgeKind.ALLOC -> {}
            EdgeKind.ASSIGN -> if (!(e.origin == EdgeOrigin.CALL_RET && (e.src to e.dst) in transferRet)) edge(e.src, e.dst)
            EdgeKind.STORE -> for (o in pts(e.dst)) edge(e.src, heap(o, e.field))
            EdgeKind.LOAD -> for (o in pts(e.src)) edge(heap(o, e.field), e.dst)
        }
        val ptH = HashMap<Int, BitSet>()
        val work = ArrayDeque<Int>()
        fun addAll(v: Int, b0: BitSet) {
            val b = if (v >= n0) b0 else BitSet().also { r -> var i = b0.nextSetBit(0); while (i >= 0) { if (hvList[i].carrier in mayPoint(v)) r.set(i); i = b0.nextSetBit(i + 1) } }
            if (b.isEmpty) return
            val cur = ptH.getOrPut(v) { BitSet() }
            val before = cur.cardinality(); cur.or(b)
            if (cur.cardinality() != before) work.addLast(v)
        }
        for (v in pag.graph.varIds()) { val b = BitSet(); for (o in pts(v)) if (isHost[o]) b.set(hv(o, baseView(o), o)); if (!b.isEmpty) addAll(v, b) }
        val transfersByRecv = HashMap<Int, MutableList<Call>>()
        for (c in calls) if (isTransferSite(c)) transfersByRecv.getOrPut(c.recv) { ArrayList() }.add(c)
        while (work.isNotEmpty()) {
            val v = work.removeFirst()
            val b = ptH[v] ?: continue
            for (w in succ[v] ?: emptyList()) addAll(w, b)
            for (c in transfersByRecv[v] ?: emptyList()) {
                val out = BitSet()
                var i = b.nextSetBit(0)
                val ret = retOf(c.tgt)
                while (i >= 0) {
                    val e = hvList[i]
                    val r = if (reaches(e, c.tgt)) transferRule(c, e.view) else null
                    if (r != null && ret >= 0) for (d in pts(ret)) if (isDep[d]) out.set(hv(e.h, if (r.arg == "SAME") e.view else View.valueOf(r.arg), d))
                    i = b.nextSetBit(i + 1)
                }
                if (!out.isEmpty) addAll(c.dest, out)
            }
        }
        fun elems(v: Int): List<HV> { val b = ptH[v] ?: return emptyList(); val r = ArrayList<HV>(); var i = b.nextSetBit(0); while (i >= 0) { r.add(hvList[i]); i = b.nextSetBit(i + 1) }; return r }
        /** (host, view) pairs of the elements at [v] whose carrier has the method [m] (all elements if [m] is null) */
        fun hostsOf(v: Int, m: SootMethod? = null): List<Pair<Int, View>> = elems(v).filter { m == null || reaches(it, m) }.map { it.h to it.view }.distinct()

        // excluded host classes: a checked call on a host (or a dependent of one) whose target is in no list
        fun onThis(c: Call): Boolean {
            val ie = (c.stmt as soot.jimple.Stmt).invokeExpr as? InstanceInvokeExpr ?: return false
            return try { c.caller.hasActiveBody() && ie.base == c.caller.activeBody.thisLocal } catch (e: RuntimeException) { false }
        }
        // inside java.util, calls of non-public methods and calls on `this` (bridges, delegation to the own API) are the implementation
        fun checked(c: Call) = !(inJavaUtil(c.caller) && (!c.tgt.isPublic || onThis(c)))
        fun known(c: Call, view: View): Boolean {
            val ss = c.tgt.subSignature
            // NEUTRAL only for methods of java.util or java.lang.Object (a user method with that sub-signature may add elements; D65)
            val decl = c.tgt.declaringClass.name
            if ((inJavaUtil(c.tgt) || decl == "java.lang.Object") && rules.any { it.kind == Kind.NEUTRAL && it.subSig == ss && (it.views == null || view in it.views) }) return true
            return inJavaUtil(c.tgt) && rules.any { it.kind != Kind.NEUTRAL && it.subSig == ss && (it.views == null || view in it.views) }
        }
        val excluded = HashSet<Type>()
        for (c in calls) {
            if (c.recv < 0 || !checked(c)) continue
            for ((h, view) in hostsOf(c.recv, c.tgt)) if (!known(c, view)) {
                if (trace && excluded.add(pag.nodes[h].type)) println("CC exclude ${pag.nodes[h].type} ($view): ${c.tgt.signature} in ${c.caller.signature}")
                excluded.add(pag.nodes[h].type)
            }
        }
        // a write into a field declared in java.util from code outside it (a subclass writing Vector.elementData) bypasses the API:
        // the classes of the hosts of the base are excluded (D65)
        for (e in pag.graph.edges) if (e.kind == EdgeKind.STORE) {
            val f = pag.fields[e.field] as? soot.SootField ?: continue
            if (!f.declaringClass.name.startsWith("java.util.")) continue
            val m = (pag.nodes[e.dst] as? LocalVarNode)?.method ?: continue
            if (inJavaUtil(m)) continue
            for (o in pts(e.dst)) if (isHost[o] && excluded.add(pag.nodes[o].type) && trace) println("CC exclude ${pag.nodes[o].type}: write to ${f.signature} in ${m.signature}")
        }
        fun hostOk(h: Int) = pag.nodes[h].type !in excluded

        // dependent objects that reach checked code only through Transfer calls on good objects (greatest fixpoint)
        val producers = HashMap<Int, MutableList<Call>>()
        // a dependent object enters code outside java.util only as the result of a call made there (or via a public static field, not checked)
        // (what the callee returns, not what the destination holds: Soot packs locals, so a destination may also get other values)
        for (c in calls) if (c.dest >= 0 && !inJavaUtil(c.caller) && inJavaUtil(c.tgt)) { val r = retOf(c.tgt); if (r >= 0) for (o in pts(r)) if (isDep[o]) producers.getOrPut(o) { ArrayList() }.add(c) }
        // a dependent object that java.util passes to code outside it (receiver or argument of a callback, e.g. the eldest entry to a
        // user's removeEldestEntry) reaches user code without a Transfer (D65)
        val escaped = BooleanArray(n0)
        // (methods of java.lang.Object itself, e.g. the super constructor call, do not touch the entry or iterator)
        for (c in calls) if (inJavaUtil(c.caller) && !inJavaUtil(c.tgt) && c.tgt.declaringClass.name != "java.lang.Object") {
            for (v in listOf(c.recv) + c.args.toList()) if (v >= 0) for (o in pts(v)) if (isDep[o] && !escaped[o]) {
                escaped[o] = true
                if (trace) println("CC dep escapes ${describe(pag.nodes[o])}: ${c.tgt.signature} from ${c.caller.signature}")
            }
        }
        // a dependent object in an array that a call from outside into java.util returns or fills (toArray(), toArray(T[])) reaches user
        // code without a Transfer — Hashtable.entrySet() is a synchronized wrapper whose toArray() walks the inner view (M18 critic, K4)
        fun arrayElements(v: Int): Collection<AllocNode> =
            pta.reachingObjectsOfArrayElement(pta.reachingObjects(pag.nodes[v])).toCIPointsToSet().toCollection()
        for (c in calls) if (!inJavaUtil(c.caller) && inJavaUtil(c.tgt)) {
            val vs = ArrayList<Int>(); val r = retOf(c.tgt); if (c.dest >= 0 && r >= 0) vs.add(r)
            for (a in c.args) if (a >= 0) vs.add(a)
            for (v in vs) for (e in arrayElements(v)) { val o = pag.idOf(e); if (o >= 0 && isDep[o] && !escaped[o]) {
                escaped[o] = true
                if (trace) println("CC dep escapes ${describe(pag.nodes[o])}: array element of ${c.tgt.signature} in ${c.caller.signature}")
            } }
        }
        val depOk = BooleanArray(n0) { isDep[it] && !escaped[it] && producers[it]?.any { c -> isTransferSite(c) } == true }
        if (trace) for (o in 0 until n0) if (isDep[o] && !depOk[o]) println("CC dep without transfer ${describe(pag.nodes[o])}")
        var changed = true
        fun objOk(o: Int) = (isHost[o] && hostOk(o)) || (isDep[o] && depOk[o])
        // ENTRANCE_ALL (addAll, putAll, copy constructors; D61): the elements of the argument's hosts become elements of the receiver's.
        // That is complete only if every object of the argument is good and each of its (host, view) has a channel matching the
        // receiver's view; otherwise the receiver's classes are excluded — a fixpoint with the dependent objects below.
        fun src(c: Call): Boolean = c.recv >= 0 && inJavaUtil(c.tgt) && !(inJavaUtil(c.caller) && onThis(c))
        fun allRules(c: Call, view: View) = rulesFor(Kind.ENTRANCE_ALL, c.tgt.subSignature, view)
        fun argChans(view: View, aview: View): List<Pair<Chan, Chan>>? = when {
            view == View.COL -> elem(aview)?.let { listOf(it to Chan.E) }
            view == View.MAP && aview == View.MAP -> listOf(Chan.K to Chan.K, Chan.V to Chan.V)
            else -> null
        }
        val allSites = calls.filter { c -> src(c) && hostsOf(c.recv, c.tgt).any { (_, view) -> allRules(c, view).isNotEmpty() } }
        while (changed) {
            changed = false
            for (c in allSites) for ((h, view) in hostsOf(c.recv, c.tgt)) {
                if (!hostOk(h)) continue
                for (r in allRules(c, view)) {
                    val a = c.args.getOrElse(r.arg.toInt()) { -1 }
                    val complete = a >= 0 && pts(a).all { objOk(it) } && hostsOf(a).all { (h2, v2) -> hostOk(h2) && argChans(view, v2) != null }
                    if (!complete) {
                        excluded.add(pag.nodes[h].type); changed = true
                        if (trace) println("CC exclude ${pag.nodes[h].type} ($view): incomplete argument of ${c.tgt.signature} in ${c.caller.signature}")
                    }
                }
            }
            for (o in 0 until n0) if (depOk[o]) {
                val bad = producers[o]!!.any { c -> !isTransferSite(c) || c.recv < 0 || pts(c.recv).any { !objOk(it) } || hostsOf(c.recv).any { !hostOk(it.first) } }
                if (bad) {
                    depOk[o] = false; changed = true
                    if (trace) println("CC dep fails ${describe(pag.nodes[o])}: " + producers[o]!!.filter { c -> !isTransferSite(c) || c.recv < 0 || pts(c.recv).any { !objOk(it) } || hostsOf(c.recv).any { !hostOk(it.first) } }
                        .joinToString { "${it.stmt} (${it.tgt.signature})" })
                }
            }
        }
        stats["containerDepOk"] = depOk.count { it }; stats["containerDep"] = isDep.count { it }; stats["containerDepEscaped"] = escaped.count { it }
        stats["containerHosts"] = isHost.count { it }; stats["containerExcludedClasses"] = excluded.size

        // exit sites: (site statement) -> targets; all call graph edges of a statement must qualify
        val bySite = LinkedHashMap<soot.Unit, MutableList<Call>>()
        for (c in calls) bySite.getOrPut(c.stmt) { ArrayList() }.add(c)
        val gates = ArrayList(tg.gates); val gateKinds = ArrayList(tg.gateKinds)
        val gateIds = HashMap<BitSet, Int>(); for ((i, g) in gates.withIndex()) gateIds.putIfAbsent(g, i)
        fun gate(p: BitSet): Int = gateIds.getOrPut(p) { gates.add(p); gateKinds.add("container"); gates.size - 1 }
        val all = BitSet(tg.tagCount).also { it.set(0, tg.tagCount) }
        val admittedCache = HashMap<Type, BitSet>()
        fun admitted(t: Type) = admittedCache.getOrPut(t) { tg.admitted(t) }

        val chanVertex = HashMap<Pair<Int, Chan>, Int>()
        var vx = tg.vertexCount
        fun chanV(h: Int, c: Chan) = chanVertex.getOrPut(h to c) { vx++.also { count("containerChannelVertices") } }
        val cut = HashSet<Pair<Int, Int>>()          // (ret, dest) return edges to cut
        val keep = HashSet<Pair<Int, Int>>()         // return edges used by a site that is not rewritten
        val rewritten = HashSet<soot.Unit>()
        val added = ArrayList<TEdge>()
        for ((_, cs) in bySite) {
            val c0 = cs.first()
            if (c0.dest < 0 || c0.recv < 0) continue
            // per target: the (host, view) pairs whose carrier has the target, and their channels (null: not an exit for that view)
            val exits = cs.map { c -> Triple(c, hostsOf(c.recv, c.tgt), if (inJavaUtil(c.tgt)) hostsOf(c.recv, c.tgt).map { (_, view) -> rulesFor(Kind.EXIT, c.tgt.subSignature, view).firstOrNull()?.let { chan(it.arg, view) } } else null) }
            val isExitSite = exits.any { (_, _, ch) -> ch != null && ch.any { it != null } }
            if (!isExitSite) continue
            val hosts = hostsOf(c0.recv)
            val covered = exits.flatMap { it.second }.toSet()
            val ok = !inJavaUtil(c0.caller) && hosts.isNotEmpty() && hosts.all { hostOk(it.first) && it in covered } &&
                pts(c0.recv).all { objOk(it) } && exits.all { (_, _, ch) -> ch != null && ch.all { it != null } }
            for ((c, _, _) in exits) { val r = retOf(c.tgt); if (r >= 0 && ok) cut.add(r to c.dest) }
            if (trace) println("CC exit ${if (ok) "cut" else "kept"} ${c0.stmt} in ${c0.caller.signature}: hosts=${hosts.map { describe(pag.nodes[it.first]) + "/" + it.second }} " +
                "objs=${pts(c0.recv).map { describe(pag.nodes[it]) + (if (objOk(it)) "+" else "-") }} chans=${exits.map { it.third }}")
            if (!ok) { count("containerExitSitesKept"); continue }
            count("containerExitSitesCut"); rewritten.add(c0.stmt)
            for ((c, hs, ch) in exits) {
                val r = retOf(c.tgt); if (r < 0) continue
                val p = (admitted(c.tgt.returnType).clone() as BitSet).also { it.and(admitted(pag.nodes[c.dest].type)) }
                for ((i, hvw) in hs.withIndex()) {
                    val g = if (p == all) null else gate(p)
                    val src = chanV(hvw.first, ch!![i]!!)
                    added.add(if (g == null) TEdge(L.ASSIGN, src, c.dest, -1) else TEdge(L.GATE, src, c.dest, g))
                }
            }
        }
        for (c in calls) if (c.dest >= 0 && c.stmt !in rewritten) { val r = retOf(c.tgt); if (r >= 0) keep.add(r to c.dest) }
        cut.removeAll(keep)
        // ENTRANCE_ALL: channel -> channel edges, creating the argument's channels (and theirs) as needed
        var grew = true
        val chanEdges = HashSet<Pair<Int, Int>>()
        while (grew) {
            grew = false
            for (c in allSites) for ((h, view) in hostsOf(c.recv, c.tgt)) {
                if (!hostOk(h)) continue
                for (r in allRules(c, view)) {
                    val a = c.args.getOrElse(r.arg.toInt()) { -1 }; if (a < 0) continue
                    for ((h2, v2) in hostsOf(a)) for ((from, to) in argChans(view, v2) ?: emptyList()) {
                        val target = chanVertex[h to to] ?: continue // no exit reads this channel
                        val before = chanVertex.size
                        val source = chanV(h2, from)
                        if (chanVertex.size != before) grew = true
                        if (chanEdges.add(source to target)) { added.add(TEdge(L.ASSIGN, source, target, -1)); count("containerChannelEdges") }
                    }
                }
            }
        }
        // entrance sites: arguments into the channel vertices of the receiver's hosts. A call on `this` inside java.util
        // (Stack.push -> this.addElement) passes on the value of the outer call, which is an Entrance itself or excludes the class;
        // its receiver merges the hosts of all outer calls, so it is not a source.
        for (c in calls) {
            if (c.recv < 0 || !inJavaUtil(c.tgt) || (inJavaUtil(c.caller) && onThis(c))) continue
            for ((h, view) in hostsOf(c.recv, c.tgt)) {
                if (!hostOk(h)) continue
                for (r in rulesFor(Kind.ENTRANCE, c.tgt.subSignature, view)) for (tok in r.arg.split(" ")) {
                    val (k, chs) = tok.split(":").let { it[0].toInt() to it[1] }
                    val ch = chan(chs, view) ?: continue
                    val target = chanVertex[h to ch] ?: continue // no exit reads this channel
                    val a = c.args.getOrElse(k) { -1 }; if (a < 0) continue
                    val pt = c.tgt.getParameterType(k)
                    if (tg.isAlloc(a)) {
                        val tag = tg.tagOf[a]
                        if (PTAUtils.castNeverFails(tg.tagTypes[tag], pt)) added.add(TEdge(L.ALLOC, a, target, tag))
                    } else {
                        val p = admitted(pt)
                        added.add(if (PTAUtils.castNeverFails(pag.nodes[a].type, pt)) TEdge(L.ASSIGN, a, target, -1) else TEdge(L.GATE, a, target, gate(p)))
                    }
                    count("containerSourceEdges")
                }
            }
        }
        val edges = LinkedHashSet<TEdge>()
        for (e in tg.edges) {
            if ((e.kind == L.ASSIGN || e.kind == L.GATE) && (e.src to e.dst) in cut) { count("containerReturnEdgesCut"); continue }
            edges.add(e)
        }
        for (e in added) if (edges.add(e)) count("containerShortcutEdges")
        return tg.withEdgesAndGates(vx, edges.toList(), gates, gateKinds)
    }

    @Suppress("unused")
    private fun isLocal(n: qilin.core.pag.Node) = n is LocalVarNode
}
