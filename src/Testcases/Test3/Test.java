/**
 * TC10 — Mega stress test: all patterns combined
 *
 * What it exercises (all at once):
 *   • Deep inheritance (6 levels), multiple interfaces, decorator stacks.
 *   • Factory methods + conditional allocation.
 *   • Visitor double-dispatch inside a decorator.
 *   • Mutual recursion between two non-trivial methods (should NOT be inlined).
 *   • thisRef site tracking: `this` inside a method should have a concrete type.
 *   • Large method count: 30+ methods, ~15 concrete classes.
 *   • MAX_PASSES convergence: the interplay of mono+inline should settle within
 *     10 passes rather than diverging.
 *   • allocCounter uniqueness: 20+ distinct allocation sites.
 *
 * Expected outcome:
 *   • Large reduction in virtualCallSites (most are on locally-newed objects).
 *   • Several inline chains collapsed.
 *   • Mutual recursion NOT inlined (currentlyInlining guard fires).
 *   • Program output deterministic and correct.
 */
public class Test {

    // ═══════════════════════════════════════════════════════════════════════
    // Part A — 6-level inheritance + interface mix
    // ═══════════════════════════════════════════════════════════════════════

    interface Measurable { int measure(); }
    interface Printable  { String label(); }

    static abstract class Shape implements Measurable {
        abstract int area();
        @Override public int measure() { return area(); }
    }

    static abstract class Solid extends Shape implements Printable {
        abstract int volume();
        @Override public int area() { return volume() / 10 + 1; }
    }

    static class Cube extends Solid {
        final int side;
        Cube(int s) { this.side = s; }
        @Override public int volume() { return side * side * side; }
        @Override public String label() { return "Cube"; }
    }

    static class RoundedCube extends Cube {
        RoundedCube(int s) { super(s); }
        @Override public int volume() { return super.volume() - side; }
        @Override public String label() { return "RCube"; }
    }

    static class Sphere extends Solid {
        final int r;
        Sphere(int r) { this.r = r; }
        @Override public int volume() { return (4 * r * r * r) / 3; }
        @Override public String label() { return "Sphere"; }
    }

    static class HollowSphere extends Sphere {
        final int thickness;
        HollowSphere(int r, int t) { super(r); this.thickness = t; }
        @Override public int volume() { return super.volume() - thickness * r; }
    }

    static class Cylinder extends Solid {
        final int r, h;
        Cylinder(int r, int h) { this.r = r; this.h = h; }
        @Override public int volume() { return r * r * h; }
        @Override public String label() { return "Cylinder"; }
    }

    // ── Factories ──────────────────────────────────────────────────────────

    static Shape makeShape(int type, int param) {
        if (type == 0) return new Cube(param);
        if (type == 1) return new Sphere(param);
        return new Cylinder(param, param + 1);
    }

    static Solid makeSolid(int param) { return new RoundedCube(param); }

    // ═══════════════════════════════════════════════════════════════════════
    // Part B — Decorator over shapes
    // ═══════════════════════════════════════════════════════════════════════

    static class ScaledShape implements Measurable {
        private final Shape inner;
        private final int   scale;
        ScaledShape(Shape s, int scale) { this.inner = s; this.scale = scale; }
        @Override public int measure() { return inner.measure() * scale; }
    }

    static class ClampedShape implements Measurable {
        private final Measurable inner;
        private final int max;
        ClampedShape(Measurable m, int max) { this.inner = m; this.max = max; }
        @Override public int measure() {
            int v = inner.measure();
            return v > max ? max : v;
        }
    }

    // Builds a decorator stack with statically-known types at each layer
    static int decoratedMeasure(int param) {
        Cube         c  = new Cube(param);
        ScaledShape  sc = new ScaledShape(c, 3);
        ClampedShape cl = new ClampedShape(sc, 1000);
        return cl.measure();
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Part C — Visitor over shape hierarchy
    // ═══════════════════════════════════════════════════════════════════════

    interface ShapeVisitor {
        int visit(Cube c);
        int visit(Sphere s);
        int visit(Cylinder cyl);
    }

    interface Visitable { int accept(ShapeVisitor v); }

    static class VisitableCube extends Cube implements Visitable {
        VisitableCube(int s) { super(s); }
        @Override public int accept(ShapeVisitor v) { return v.visit(this); }
    }

    static class VisitableSphere extends Sphere implements Visitable {
        VisitableSphere(int r) { super(r); }
        @Override public int accept(ShapeVisitor v) { return v.visit(this); }
    }

    static class VisitableCylinder extends Cylinder implements Visitable {
        VisitableCylinder(int r, int h) { super(r, h); }
        @Override public int accept(ShapeVisitor v) { return v.visit(this); }
    }

    static class VolumeVisitor implements ShapeVisitor {
        @Override public int visit(Cube c)     { return c.volume(); }
        @Override public int visit(Sphere s)   { return s.volume(); }
        @Override public int visit(Cylinder c) { return c.volume(); }
    }

    static class LabelLengthVisitor implements ShapeVisitor {
        @Override public int visit(Cube c)     { return c.label().length(); }
        @Override public int visit(Sphere s)   { return s.label().length(); }
        @Override public int visit(Cylinder c) { return c.label().length(); }
    }

    static int visitorStress(int param) {
        VisitableCube     vc  = new VisitableCube(param);
        VisitableSphere   vs  = new VisitableSphere(param);
        VisitableCylinder vcy = new VisitableCylinder(param, param + 1);
        VolumeVisitor      vv = new VolumeVisitor();
        LabelLengthVisitor ll = new LabelLengthVisitor();
        return vc.accept(vv) + vs.accept(vv) + vcy.accept(vv)
             + vc.accept(ll) + vs.accept(ll) + vcy.accept(ll);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Part D — Mutual recursion (must NOT be inlined)
    // ═══════════════════════════════════════════════════════════════════════

    static int isEven(int n) {
        if (n == 0) return 1;
        return isOdd(n - 1);
    }

    static int isOdd(int n) {
        if (n == 0) return 0;
        return isEven(n - 1);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Part E — thisRef tracking
    // ═══════════════════════════════════════════════════════════════════════

    static class Counter {
        int count = 0;
        Counter increment() { count++; return this; }
        Counter add(int n)  { count += n; return this; }
        int get()           { return count; }
    }

    static class BoundedCounter extends Counter {
        final int bound;
        BoundedCounter(int b) { this.bound = b; }
        @Override Counter increment() {
            if (count < bound) count++;
            return this;
        }
    }

    static int counterStress(int n) {
        Counter c = new Counter();
        c.increment().increment().add(n);
        int r1 = c.get();

        BoundedCounter bc = new BoundedCounter(10);
        bc.increment().increment().increment().add(n);
        int r2 = bc.get();

        return r1 + r2;
    }

    // ═══════════════════════════════════════════════════════════════════════
    // main
    // ═══════════════════════════════════════════════════════════════════════

    public static void main(String[] args) {
        int acc = 0;
        for (int i = 1; i <= 100; i++) {
            // Part A
            Shape s = makeShape(i % 3, i);
            acc += s.measure();
            Solid sol = makeSolid(i);
            acc += sol.volume() + sol.area();

            // Part B
            acc += decoratedMeasure(i);

            // Part C
            acc += visitorStress(i);

            // Part D — mutual recursion (small n to keep runtime fast)
            acc += isEven(i % 20) + isOdd(i % 20);

            // Part E
            acc += counterStress(i);
        }
        System.out.println(acc);
    }
}