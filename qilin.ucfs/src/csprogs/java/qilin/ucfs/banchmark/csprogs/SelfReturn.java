package qilin.ucfs.banchmark.csprogs;

/**
 * A virtual method returning `this` (like Qilin's model of Object.clone): the shortcut passes the receiver through the
 * dispatch gate into a shadow vertex, and the reduction may make that shadow the representative of the result variable.
 */
public class SelfReturn {
    static class C {
        C self() {
            return this;
        }
    }

    public static void main(String[] args) {
        C a1 = new C();
        C a2 = new C();
        C b1 = a1.self();
        C b2 = a2.self();
        Check.mayAlias(a1, b1);
        Check.mayAlias(a2, b2);
        Check.notAlias(b1, b2);
    }
}
