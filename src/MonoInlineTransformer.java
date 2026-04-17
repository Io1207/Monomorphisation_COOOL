// // // import soot.*;
// // // import soot.jimple.*;
// // // import soot.jimple.toolkits.callgraph.CallGraph;
// // // import soot.jimple.toolkits.callgraph.Edge;
// // // import soot.util.Chain;

// // // import java.util.*;

// // // public class MonoInlineTransformer extends SceneTransformer {

// // //     private static final int INLINE_THRESHOLD = 20;
// // //     private final Set<String> currentlyInlining = new HashSet<>();

// // //     @Override
// // //     protected void internalTransform(String phaseName, Map<String, String> options) {
// // //         CallGraph cg = Scene.v().getCallGraph();

// // //         List<SootMethod> appMethods = new ArrayList<>();
// // //         for (SootClass sc : Scene.v().getApplicationClasses()) {
// // //             appMethods.addAll(sc.getMethods());
// // //         }

// // //         int monoCount   = 0;
// // //         int inlineCount = 0;

// // //         for (SootMethod method : appMethods) {
// // //             if (!method.isConcrete()) continue;

// // //             Body body = method.retrieveActiveBody();

// // //             List<Unit> units = new ArrayList<>(body.getUnits());

// // //             for (Unit u : units) {
// // //                 Stmt stmt = (Stmt) u;
// // //                 if (!stmt.containsInvokeExpr()) continue;

// // //                 InvokeExpr ie = stmt.getInvokeExpr();

// // //                 if (!(ie instanceof VirtualInvokeExpr) && !(ie instanceof InterfaceInvokeExpr))
// // //                     continue;

// // //                 // Gather call-graph targets for this site.
// // //                 List<SootMethod> targets = new ArrayList<>();
// // //                 Iterator<Edge> edges = cg.edgesOutOf(stmt);
// // //                 while (edges.hasNext()) targets.add(edges.next().tgt());

// // //                 if (targets.size() != 1) continue;
// // //                 SootMethod target = targets.get(0);
// // //                 if (!target.isConcrete())              continue;
// // //                 if (target.isAbstract())               continue;

// // //                 SpecialInvokeExpr mono = Jimple.v().newSpecialInvokeExpr(
// // //                         (Local) ((InstanceInvokeExpr) ie).getBase(),
// // //                         target.makeRef(),
// // //                         ie.getArgs());

// // //                 // in place patching for this call site 
// // //                 if (stmt instanceof AssignStmt) {
// // //                     ((AssignStmt) stmt).setRightOp(mono);
// // //                 } else if (stmt instanceof InvokeStmt) {
// // //                     ((InvokeStmt) stmt).setInvokeExpr(mono);
// // //                 } else {
// // //                     continue; // took Manas sir's advice and just ignored this case
// // //                 }
// // //                 monoCount++;

// // //                 System.out.println("[MONO]   " + method.getName()
// // //                         + " @ line " + stmt.getJavaSourceStartLineNumber()
// // //                         + "  →  " + target.getName());

// // //                 int calleeSize = target.retrieveActiveBody().getUnits().size();
// // //                 if (calleeSize > INLINE_THRESHOLD) {
// // //                     System.out.println("[INLINE] Skipped (size=" + calleeSize + "): " + target.getName());
// // //                     continue;
// // //                 }
// // //                 if (currentlyInlining.contains(target.getSignature())) {
// // //                     System.out.println("[INLINE] Skipped (recursive): " + target.getName());
// // //                     continue;
// // //                 }

// // //                 boolean inlined = tryInline(body, stmt, target);
// // //                 if (inlined) {
// // //                     inlineCount++;
// // //                     System.out.println("[INLINE] Inlined " + target.getName()
// // //                             + " into " + method.getName()
// // //                             + " @ line " + stmt.getJavaSourceStartLineNumber());
// // //                 }
// // //             }
// // //         }

// // //         System.out.println("\n=== Monomorphization: " + monoCount + " sites ===");
// // //         System.out.println("=== Inlining:         " + inlineCount + " sites ===\n");
// // //     }

// // //     private boolean tryInline(Body callerBody, Stmt callStmt, SootMethod target) {
// // //         currentlyInlining.add(target.getSignature());
// // //         try {
// // //             Body calleeBody;
// // //             try {
// // //                 calleeBody = target.retrieveActiveBody();
// // //             } catch (Exception e) {
// // //                 return false;
// // //             }

// // //             InvokeExpr ie = callStmt.getInvokeExpr();
// // //             Chain<Unit> callerUnits = callerBody.getUnits();

// // //             // Figure out if there is a return value we need to capture.
// // //             Local returnReceiver = null;
// // //             if (callStmt instanceof AssignStmt) {
// // //                 Value lhs = ((AssignStmt) callStmt).getLeftOp();
// // //                 if (lhs instanceof Local) returnReceiver = (Local) lhs;
// // //             }

// // //             // clone callee locals into caller
// // //             String prefix = "_inl_" + target.getName() + "_";
// // //             Map<Local, Local> localMap = new HashMap<>();

// // //             for (Local orig : calleeBody.getLocals()) {
// // //                 Local clone = Jimple.v().newLocal(prefix + orig.getName(), orig.getType());
// // //                 callerBody.getLocals().add(clone);
// // //                 localMap.put(orig, clone);
// // //             }

// // //             Map<Integer, Value> paramMap = new HashMap<>();
// // //             List<Value> args = ie.getArgs();
// // //             for (int i = 0; i < args.size(); i++) paramMap.put(i, args.get(i));

// // //             Value baseValue = (ie instanceof InstanceInvokeExpr)
// // //                     ? ((InstanceInvokeExpr) ie).getBase() : null;

// // //             // Cloning and remapping callee units
// // //             List<Unit> clonedUnits = new ArrayList<>();

// // //             for (Unit u : calleeBody.getUnits()) {
// // //                 Stmt s = (Stmt) u;

// // //                 if (s instanceof IdentityStmt) {
// // //                     IdentityStmt id = (IdentityStmt) s;
// // //                     Value lhs = id.getLeftOp();
// // //                     Value rhs = id.getRightOp();

// // //                     if (!(lhs instanceof Local)) continue; 

// // //                     Local mappedLhs = localMap.get((Local) lhs);
// // //                     if (mappedLhs == null) continue;

// // //                     if (rhs instanceof ThisRef && baseValue != null) {
// // //                         clonedUnits.add(Jimple.v().newAssignStmt(mappedLhs, baseValue));
// // //                     } else if (rhs instanceof ParameterRef) {
// // //                         int idx = ((ParameterRef) rhs).getIndex();
// // //                         Value actual = paramMap.get(idx);
// // //                         if (actual != null) {
// // //                             clonedUnits.add(Jimple.v().newAssignStmt(mappedLhs, actual));
// // //                         }
// // //                     }
// // //                     continue;
// // //                 }

// // //                 if (s instanceof ReturnStmt) {
// // //                     if (returnReceiver != null) {
// // //                         Value retVal = remapValue(((ReturnStmt) s).getOp(), localMap);
// // //                         clonedUnits.add(Jimple.v().newAssignStmt(returnReceiver, retVal));
// // //                     }
// // //                     continue;
// // //                 }
// // //                 if (s instanceof ReturnVoidStmt) {
// // //                     continue;
// // //                 }

// // //                 Unit cloned = (Unit) s.clone();
// // //                 remapStmt((Stmt) cloned, localMap);
// // //                 clonedUnits.add(cloned);
// // //             }
// // //             for (Unit cu : clonedUnits) { // inserting cloned things before call site
// // //                 callerUnits.insertBefore(cu, callStmt);
// // //             }
// // //             callerUnits.remove(callStmt);

// // //             return true;

// // //         } finally {
// // //             currentlyInlining.remove(target.getSignature());
// // //         }
// // //     }

// // //     private void remapStmt(Stmt stmt, Map<Local, Local> localMap) {
// // //         /*
// // //          THIS IS AN ENTIRELY AI-ed FUNCTION: i wrote something and couldn't debug it, i asked 
// // //          gpt and pasted the response and it works
// // //          */
// // //         for (ValueBox vb : stmt.getUseAndDefBoxes()) {
// // //             Value v = vb.getValue();
// // //             Value remapped = remapValue(v, localMap);
// // //             if (remapped != v) {
// // //                 try { vb.setValue(remapped); } catch (RuntimeException ignored) {}
// // //             }
// // //         }
// // //     }

// // //     private Value remapValue(Value v, Map<Local, Local> localMap) {
// // //         /*
// // //          THIS IS AN ENTIRELY AI-ed FUNCTION: i wrote something and couldn't debug it, i asked 
// // //          gpt and pasted the response and it works
// // //          */
// // //         if (v instanceof Local) {
// // //             Local mapped = localMap.get(v);
// // //             return mapped != null ? mapped : v;
// // //         }
// // //         // For compound expressions (InstanceFieldRef, InvokeExpr, etc.)
// // //         // Soot's clone() already deep-copied them; we just remap sub-Values.
// // //         for (ValueBox vb : v.getUseBoxes()) {
// // //             Value sub = vb.getValue();
// // //             Value remapped = remapValue(sub, localMap);
// // //             if (remapped != sub) {
// // //                 try { vb.setValue(remapped); } catch (RuntimeException ignored) {}
// // //             }
// // //         }
// // //         return v;
// // //     }
// // // }

// hi


// // import soot.*;
// // import soot.jimple.*;
// // import soot.jimple.toolkits.callgraph.CallGraph;
// // import soot.jimple.toolkits.callgraph.Edge;
// // import soot.toolkits.graph.BriefUnitGraph;
// // import soot.toolkits.graph.UnitGraph;
// // import soot.util.Chain;

// // import java.util.*;

// // /**
// //  * MonoInlineTransformer
// //  * ─────────────────────
// //  * Per method, repeats until convergence:
// //  *
// //  *   FORWARD PASS
// //  *     Walk CFG top→bottom.  For every virtual/interface call site where the
// //  *     call graph gives exactly one concrete target, replace the virtualinvoke
// //  *     with a specialinvoke (monomorphization).  Then inline any now-direct
// //  *     callee whose body is small enough.
// //  *
// //  *   BACKWARD PASS
// //  *     Walk CFG bottom→top.  Propagate concrete type facts backwards:
// //  *       • x = new Foo()        → x is concretely Foo everywhere above
// //  *       • x = y  (copy)        → if x's type is known downstream, tag y too
// //  *       • x = (Foo) y          → x is Foo above the cast
// //  *       • specialinvoke r.<M>  → r must be M's declaring class above the call
// //  *     Any virtual call site whose receiver local now has a proven concrete
// //  *     type is monomorphized again (BACKWARD MONO sub-step).
// //  *
// //  *   CONVERGENCE
// //  *     If neither pass produced any new monomorphizations or inlines this
// //  *     round, the method has fully converged and we stop.  The cap of
// //  *     MAX_PASSES guards against degenerate cases (e.g. recursive inlining
// //  *     that keeps growing the body).
// //  *
// //  * WHY THIS HANDLES RECURSION
// //  *     Recursive call sites appear in the call graph as self-edges.  On the
// //  *     first forward pass they are skipped (the recursion guard
// //  *     currentlyInlining prevents infinite unrolling).  However, the backward
// //  *     pass can still prove the receiver type at the recursive site from the
// //  *     allocation site that dominates the call, allowing monomorphization of
// //  *     the self-call even though inlining is suppressed.
// //  */
// // public class MonoInlineTransformer extends SceneTransformer {

// //     private static final int INLINE_THRESHOLD = 20;
// //     private static final int MAX_PASSES       = 8;

// //     private final Set<String> currentlyInlining = new HashSet<>();

// //     // =========================================================================
// //     // Entry point
// //     // =========================================================================

// //     @Override
// //     protected void internalTransform(String phaseName, Map<String, String> options) {
// //         CallGraph cg = Scene.v().getCallGraph();

// //         List<SootMethod> appMethods = new ArrayList<>();
// //         for (SootClass sc : Scene.v().getApplicationClasses())
// //             appMethods.addAll(sc.getMethods());

// //         int totalMono = 0, totalInline = 0;

// //         for (SootMethod method : appMethods) {
// //             if (!method.isConcrete()) continue;

// //             for (int pass = 1; pass <= MAX_PASSES; pass++) {
// //                 int mono = 0, inline = 0;

// //                 // ── 1. FORWARD: monomorphize then inline ───────────────────
// //                 mono   += forwardMono(method, cg);
// //                 inline += inlinePass(method, cg);

// //                 // ── 2. BACKWARD: propagate types, monomorphize residue ─────
// //                 Map<Local, SootClass> backTypes = backwardTypePass(method);
// //                 mono += backwardMono(method, cg, backTypes);

// //                 totalMono   += mono;
// //                 totalInline += inline;

// //                 System.out.printf("[PASS %d][%s]  fwd-mono+inline=%d+%d  bwd-mono=%d%n",
// //                         pass, method.getName(), mono - (pass > 1 ? 0 : 0), inline,
// //                         mono - forwardMonoCount);   // approximate — see note below

// //                 // Converged: nothing changed this round.
// //                 if (mono == 0 && inline == 0) break;
// //             }
// //         }

// //         System.out.printf("%n=== Total monomorphized : %d ===%n", totalMono);
// //         System.out.printf("=== Total inlined       : %d ===%n%n", totalInline);
// //     }

// //     // kept so the progress print inside the loop compiles; see printout below
// //     private int forwardMonoCount = 0;

// //     // =========================================================================
// //     // FORWARD PASS — monomorphize virtual→special for single-target CG edges
// //     // =========================================================================

// //     private int forwardMono(SootMethod method, CallGraph cg) {
// //         Body body = method.retrieveActiveBody();
// //         List<Unit> snapshot = new ArrayList<>(body.getUnits());
// //         int count = 0;

// //         for (Unit u : snapshot) {
// //             Stmt stmt = (Stmt) u;
// //             if (!stmt.containsInvokeExpr()) continue;

// //             InvokeExpr ie = stmt.getInvokeExpr();
// //             if (!(ie instanceof VirtualInvokeExpr) && !(ie instanceof InterfaceInvokeExpr))
// //                 continue;

// //             List<SootMethod> targets = cgTargets(cg, stmt);
// //             if (targets.size() != 1) continue;

// //             SootMethod target = targets.get(0);
// //             if (!target.isConcrete() || target.isAbstract()) continue;

// //             SpecialInvokeExpr mono = Jimple.v().newSpecialInvokeExpr(
// //                     (Local) ((InstanceInvokeExpr) ie).getBase(),
// //                     target.makeRef(),
// //                     ie.getArgs());

// //             replaceInvokeInStmt(stmt, mono);
// //             count++;

// //             System.out.printf("[MONO-FWD]  %s @ line %d  →  %s%n",
// //                     method.getName(), stmt.getJavaSourceStartLineNumber(), target.getName());
// //         }

// //         forwardMonoCount = count;
// //         return count;
// //     }

// //     // =========================================================================
// //     // INLINE PASS — inline small direct callees produced by forwardMono
// //     // =========================================================================

// //     private int inlinePass(SootMethod method, CallGraph cg) {
// //         Body body = method.retrieveActiveBody();
// //         int count = 0;

// //         // Take a snapshot; the body grows as we inline.
// //         List<Unit> snapshot = new ArrayList<>(body.getUnits());

// //         for (Unit u : snapshot) {
// //             if (!body.getUnits().contains(u)) continue;   // already removed

// //             Stmt stmt = (Stmt) u;
// //             if (!stmt.containsInvokeExpr()) continue;

// //             InvokeExpr ie = stmt.getInvokeExpr();
// //             // Only inline direct calls — virtual dispatch still needs the vtable.
// //             if (!(ie instanceof SpecialInvokeExpr) && !(ie instanceof StaticInvokeExpr))
// //                 continue;

// //             SootMethod target = ie.getMethod();
// //             if (!target.isConcrete()) continue;
// //             if (currentlyInlining.contains(target.getSignature())) {
// //                 System.out.printf("[INLINE] Skipped (recursive): %s%n", target.getName());
// //                 continue;
// //             }

// //             int sz;
// //             try { sz = target.retrieveActiveBody().getUnits().size(); }
// //             catch (Exception e) { continue; }

// //             if (sz > INLINE_THRESHOLD) {
// //                 System.out.printf("[INLINE] Skipped (size=%d): %s%n", sz, target.getName());
// //                 continue;
// //             }

// //             if (tryInline(body, stmt, target)) {
// //                 count++;
// //                 System.out.printf("[INLINE]  %s  →  %s @ line %d%n",
// //                         target.getName(), method.getName(),
// //                         stmt.getJavaSourceStartLineNumber());
// //             }
// //         }
// //         return count;
// //     }

// //     // =========================================================================
// //     // BACKWARD TYPE PASS
// //     //
// //     // Standard backward dataflow.  The lattice element at each program point
// //     // is  Map<Local, SootClass>  — a partial map from locals to their proven
// //     // concrete (single) class.  Top = empty map (nothing known).
// //     //
// //     // Meet operator  =  intersection: only keep facts that ALL successors agree
// //     //                   on.  This is conservative: if two paths give a local
// //     //                   different types we drop the fact rather than guess.
// //     //
// //     // Transfer function (backward):
// //     //   x = new Foo      →  add x→Foo  (allocation is the ground truth)
// //     //   x = y            →  if x has a known type T downstream, also tag y→T
// //     //                        if y has type T, tag x→T going upward
// //     //   x = (Foo) y      →  add x→Foo
// //     //   specialinvoke r  →  r's type is at least the declaring class of the method
// //     //
// //     // Fixed-point: re-process a node whenever its OUT set changes.
// //     // =========================================================================

// //     private Map<Local, SootClass> backwardTypePass(SootMethod method) {
// //         if (!method.isConcrete()) return Collections.emptyMap();

// //         Body      body = method.retrieveActiveBody();
// //         UnitGraph cfg  = new BriefUnitGraph(body);
// //         List<Unit> units = new ArrayList<>(body.getUnits());

// //         // OUT[u] — what we know after u (flowing backward = what successors told us)
// //         Map<Unit, Map<Local, SootClass>> out = new HashMap<>();
// //         for (Unit u : units) out.put(u, new HashMap<>());

// //         // Worklist seeded in reverse order so exits come first — faster convergence.
// //         LinkedHashSet<Unit> worklist = new LinkedHashSet<>();
// //         for (int i = units.size() - 1; i >= 0; i--) worklist.add(units.get(i));

// //         while (!worklist.isEmpty()) {
// //             Unit u = worklist.iterator().next();
// //             worklist.remove(u);

// //             // IN[u] (backward) = meet of OUT of all successors
// //             Map<Local, SootClass> succMeet = meetSuccessors(u, cfg, out);

// //             // Apply backward transfer: derive OUT[u] from IN[u] = succMeet
// //             Map<Local, SootClass> newOut = new HashMap<>(succMeet);
// //             backwardTransfer((Stmt) u, newOut);

// //             if (!newOut.equals(out.get(u))) {
// //                 out.put(u, newOut);
// //                 // Predecessors' IN may change — re-add them.
// //                 worklist.addAll(cfg.getPredsOf(u));
// //             }
// //         }

// //         // Union all per-statement maps so callers can look up any local at any point.
// //         Map<Local, SootClass> result = new HashMap<>();
// //         for (Map<Local, SootClass> m : out.values()) result.putAll(m);
// //         return result;
// //     }

// //     /** Intersection of all successor OUT maps. */
// //     private Map<Local, SootClass> meetSuccessors(Unit u, UnitGraph cfg,
// //                                                   Map<Unit, Map<Local, SootClass>> out) {
// //         List<Unit> succs = cfg.getSuccsOf(u);
// //         if (succs.isEmpty()) return new HashMap<>();

// //         Map<Local, SootClass> result = new HashMap<>(out.getOrDefault(succs.get(0), new HashMap<>()));
// //         for (int i = 1; i < succs.size(); i++) {
// //             Map<Local, SootClass> sMap = out.getOrDefault(succs.get(i), new HashMap<>());
// //             // Retain only keys present in both with the same value.
// //             result.entrySet().removeIf(e -> !e.getValue().equals(sMap.get(e.getKey())));
// //         }
// //         return result;
// //     }

// //     /**
// //      * Backward transfer: given what we know AFTER this statement (in {@code types},
// //      * representing the meet of successors), update {@code types} with what we can
// //      * additionally deduce BEFORE this statement.
// //      */
// //     private void backwardTransfer(Stmt stmt, Map<Local, SootClass> types) {
// //         if (stmt instanceof AssignStmt) {
// //             AssignStmt as  = (AssignStmt) stmt;
// //             Value      lhs = as.getLeftOp();
// //             Value      rhs = as.getRightOp();

// //             // x = new Foo()  ─→  x is concretely Foo
// //             if (lhs instanceof Local && rhs instanceof NewExpr) {
// //                 SootClass alloc = ((NewExpr) rhs).getBaseType().getSootClass();
// //                 types.put((Local) lhs, alloc);
// //             }

// //             // x = y  (plain copy)  ─→  propagate type in both directions
// //             if (lhs instanceof Local && rhs instanceof Local) {
// //                 SootClass lhsT = types.get(lhs);
// //                 SootClass rhsT = types.get(rhs);
// //                 if (lhsT != null) types.putIfAbsent((Local) rhs, lhsT);
// //                 if (rhsT != null) types.putIfAbsent((Local) lhs, rhsT);
// //             }

// //             // x = (Foo) y  ─→  x is Foo above the cast
// //             if (lhs instanceof Local && rhs instanceof CastExpr) {
// //                 Type castType = ((CastExpr) rhs).getCastType();
// //                 if (castType instanceof RefType)
// //                     types.put((Local) lhs, ((RefType) castType).getSootClass());
// //             }
// //         }

// //         // specialinvoke r.<Foo: m()>  ─→  r is (at least) Foo above this call
// //         if (stmt.containsInvokeExpr()) {
// //             InvokeExpr ie = stmt.getInvokeExpr();
// //             if (ie instanceof SpecialInvokeExpr) {
// //                 Value base = ((SpecialInvokeExpr) ie).getBase();
// //                 if (base instanceof Local)
// //                     types.putIfAbsent((Local) base,
// //                             ie.getMethod().getDeclaringClass());
// //             }
// //         }
// //     }

// //     // =========================================================================
// //     // BACKWARD MONO — resolve remaining virtuals using backward type map
// //     // =========================================================================

// //     private int backwardMono(SootMethod method, CallGraph cg,
// //                               Map<Local, SootClass> backTypes) {
// //         if (!method.isConcrete()) return 0;

// //         Body       body     = method.retrieveActiveBody();
// //         List<Unit> snapshot = new ArrayList<>(body.getUnits());
// //         int        count    = 0;

// //         for (Unit u : snapshot) {
// //             Stmt stmt = (Stmt) u;
// //             if (!stmt.containsInvokeExpr()) continue;

// //             InvokeExpr ie = stmt.getInvokeExpr();
// //             if (!(ie instanceof VirtualInvokeExpr) && !(ie instanceof InterfaceInvokeExpr))
// //                 continue;

// //             Value base = ((InstanceInvokeExpr) ie).getBase();
// //             if (!(base instanceof Local)) continue;

// //             SootClass concrete = backTypes.get((Local) base);
// //             if (concrete == null) continue;

// //             SootMethod resolved = resolveUp(concrete, ie.getMethod().getSubSignature());
// //             if (resolved == null || !resolved.isConcrete()) continue;

// //             SpecialInvokeExpr mono = Jimple.v().newSpecialInvokeExpr(
// //                     (Local) base, resolved.makeRef(), ie.getArgs());

// //             replaceInvokeInStmt(stmt, mono);
// //             count++;

// //             System.out.printf("[MONO-BWD]  %s @ line %d  receiver=%s  →  %s%n",
// //                     method.getName(), stmt.getJavaSourceStartLineNumber(),
// //                     concrete.getShortName(), resolved.getName());
// //         }
// //         return count;
// //     }

// //     // =========================================================================
// //     // Inlining implementation (same logic, extracted cleanly)
// //     // =========================================================================

// //     private boolean tryInline(Body callerBody, Stmt callStmt, SootMethod target) {
// //         currentlyInlining.add(target.getSignature());
// //         try {
// //             Body calleeBody;
// //             try { calleeBody = target.retrieveActiveBody(); }
// //             catch (Exception e) { return false; }

// //             InvokeExpr  ie          = callStmt.getInvokeExpr();
// //             Chain<Unit> callerUnits = callerBody.getUnits();

// //             // Capture the LHS of the call if this is an assignment.
// //             Local returnReceiver = null;
// //             if (callStmt instanceof AssignStmt) {
// //                 Value lhs = ((AssignStmt) callStmt).getLeftOp();
// //                 if (lhs instanceof Local) returnReceiver = (Local) lhs;
// //             }

// //             // 1. Clone callee locals into caller with a unique prefix.
// //             String prefix = "_inl_" + target.getName() + "_";
// //             Map<Local, Local> localMap = new HashMap<>();
// //             for (Local orig : calleeBody.getLocals()) {
// //                 Local clone = Jimple.v().newLocal(prefix + orig.getName(), orig.getType());
// //                 callerBody.getLocals().add(clone);
// //                 localMap.put(orig, clone);
// //             }

// //             // 2. Prepare actual-argument and this-pointer mappings.
// //             Map<Integer, Value> paramMap = new HashMap<>();
// //             List<Value> args = ie.getArgs();
// //             for (int i = 0; i < args.size(); i++) paramMap.put(i, args.get(i));
// //             Value baseValue = (ie instanceof InstanceInvokeExpr)
// //                     ? ((InstanceInvokeExpr) ie).getBase() : null;

// //             // 3. Clone and patch each callee statement.
// //             List<Unit> cloned = new ArrayList<>();
// //             for (Unit u : calleeBody.getUnits()) {
// //                 Stmt s = (Stmt) u;

// //                 if (s instanceof IdentityStmt) {
// //                     // Map this/param bindings to actual arguments.
// //                     IdentityStmt id  = (IdentityStmt) s;
// //                     Value        lhs = id.getLeftOp();
// //                     Value        rhs = id.getRightOp();
// //                     if (!(lhs instanceof Local)) continue;
// //                     Local mappedLhs = localMap.get((Local) lhs);
// //                     if (mappedLhs == null) continue;
// //                     if (rhs instanceof ThisRef && baseValue != null)
// //                         cloned.add(Jimple.v().newAssignStmt(mappedLhs, baseValue));
// //                     else if (rhs instanceof ParameterRef) {
// //                         Value actual = paramMap.get(((ParameterRef) rhs).getIndex());
// //                         if (actual != null)
// //                             cloned.add(Jimple.v().newAssignStmt(mappedLhs, actual));
// //                     }
// //                     continue;
// //                 }
// //                 if (s instanceof ReturnStmt) {
// //                     if (returnReceiver != null) {
// //                         Value retVal = remapValue(((ReturnStmt) s).getOp(), localMap);
// //                         cloned.add(Jimple.v().newAssignStmt(returnReceiver, retVal));
// //                     }
// //                     continue;
// //                 }
// //                 if (s instanceof ReturnVoidStmt) continue;

// //                 Unit c = (Unit) s.clone();
// //                 remapStmt((Stmt) c, localMap);
// //                 cloned.add(c);
// //             }

// //             // 4. Insert cloned units and remove the original call.
// //             for (Unit cu : cloned) callerUnits.insertBefore(cu, callStmt);
// //             callerUnits.remove(callStmt);
// //             return true;

// //         } finally {
// //             currentlyInlining.remove(target.getSignature());
// //         }
// //     }

// //     // =========================================================================
// //     // Shared helpers
// //     // =========================================================================

// //     private List<SootMethod> cgTargets(CallGraph cg, Stmt stmt) {
// //         List<SootMethod> out = new ArrayList<>();
// //         Iterator<Edge> it = cg.edgesOutOf(stmt);
// //         while (it.hasNext()) out.add(it.next().tgt());
// //         return out;
// //     }

// //     private void replaceInvokeInStmt(Stmt stmt, InvokeExpr newExpr) {
// //         if      (stmt instanceof AssignStmt)  ((AssignStmt)  stmt).setRightOp(newExpr);
// //         else if (stmt instanceof InvokeStmt)  ((InvokeStmt)  stmt).setInvokeExpr(newExpr);
// //     }

// //     /** Walk up the class hierarchy to find a concrete implementation of subSig. */
// //     private SootMethod resolveUp(SootClass sc, String subSig) {
// //         SootClass cur = sc;
// //         while (cur != null) {
// //             if (cur.declaresMethod(subSig)) {
// //                 SootMethod m = cur.getMethod(subSig);
// //                 if (m.isConcrete()) return m;
// //             }
// //             if (!cur.hasSuperclass()) break;
// //             cur = cur.getSuperclass();
// //         }
// //         return null;
// //     }

// //     private void remapStmt(Stmt stmt, Map<Local, Local> localMap) {
// //         for (ValueBox vb : stmt.getUseAndDefBoxes()) {
// //             Value remapped = remapValue(vb.getValue(), localMap);
// //             if (remapped != vb.getValue()) {
// //                 try { vb.setValue(remapped); } catch (RuntimeException ignored) {}
// //             }
// //         }
// //     }

// //     private Value remapValue(Value v, Map<Local, Local> localMap) {
// //         if (v instanceof Local) {
// //             Local mapped = localMap.get(v);
// //             return (mapped != null) ? mapped : v;
// //         }
// //         for (ValueBox vb : v.getUseBoxes()) {
// //             Value sub      = vb.getValue();
// //             Value remapped = remapValue(sub, localMap);
// //             if (remapped != sub) {
// //                 try { vb.setValue(remapped); } catch (RuntimeException ignored) {}
// //             }
// //         }
// //         return v;
// //     }
// // }

// ayo

import soot.*;
import soot.jimple.*;
import soot.jimple.toolkits.callgraph.CallGraph;
import soot.jimple.toolkits.callgraph.Edge;
import soot.toolkits.graph.BriefUnitGraph;
import soot.toolkits.graph.UnitGraph;
import soot.util.Chain;

import java.util.*;

public class MonoInlineTransformer extends SceneTransformer {

    private static final int INLINE_THRESHOLD = 20;
    private static final int MAX_PASSES       = 8;

    private final Set<String> currentlyInlining = new HashSet<>();

    // =========================================================================
    // Entry point
    // =========================================================================

    @Override
    protected void internalTransform(String phaseName, Map<String, String> options) {
        CallGraph cg = Scene.v().getCallGraph();

        List<SootMethod> appMethods = new ArrayList<>();
        for (SootClass sc : Scene.v().getApplicationClasses())
            appMethods.addAll(sc.getMethods());

        int totalMono = 0, totalInline = 0;

        for (SootMethod method : appMethods) {
            if (!method.isConcrete()) continue;

            for (int pass = 1; pass <= MAX_PASSES; pass++) {

                // FORWARD: monomorphize then inline
                int fwdMono   = forwardMono(method, cg);
                int inlined   = inlinePass(method, cg);

                // BACKWARD: propagate concrete types, monomorphize residue
                Map<Local, SootClass> backTypes = backwardTypePass(method);
                int bwdMono = backwardMono(method, cg, backTypes);

                int monoThisPass = fwdMono + bwdMono;

                totalMono   += monoThisPass;
                totalInline += inlined;

                System.out.printf("[PASS %d][%s]  fwd-mono=%d  inline=%d  bwd-mono=%d%n",
                        pass, method.getName(), fwdMono, inlined, bwdMono);

                // Converged: nothing changed this round.
                if (monoThisPass == 0 && inlined == 0) break;
            }
        }

        System.out.printf("%n=== Total monomorphized : %d ===%n", totalMono);
        System.out.printf("=== Total inlined       : %d ===%n%n", totalInline);
    }

    // =========================================================================
    // FORWARD PASS — monomorphize virtual to special for single-target CG edges
    // =========================================================================

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

            List<SootMethod> targets = cgTargets(cg, stmt);
            if (targets.size() != 1) continue;

            SootMethod target = targets.get(0);
            if (!target.isConcrete() || target.isAbstract()) continue;

            SpecialInvokeExpr mono = Jimple.v().newSpecialInvokeExpr(
                    (Local) ((InstanceInvokeExpr) ie).getBase(),
                    target.makeRef(),
                    ie.getArgs());

            replaceInvokeInStmt(stmt, mono);
            count++;

            System.out.printf("[MONO-FWD]  %s @ line %d  ->  %s%n",
                    method.getName(), stmt.getJavaSourceStartLineNumber(), target.getName());
        }

        return count;
    }

    // =========================================================================
    // INLINE PASS — inline small direct callees produced by forwardMono
    // =========================================================================

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

    // =========================================================================
    // BACKWARD TYPE PASS — fixed-point backward dataflow over the CFG
    // =========================================================================

    private Map<Local, SootClass> backwardTypePass(SootMethod method) {
        if (!method.isConcrete()) return Collections.emptyMap();

        Body      body  = method.retrieveActiveBody();
        UnitGraph cfg   = new BriefUnitGraph(body);
        List<Unit> units = new ArrayList<>(body.getUnits());

        // OUT[u]: what we know after u (flowing backwards = info from successors)
        Map<Unit, Map<Local, SootClass>> out = new HashMap<>();
        for (Unit u : units) out.put(u, new HashMap<>());

        // Seed worklist in reverse order so exits are processed first
        LinkedHashSet<Unit> worklist = new LinkedHashSet<>();
        for (int i = units.size() - 1; i >= 0; i--) worklist.add(units.get(i));

        while (!worklist.isEmpty()) {
            Unit u = worklist.iterator().next();
            worklist.remove(u);

            // IN[u] backward = meet (intersection) of all successors' OUT
            Map<Local, SootClass> succMeet = meetSuccessors(u, cfg, out);

            // Transfer: derive what we know BEFORE u given what we know AFTER u
            Map<Local, SootClass> newOut = new HashMap<>(succMeet);
            backwardTransfer((Stmt) u, newOut);

            if (!newOut.equals(out.get(u))) {
                out.put(u, newOut);
                // Predecessors' IN may now change — re-add them
                worklist.addAll(cfg.getPredsOf(u));
            }
        }

        // Union all per-statement maps for easy lookup by caller
        Map<Local, SootClass> result = new HashMap<>();
        for (Map<Local, SootClass> m : out.values()) result.putAll(m);
        return result;
    }

    /** Meet = intersection: only keep type facts ALL successors agree on. */
    private Map<Local, SootClass> meetSuccessors(Unit u, UnitGraph cfg,
                                                  Map<Unit, Map<Local, SootClass>> out) {
        List<Unit> succs = cfg.getSuccsOf(u);
        if (succs.isEmpty()) return new HashMap<>();

        Map<Local, SootClass> result = new HashMap<>(out.getOrDefault(succs.get(0), new HashMap<>()));
        for (int i = 1; i < succs.size(); i++) {
            Map<Local, SootClass> sMap = out.getOrDefault(succs.get(i), new HashMap<>());
            result.entrySet().removeIf(e -> !e.getValue().equals(sMap.get(e.getKey())));
        }
        return result;
    }

    /**
     * Backward transfer function.
     * Given what we know AFTER stmt (succMeet, already copied into types),
     * add what we can deduce BEFORE stmt.
     */
    private void backwardTransfer(Stmt stmt, Map<Local, SootClass> types) {
        if (stmt instanceof AssignStmt) {
            AssignStmt as  = (AssignStmt) stmt;
            Value      lhs = as.getLeftOp();
            Value      rhs = as.getRightOp();

            // x = new Foo()  ->  x is concretely Foo
            if (lhs instanceof Local && rhs instanceof NewExpr) {
                SootClass alloc = ((NewExpr) rhs).getBaseType().getSootClass();
                types.put((Local) lhs, alloc);
            }

            // x = y  ->  propagate type in both directions
            if (lhs instanceof Local && rhs instanceof Local) {
                SootClass lhsT = types.get((Local) lhs);
                SootClass rhsT = types.get((Local) rhs);
                if (lhsT != null) types.putIfAbsent((Local) rhs, lhsT);
                if (rhsT != null) types.putIfAbsent((Local) lhs, rhsT);
            }

            // x = (Foo) y  ->  x is Foo
            if (lhs instanceof Local && rhs instanceof CastExpr) {
                Type castType = ((CastExpr) rhs).getCastType();
                if (castType instanceof RefType)
                    types.put((Local) lhs, ((RefType) castType).getSootClass());
            }
        }

        // specialinvoke r.<Foo: m()>  ->  r is (at least) Foo above this call
        if (stmt.containsInvokeExpr()) {
            InvokeExpr ie = stmt.getInvokeExpr();
            if (ie instanceof SpecialInvokeExpr) {
                // Value base = ((SpecialInvokeExpr) ie).getBase();
                Value base = ((InstanceInvokeExpr) ie).getBase();
                if (!(base instanceof Local)){}
                else types.putIfAbsent((Local) base, ie.getMethod().getDeclaringClass());
            }
        }
    }

    // =========================================================================
    // BACKWARD MONO — resolve remaining virtuals using backward type map
    // =========================================================================

    private int backwardMono(SootMethod method, CallGraph cg,
                              Map<Local, SootClass> backTypes) {
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

            SootClass concrete = backTypes.get((Local) base);
            if (concrete == null) continue;

            SootMethod resolved = resolveUp(concrete, ie.getMethod().getSubSignature());
            if (resolved == null || !resolved.isConcrete()) continue;

            SpecialInvokeExpr mono = Jimple.v().newSpecialInvokeExpr(
                    (Local) base, resolved.makeRef(), ie.getArgs());

            replaceInvokeInStmt(stmt, mono);
            count++;

            System.out.printf("[MONO-BWD]  %s @ line %d  receiver=%s  ->  %s%n",
                    method.getName(), stmt.getJavaSourceStartLineNumber(),
                    concrete.getShortName(), resolved.getName());
        }
        return count;
    }

    // =========================================================================
    // INLINING
    // =========================================================================

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

            // Clone callee locals into caller with a unique prefix
            String prefix = "_inl_" + target.getName() + "_";
            Map<Local, Local> localMap = new HashMap<>();
            for (Local orig : calleeBody.getLocals()) {
                Local clone = Jimple.v().newLocal(prefix + orig.getName(), orig.getType());
                callerBody.getLocals().add(clone);
                localMap.put(orig, clone);
            }

            // Map parameters and this-pointer to actual arguments
            Map<Integer, Value> paramMap = new HashMap<>();
            List<Value> args = ie.getArgs();
            for (int i = 0; i < args.size(); i++) paramMap.put(i, args.get(i));
            Value baseValue = (ie instanceof InstanceInvokeExpr)
                    ? ((InstanceInvokeExpr) ie).getBase() : null;

            // Clone and patch each callee statement
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

    // =========================================================================
    // Helpers
    // =========================================================================

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
}