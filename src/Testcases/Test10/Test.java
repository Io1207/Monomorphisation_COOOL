public class Test {
 
    interface Listener {
        void onEvent(int code);
        int getCount();
    }
 
    static class LogListener implements Listener {
        int count = 0;
        @Override public void onEvent(int code) { count += code; }
        @Override public int getCount() { return count; }
    }
 
    static class AlertListener implements Listener {
        int count = 0;
        @Override public void onEvent(int code) { count += code * 2; }
        @Override public int getCount() { return count; }
    }
 
    static class FilterListener implements Listener {
        int count = 0;
        @Override public void onEvent(int code) {
            if ((code & 1) == 0) count++;
        }
        @Override public int getCount() { return count; }
    }
 
    // ── DirectBus: single listener stored in a field ───────────────────────
    // PT graph should be able to trace: new LogListener() → field → .onEvent()
 
    static class DirectBus {
        Listener listener;
        DirectBus(Listener l) { this.listener = l; }
        void fire(int code)   { listener.onEvent(code); }
        int  result()         { return listener.getCount(); }
    }
 
    // ── Uses of DirectBus with statically-known listener types ────────────
 
    static int runLog(int n) {
        LogListener ll = new LogListener();
        DirectBus   db = new DirectBus(ll);
        for (int i = 0; i < n; i++) db.fire(i);
        return db.result();
    }
 
    static int runAlert(int n) {
        AlertListener al = new AlertListener();
        DirectBus     db = new DirectBus(al);
        for (int i = 0; i < n; i++) db.fire(i);
        return db.result();
    }
 
    static int runFilter(int n) {
        FilterListener fl = new FilterListener();
        DirectBus      db = new DirectBus(fl);
        for (int i = 0; i < n; i++) db.fire(i);
        return db.result();
    }
 
    // ── Multi-cast: two DirectBus instances chained ────────────────────────
 
    static int runChained(int n) {
        LogListener    ll = new LogListener();
        FilterListener fl = new FilterListener();
        DirectBus      b1 = new DirectBus(ll);
        DirectBus      b2 = new DirectBus(fl);
        for (int i = 0; i < n; i++) {
            b1.fire(i);
            b2.fire(i);
        }
        return b1.result() + b2.result();
    }
 
    // ── Inline stress: tiny helper called from observer ────────────────────
 
    static int transformCode(int code) { return code ^ 0x5A; }
 
    static class XorListener implements Listener {
        int acc = 0;
        @Override public void onEvent(int code) { acc += transformCode(code); }
        @Override public int getCount() { return acc; }
    }
 
    static int runXor(int n) {
        XorListener xl = new XorListener();
        DirectBus   db = new DirectBus(xl);
        for (int i = 0; i < n; i++) db.fire(i);
        return db.result();
    }
 
    static int runOnce () {
        int acc = 0;
        acc += runLog(100);
        acc += runAlert(100);
        acc += runFilter(100);
        acc += runChained(100);
        acc += runXor(100);
        System.out.println(acc);
        return acc;
    }

    public static void main(String[] args) {
        int iterations = 50000;

        long start = System.nanoTime();

        int acc = 0;
        for (int iter = 0; iter < iterations; iter++) {
            acc += runOnce();
        }

        long end = System.nanoTime();
        System.out.println("Result : " + acc);
        System.out.printf("Time   : %.3f ms%n", (end - start) / 1_000_000.0);
    }
}