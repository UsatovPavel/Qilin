package qilin.ucfs.banchmark.csprogs;

/** Direct and mutual recursion: the value leaves the recursion at the call site it entered through. */
public class RecursionCtx {
    static Object rec(Object a, int n) {
        if (n > 0) {
            return rec(a, n - 1);
        }
        return a;
    }

    static Object even(Object a, int n) {
        if (n == 0) {
            return a;
        }
        return odd(a, n - 1);
    }

    static Object odd(Object a, int n) {
        return even(a, n - 1);
    }

    public static void main(String[] args) {
        Object o1 = new Object();
        Object o2 = new Object();
        Object x1 = rec(o1, 3);
        Object x2 = rec(o2, 3);
        Object y1 = even(o1, 4);
        Object y2 = even(o2, 4);
        Check.mayAlias(x1, o1);
        Check.mayAlias(y2, o2);
        Check.notAlias(x1, x2);
        Check.notAlias(y1, y2);
    }
}
