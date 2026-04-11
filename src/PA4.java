// import soot.*;
// import soot.options.Options;

// public class PA4 {
//     public static void main(String[] args) {
//         String classPath = "./testcases/" + args[0];

//         // 1. Global options
//         Options.v().set_keep_line_number(true);

//         // 2a. Monomorphization + Inlining pass (runs FIRST)
//         //     This simplifies virtual dispatch and eliminates small call frames
//         //     so that the pointer-analysis pass sees a cleaner, flatter CFG.
//         PackManager.v().getPack("wjtp").add(
//             new Transform("wjtp.mono", new MonoInlineTransformer()));

//         // 2b. Your existing DFA / pointer-analysis pass (runs SECOND)
//         PackManager.v().getPack("wjtp").add(
//             new Transform("wjtp.dfa", new AnalysisTransformer()));

//         // 3. Soot arguments (unchanged from your original)
//         String[] sootArgs = {
//             "-cp", classPath,
//             "-pp",
//             "-w",
//             "-app",
//             "-allow-phantom-refs",
//             "-no-bodies-for-excluded",
//             "-exclude", "java.*",
//             "-exclude", "javax.*",
//             "-exclude", "sun.*",
//             "-exclude", "com.sun.*",
//             "-exclude", "jdk.*",
//             "-f", "J",
//             "-t", "1",
//             "-main-class", "Test",
//             "-process-dir", classPath
//         };

//         soot.Main.main(sootArgs);
//     }
// }

import soot.*;
import soot.options.Options;
import soot.jimple.*;

import java.io.*;
import java.nio.file.*;
import java.util.*;

public class PA4 {

    public static void main(String[] args) throws Exception {
        String classPath = "./testcases/" + args[0];
        String outDir    = "./output/" + args[0];

        Files.createDirectories(Paths.get(outDir + "/before"));
        Files.createDirectories(Paths.get(outDir + "/after"));

        // ── PASS 1: dump Jimple BEFORE transformation ─────────────────────
        configureSoot(classPath);
        // Register a transformer that ONLY snapshots the bodies, then exits.
        PackManager.v().getPack("wjtp").add(
            new Transform("wjtp.snapshot_before", new SceneTransformer() {
                @Override
                protected void internalTransform(String p, Map<String, String> o) {
                    dumpAllJimple(outDir + "/before");
                    collectMetrics("BEFORE", outDir + "/before/metrics.txt");
                }
            }));
        soot.Main.main(buildArgs(classPath));

        // Reset Soot completely between passes
        G.reset();

        // ── PASS 2: run transforms, dump Jimple AFTER ─────────────────────
        configureSoot(classPath);
        PackManager.v().getPack("wjtp").add(
            new Transform("wjtp.mono", new MonoInlineTransformer()));
        PackManager.v().getPack("wjtp").add(
            new Transform("wjtp.dfa",  new AnalysisTransformer()));
        PackManager.v().getPack("wjtp").add(
            new Transform("wjtp.snapshot_after", new SceneTransformer() {
                @Override
                protected void internalTransform(String p, Map<String, String> o) {
                    dumpAllJimple(outDir + "/after");
                    collectMetrics("AFTER", outDir + "/after/metrics.txt");
                }
            }));
        soot.Main.main(buildArgs(classPath));

        // ── PASS 3: diff the two metrics files and print a summary ─────────
        G.reset();
        printComparison(outDir + "/before/metrics.txt",
                        outDir + "/after/metrics.txt");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Soot configuration (identical for both passes)
    // ─────────────────────────────────────────────────────────────────────────

    static void configureSoot(String classPath) {
        Options.v().set_keep_line_number(true);
        Options.v().set_whole_program(true);
        Options.v().set_app(true);
        Options.v().set_allow_phantom_refs(true);
    }

    static String[] buildArgs(String classPath) {
        return new String[]{
            "-cp", classPath,
            "-pp",
            "-w",
            "-app",
            "-allow-phantom-refs",
            "-no-bodies-for-excluded",
            "-exclude", "java.*",
            "-exclude", "javax.*",
            "-exclude", "sun.*",
            "-exclude", "com.sun.*",
            "-exclude", "jdk.*",
            "-f", "J",          // output format: Jimple
            "-t", "1",
            "-main-class", "Test",
            "-process-dir", classPath
        };
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Dump one .jimple file per application method into targetDir/
    // ─────────────────────────────────────────────────────────────────────────

    static void dumpAllJimple(String targetDir) {
        for (SootClass sc : Scene.v().getApplicationClasses()) {
            // One file per class, named ClassName.jimple
            StringBuilder sb = new StringBuilder();
            sb.append("// Class: ").append(sc.getName()).append("\n\n");

            for (SootMethod m : sc.getMethods()) {
                if (!m.isConcrete()) continue;
                sb.append("// Method: ").append(m.getSubSignature()).append("\n");
                try {
                    Body body = m.retrieveActiveBody();
                    // Jimple printer
                    StringWriter sw = new StringWriter();
                    PrintWriter  pw = new PrintWriter(sw);
                    soot.Printer.v().printTo(body, pw);
                    pw.flush();
                    sb.append(sw).append("\n\n");
                } catch (Exception e) {
                    sb.append("  <no body: ").append(e.getMessage()).append(">\n\n");
                }
            }

            String fileName = targetDir + "/" + sc.getShortName() + ".jimple";
            try (PrintWriter out = new PrintWriter(fileName)) {
                out.print(sb);
            } catch (IOException e) {
                System.err.println("Could not write " + fileName + ": " + e.getMessage());
            }
        }
        System.out.println("[DUMP] Jimple written to " + targetDir);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Collect quantitative metrics into a text file
    // ─────────────────────────────────────────────────────────────────────────

    static void collectMetrics(String label, String outFile) {
        /*
         * Metrics we track:
         *
         *  totalStmts          – raw statement count across all app methods
         *  virtualCallSites    – VirtualInvokeExpr + InterfaceInvokeExpr sites
         *  directCallSites     – SpecialInvokeExpr + StaticInvokeExpr sites
         *  inlineOpportunities – call sites where the callee has ≤20 stmts
         *  methodCount         – number of concrete app methods
         *
         * After inlining you expect:
         *   totalStmts        to RISE   (callee bodies moved inline)
         *   virtualCallSites  to FALL   (monomorphized → direct)
         *   directCallSites   to FALL   (inlined → removed)
         *   methodCount       unchanged (we don't delete callees)
         */
        int totalStmts        = 0;
        int virtualCallSites  = 0;
        int directCallSites   = 0;
        int inlineOpps        = 0;
        int methodCount       = 0;

        for (SootClass sc : Scene.v().getApplicationClasses()) {
            for (SootMethod m : sc.getMethods()) {
                if (!m.isConcrete()) continue;
                methodCount++;
                Body body;
                try { body = m.retrieveActiveBody(); }
                catch (Exception e) { continue; }

                for (Unit u : body.getUnits()) {
                    totalStmts++;
                    Stmt s = (Stmt) u;
                    if (!s.containsInvokeExpr()) continue;
                    InvokeExpr ie = s.getInvokeExpr();

                    if (ie instanceof VirtualInvokeExpr || ie instanceof InterfaceInvokeExpr) {
                        virtualCallSites++;
                    } else if (ie instanceof SpecialInvokeExpr || ie instanceof StaticInvokeExpr) {
                        directCallSites++;
                        // Is this callee small enough to have been inlined?
                        SootMethod callee = ie.getMethod();
                        if (callee.isConcrete()) {
                            try {
                                int sz = callee.retrieveActiveBody().getUnits().size();
                                if (sz <= 20) inlineOpps++;
                            } catch (Exception ignored) {}
                        }
                    }
                }
            }
        }

        String content = String.format(
            "label=%s\n" +
            "methodCount=%d\n" +
            "totalStmts=%d\n" +
            "virtualCallSites=%d\n" +
            "directCallSites=%d\n" +
            "inlineOpportunities=%d\n",
            label, methodCount, totalStmts,
            virtualCallSites, directCallSites, inlineOpps);

        try (PrintWriter pw = new PrintWriter(outFile)) {
            pw.print(content);
        } catch (IOException e) {
            System.err.println("Could not write metrics: " + e.getMessage());
        }

        System.out.println("\n[METRICS " + label + "]\n" + content);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Side-by-side comparison printed to stdout
    // ─────────────────────────────────────────────────────────────────────────

    static void printComparison(String beforeFile, String afterFile) throws Exception {
        Properties before = load(beforeFile);
        Properties after  = load(afterFile);

        System.out.println("\n╔══════════════════════════════════════════════════════════╗");
        System.out.println("║           TRANSFORMATION IMPROVEMENT SUMMARY             ║");
        System.out.println("╠══════════════════╦═════════╦═════════╦══════════════════╣");
        System.out.printf( "║ %-16s ║ %7s ║ %7s ║ %16s ║%n",
                           "Metric", "BEFORE", "AFTER", "Change");
        System.out.println("╠══════════════════╬═════════╬═════════╬══════════════════╣");

        String[] keys = {
            "methodCount", "totalStmts",
            "virtualCallSites", "directCallSites", "inlineOpportunities"
        };
        // For each metric, lower virtual sites = better; lower direct+inline = more inlined
        // We'll just show delta and % change.
        for (String key : keys) {
            int b = Integer.parseInt(before.getProperty(key, "0"));
            int a = Integer.parseInt(after.getProperty(key, "0"));
            int delta = a - b;
            String pct = b == 0 ? "  n/a" : String.format("%+.1f%%", 100.0 * delta / b);
            System.out.printf("║ %-16s ║ %7d ║ %7d ║ %+7d  %7s ║%n",
                              key, b, a, delta, pct);
        }

        System.out.println("╚══════════════════╩═════════╩═════════╩══════════════════╝");

        System.out.println("\nINTERPRETATION:");
        int vBefore = Integer.parseInt(before.getProperty("virtualCallSites","0"));
        int vAfter  = Integer.parseInt(after.getProperty("virtualCallSites","0"));
        int dBefore = Integer.parseInt(before.getProperty("directCallSites","0"));
        int dAfter  = Integer.parseInt(after.getProperty("directCallSites","0"));

        int monoedSites  = vBefore - vAfter;
        int inlinedSites = dBefore - dAfter;

        System.out.printf("  • %d virtual call(s) monomorphized (virtual dispatch eliminated)%n", monoedSites);
        System.out.printf("  • %d direct call(s) inlined (call frame eliminated)%n", inlinedSites);
        System.out.println("  • Jimple diffs: compare before/ and after/ directories");
        System.out.println("    e.g.:  diff -u output/<testcase>/before/Test.jimple output/<testcase>/after/Test.jimple");
    }

    static Properties load(String path) throws Exception {
        Properties p = new Properties();
        try (FileReader fr = new FileReader(path)) { p.load(fr); }
        return p;
    }
}