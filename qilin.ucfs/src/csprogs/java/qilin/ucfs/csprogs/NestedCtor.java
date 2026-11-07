package qilin.ucfs.csprogs;

/** Ma et al. PLDI'23, Fig. 3: a field store reached through a chain of constructor calls. */
public class NestedCtor {
    static class A {
        Object f;

        A(Object o) {
            this.f = o;
        }
    }

    static class B extends A {
        B(Object o) {
            super(o);
        }
    }

    public static void main(String[] args) {
        Object o1 = new Object();
        Object o2 = new Object();
        B b1 = new B(o1);
        B b2 = new B(o2);
        Object x1 = b1.f;
        Object x2 = b2.f;
        Check.mayAlias(x1, o1);
        Check.mayAlias(x2, o2);
        Check.notAlias(x1, x2);
    }
}
