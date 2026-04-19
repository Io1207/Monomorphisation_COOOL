/**
 * TC7 — State machine with virtual dispatch
 *
 * What it exercises:
 *   • State field is assigned once per `new XxxState()` allocation, then
 *     overwritten in transition().  PT graph should track both old and new
 *     values flowing into the state field over iterations.
 *   • forwardMono: each individual transition() call site may have a
 *     statically known CG edge (if the CG was built with type inference).
 *   • Checks that the MAX_PASSES loop terminates: after each inline+mono
 *     cycle the worklist quiesces.
 *   • Tests the `currentlyInlining` guard: the state machine loop is NOT
 *     recursive, but the inliner must not get confused by the loop body.
 *   • Tiny state methods (1–4 stmts) → eligible for inlining.
 *
 * Expected outcome:
 *   • Transitions on known state types → specialinvoke.
 *   • Most state impls inlined.
 *   • Pass loop exits within ≤ MAX_PASSES iterations.
 */
public class Test {

    // ── State interface ────────────────────────────────────────────────────

    interface State {
        State next(int input);
        int   output();
    }

    // ── Concrete states ────────────────────────────────────────────────────

    static class IdleState implements State {
        @Override public State next(int input) {
            if (input > 0) return new RunningState(input);
            return this;
        }
        @Override public int output() { return 0; }
    }

    static class RunningState implements State {
        private final int value;
        RunningState(int v) { this.value = v; }
        @Override public State next(int input) {
            if (input < 0) return new ErrorState(-input);
            if (input == 0) return new IdleState();
            return new RunningState(value + input);
        }
        @Override public int output() { return value; }
    }

    static class ErrorState implements State {
        private final int code;
        ErrorState(int c) { this.code = c; }
        @Override public State next(int input) {
            return new IdleState();   // reset
        }
        @Override public int output() { return -code; }
    }

    static class DoneState implements State {
        private final int result;
        DoneState(int r) { this.result = r; }
        @Override public State next(int input) { return this; }  // absorbing
        @Override public int output() { return result; }
    }

    // ── State machine runner ───────────────────────────────────────────────

    static class Machine {
        private State state;
        Machine() { this.state = new IdleState(); }

        void step(int input) { state = state.next(input); }
        int  read()          { return state.output(); }
    }

    // ── Usage: deterministic input sequences ──────────────────────────────

    static int runSequence(int[] inputs) {
        Machine m = new Machine();
        int acc = 0;
        for (int v : inputs) {
            m.step(v);
            acc += m.read();
        }
        return acc;
    }

    // ── Inline-able transition helper ─────────────────────────────────────

    static State makeIdle()           { return new IdleState(); }
    static State makeRunning(int v)   { return new RunningState(v); }
    static State makeError(int c)     { return new ErrorState(c); }
    static State makeDone(int r)      { return new DoneState(r); }

    // ── Direct-allocation transitions (no Machine wrapper) ────────────────
    // Each local here is a statically-known concrete type → full mono+inline

    static int directTransitions(int x) {
        IdleState    idle    = new IdleState();
        State        s1      = idle.next(x);        // → RunningState or this
        RunningState running = new RunningState(x);
        State        s2      = running.next(x + 1);
        ErrorState   err     = new ErrorState(5);
        State        s3      = err.next(0);         // → IdleState
        return s1.output() + s2.output() + s3.output();
    }

    static int runOnce() {
        int[] seq1 = {1, 2, 3, -1, 0, 5, -2, 0};
        int[] seq2 = {10, 5, 3,  0, 7,  0, -1, 0};
        int[] seq3 = new int[50];
        for (int i = 0; i < 50; i++) seq3[i] = (i % 7) - 3;

        int acc = 0;
        acc += runSequence(seq1);
        acc += runSequence(seq2);
        acc += runSequence(seq3);
        for (int i = 0; i < 100; i++) acc += directTransitions(i);
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