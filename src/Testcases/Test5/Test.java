/**
 * TC8 — Inline threshold boundary (INLINE_THRESHOLD = 30)
 *
 * What it exercises:
 *   • Methods with exactly 28, 29, 30, 31 Jimple statements to probe the
 *     boundary between "gets inlined" and "skipped (size=N)".
 *   • A large method (60 stmts) that should NEVER be inlined.
 *   • Tests the `sz > INLINE_THRESHOLD` guard in inlinePass.
 *   • After inlining a 28-stmt callee, the caller grows; re-running the pass
 *     on the grown caller must still be stable (no duplicate inlines).
 *   • staticinvoke chains: static helpers call other static helpers;
 *     the pass must follow the chain depth by depth across passes.
 *
 * Expected outcome:
 *   • smallHelper (< 30) → inlined.
 *   • borderHelper (≈ 30) → may or may not inline depending on exact Jimple count.
 *   • bigHelper (> 30) → skipped with "[INLINE] Skipped (size=N)" log.
 *   • virtualCallSites drop from monomorphization; directCallSites drop from inlining.
 */
public class Test {

    // ── Helpers of various sizes ───────────────────────────────────────────

    // ~10 Jimple statements — well under threshold, definitely inlined
    static int tinyMath(int x) {
        int a = x + 1;
        int b = a * 3;
        int c = b - 2;
        return c ^ a;
    }

    // ~28 statements — just under threshold
    static int nearThresholdBelow(int x) {
        int a = x;
        int b = a + 1; int c = b + 2; int d = c + 3; int e = d + 4;
        int f = e - 1; int g = f - 2; int h = g - 3; int k = h - 4;
        int l = k ^ b; int m = l ^ c; int n = m ^ d; int o = n ^ e;
        int p = o & f; int q = p & g; int r = q & h; int s = r & k;
        int t = s | l; int u = t | m; int v = u | n; int w = v | o;
        int y = w + x;
        return y;
    }

    // ~32 statements — just over threshold, should be skipped
    static int nearThresholdAbove(int x) {
        int a = x;
        int b = a + 1; int c = b + 2; int d = c + 3; int e = d + 4;
        int f = e + 5; int g = f + 6; int h = g + 7; int k = h + 8;
        int l = k - 1; int m = l - 2; int n = m - 3; int o = n - 4;
        int p = o - 5; int q = p - 6; int r = q - 7; int s = r - 8;
        int t = s ^ b; int u = t ^ c; int v = u ^ d; int w = v ^ e;
        int y = w ^ f; int z = y ^ g;
        return z + a;
    }

    // ~60 statements — large, never inlined
    static int bigComputation(int x) {
        int a = x; int b = a+1; int c = b+2; int d = c+3; int e = d+4;
        int f = e+5; int g = f+6; int h = g+7; int k = h+8; int l = k+9;
        int m = l+10; int n = m+11; int o = n+12; int p = o+13; int q = p+14;
        int r = q+15; int s = r+16; int t = s+17; int u = t+18; int v = u+19;
        int w = v-1;  int y = w-2;  int z = y-3;  int aa = z-4; int bb = aa-5;
        int cc = bb-6; int dd = cc-7; int ee = dd-8; int ff = ee-9; int gg = ff-10;
        int hh = gg^b; int ii = hh^c; int jj = ii^d; int kk = jj^e; int ll = kk^f;
        int mm = ll^g; int nn = mm^h; int oo = nn^k; int pp = oo^l; int qq = pp^m;
        int rr = qq+a; int ss = rr+b; int tt = ss+c; int uu = tt+d; int vv = uu+e;
        int ww = vv*2; int xx = ww*3; int yy = xx*4; int zz = yy*5;
        return zz + n + o + p + q + r + s + t + u + v + w;
    }

    // ── Static chains: inner calls other statics ───────────────────────────

    static int step1(int x) { return tinyMath(x); }
    static int step2(int x) { return step1(x) + nearThresholdBelow(x); }
    static int step3(int x) { return step2(x) + nearThresholdAbove(x); }
    static int step4(int x) { return step3(x) + bigComputation(x); }

    // ── Polymorphic wrappers around boundary helpers ───────────────────────

    interface Processor { int process(int x); }

    static class SmallProcessor implements Processor {
        @Override public int process(int x) { return tinyMath(x); }
    }

    static class BoundaryProcessor implements Processor {
        @Override public int process(int x) { return nearThresholdBelow(x); }
    }

    static class BigProcessor implements Processor {
        @Override public int process(int x) { return bigComputation(x); }
    }

    // These locals are statically typed → forwardMono / ptGraphMono
    static int polymorphicUsage(int x) {
        SmallProcessor    sp = new SmallProcessor();
        BoundaryProcessor bp = new BoundaryProcessor();
        BigProcessor      bg = new BigProcessor();
        return sp.process(x) + bp.process(x) + bg.process(x);
    }

    public static void main(String[] args) {
        int acc = 0;
        for (int i = 0; i < 500; i++) {
            acc += step4(i);
            acc += polymorphicUsage(i);
        }
        System.out.println(acc);
    }
}