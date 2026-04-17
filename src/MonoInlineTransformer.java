import soot.*;
import soot.jimple.*;
import soot.jimple.toolkits.callgraph.CallGraph;
import soot.jimple.toolkits.callgraph.Edge;
import soot.toolkits.graph.BriefUnitGraph;
import soot.toolkits.graph.UnitGraph;
import soot.util.Chain;

import java.util.*;

public class MonoInlineTransformer extends SceneTransformer {

    private static final int INLINE_THRESHOLD = 30;
    private static final int MAX_PASSES = 8;
    private static final int MAX_INLINE_DEPTH = 3;

    private final Map<String, PTGraph> methodSummaries = new HashMap<>();

    private int allocCounter = 0;
    private final Map<NewExpr, Integer> allocSiteIds  = new IdentityHashMap<>();
    private final Map<Integer, SootClass> allocSiteTypes = new HashMap<>();
    private final Map<String, Integer> thisRefSiteIds = new HashMap<>();

    private final Set<String> currentlyInlining = new HashSet<>();

    @Override
    protected void internalTransform(String phaseName, Map<String, String> options) {
        CallGraph cg = Scene.v().getCallGraph();

        List<SootMethod> appMethods = new ArrayList<>();
        for (SootClass sc : Scene.v().getApplicationClasses())
            appMethods.addAll(sc.getMethods());

        int totalMono = 0, totalInline = 0;

        for (SootMethod method : appMethods) {
            if (!method.isConcrete()) continue;

            methodSummaries.clear();
            PTGraph ptGraph = buildPTGraphInter(method, MAX_INLINE_DEPTH);

            for (int pass = 1; pass <= MAX_PASSES; pass++) {

                int fwdMono = forwardMono(method, cg);
                int inlined = inlinePass(method, cg);
                int ptMono  = ptGraphMono(method, ptGraph);

                if (inlined > 0) {
                    methodSummaries.clear();
                    ptGraph = buildPTGraphInter(method, MAX_INLINE_DEPTH);
                }

                int monoThisPass = fwdMono + ptMono;
                totalMono   += monoThisPass;
                totalInline += inlined;

                System.out.printf("[PASS %d][%s]  fwd-mono=%d  inline=%d  pt-mono=%d%n",
                        pass, method.getName(), fwdMono, inlined, ptMono);

                if (monoThisPass == 0 && inlined == 0) break;
            }
        }

        System.out.printf("%n=== Total monomorphized : %d ===%n", totalMono);
        System.out.printf("=== Total inlined       : %d ===%n%n", totalInline);
    }

    // intraproc

    PTGraph buildPTGraph(SootMethod method) {
        if (!method.isConcrete()) return new PTGraph();

        Body       body  = method.retrieveActiveBody();
        UnitGraph  cfg   = new BriefUnitGraph(body);
        List<Unit> units = new ArrayList<>(body.getUnits());

        Map<Unit, PTGraph> in = new HashMap<>();
        for (Unit u : units) in.put(u, new PTGraph());

        LinkedHashSet<Unit> worklist = new LinkedHashSet<>(units);

        while (!worklist.isEmpty()) {
            Unit u = worklist.iterator().next();
            worklist.remove(u);

            PTGraph inState = new PTGraph();
            for (Unit pred : cfg.getPredsOf(u)) {
                PTGraph predIn = in.get(pred);
                if (predIn == null) continue;
                PTGraph predOut = new PTGraph();
                predOut.copy(predIn);
                // depth=0 → intraprocedural only, no callee descent
                applyTransferInter((Stmt) pred, predOut, method.getSignature(), 0);
                inState.union(predOut);
            }

            if (!inState.equals(in.get(u))) {
                in.put(u, inState);
                worklist.addAll(cfg.getSuccsOf(u));
            }
        }

        PTGraph result = new PTGraph();
        for (Unit u : units) {
            PTGraph inState = in.get(u);
            if (inState == null) continue;
            PTGraph out = new PTGraph();
            out.copy(inState);
            applyTransferInter((Stmt) u, out, method.getSignature(), 0);
            result.union(out);
        }
        return result;
    }

    // =========================================================================
    // INTERPROCEDURAL PT GRAPH — explore callees up to `depth`
    // =========================================================================

    private PTGraph buildPTGraphInter(SootMethod method, int depth) {
        String sig = method.getSignature();

        if (methodSummaries.containsKey(sig))
            return methodSummaries.get(sig);

        if (depth <= 0) {
            System.out.printf("[PT-INTER] Depth limit reached at: %s%n", method.getName());
            return new PTGraph();
        }

        if (!method.isConcrete()) return new PTGraph();

        // Stub to break cycles before we recurse
        methodSummaries.put(sig, new PTGraph());

        Body       body  = method.retrieveActiveBody();
        UnitGraph  cfg   = new BriefUnitGraph(body);
        List<Unit> units = new ArrayList<>(body.getUnits());

        Map<Unit, PTGraph> in = new HashMap<>();
        for (Unit u : units) in.put(u, new PTGraph());

        LinkedHashSet<Unit> worklist = new LinkedHashSet<>(units);

        while (!worklist.isEmpty()) {
            Unit u = worklist.iterator().next();
            worklist.remove(u);

            PTGraph inState = new PTGraph();
            for (Unit pred : cfg.getPredsOf(u)) {
                PTGraph predIn = in.get(pred);
                if (predIn == null) continue;
                PTGraph predOut = new PTGraph();
                predOut.copy(predIn);
                applyTransferInter((Stmt) pred, predOut, sig, depth);
                inState.union(predOut);
            }

            if (!inState.equals(in.get(u))) {
                in.put(u, inState);
                worklist.addAll(cfg.getSuccsOf(u));
            }
        }

        PTGraph result = new PTGraph();
        for (Unit u : units) {
            PTGraph inState = in.get(u);
            if (inState == null) continue;
            PTGraph out = new PTGraph();
            out.copy(inState);
            applyTransferInter((Stmt) u, out, sig, depth);
            result.union(out);
        }

        methodSummaries.put(sig, result);
        System.out.printf("[PT-INTER] Summarized %s at depth %d%n", method.getName(), depth);
        return result;
    }

    // =========================================================================
    // TRANSFER FUNCTION
    //
    // Single unified method.  depth==0 → intraprocedural rules only.
    //                         depth >0 → also descends into callees.
    //
    // Intraprocedural rules:
    //   x = new Foo()   addStackPtr(x, freshId)
    //   x = y           pts(x) ⊇ pts(y)
    //   x = (T)y        pts(x) ⊇ pts(y)   [cast is transparent]
    //   x = y.f         pts(x) ⊇ ⋃_{o∈pts(y)} heap[o][f]
    //   y.f = x         heap[o][f] ⊇ pts(x)  for o∈pts(y)
    //   x = y[i]        pts(x) ⊇ all objects reachable via any field of pts(y)
    //   x[i] = y        heap[o][*] ⊇ pts(y)  for o∈pts(x)   (conservative)
    // =========================================================================

    private void applyTransferInter(Stmt stmt, PTGraph pt,
                                    String callerSig, int depth) {
        // ── Assignment statements ──────────────────────────────────────────
        if (stmt instanceof AssignStmt) {
            AssignStmt as  = (AssignStmt) stmt;
            Value      lhs = as.getLeftOp();
            Value      rhs = as.getRightOp();

            // x = new Foo()
            if (lhs instanceof Local && rhs instanceof NewExpr) {
                NewExpr ne     = (NewExpr) rhs;
                int     siteId = allocSiteIds.computeIfAbsent(ne, k -> allocCounter++);
                pt.addStackPtr((Local) lhs, siteId);
                allocSiteTypes.put(siteId, ne.getBaseType().getSootClass());
                return;
            }

            // x = y
            if (lhs instanceof Local && rhs instanceof Local) {
                for (int o : pt.stack.getOrDefault((Local) rhs, Collections.emptySet()))
                    pt.addStackPtr((Local) lhs, o);
                return;
            }

            // x = (T) y
            if (lhs instanceof Local && rhs instanceof CastExpr) {
                Value op = ((CastExpr) rhs).getOp();
                if (op instanceof Local)
                    for (int o : pt.stack.getOrDefault((Local) op, Collections.emptySet()))
                        pt.addStackPtr((Local) lhs, o);
                return;
            }

            // x = y.f
            if (lhs instanceof Local && rhs instanceof InstanceFieldRef) {
                InstanceFieldRef fr = (InstanceFieldRef) rhs;
                if (fr.getBase() instanceof Local) {
                    Local     base    = (Local) fr.getBase();
                    SootField fld     = fr.getField();
                    for (int o : pt.stack.getOrDefault(base, Collections.emptySet())) {
                        Map<SootField, Set<Integer>> fMap =
                                pt.heap.getOrDefault(o, Collections.emptyMap());
                        for (int t : fMap.getOrDefault(fld, Collections.emptySet()))
                            pt.addStackPtr((Local) lhs, t);
                    }
                }
                return;
            }

            // y.f = x
            if (lhs instanceof InstanceFieldRef) {
                InstanceFieldRef fr = (InstanceFieldRef) lhs;
                if (fr.getBase() instanceof Local && rhs instanceof Local) {
                    Local     base = (Local) fr.getBase();
                    Local     src  = (Local) rhs;
                    SootField fld  = fr.getField();
                    for (int o : pt.stack.getOrDefault(base, Collections.emptySet()))
                        for (int t : pt.stack.getOrDefault(src, Collections.emptySet()))
                            pt.addHeapPtr(o, fld, t);
                }
                return;
            }

            // x = y[i]  — conservative: union all field targets of pts(y)
            if (lhs instanceof Local && rhs instanceof ArrayRef) {
                ArrayRef ar = (ArrayRef) rhs;
                if (ar.getBase() instanceof Local) {
                    Local base = (Local) ar.getBase();
                    for (int o : pt.stack.getOrDefault(base, Collections.emptySet())) {
                        Map<SootField, Set<Integer>> fMap =
                                pt.heap.getOrDefault(o, Collections.emptyMap());
                        for (Set<Integer> targets : fMap.values())
                            for (int t : targets)
                                pt.addStackPtr((Local) lhs, t);
                    }
                }
                return;
            }

            // x[i] = y  — skip (array stores need a proper array-contents map)
        }

        // ── Identity statements  (this := @this,  p0 := @parameter0) ─────
        if (stmt instanceof IdentityStmt) {
            IdentityStmt id  = (IdentityStmt) stmt;
            Value        lhs = id.getLeftOp();
            Value        rhs = id.getRightOp();
            if (lhs instanceof Local && rhs instanceof ThisRef) {
                Type t = ((ThisRef) rhs).getType();
                if (t instanceof RefType) {
                    SootClass thisClass = ((RefType) t).getSootClass();
                    int siteId = thisRefSiteIds.computeIfAbsent(
                            thisClass.getName(), k -> allocCounter++);
                    pt.addStackPtr((Local) lhs, siteId);
                    allocSiteTypes.put(siteId, thisClass);
                }
            }
            // @parameter refs: left unmapped — caller context unknown
            return;
        }

        // ── Interprocedural extension — only when depth > 0 ───────────────
        if (!stmt.containsInvokeExpr() || depth <= 0) return;

        InvokeExpr       ie      = stmt.getInvokeExpr();
        List<SootMethod> targets = resolveCallTargets(ie, pt);

        for (SootMethod target : targets) {
            // Break direct recursion
            if (target.getSignature().equals(callerSig)) {
                System.out.printf("[PT-INTER] Skipping recursive call to %s%n",
                        target.getName());
                continue;
            }

            PTGraph calleeSummary = buildPTGraphInter(target, depth - 1);

            // Propagate callee heap side-effects into caller
            mergeHeap(calleeSummary, pt);

            // Flow return allocation sites into the lhs of an assignment
            if (stmt instanceof AssignStmt) {
                Value lhs = ((AssignStmt) stmt).getLeftOp();
                if (lhs instanceof Local)
                    flowReturnSites(calleeSummary, (Local) lhs, pt);
            }
        }
    }

    // =========================================================================
    // Helper: resolve the concrete target(s) of a call site using current PT
    // =========================================================================

    private List<SootMethod> resolveCallTargets(InvokeExpr ie, PTGraph pt) {
        List<SootMethod> out = new ArrayList<>();

        if (ie instanceof StaticInvokeExpr || ie instanceof SpecialInvokeExpr) {
            SootMethod t = ie.getMethod();
            if (t.isConcrete()) out.add(t);
            return out;
        }

        if (ie instanceof VirtualInvokeExpr || ie instanceof InterfaceInvokeExpr) {
            Value base = ((InstanceInvokeExpr) ie).getBase();
            if (!(base instanceof Local)) return out;
            for (int siteId : pt.stack.getOrDefault((Local) base, Collections.emptySet())) {
                SootClass concrete = allocSiteTypes.get(siteId);
                if (concrete == null) continue;
                SootMethod resolved = resolveUp(concrete, ie.getMethod().getSubSignature());
                if (resolved != null && resolved.isConcrete()) out.add(resolved);
            }
        }
        return out;
    }

    // merge callee heap edges into caller PT graph

    private void mergeHeap(PTGraph src, PTGraph dst) {
        for (Map.Entry<Integer, Map<SootField, Set<Integer>>> h : src.heap.entrySet()) {
            if (h.getValue() == null) continue;
            for (Map.Entry<SootField, Set<Integer>> e : h.getValue().entrySet()) {
                if (e.getValue() == null) continue;
                dst.heap.computeIfAbsent(h.getKey(), k -> new HashMap<>())
                        .computeIfAbsent(e.getKey(), k -> new HashSet<>())
                        .addAll(e.getValue());
            }
        }
    }

    // =========================================================================
    // Helper: flow allocation sites from callee summary into caller lhs local.
    //
    // the idea is to collect every site in the callee whose type is assignment-
    // compatible with lhs's declared type.  This is sounder than the old
    // name-prefix heuristic ("ret...") and handles all Jimple temporaries.
    // =========================================================================

    private void flowReturnSites(PTGraph calleeSummary, Local lhs, PTGraph pt) {
        Type lhsType = lhs.getType();
        for (Map.Entry<Local, Set<Integer>> e : calleeSummary.stack.entrySet()) {
            if (!e.getKey().getType().equals(lhsType)) continue;
            for (int siteId : e.getValue()) {
                SootClass sc = allocSiteTypes.get(siteId);
                if (sc == null) continue;
                // Check assignment compatibility via Soot's hierarchy
                if (isAssignableTo(sc, lhsType))
                    pt.addStackPtr(lhs, siteId);
            }
        }
    }

    private boolean isAssignableTo(SootClass sc, Type targetType) {
        if (!(targetType instanceof RefType)) return false;
        SootClass target = ((RefType) targetType).getSootClass();
        // Walk superclass chain
        SootClass cur = sc;
        while (cur != null) {
            if (cur.equals(target)) return true;
            // Check interfaces
            for (SootClass iface : cur.getInterfaces())
                if (iface.equals(target)) return true;
            if (!cur.hasSuperclass()) break;
            cur = cur.getSuperclass();
        }
        return false;
    }

    // PT-GRAPH MONO — resolve virtual calls using the PT graph

    private int ptGraphMono(SootMethod method, PTGraph pt) {
        if (!method.isConcrete()) return 0;

        Body       body     = method.retrieveActiveBody();
        List<Unit> snapshot = new ArrayList<>(body.getUnits());
        int        count    = 0;

        for (Unit u : snapshot) {
            Stmt stmt = (Stmt) u;
            if (!stmt.containsInvokeExpr()) continue;

            InvokeExpr ie = stmt.getInvokeExpr();
            if (!(ie instanceof VirtualInvokeExpr) && !(ie instanceof InterfaceInvokeExpr))
                continue;

            Value base = ((InstanceInvokeExpr) ie).getBase();
            if (!(base instanceof Local)) continue;

            Set<Integer> pts = pt.stack.getOrDefault((Local) base, Collections.emptySet());
            if (pts.isEmpty()) continue;

            Set<SootClass> concreteTypes = new HashSet<>();
            boolean unknown = false;
            for (int siteId : pts) {
                SootClass sc = allocSiteTypes.get(siteId);
                if (sc == null) { unknown = true; break; }
                concreteTypes.add(sc);
            }
            if (unknown || concreteTypes.size() != 1) continue;

            SootClass  concrete = concreteTypes.iterator().next();
            SootMethod resolved = resolveUp(concrete, ie.getMethod().getSubSignature());
            if (resolved == null || !resolved.isConcrete()) continue;

            SpecialInvokeExpr mono = Jimple.v().newSpecialInvokeExpr(
                    (Local) base, resolved.makeRef(), ie.getArgs());
            replaceInvokeInStmt(stmt, mono);
            count++;

            System.out.printf("[MONO-PT]  %s @ line %d  pts={%s}  ->  %s%n",
                    method.getName(), stmt.getJavaSourceStartLineNumber(),
                    concreteTypesStr(pts), resolved.getName());
        }
        return count;
    }

    // FORWARD PASS — monomorphize virtual calls to special for single-target CG edges

    private int forwardMono(SootMethod method, CallGraph cg) {
        Body body = method.retrieveActiveBody();
        List<Unit> snapshot = new ArrayList<>(body.getUnits());
        int count = 0;

        for (Unit u : snapshot) {
            Stmt stmt = (Stmt) u;
            if (!stmt.containsInvokeExpr()) continue;

            InvokeExpr ie = stmt.getInvokeExpr();
            if (!(ie instanceof VirtualInvokeExpr) && !(ie instanceof InterfaceInvokeExpr))
                continue;

            Value base = ((InstanceInvokeExpr) ie).getBase();
            if (!(base instanceof Local)) continue;

            List<SootMethod> targets = cgTargets(cg, stmt);
            if (targets.size() != 1) continue;

            SootMethod target = targets.get(0);
            if (!target.isConcrete() || target.isAbstract()) continue;

            SpecialInvokeExpr mono = Jimple.v().newSpecialInvokeExpr(
                    (Local) base, target.makeRef(), ie.getArgs());
            replaceInvokeInStmt(stmt, mono);
            count++;

            System.out.printf("[MONO-FWD]  %s @ line %d  ->  %s%n",
                    method.getName(), stmt.getJavaSourceStartLineNumber(),
                    target.getName());
        }
        return count;
    }

    // Inline pass

    private int inlinePass(SootMethod method, CallGraph cg) {
        Body body = method.retrieveActiveBody();
        int count = 0;
        List<Unit> snapshot = new ArrayList<>(body.getUnits());

        for (Unit u : snapshot) {
            if (!body.getUnits().contains(u)) continue;

            Stmt stmt = (Stmt) u;
            if (!stmt.containsInvokeExpr()) continue;

            InvokeExpr ie = stmt.getInvokeExpr();
            if (!(ie instanceof SpecialInvokeExpr) && !(ie instanceof StaticInvokeExpr))
                continue;

            if (ie instanceof InstanceInvokeExpr) {
                Value base = ((InstanceInvokeExpr) ie).getBase();
                if (!(base instanceof Local)) continue;
            }

            SootMethod target = ie.getMethod();
            if (!target.isConcrete()) continue;
            if (currentlyInlining.contains(target.getSignature())) {
                System.out.printf("[INLINE] Skipped (recursive): %s%n", target.getName());
                continue;
            }

            int sz;
            try { sz = target.retrieveActiveBody().getUnits().size(); }
            catch (Exception e) { continue; }

            if (sz > INLINE_THRESHOLD) {
                System.out.printf("[INLINE] Skipped (size=%d): %s%n", sz, target.getName());
                continue;
            }

            if (tryInline(body, stmt, target)) {
                count++;
                System.out.printf("[INLINE]  %s  ->  %s @ line %d%n",
                        target.getName(), method.getName(),
                        stmt.getJavaSourceStartLineNumber());
            }
        }
        return count;
    }

    // Inlining

    private boolean tryInline(Body callerBody, Stmt callStmt, SootMethod target) {
        currentlyInlining.add(target.getSignature());
        try {
            Body calleeBody;
            try { calleeBody = target.retrieveActiveBody(); }
            catch (Exception e) { return false; }

            InvokeExpr  ie          = callStmt.getInvokeExpr();
            Chain<Unit> callerUnits = callerBody.getUnits();

            Local returnReceiver = null;
            if (callStmt instanceof AssignStmt) {
                Value lhs = ((AssignStmt) callStmt).getLeftOp();
                if (lhs instanceof Local) returnReceiver = (Local) lhs;
            }

            String prefix = "_inl_" + target.getName() + "_";
            Map<Local, Local> localMap = new HashMap<>();
            for (Local orig : calleeBody.getLocals()) {
                Local clone = Jimple.v().newLocal(prefix + orig.getName(), orig.getType());
                callerBody.getLocals().add(clone);
                localMap.put(orig, clone);
            }

            Map<Integer, Value> paramMap = new HashMap<>();
            List<Value> args = ie.getArgs();
            for (int i = 0; i < args.size(); i++)
                paramMap.put(i, args.get(i));

            Value baseValue = null;
            if (ie instanceof InstanceInvokeExpr) {
                Value b = ((InstanceInvokeExpr) ie).getBase();
                if (b instanceof Local) baseValue = b;
                else return false;
            }

            List<Unit> cloned = new ArrayList<>();
            for (Unit u : calleeBody.getUnits()) {
                Stmt s = (Stmt) u;

                if (s instanceof IdentityStmt) {
                    IdentityStmt id  = (IdentityStmt) s;
                    Value        lhs = id.getLeftOp();
                    Value        rhs = id.getRightOp();
                    if (!(lhs instanceof Local)) continue;
                    Local mappedLhs = localMap.get((Local) lhs);
                    if (mappedLhs == null) continue;
                    if (rhs instanceof ThisRef && baseValue != null) {
                        cloned.add(Jimple.v().newAssignStmt(mappedLhs, baseValue));
                    } else if (rhs instanceof ParameterRef) {
                        Value actual = paramMap.get(((ParameterRef) rhs).getIndex());
                        if (actual != null)
                            cloned.add(Jimple.v().newAssignStmt(mappedLhs, actual));
                    }
                    continue;
                }
                if (s instanceof ReturnStmt) {
                    if (returnReceiver != null) {
                        Value retVal = remapValue(((ReturnStmt) s).getOp(), localMap);
                        cloned.add(Jimple.v().newAssignStmt(returnReceiver, retVal));
                    }
                    continue;
                }
                if (s instanceof ReturnVoidStmt) continue;

                Unit c = (Unit) s.clone();
                remapStmt((Stmt) c, localMap);
                cloned.add(c);
            }

            for (Unit cu : cloned) callerUnits.insertBefore(cu, callStmt);
            callerUnits.remove(callStmt);
            return true;

        } finally {
            currentlyInlining.remove(target.getSignature());
        }
    }

    private List<SootMethod> cgTargets(CallGraph cg, Stmt stmt) {
        List<SootMethod> out = new ArrayList<>();
        Iterator<Edge> it = cg.edgesOutOf(stmt);
        while (it.hasNext()) out.add(it.next().tgt());
        return out;
    }

    private void replaceInvokeInStmt(Stmt stmt, InvokeExpr newExpr) {
        if      (stmt instanceof AssignStmt) ((AssignStmt) stmt).setRightOp(newExpr);
        else if (stmt instanceof InvokeStmt) ((InvokeStmt) stmt).setInvokeExpr(newExpr);
    }

    private SootMethod resolveUp(SootClass sc, String subSig) {
        SootClass cur = sc;
        while (cur != null) {
            if (cur.declaresMethod(subSig)) {
                SootMethod m = cur.getMethod(subSig);
                if (m.isConcrete()) return m;
            }
            if (!cur.hasSuperclass()) break;
            cur = cur.getSuperclass();
        }
        return null;
    }

    private void remapStmt(Stmt stmt, Map<Local, Local> localMap) {
        for (ValueBox vb : stmt.getUseAndDefBoxes()) {
            Value remapped = remapValue(vb.getValue(), localMap);
            if (remapped != vb.getValue()) {
                try { vb.setValue(remapped); } catch (RuntimeException ignored) {}
            }
        }
    }

    private Value remapValue(Value v, Map<Local, Local> localMap) {
        if (v instanceof Local) {
            Local mapped = localMap.get(v);
            return (mapped != null) ? mapped : v;
        }
        for (ValueBox vb : v.getUseBoxes()) {
            Value sub      = vb.getValue();
            Value remapped = remapValue(sub, localMap);
            if (remapped != sub) {
                try { vb.setValue(remapped); } catch (RuntimeException ignored) {}
            }
        }
        return v;
    }

    private String concreteTypesStr(Set<Integer> pts) {
        StringBuilder sb = new StringBuilder();
        for (int id : pts) {
            SootClass sc = allocSiteTypes.get(id);
            sb.append(sc != null ? sc.getShortName() : "?").append("@").append(id).append(" ");
        }
        return sb.toString().trim();
    }
}