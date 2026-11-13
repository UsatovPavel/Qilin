package qilin.ucfs.benchmark.csprogs;

import java.util.HashMap;
import java.util.Hashtable;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Counterexample K4 of the M18 critic (round 2): an entry of a Hashtable reaches user code as an element of the array that
 * entrySet().toArray() returns — entrySet() is a synchronized wrapper whose toArray() walks the inner view, so no Transfer carries
 * the host of h2 to the entry. Every flow must be kept; values stay live until the checks at the end (no packing of locals).
 */
public class ContainerArray {
    static Object val(Map.Entry e) {
        return e.getValue();
    }

    public static void main(String[] args) {
        Hashtable h1 = new Hashtable();
        Object k1 = new Object();
        Object v1 = new Object();
        h1.put(k1, v1);
        Set es1 = h1.entrySet();
        Object t1 = es1.iterator().next();
        Object r1 = val((Map.Entry) t1);
        Hashtable h2 = new Hashtable();
        Object k2 = new Object();
        Object v2 = new Object();
        h2.put(k2, v2);
        Set es2 = h2.entrySet();
        Object[] arr = es2.toArray();
        Object t2 = arr[0];
        Object r2 = val((Map.Entry) t2);
        // the same through maps that the JRE code does not exclude (Hashtable is excluded there); toArray() of the view itself.
        // These also pass without the fix: the array of AbstractCollection.toArray is one allocation for all collections of the
        // JRE, so the receiver of getValue() mixes entries whose getValue() is not a described exit (traced, D67)
        HashMap h3 = new HashMap();
        Object k3 = new Object();
        Object v3 = new Object();
        h3.put(k3, v3);
        Object[] arr3 = h3.entrySet().toArray();
        Object r3 = val((Map.Entry) arr3[0]);
        TreeMap h4 = new TreeMap();
        Object k4 = new Object();
        Object v4 = new Object();
        h4.put(k4, v4);
        Object[] arr4 = h4.entrySet().toArray(new Object[1]);
        Object r4 = val((Map.Entry) arr4[0]);
        HashMap h5 = new HashMap();
        Object k5 = new Object();
        Object v5 = new Object();
        h5.put(k5, v5);
        Object[] arr5 = h5.values().toArray();
        Object r5 = arr5[0];

        Check.mayAlias(r1, v1);
        Check.mayAlias(r2, v2);
        Check.mayAlias(r3, v3);
        Check.mayAlias(r4, v4);
        Check.mayAlias(r5, v5);
        Check.mayAlias(h3, h3);
        Check.mayAlias(h4, h4);
        Check.mayAlias(h5, h5);
        Check.mayAlias(k3, k3);
        Check.mayAlias(k4, k4);
        Check.mayAlias(k5, k5);
        Check.mayAlias(arr3, arr3);
        Check.mayAlias(arr4, arr4);
        Check.mayAlias(arr5, arr5);
        Check.mayAlias(h1, h1);
        Check.mayAlias(h2, h2);
        Check.mayAlias(k1, k1);
        Check.mayAlias(k2, k2);
        Check.mayAlias(es1, es1);
        Check.mayAlias(es2, es2);
        Check.mayAlias(t1, t1);
        Check.mayAlias(t2, t2);
        Check.mayAlias(arr, arr);
    }
}
