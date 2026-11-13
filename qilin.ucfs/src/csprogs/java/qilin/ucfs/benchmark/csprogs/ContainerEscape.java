package qilin.ucfs.benchmark.csprogs;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Negative cases of the container access pattern: values reach a container through methods outside the described API
 * (copy constructor, addAll, clone, a wrapper, a user subclass, the defaults of a Properties) or through Map.Entry.setValue. Every flow must be kept.
 */
public class ContainerEscape {
    static class MyList extends ArrayList {
        public Object get(int i) {
            Object r = super.get(i);
            return r;
        }
    }

    public static void main(String[] args) {
        ArrayList l1 = new ArrayList();
        Object a = new Object();
        l1.add(a);
        ArrayList l2 = new ArrayList(l1);
        Object x = l2.get(0);
        Check.mayAlias(x, a);

        ArrayList l3 = new ArrayList();
        l3.addAll(l1);
        Object y = l3.get(0);
        Check.mayAlias(y, a);

        ArrayList l5 = (ArrayList) l1.clone();
        Object c5 = new Object();
        l5.add(c5);
        Object x5 = l5.get(0);
        Object y5 = l1.get(0);
        Check.mayAlias(x5, a);
        Check.mayAlias(c5, c5);

        List u = Collections.unmodifiableList(l1);
        Object w = u.get(0);
        Check.mayAlias(w, a);

        MyList ml = new MyList();
        Object c = new Object();
        ml.add(c);
        Object q = ml.get(0);
        Check.mayAlias(q, c);

        HashMap m = new HashMap();
        Object k = new Object();
        Object v = new Object();
        Object v2 = new Object();
        m.put(k, v);
        Map.Entry e = (Map.Entry) m.entrySet().iterator().next();
        e.setValue(v2);
        Object z = m.get(k);
        Check.mayAlias(v, v);
        Check.mayAlias(z, v2);

        // D67: Properties.getProperty also reads the defaults given to the constructor (another host)
        java.util.Properties pd = new java.util.Properties();
        String sd = new String("d");
        pd.setProperty("k", sd);
        java.util.Properties pp = new java.util.Properties(pd);
        String gd = pp.getProperty("k");
        Check.mayAlias(gd, sd);
    }
}
