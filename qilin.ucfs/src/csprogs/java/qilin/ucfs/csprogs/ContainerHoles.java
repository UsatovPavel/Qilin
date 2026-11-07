package qilin.ucfs.csprogs;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Vector;

/**
 * Counterexamples of the M18 verifier (D65), confirmed by a run: (a) a user subclass of LinkedHashMap sets the value of the eldest
 * entry in the callback removeEldestEntry; (b) a static method of a user subclass of Vector writes the protected array directly.
 * Every flow must be kept.
 */
public class ContainerHoles {
    static Object SB;

    static class MyLHM extends LinkedHashMap {
        protected boolean removeEldestEntry(Map.Entry e) {
            Object s = new StringBuffer("secret");
            SB = s;
            e.setValue(s);
            return false;
        }
    }

    static class MyVec extends Vector {
        static void sneak(MyVec v, Object x) {
            v.elementData[0] = x;
            v.elementCount = 1;
        }
    }

    public static void main(String[] args) {
        // every local stays live until the checks at the end (no packing of results with temporaries)
        MyLHM m = new MyLHM();
        Object k = new Object();
        Object v = new Object();
        m.put(k, v);
        Object k3 = new Object();
        Object v3 = new Object();
        m.put(k3, v3);
        Object r = m.get(k);
        Object sb = SB;

        MyVec w = new MyVec();
        Object s2 = new StringBuilder("s2");
        MyVec.sneak(w, s2);
        Object q = w.get(0);

        Check.mayAlias(v, v);
        Check.mayAlias(r, sb);
        Check.mayAlias(q, s2);
        Check.mayAlias(m, m);
        Check.mayAlias(k, k);
        Check.mayAlias(k3, k3);
        Check.mayAlias(v3, v3);
        Check.mayAlias(w, w);
    }
}
