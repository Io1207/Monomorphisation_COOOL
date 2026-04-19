
import soot.*;
import soot.options.Options;
import soot.jimple.*;

import java.io.*;
import java.nio.file.*;
import java.util.*;

public class PA4 {
    public static void main(String[] args) throws Exception {
        String testcase  = args[0];
        String classPath = "./testcases/" + testcase;
        String outDir    = "./output/"    + testcase;

        Files.createDirectories(Paths.get(outDir + "/before"));
        Files.createDirectories(Paths.get(outDir + "/after"));
        Files.createDirectories(Paths.get(outDir + "/transformed_classes"));

        configureSoot(classPath);
        PackManager.v().getPack("wjtp").add(
            new Transform("wjtp.snapshot_before", new SceneTransformer() {
                @Override
                protected void internalTransform(String p, Map<String, String> o) {
                    dumpAllJimple(outDir + "/before");
                    collectMetrics("BEFORE", outDir + "/before/metrics.txt");
                }
            }));
        soot.Main.main(buildArgsJimple(classPath));
        G.reset();

        configureSoot(classPath);
        PackManager.v().getPack("wjtp").add(
            new Transform("wjtp.mono", new MonoInlineTransformer()));
        PackManager.v().getPack("wjtp").add(
            new Transform("wjtp.snapshot_after", new SceneTransformer() {
                @Override
                protected void internalTransform(String p, Map<String, String> o) {
                    dumpAllJimple(outDir + "/after");
                    collectMetrics("AFTER", outDir + "/after/metrics.txt");
                }
            }));
        soot.Main.main(buildArgsJimple(classPath));
        G.reset();

        // ── PASS 3: emit TRANSFORMED .class files for wall-clock timing ───
        configureSoot(classPath);
        PackManager.v().getPack("wjtp").add(
            new Transform("wjtp.mono", new MonoInlineTransformer()));
        soot.Main.main(buildArgsClassOutput(classPath,
                outDir + "/transformed_classes"));
        G.reset();

        // ── PASS 4: emit ORIGINAL .class files for baseline timing ────────
        //    (Soot re-processes without any transformer so we get a clean
        //     class-file baseline that went through the same Soot pipeline,
        //     avoiding apples-to-oranges comparisons with javac output.)
        configureSoot(classPath);
        PackManager.v().getPack("wjtp").add(
            new Transform("wjtp.snapshot_original", new SceneTransformer() {
                @Override
                protected void internalTransform(String p, Map<String, String> o) {
                    // no-op — we just want the class files emitted
                }
            }));
        Files.createDirectories(Paths.get(outDir + "/original_classes"));
        soot.Main.main(buildArgsClassOutput(classPath,
                outDir + "/original_classes"));
        G.reset();

        // ── Static metrics comparison ──────────────────────────────────────
        printComparison(outDir + "/before/metrics.txt",
                        outDir + "/after/metrics.txt");

        // ── Wall-clock timing (5 runs each, -Xint interpreter mode) ───────
        System.out.println();
        runTimingComparison(
                outDir + "/original_classes",
                outDir + "/transformed_classes",
                testcase,
                /* runs = */ 5);

        // ── Full combined report ───────────────────────────────────────────
        printFullReport(outDir + "/before/metrics.txt",
                        outDir + "/after/metrics.txt",
                        testcase);
    }

    static void configureSoot(String classPath) {
        Options.v().set_keep_line_number(true);
        Options.v().set_whole_program(true);
        Options.v().set_app(true);
        Options.v().set_allow_phantom_refs(true);
    }

    static String[] buildArgsJimple(String classPath) {
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
            "-f", "J",
            "-t", "1",
            "-main-class", "Test",
            "-process-dir", classPath
        };
    }

    static String[] buildArgsClassOutput(String classPath, String outClassDir) {
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
            "-f", "c",          // class file output
            "-d", outClassDir,  // output directory
            "-t", "1",
            "-main-class", "Test",
            "-process-dir", classPath
        };
    }

    static void dumpAllJimple(String targetDir) {
        for (SootClass sc : Scene.v().getApplicationClasses()) {
            StringBuilder sb = new StringBuilder();
            sb.append("// Class: ").append(sc.getName()).append("\n\n");

            for (SootMethod m : sc.getMethods()) {
                if (!m.isConcrete()) continue;
                sb.append("// Method: ").append(m.getSubSignature()).append("\n");
                try {
                    Body         body = m.retrieveActiveBody();
                    StringWriter sw   = new StringWriter();
                    PrintWriter  pw   = new PrintWriter(sw);
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

    // =========================================================================
    // Static metrics collection
    //
    //  totalStmts          – raw statement count across all app methods
    //  virtualCallSites    – VirtualInvokeExpr + InterfaceInvokeExpr
    //  directCallSites     – SpecialInvokeExpr + StaticInvokeExpr
    //  inlineOpportunities – direct call sites where callee has ≤ 20 stmts
    //  methodCount         – number of concrete app methods
    // =========================================================================

    static void collectMetrics(String label, String outFile) {
        int totalStmts = 0;
        int virtualCallSites = 0;
        int directCallSites = 0;
        int inlineOpps = 0;
        int methodCount = 0;

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

                    if (ie instanceof VirtualInvokeExpr ||
                        ie instanceof InterfaceInvokeExpr) {
                        virtualCallSites++;
                    } else if (ie instanceof SpecialInvokeExpr ||
                               ie instanceof StaticInvokeExpr) {
                        directCallSites++;
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

    // =========================================================================
    // Wall-clock timing comparison
    //
    //  Runs the Test class from both classpaths `runs` times each using the
    //  JVM interpreter (-Xint, no JIT) so that virtual-vs-direct dispatch
    //  cost differences are visible in the timing.
    //
    //  Each run captures stdout and looks for the line:
    //      Time   : <number> ms
    //  written by the test's own main().  If that line is absent the run is
    //  marked as N/A and excluded from the average.
    // =========================================================================

    static void runTimingComparison(String origClassDir,
                                     String transformedClassDir,
                                     String testcase,
                                     int    runs) throws Exception {
        System.out.println("╔══════════════════════════════════════════════════════════╗");
        System.out.println("║               WALL-CLOCK TIMING (-Xint)                  ║");
        System.out.println("╠══════════════════════════════════════════════════════════╣");
        System.out.printf( "║  Testcase : %-44s ║%n", testcase);
        System.out.printf( "║  Runs     : %-44d ║%n", runs);
        System.out.println("╠════════════╦══════════════════════╦═════════════════════╣");
        System.out.printf( "║ %-10s ║ %-20s ║ %-19s ║%n",
                           "Run", "Original (ms)", "Transformed (ms)");
        System.out.println("╠════════════╬══════════════════════╬═════════════════════╣");

        double[] origTimes = new double[runs];
        double[] tranTimes = new double[runs];

        for (int i = 0; i < runs; i++) {
            origTimes[i] = timeRun(origClassDir);
            tranTimes[i] = timeRun(transformedClassDir);
            String origStr = origTimes[i] < 0
                    ? "      N/A" : String.format("%18.3f", origTimes[i]);
            String tranStr = tranTimes[i] < 0
                    ? "     N/A" : String.format("%17.3f", tranTimes[i]);
            System.out.printf("║ Run %-6d ║ %s ║ %s ║%n",
                    i + 1, origStr, tranStr);
        }

        System.out.println("╠════════════╩══════════════════════╩═════════════════════╣");

        double origAvg = average(origTimes);
        double tranAvg = average(tranTimes);

        if (origAvg < 0 || tranAvg < 0) {
            System.out.println("║  Could not compute averages — some runs returned N/A.   ║");
            System.out.println("║  Make sure Test.main() prints:  Time   : X.XXX ms       ║");
            System.out.println("╚══════════════════════════════════════════════════════════╝");
            return;
        }

        double speedup = origAvg / tranAvg;
        double pct     = ((origAvg - tranAvg) / origAvg) * 100.0;

        System.out.printf("║  Original    avg : %33.3f ms ║%n", origAvg);
        System.out.printf("║  Transformed avg : %33.3f ms ║%n", tranAvg);
        System.out.printf("║  Speedup         : %33.3fx ║%n", speedup);
        System.out.printf("║  Improvement     : %32.1f%%  ║%n", pct);
        System.out.println("╚══════════════════════════════════════════════════════════╝");
    }

    static double timeRun(String classDir) throws Exception {
        ProcessBuilder pb = new ProcessBuilder("java", "-Xint", "-cp", classDir, "Test");
        pb.redirectErrorStream(true);
        Process proc = pb.start();

        double ms = -1.0;
        try (BufferedReader br =
                new BufferedReader(new InputStreamReader(proc.getInputStream()))) {
            String line;
            while ((line = br.readLine()) != null) {
                // Test.main() must print:  Time   : 123.456 ms
                if (line.startsWith("Time")) {
                    String[] parts = line.split(":");
                    if (parts.length >= 2) {
                        String val = parts[1].trim().replace("ms", "").trim();
                        try { ms = Double.parseDouble(val); }
                        catch (NumberFormatException ignored) {}
                    }
                }
            }
        }
        proc.waitFor();
        return ms;
    }

    static double average(double[] vals) {
        double sum = 0;
        int count = 0;
        for (double v : vals) if (v >= 0) { sum += v; count++; }
        return count == 0 ? -1.0 : sum / count;
    }

    // =========================================================================
    // Static metrics side-by-side table
    // =========================================================================

    static void printComparison(String beforeFile, String afterFile) throws Exception {
        Properties before = load(beforeFile);
        Properties after  = load(afterFile);

        System.out.println("\n╔══════════════════════════════════════════════════════════╗");
        System.out.println("║           TRANSFORMATION IMPROVEMENT SUMMARY             ║");
        System.out.println("╠══════════════════╦═════════╦═════════╦══════════════════╣");
        System.out.printf( "║ %-16s ║ %7s ║ %7s ║ %16s ║%n",
                           "Metric", "BEFORE", "AFTER", "Change");
        System.out.println("╠══════════════════╬═════════╬═════════╬══════════════════╣");

        String[] keys = { "methodCount", "totalStmts",
                          "virtualCallSites", "directCallSites", "inlineOpportunities"
                        };

        for (String key : keys) {
            int b = Integer.parseInt(before.getProperty(key, "0"));
            int a = Integer.parseInt(after .getProperty(key, "0"));
            int delta = a - b;
            String pct = b == 0 ? " n/a" : String.format("%+.1f%%", 100.0 * delta / b);
            System.out.printf("║ %-16s ║ %7d ║ %7d ║ %+7d  %7s ║%n", key, b, a, delta, pct);
        }

        System.out.println("╚══════════════════╩═════════╩═════════╩══════════════════╝");

        int vBefore = Integer.parseInt(before.getProperty("virtualCallSites", "0"));
        int vAfter  = Integer.parseInt(after .getProperty("virtualCallSites", "0"));
        int dBefore = Integer.parseInt(before.getProperty("directCallSites",  "0"));
        int dAfter  = Integer.parseInt(after .getProperty("directCallSites",  "0"));

        System.out.println("\nFINAL RESULTS:");
        System.out.printf("- %d virtual call(s) monomorphized (virtual dispatch eliminated)%n", vBefore - vAfter);
        System.out.printf("- %d direct call(s) inlined (call frame eliminated)%n",dBefore - dAfter);
        System.out.println("- Jimple diffs: compare before/ and after/ directories");
    }

    static void printFullReport(String beforeFile,
                                 String afterFile,
                                 String testcase) throws Exception {
        Properties before = load(beforeFile);
        Properties after  = load(afterFile);

        int vBefore = Integer.parseInt(before.getProperty("virtualCallSites", "0"));
        int vAfter  = Integer.parseInt(after .getProperty("virtualCallSites", "0"));
        int dBefore = Integer.parseInt(before.getProperty("directCallSites", "0"));
        int dAfter  = Integer.parseInt(after .getProperty("directCallSites", "0"));
        int sBefore = Integer.parseInt(before.getProperty("totalStmts", "0"));
        int sAfter  = Integer.parseInt(after .getProperty("totalStmts", "0"));

        int monoed = vBefore - vAfter;
        int inlined = dBefore - dAfter;
        double vPct = vBefore == 0 ? 0.0 : 100.0 * monoed / vBefore;
        double dPct = dBefore == 0 ? 0.0 : 100.0 * inlined / dBefore;
        double stmtPct = sBefore == 0 ? 0.0 : 100.0 * (sAfter - sBefore)/sBefore;

        System.out.println("\n╔══════════════════════════════════════════════╗");
        System.out.println("║            FULL IMPROVEMENT REPORT           ║");
        System.out.println("╠══════════════════════════════════════════════╣");
        System.out.printf( "║  Testcase         : %-24s ║%n", testcase);
        System.out.println("╠══════════════════════════════════════════════╣");
        System.out.printf( "║  Virtual sites    : %5d → %5d  (%+.1f%%)%s║%n",
                vBefore, vAfter, -vPct,vPct < 10 ? "  " : vPct < 100 ? "  " : " ");
        System.out.printf( "║  Direct  sites    : %5d → %5d  (%+.1f%%)%s║%n",
                dBefore, dAfter, -dPct, dPct < 10 ? "   " : dPct < 100 ? "  " : " ");
        System.out.printf( "║  Total stmts      : %5d → %5d  (%+.1f%%)%s║%n",
                sBefore, sAfter, stmtPct, Math.abs(stmtPct) < 10 ? "  " : " ");
        System.out.println("╠══════════════════════════════════════════════╣");
        System.out.printf( "║  Monomorphized    : %5d call sites          ║%n", monoed);
        System.out.printf( "║  Inlined          : %5d call sites          ║%n", inlined);
        System.out.println("╠══════════════════════════════════════════════╣");
        System.out.println("║  Wall-clock timing written above             ║");
        System.out.println("║  Class files:                                ║");
        System.out.printf( "║    Original    : output/%s/original_classes%n", testcase);
        System.out.printf( "║    Transformed : output/%s/transformed_classes%n", testcase);
        System.out.println("║  To re-run timing manually:                  ║");
        System.out.println("║    java -Xint -cp output/<tc>/original_classes     Test ║");
        System.out.println("║    java -Xint -cp output/<tc>/transformed_classes  Test ║");
        System.out.println("╚══════════════════════════════════════════════╝");
    }

    static Properties load(String path) throws Exception {
        Properties p = new Properties();
        try (FileReader fr = new FileReader(path)) { p.load(fr); }
        return p;
    }
}