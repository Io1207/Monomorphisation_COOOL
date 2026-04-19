public class Test {
 
    // ── Hierarchy ──────────────────────────────────────────────────────────
 
    static abstract class A {
        abstract int compute(int x);
        int shared(int x) { return x + 1; }
    }
 
    static abstract class B extends A {
        @Override int shared(int x) { return x * 2; }
    }
 
    static class C extends B {
        @Override public int compute(int x) { return x + 10; }
    }
 
    static class D extends B {
        @Override public int compute(int x) { return x * 3; }
        @Override int shared(int x) { return x - 1; }
    }
 
    static class E extends C {
        @Override public int compute(int x) { return x ^ 0xFF; }
    }
 
    // ── Wrapper that the analyser sees as "might be anything" ─────────────
    // (but each call site is actually monomorphic)
 
    static int useA(A a, int v) {
        return a.compute(v) + a.shared(v);
    }
 
    // ── Chains ────────────────────────────────────────────────────────────
 
    static int chainC(int seed) {
        C c = new C();
        int r = c.compute(seed);
        r += c.shared(seed);
        return r;
    }
 
    static int chainD(int seed) {
        D d = new D();
        int r = d.compute(seed);
        r += d.shared(seed);
        return r;
    }
 
    static int chainE(int seed) {
        E e = new E();
        return e.compute(seed) + e.shared(seed) + e.compute(seed + 1);
    }
 
    // ── Methods that call useA with a statically known concrete type ──────
    // PT graph / CG should see these as monomorphic even though useA's param
    // is typed as A.
 
    static int callViaAbstractC(int v) {
        C obj = new C();
        return useA(obj, v);           // virtual inside useA → only C reachable
    }
 
    static int callViaAbstractD(int v) {
        D obj = new D();
        return useA(obj, v);
    }
 
    // ── Accumulator (prevents DCE) ────────────────────────────────────────
 
    static void runOnce() {
        int acc = 0;
        for (int i = 0; i < 1000; i++) {
            acc += chainC(i);
            acc += chainD(i);
            acc += chainE(i);
            acc += callViaAbstractC(i);
            acc += callViaAbstractD(i);
        }
        System.out.println(acc);
    }
    public static void main(String[] args) {
        int iterations = 50000;

        long start = System.nanoTime();

        int acc = 0;
        for (int iter = 0; iter < iterations; iter++) {
          runOnce();
        }

        long end = System.nanoTime();
        System.out.println("Result : " + acc);
        System.out.printf("Time   : %.3f ms%n", (end - start) / 1_000_000.0);
    }
}