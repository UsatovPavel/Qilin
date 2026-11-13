package qilin.ucfs.benchmark.csprogs;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/**
 * Ma et al. PLDI'23 §3.3, Fig. 4: container access pattern. Two containers of each kind, filled and read through the
 * described API only (add/get, iterator, put/get, keySet, entrySet, an iterator kept in a field, addAll and a copy constructor
 * of another described list, D61): the elements of different
 * hosts must not mix. Every value stays live until the checks at the end, so that Soot does not pack the locals of different
 * containers into one (the analysis is flow-insensitive per local).
 */
public class ContainerFlow {
    static class Holder {
        Iterator it;
    }

    public static void main(String[] args) {
        ArrayList l1 = new ArrayList();
        ArrayList l2 = new ArrayList();
        Object a = new Object();
        Object b = new Object();
        l1.add(a);
        l2.add(b);
        Object x = l1.get(0);
        Object y = l2.get(0);
        Iterator i1 = l1.iterator();
        Iterator i2 = l2.iterator();
        Object r1 = i1.next();
        Object r2 = i2.next();
        Holder h = new Holder();
        h.it = l1.iterator();
        Iterator i3 = h.it;
        Object r3 = i3.next();

        ArrayList l3 = new ArrayList();
        l3.addAll(l1);
        ArrayList l4 = new ArrayList(l2);
        Object x3 = l3.get(0);
        Object x4 = l4.get(0);

        HashMap m1 = new HashMap();
        HashMap m2 = new HashMap();
        Object k1 = new Object();
        Object k2 = new Object();
        Object v1 = new Object();
        Object v2 = new Object();
        m1.put(k1, v1);
        m2.put(k2, v2);
        Object g1 = m1.get(k1);
        Object g2 = m2.get(k2);
        Set ks1 = m1.keySet();
        Set ks2 = m2.keySet();
        Iterator j1 = ks1.iterator();
        Iterator j2 = ks2.iterator();
        Object s1 = j1.next();
        Object s2 = j2.next();
        Set es1 = m1.entrySet();
        Set es2 = m2.entrySet();
        Iterator n1 = es1.iterator();
        Iterator n2 = es2.iterator();
        Object t1 = n1.next();
        Object t2 = n2.next();
        Map.Entry e1 = (Map.Entry) t1;
        Map.Entry e2 = (Map.Entry) t2;
        Object ev1 = e1.getValue();
        Object ev2 = e2.getValue();
        Object ek1 = e1.getKey();

        Check.mayAlias(x, a);
        Check.notAlias(x, y);
        Check.mayAlias(y, b);
        Check.mayAlias(r1, a);
        Check.notAlias(r1, r2);
        Check.mayAlias(r2, b);
        Check.mayAlias(r3, a);
        Check.mayAlias(x3, a);
        Check.notAlias(x3, b);
        Check.mayAlias(x4, b);
        Check.notAlias(x4, a);
        Check.mayAlias(g1, v1);
        Check.notAlias(g1, g2);
        Check.mayAlias(g2, v2);
        Check.notAlias(g1, k1);
        Check.mayAlias(s1, k1);
        Check.notAlias(s1, s2);
        Check.mayAlias(s2, k2);
        Check.notAlias(s1, v1);
        Check.mayAlias(ev1, v1);
        Check.notAlias(ev1, ev2);
        Check.mayAlias(ev2, v2);
        Check.mayAlias(ek1, k1);
        Check.notAlias(ek1, ev1);
        Check.mayAlias(i1, i1);
        Check.mayAlias(i2, i2);
        Check.mayAlias(j1, j1);
        Check.mayAlias(j2, j2);
        Check.mayAlias(n1, n1);
        Check.mayAlias(n2, n2);
        Check.mayAlias(e1, e1);
        Check.mayAlias(e2, e2);
        Check.mayAlias(t1, t1);
        Check.mayAlias(t2, t2);
        Check.mayAlias(h, h);
        Check.mayAlias(i3, i3);
        Check.mayAlias(ks1, ks1);
        Check.mayAlias(ks2, ks2);
        Check.mayAlias(es1, es1);
        Check.mayAlias(es2, es2);
    }
}
