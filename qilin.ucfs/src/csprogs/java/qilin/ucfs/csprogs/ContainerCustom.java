package qilin.ucfs.csprogs;

import java.util.AbstractMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.Vector;

/**
 * Counterexamples of the M18 critic (D65) to "the container pattern loses no real flow": (K1) a subclass of a java.util skeleton whose
 * data lives in user code and is read by the skeleton through a call on `this`; (K2) a dependent object (a LinkedHashMap entry) that
 * reaches user code as the argument of a callback; (K3) a user subclass of Vector that writes its protected array directly. Every
 * flow must be kept.
 */
public class ContainerCustom {
    static final Object K = new Object();
    static final Object V0 = new Object();
    static Object R;
    static final Object X = new Object();

    static class RO extends AbstractMap {
        Set es = new HashSet();

        RO() {
            es.add(new AbstractMap.SimpleEntry(K, V0));
        }

        public Set entrySet() {
            return es;
        }
    }

    static class LRU extends LinkedHashMap {
        LRU(int n) {
            super(16, 0.75f, true);
        }

        protected boolean removeEldestEntry(Map.Entry e) {
            R = log(e);
            return false;
        }
    }

    static Object log(Map.Entry e) {
        return e.getValue();
    }

    static class V2 extends Vector {
        V2() {
            super();
            elementData[0] = X;
            elementCount = 1;
        }
    }

    public static void main(String[] args) {
        // every local stays live until the checks at the end: Soot must not pack a container result with a temporary that loads K, V0 or X
        Object k0 = K;
        Object v0 = V0;
        Object x0 = X;
        RO m = new RO();
        Object v = m.get(k0);

        LinkedHashMap b = new LinkedHashMap();
        Object k2 = new Object();
        Object v2 = new Object();
        b.put(k2, v2);
        Set es = b.entrySet();
        Object t = es.iterator().next();
        Object w = log((Map.Entry) t);
        LRU a = new LRU(4);
        Object k1 = new Object();
        Object v1 = new Object();
        a.put(k1, v1);
        Object r = R;

        V2 vv = new V2();
        Object y = vv.get(0);

        Check.mayAlias(v, v0);
        Check.mayAlias(w, v2);
        Check.mayAlias(r, v1);
        Check.mayAlias(y, x0);
        Check.mayAlias(k0, k0);
        Check.mayAlias(m, m);
        Check.mayAlias(b, b);
        Check.mayAlias(es, es);
        Check.mayAlias(t, t);
        Check.mayAlias(a, a);
        Check.mayAlias(k1, k1);
        Check.mayAlias(k2, k2);
        Check.mayAlias(vv, vv);
    }
}
