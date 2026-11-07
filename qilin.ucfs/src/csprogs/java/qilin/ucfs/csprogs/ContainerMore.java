package qilin.ucfs.csprogs;

import java.util.EnumMap;
import java.util.Properties;
import java.util.Stack;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Container access pattern on the methods added after the comparison with the authors' specification (D67): navigable sets,
 * blocking queues and deques, Stack.push as an exit, CopyOnWriteArrayList.addIfAbsent, EnumMap, Properties,
 * ConcurrentMap.replace. Two containers of each kind; the elements of different hosts must not mix. As in ContainerFlow,
 * every value stays live until the checks at the end. Properties is checked for soundness only: the JRE code of every program
 * (Security.initialize) calls the undescribed load(InputStream) on a Properties created outside java.util, which excludes the
 * class (found by tracing, D67).
 */
public class ContainerMore {
    enum Key { K }

    public static void main(String[] args) throws InterruptedException {
        Object a = new Object();
        Object b = new Object();

        TreeSet s1 = new TreeSet();
        TreeSet s2 = new TreeSet();
        s1.add(a);
        s2.add(b);
        Object c1 = s1.ceiling(a);
        Object c2 = s2.floor(b);
        Object c3 = s1.higher(a);
        Object c4 = s2.lower(b);

        LinkedBlockingQueue q1 = new LinkedBlockingQueue();
        LinkedBlockingQueue q2 = new LinkedBlockingQueue();
        q1.put(a);
        q2.offer(b, 1, TimeUnit.SECONDS);
        Object t1 = q1.take();
        Object t2 = q2.poll(1, TimeUnit.SECONDS);

        LinkedBlockingDeque d1 = new LinkedBlockingDeque();
        LinkedBlockingDeque d2 = new LinkedBlockingDeque();
        d1.putFirst(a);
        d2.putLast(b);
        Object f1 = d1.takeLast();
        Object f2 = d2.takeFirst();

        Stack st1 = new Stack();
        Stack st2 = new Stack();
        Object p1 = st1.push(a);
        Object p2 = st2.push(b);

        CopyOnWriteArrayList w1 = new CopyOnWriteArrayList();
        CopyOnWriteArrayList w2 = new CopyOnWriteArrayList();
        w1.addIfAbsent(a);
        w2.addIfAbsent(b);
        Object g1 = w1.get(0);
        Object g2 = w2.get(0);

        EnumMap e1 = new EnumMap(Key.class);
        EnumMap e2 = new EnumMap(Key.class);
        e1.put(Key.K, a);
        e2.put(Key.K, b);
        Object h1 = e1.get(Key.K);
        Object h2 = e2.get(Key.K);

        Properties r1 = new Properties();
        Properties r2 = new Properties();
        String sa = new String("a");
        String sb = new String("b");
        r1.setProperty("k", sa);
        r2.setProperty("k", sb);
        String u1 = r1.getProperty("k");
        String u2 = r2.getProperty("k");

        ConcurrentHashMap m1 = new ConcurrentHashMap();
        ConcurrentHashMap m2 = new ConcurrentHashMap();
        Object a2 = new Object();
        Object b2 = new Object();
        m1.put("k", a);
        m2.put("k", b);
        Object o1 = m1.replace("k", a2);
        Object o2 = m2.replace("k", b2);
        m1.replace("k", a2, a);
        Object v1 = m1.get("k");
        Object v2 = m2.get("k");

        Check.mayAlias(c1, a);
        Check.mayAlias(c2, b);
        Check.notAlias(c1, c2);
        Check.mayAlias(c3, a);
        Check.notAlias(c3, c4);
        Check.mayAlias(t1, a);
        Check.mayAlias(t2, b);
        Check.notAlias(t1, t2);
        Check.mayAlias(f1, a);
        Check.notAlias(f1, f2);
        Check.mayAlias(p1, a);
        Check.mayAlias(p2, b);
        Check.notAlias(p1, p2);
        Check.mayAlias(g1, a);
        Check.notAlias(g1, g2);
        Check.mayAlias(h1, a);
        Check.mayAlias(h2, b);
        Check.notAlias(h1, h2);
        Check.mayAlias(u1, sa);
        Check.mayAlias(u2, sb);
        Check.mayAlias(o1, a);
        Check.notAlias(o1, o2);
        Check.mayAlias(v1, a2);
        Check.mayAlias(v1, a);
        Check.notAlias(v1, v2);
        Check.mayAlias(s1, s1);
        Check.mayAlias(s2, s2);
        Check.mayAlias(q1, q1);
        Check.mayAlias(q2, q2);
        Check.mayAlias(d1, d1);
        Check.mayAlias(d2, d2);
        Check.mayAlias(st1, st1);
        Check.mayAlias(st2, st2);
        Check.mayAlias(w1, w1);
        Check.mayAlias(w2, w2);
        Check.mayAlias(e1, e1);
        Check.mayAlias(e2, e2);
        Check.mayAlias(r1, r1);
        Check.mayAlias(r2, r2);
        Check.mayAlias(m1, m1);
        Check.mayAlias(m2, m2);
        Check.mayAlias(o2, o2);
        Check.mayAlias(b2, b2);
        Check.mayAlias(u2, u2);
        Check.mayAlias(c4, c4);
        Check.mayAlias(f2, f2);
    }
}
