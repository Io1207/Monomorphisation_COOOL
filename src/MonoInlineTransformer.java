import soot.*;
import soot.jimple.*;
import soot.jimple.toolkits.callgraph.CallGraph;
import soot.jimple.toolkits.callgraph.Edge;
import soot.util.Chain;

import java.util.*;

public class MonoInlineTransformer extends SceneTransformer {

    private static final int INLINE_THRESHOLD = 20;
    private final Set<String> currentlyInlining = new HashSet<>();

    @Override
    protected void internalTransform(String phaseName, Map<String, String> options) {
        CallGraph cg = Scene.v().getCallGraph();

        List<SootMethod> appMethods = new ArrayList<>();
        for (SootClass sc : Scene.v().getApplicationClasses()) {
            appMethods.addAll(sc.getMethods());
        }

        int monoCount   = 0;
        int inlineCount = 0;

        for (SootMethod method : appMethods) {
            if (!method.isConcrete()) continue;

            Body body = method.retrieveActiveBody();

            List<Unit> units = new ArrayList<>(body.getUnits());

            for (Unit u : units) {
                Stmt stmt = (Stmt) u;
                if (!stmt.containsInvokeExpr()) continue;

                InvokeExpr ie = stmt.getInvokeExpr();

                if (!(ie instanceof VirtualInvokeExpr) && !(ie instanceof InterfaceInvokeExpr))
                    continue;

                // Gather call-graph targets for this site.
                List<SootMethod> targets = new ArrayList<>();
                Iterator<Edge> edges = cg.edgesOutOf(stmt);
                while (edges.hasNext()) targets.add(edges.next().tgt());

                if (targets.size() != 1) continue;
                SootMethod target = targets.get(0);
                if (!target.isConcrete())              continue;
                if (target.isAbstract())               continue;

                SpecialInvokeExpr mono = Jimple.v().newSpecialInvokeExpr(
                        (Local) ((InstanceInvokeExpr) ie).getBase(),
                        target.makeRef(),
                        ie.getArgs());

                // in place patching for this call site 
                if (stmt instanceof AssignStmt) {
                    ((AssignStmt) stmt).setRightOp(mono);
                } else if (stmt instanceof InvokeStmt) {
                    ((InvokeStmt) stmt).setInvokeExpr(mono);
                } else {
                    continue; // took Manas sir's advice and just ignored this case
                }
                monoCount++;

                System.out.println("[MONO]   " + method.getName()
                        + " @ line " + stmt.getJavaSourceStartLineNumber()
                        + "  →  " + target.getName());

                int calleeSize = target.retrieveActiveBody().getUnits().size();
                if (calleeSize > INLINE_THRESHOLD) {
                    System.out.println("[INLINE] Skipped (size=" + calleeSize + "): " + target.getName());
                    continue;
                }
                if (currentlyInlining.contains(target.getSignature())) {
                    System.out.println("[INLINE] Skipped (recursive): " + target.getName());
                    continue;
                }

                boolean inlined = tryInline(body, stmt, target);
                if (inlined) {
                    inlineCount++;
                    System.out.println("[INLINE] Inlined " + target.getName()
                            + " into " + method.getName()
                            + " @ line " + stmt.getJavaSourceStartLineNumber());
                }
            }
        }

        System.out.println("\n=== Monomorphization: " + monoCount + " sites ===");
        System.out.println("=== Inlining:         " + inlineCount + " sites ===\n");
    }

    private boolean tryInline(Body callerBody, Stmt callStmt, SootMethod target) {
        currentlyInlining.add(target.getSignature());
        try {
            Body calleeBody;
            try {
                calleeBody = target.retrieveActiveBody();
            } catch (Exception e) {
                return false;
            }

            InvokeExpr ie = callStmt.getInvokeExpr();
            Chain<Unit> callerUnits = callerBody.getUnits();

            // Figure out if there is a return value we need to capture.
            Local returnReceiver = null;
            if (callStmt instanceof AssignStmt) {
                Value lhs = ((AssignStmt) callStmt).getLeftOp();
                if (lhs instanceof Local) returnReceiver = (Local) lhs;
            }

            // clone callee locals into caller
            String prefix = "_inl_" + target.getName() + "_";
            Map<Local, Local> localMap = new HashMap<>();

            for (Local orig : calleeBody.getLocals()) {
                Local clone = Jimple.v().newLocal(prefix + orig.getName(), orig.getType());
                callerBody.getLocals().add(clone);
                localMap.put(orig, clone);
            }

            Map<Integer, Value> paramMap = new HashMap<>();
            List<Value> args = ie.getArgs();
            for (int i = 0; i < args.size(); i++) paramMap.put(i, args.get(i));

            Value baseValue = (ie instanceof InstanceInvokeExpr)
                    ? ((InstanceInvokeExpr) ie).getBase() : null;

            // Cloning and remapping callee units
            List<Unit> clonedUnits = new ArrayList<>();

            for (Unit u : calleeBody.getUnits()) {
                Stmt s = (Stmt) u;

                if (s instanceof IdentityStmt) {
                    IdentityStmt id = (IdentityStmt) s;
                    Value lhs = id.getLeftOp();
                    Value rhs = id.getRightOp();

                    if (!(lhs instanceof Local)) continue; 

                    Local mappedLhs = localMap.get((Local) lhs);
                    if (mappedLhs == null) continue;

                    if (rhs instanceof ThisRef && baseValue != null) {
                        clonedUnits.add(Jimple.v().newAssignStmt(mappedLhs, baseValue));
                    } else if (rhs instanceof ParameterRef) {
                        int idx = ((ParameterRef) rhs).getIndex();
                        Value actual = paramMap.get(idx);
                        if (actual != null) {
                            clonedUnits.add(Jimple.v().newAssignStmt(mappedLhs, actual));
                        }
                    }
                    continue;
                }

                if (s instanceof ReturnStmt) {
                    if (returnReceiver != null) {
                        Value retVal = remapValue(((ReturnStmt) s).getOp(), localMap);
                        clonedUnits.add(Jimple.v().newAssignStmt(returnReceiver, retVal));
                    }
                    continue;
                }
                if (s instanceof ReturnVoidStmt) {
                    continue;
                }

                Unit cloned = (Unit) s.clone();
                remapStmt((Stmt) cloned, localMap);
                clonedUnits.add(cloned);
            }
            for (Unit cu : clonedUnits) { // inserting cloned things before call site
                callerUnits.insertBefore(cu, callStmt);
            }
            callerUnits.remove(callStmt);

            return true;

        } finally {
            currentlyInlining.remove(target.getSignature());
        }
    }

    private void remapStmt(Stmt stmt, Map<Local, Local> localMap) {
        /*
         THIS IS AN ENTIRELY AI-ed FUNCTION: i wrote something and couldn't debug it, i asked 
         gpt and pasted the response and it works
         */
        for (ValueBox vb : stmt.getUseAndDefBoxes()) {
            Value v = vb.getValue();
            Value remapped = remapValue(v, localMap);
            if (remapped != v) {
                try { vb.setValue(remapped); } catch (RuntimeException ignored) {}
            }
        }
    }

    private Value remapValue(Value v, Map<Local, Local> localMap) {
        /*
         THIS IS AN ENTIRELY AI-ed FUNCTION: i wrote something and couldn't debug it, i asked 
         gpt and pasted the response and it works
         */
        if (v instanceof Local) {
            Local mapped = localMap.get(v);
            return mapped != null ? mapped : v;
        }
        // For compound expressions (InstanceFieldRef, InvokeExpr, etc.)
        // Soot's clone() already deep-copied them; we just remap sub-Values.
        for (ValueBox vb : v.getUseBoxes()) {
            Value sub = vb.getValue();
            Value remapped = remapValue(sub, localMap);
            if (remapped != sub) {
                try { vb.setValue(remapped); } catch (RuntimeException ignored) {}
            }
        }
        return v;
    }
}
