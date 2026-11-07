package qilin.ucfs.csprogs;

/** Ma et al. PLDI'23, Fig. 5: parameters flow to the return value through local assignments. */
public class LocalFlow {
    static Object select(Object p1, Object p2) {
        Object r;
        if (Check.flag) {
            r = p1;
        } else {
            r = p2;
        }
        return r;
    }

    static Object id(Object p) {
        return p;
    }

    public static void main(String[] args) {
        Object a1 = new Object();
        Object a2 = new Object();
        Object r1 = select(a1, a2);
        Object a3 = new Object();
        Object a4 = new Object();
        Object r2 = select(a3, a4);
        Object v1 = id(a1);
        Object v2 = id(a3);
        Check.mayAlias(r1, a1);
        Check.mayAlias(r1, a2);
        Check.mayAlias(r2, a4);
        Check.notAlias(r1, r2);
        Check.mayAlias(v1, a1);
        Check.notAlias(v1, v2);
    }
}
