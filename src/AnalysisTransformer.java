import java.util.*;
// import javafx.util.Pair;

import soot.*;
import soot.jimple.toolkits.callgraph.CallGraph;
import soot.jimple.toolkits.callgraph.Edge;

import soot.jimple.Ref;
import soot.jimple.AssignStmt;
import soot.jimple.StaticFieldRef;
import soot.jimple.Stmt;
import soot.jimple.ThisRef;
import soot.jimple.ParameterRef;
import soot.jimple.InstanceFieldRef;
import soot.jimple.InstanceInvokeExpr;
import soot.jimple.NewExpr;
import soot.jimple.InvokeExpr;
import soot.jimple.CastExpr;
import soot.jimple.IdentityStmt;
import soot.toolkits.graph.*;
import soot.toolkits.scalar.BackwardFlowAnalysis;
import soot.toolkits.scalar.FlowSet;


class PTGraph {
    Map<Local, Set<Integer>> stack;
    Map<Integer, Map<SootField, Set<Integer>>> heap;

    PTGraph() {
        stack = new HashMap<>();
        heap = new HashMap<>();
    }

    public void clear() {
        stack.clear();
        heap.clear();
    }

    @Override public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof PTGraph)) return false;
        PTGraph o = (PTGraph) other;
        if (!stack.equals(o.stack)) return false;
        if (!heap.equals(o.heap)) return false;
        return true;
    }

    void addStackPtr(Local id, int sId) {
        stack.computeIfAbsent(id, i -> new HashSet<>()).add(sId);
    }

    void addHeapPtr(Integer id, SootField sfld, int pt2Id) {
        heap.computeIfAbsent(id, k -> new HashMap<>())
            .computeIfAbsent(sfld, k -> new HashSet<>())
            .add(pt2Id);
    }

    void copy (PTGraph Other) {
        stack.clear();
        heap.clear();
        for (Map.Entry<Local, Set<Integer>> s : Other.stack.entrySet()) {
            stack.computeIfAbsent(s.getKey(), k -> new HashSet<>()).addAll(s.getValue());
        }

        for (Map.Entry<Integer, Map<SootField, Set<Integer>>> h : Other.heap.entrySet()) {
            int inner_k = h.getKey();
            for (Map.Entry<SootField, Set<Integer>> e : h.getValue().entrySet()) {
                heap.computeIfAbsent(inner_k, k -> new HashMap<>())
                        .computeIfAbsent(e.getKey(), k -> new HashSet<>())
                        .addAll(e.getValue());
            }
        }
    }

    Set<Integer> objectsPointedBy(Local l) {
        if (stack.containsKey(l)) {
            return new HashSet<>(stack.get(l));
        } else {
            throw new RuntimeException("Entry for local l doesn't exist");
        } 
    }

    void union (PTGraph Other) {
        /*
           right now this takes union and just puts in the 'this' object's
           stack; probably should name this merge 
        */
        Set<Local> lList = Other.stack.keySet();
        for (Map.Entry<Local, Set<Integer>> s : stack.entrySet()) {
            Local l = s.getKey();
            if (Other.stack.containsKey(l)) {
                lList.remove(l);
                stack.get(l).addAll(Other.stack.get(l));
            }
        }
        for (Map.Entry<Local, Set<Integer>> s : Other.stack.entrySet()) {
            Local l = s.getKey();
            if (lList.contains(l)) {
                stack.computeIfAbsent(s.getKey(), k -> new HashSet<>())
                     .addAll(s.getValue());
            }
        }
    }

    @Override public int hashCode() {
        return stack.hashCode() + 31 * heap.hashCode();
    }

    public void clearHeap(int oId, SootField sfld) {
        if (heap.containsKey(oId)) {
            Map<SootField, Set<Integer>> fMap = heap.get(oId);
            if (fMap.containsKey(sfld)) {
                fMap.get(sfld).clear();
            }
        }
    }
} 

public class AnalysisTransformer extends SceneTransformer {

    static CallGraph cg;

    static class AllocSite {
        int lineNumber;
        Unit unit;

        AllocSite(Unit ut, int line) {
            this.unit = ut;
            this.lineNumber = line;
        }

        @Override public int hashCode() {
            return unit.hashCode();
        } 

        @Override public boolean equals(Object o) {
            if (!(o instanceof AllocSite)) return false;
            return ((AllocSite)o).unit.equals(this.unit);
        }

        @Override public String toString() {
            return String.valueOf(lineNumber);
        }
    }

    static class PointsToState {
        Map <Local, Set<AllocSite>> varPts = new HashMap<>();
        Set<AllocSite> get(Local l) {
            return varPts.getOrDefault(l, Collections.emptySet());
        }

        void set(Local l, Set<AllocSite> setA) {
            varPts.put(l, new HashSet<>(setA));
        }

        void add(Local l, AllocSite a) {
            varPts.computeIfAbsent(l, k -> new HashSet<>()).add(a);
        }

        void weakset(Local l, Set<AllocSite> site) {
            Set<AllocSite> old = varPts.getOrDefault(l, Collections.emptySet());
            Set<AllocSite> merged = new HashSet<>(old);
            merged.addAll(site);
            varPts.put(l, merged);
        }

        PointsToState copy() {
            PointsToState out = new PointsToState();
            for (Map.Entry<Local, Set<AllocSite>> e: varPts.entrySet()) {
                out.varPts.put(e.getKey(), new HashSet<>(e.getValue()));
            }
            return out;
        }
    }

    Map<AllocSite, Boolean> escapesGlobal = new HashMap<>();
    Map<AllocSite, Boolean> modified = new HashMap<>();
    Map<AllocSite, Set<Integer>> callLines = new HashMap<>();

    Map<AllocSite, Set<AllocSite>> buddyObjectsMap = new HashMap<>();
    List<Map<AllocSite, Set<Map<SootMethod, Integer>>>> methodModifiesWhere = new ArrayList<>();

    Set<SootMethod> visited = new HashSet<>();
    Map<AllocSite, Map<SootMethod, Integer>> libMod = new HashMap<>();
    SootMethod mainMethod;
    // Set<String> activeContext = new HashSet<>();

    @Override
    protected void internalTransform(String phaseName, Map<String, String> options) {
        // Store the call graph once as a static field...
        cg = Scene.v().getCallGraph();
        // System.out.println(cg);
        // This code lets us get the main method, our testcases will only have one start point that is the main method
        // in the Test class...
        var entrypoints = Scene.v().getEntryPoints();
        assert(entrypoints.size() == 1);
        SootMethod entryMethod = entrypoints.get(0);
        mainMethod = entryMethod;
        handleMainMethod(entryMethod);
        // computeInlineLinesFromMain(entryMethod);
        // adjust(false);

        List<AllocSite> sites = new ArrayList<>(escapesGlobal.keySet());
        sites.sort(Comparator.comparingInt(a -> a.lineNumber));

        for (AllocSite a : sites) {
            boolean global = escapesGlobal.getOrDefault(a, false);
            boolean mod = modified.getOrDefault(a, false);

            if (!global && !mod) {
                callLines.put(a, new HashSet<>());
                collectCallLinesForAllocSite(entryMethod, 
                                       new PointsToState(), a, 
                                       new LinkedHashSet<>(),
                                       new HashSet<>(), false);
            }

            if (global || mod) System.out.println("O" + a + " = N");
            else {
                // List<Integer> lines = new ArrayList<>(callLines.get(a));

                Set<Integer> lineSet = callLines.getOrDefault(a, Collections.emptySet());
                List<Integer> lines = new ArrayList<>(lineSet);

                Collections.sort(lines);
                System.out.println("O"+a + " = Y" + lines);
            }
        }
    }

    void handleMainMethod(SootMethod myMethod) {

        if (visited.contains(myMethod)) return;
        visited.add(myMethod);

        if (!myMethod.isConcrete()) return;
        analyzeMethod(myMethod);

        // Get the body
        Body body = myMethod.retrieveActiveBody();
        
        // Iterate over statements
        for (Unit u : body.getUnits()) {
            Stmt stmt = (Stmt) u;
            
            // If a statement contains a call expression, find and print its call targets.
            if (stmt.containsInvokeExpr()) {
                // System.out.println("Call site found: " + stmt + "@" + lineNumber);
                Iterator<Edge> targets = cg.edgesOutOf(stmt);
                
                while (targets.hasNext()) {
                    Edge edge = targets.next();
                    SootMethod targetMethod = edge.tgt();
                    handleMainMethod(targetMethod);
                    // System.out.println("  -> Potential target: " + targetMethod.getSignature());
                }
            }
        }
    }

    void adjust(boolean anyChange) {
        // System.out.println("hiya");
        // System.out.println(callLines);

        // the point is to check post primary analysis if any escaping
        // object can access a currently unmarked object
        List<AllocSite> geSites = new ArrayList<>(escapesGlobal.keySet());
        for (AllocSite a : geSites) {
            boolean g = escapesGlobal.getOrDefault(a, false);
            boolean m = modified.getOrDefault(a, false);

            if (!g && !m) {
                Set<AllocSite> buddies = buddyObjectsMap.get(a);
                for (AllocSite a1: buddies) {
                    if (g) escapesGlobal.put(a1,true);
                    // if (m) modified.put(a1, true);
                }
            }
        }

    }

    void analyzeMethod(SootMethod method) { 
        if (!method.isConcrete()) return;

        Body body = method.retrieveActiveBody();

        UnitGraph cfg = new BriefUnitGraph(body);

        Map<Unit, PointsToState> unitState = new HashMap<>();
        LinkedList<Unit> workList = new LinkedList<>(body.getUnits());

        PointsToState entry = new PointsToState();
        unitState.put(body.getUnits().getFirst(), entry);

        while (!workList.isEmpty()) {
            Unit u = workList.poll();
            PointsToState inState = new PointsToState();

            List<Unit> preds = cfg.getPredsOf(u);
            if (preds.isEmpty()) inState = unitState.getOrDefault(u, new PointsToState());
            else {
                for (Unit p:preds) {
                    PointsToState predState = unitState.getOrDefault(p, new PointsToState());
                    for (Map.Entry<Local, Set<AllocSite>> e: predState.varPts.entrySet()) {
                        inState.weakset(e.getKey(), e.getValue());
                    }
                }
            }
            PointsToState outState = new PointsToState();
            outState.varPts.putAll(inState.varPts);

            Stmt stmt = (Stmt) u;
            int line = stmt.getJavaSourceStartLineNumber();
            
            if (stmt instanceof IdentityStmt) {
                IdentityStmt is = (IdentityStmt) stmt;
                Value lhs = is.getLeftOp();
                Value rhs = is.getRightOp();

                if (lhs instanceof Local) {
                    if (rhs instanceof ThisRef || rhs instanceof ParameterRef) {
                        try {
                            Local thisL = body.getThisLocal();
                            outState.set((Local) lhs, inState.get(thisL));
                        } catch (RuntimeException ignored){}
                        
                    } else if (rhs instanceof ParameterRef) {
                        int idx = ((ParameterRef) rhs).getIndex();
                        if (idx < method.getParameterCount()) {
                            Local paramL = body.getParameterLocal(idx);
                            outState.set((Local) lhs, inState.get(paramL));
                        }
                    }
                }
            }

            if (stmt instanceof AssignStmt) {
                AssignStmt s = (AssignStmt) stmt;
                Value lhs = s.getLeftOp();
                Value rhs = s.getRightOp();

                // if (line==35) {
                //     System.out.println(s.getLeftOp() instanceof StaticFieldRef);
                //     System.out.println(s.getRightOp().getType());
                // }

                // x = new A();
                if ((lhs instanceof Local) && (rhs instanceof NewExpr)) {
                    AllocSite site = new AllocSite(s, line);

                    Set<AllocSite> singleton = new HashSet<>();
                    singleton.add(site);
                    outState.set((Local) lhs, singleton);

                    escapesGlobal.putIfAbsent(site, false);
                    modified.putIfAbsent(site, false);
                    callLines.computeIfAbsent(site, k -> new HashSet<>());
                }

                // x = y
                if ((lhs instanceof Local) && (rhs instanceof Local || rhs instanceof CastExpr)) {
                    Local rhsL = rhs instanceof Local ? (Local) rhs: (Local) ((CastExpr) rhs).getOp();
                    Local lhsL = (Local) lhs;
                    if (preds.size() > 1) {outState.weakset(lhsL, inState.get(rhsL));}
                    else outState.set((Local) lhs, inState.get(rhsL));
                    // state.weakset((Local) lhs, state.get((Local) rhs));
                    // buddyObjectsMap.get(callLines)
                    // if (escapesGlobal.containsKey(lhs.))
                }

                // x.f = y
                if (lhs instanceof InstanceFieldRef) {
                    InstanceFieldRef ifr = (InstanceFieldRef) lhs;
                    Value base = ifr.getBase();
                    Type fieldType = ifr.getField().getType();
                    if (base instanceof Local && fieldType instanceof RefLikeType) {
                        for (AllocSite a : outState.get((Local) base)) {
                            if (method != mainMethod) modified.put(a, true);
                            //  System.out.print("case 3: "); ;System.out.println(u);
                        }
                    }
                }

                //x = y.f
                if (lhs instanceof Local && rhs instanceof InstanceFieldRef) {
                    InstanceFieldRef fr = (InstanceFieldRef) rhs;
                    Value baseValue = fr.getBase();
                    if (baseValue instanceof Local) {
                        Type fldType = fr.getField().getType();
                        if (fldType instanceof RefLikeType) {
                            if (preds.size() > 1) outState.weakset((Local) lhs, inState.get((Local) baseValue));
                            else outState.set((Local) lhs, inState.get((Local) baseValue));
                        } else {
                            outState.set((Local) lhs, Collections.emptySet());
                        }
                    }
                }

                // global x = O_n;
                if (lhs instanceof StaticFieldRef && rhs instanceof Local) {
                    for (AllocSite a : outState.get((Local) rhs)) {
                        escapesGlobal.put(a, true); 
                        // System.out.print("case 4: "); ;System.out.println(u);
                    }
                }

                // static field stroing a field of the object
                // SR if primitive type field otherwise an issue
                if (lhs instanceof StaticFieldRef && rhs instanceof InstanceFieldRef) {
                    // System.out.println("aaya to tha");
                    InstanceFieldRef fr = (InstanceFieldRef) rhs;
                    Value base = fr.getBase();
                    Type fldType = fr.getField().getType();

                    if (base instanceof Local && fldType instanceof RefLikeType) {
                        for (AllocSite a: outState.get((Local) base)) escapesGlobal.put(a, true);
                    }
                }
                
                // x = (A) y
                // if (lhs instanceof Local && rhs instanceof CastExpr) {
                //     Value op = ((CastExpr) rhs).getOp();
                //     if (op instanceof Local) outState.set((Local) lhs, outState.get((Local) op));
                // }
            }

            if (stmt.containsInvokeExpr()) {
                InvokeExpr ie = stmt.getInvokeExpr();

                Iterator<Edge> edges = cg.edgesOutOf(stmt);

                while (edges.hasNext()) {
                    SootMethod tgt = edges.next().tgt();

                    if (tgt.isConstructor() || tgt.getName().equals("<init>") || tgt.getName().equals("clinit")) continue;
                    if (tgt.getDeclaringClass().getName().equals("java.io.PrintStream")) continue;

                    if (ie instanceof InstanceInvokeExpr) {
                        Value baseVal = ((InstanceInvokeExpr) ie).getBase();
                        if (baseVal instanceof Local) {
                            for (AllocSite a: outState.get((Local) baseVal)) {
                                callLines.computeIfAbsent(a, k-> new HashSet<>()).add(line);
                                if (!tgt.getDeclaringClass().isApplicationClass()) {
                                    if (method!=mainMethod) modified.put(a, true);
                                } else if (methodModifiesThis(tgt)) modified.put(a, true);
                            }
                        }
                    }

                    Set<Integer> modifiedParams = tgt.getDeclaringClass().isApplicationClass() ? methodModifiedParams(tgt) : Collections.emptySet();
                    // System.out.println(ie.getArgs());
                    // System.out.println(methodModifiedParams(tgt));

                    List<Value> args = ie.getArgs();
                    for (int i = 0; i < args.size(); i++) {
                        Value arg = args.get(i);
                        if (!(arg instanceof Local)) continue;

                        for (AllocSite a: outState.get((Local) arg)) {
                            callLines.computeIfAbsent(a, k-> new HashSet<>()).add(line);
                            if (!tgt.getDeclaringClass().isApplicationClass()) {
                                modified.put(a, true);
                            } else if (modifiedParams.contains(i)) {
                                modified.put(a, true);
                            }
                        }
                    }

                    if (!tgt.getDeclaringClass().isApplicationClass()) {
                        for (Value arg: ie.getArgs()) {
                            if (!(arg instanceof Local)) continue;
                            for (AllocSite a: outState.get((Local) arg)) {

                            }
                        }
                    }
                }
            }


            unitState.put(u, outState);
            for (Unit succ : cfg.getSuccsOf(u)) workList.add(succ);
        }
    }

    void analyseMethod (SootMethod method) {
        if (!method.isConcrete()) return;

        Body body = method.retrieveActiveBody();
        PointsToState state = new PointsToState();
        for (Unit u: body.getUnits()) {
            Stmt stmt = (Stmt) u;
            int line = stmt.getJavaSourceStartLineNumber();

            // case of allocations and assignments
            if (stmt instanceof AssignStmt) {
                AssignStmt s = (AssignStmt) stmt;
                Value lhs = s.getLeftOp();
                Value rhs = s.getRightOp();

                // if (line==35) {
                //     System.out.println(s.getLeftOp() instanceof StaticFieldRef);
                //     System.out.println(s.getRightOp().getType());
                // }

                // x = new A();
                if ((lhs instanceof Local) && (rhs instanceof NewExpr)) {
                    AllocSite site = new AllocSite(s, line);
                    // state.set((Local) lhs, Set.of(site));

                    Set<AllocSite> singleton = new HashSet<>();
                    singleton.add(site);
                    state.set((Local) lhs, singleton);

                    escapesGlobal.putIfAbsent(site, false);
                    modified.putIfAbsent(site, false);
                    callLines.computeIfAbsent(site, k -> new HashSet<>());
                }

                // x = y
                if ((lhs instanceof Local) && (rhs instanceof Local)) {
                    state.set((Local) lhs, state.get((Local) rhs));
                    // state.weakset((Local) lhs, state.get((Local) rhs));
                    // buddyObjectsMap.get(callLines)
                    // if (escapesGlobal.containsKey(lhs.))
                }

                // x.f = y
                if (lhs instanceof InstanceFieldRef) {
                    InstanceFieldRef ifr = (InstanceFieldRef) lhs;
                    Value base = ifr.getBase();
                    Type fieldType = ifr.getField().getType();
                    if (base instanceof Local && fieldType instanceof RefLikeType) {
                        for (AllocSite a : state.get((Local) base)) {
                            if (method != mainMethod) modified.put(a, true);
                            //  System.out.print("case 3: "); ;System.out.println(u);
                        }
                    }
                }

                //x = y.f
                if (lhs instanceof Local && rhs instanceof InstanceFieldRef) {
                    InstanceFieldRef fr = (InstanceFieldRef) rhs;
                    Value baseValue = fr.getBase();
                    if (baseValue instanceof Local) {
                        Type fldType = fr.getField().getType();
                        if (fldType instanceof RefLikeType) {
                            state.set((Local) lhs, state.get((Local) baseValue));
                        } else {
                            state.set((Local) lhs, Collections.emptySet());
                        }
                    }
                }

                // global x = O_n;
                if (lhs instanceof StaticFieldRef && rhs instanceof Local) {
                    for (AllocSite a : state.get((Local) rhs)) {
                        escapesGlobal.put(a, true); 
                        // System.out.print("case 4: "); ;System.out.println(u);
                    }
                }

                // static field stroing a field of the object
                // SR if primitive type field otherwise an issue
                if (lhs instanceof StaticFieldRef && rhs instanceof InstanceFieldRef) {
                    // System.out.println("aaya to tha");
                    InstanceFieldRef fr = (InstanceFieldRef) rhs;
                    Value base = fr.getBase();
                    Type fldType = fr.getField().getType();

                    if (base instanceof Local && fldType instanceof RefLikeType) {
                        for (AllocSite a: state.get((Local) base)) escapesGlobal.put(a, true);
                    }
                }
                
                // x = (A) y
                if (lhs instanceof Local && rhs instanceof CastExpr) {
                    Value op = ((CastExpr) rhs).getOp();
                    if (op instanceof Local) state.set((Local) lhs, state.get((Local) op));
                }
            }

            
            // case of method calls
            if (stmt.containsInvokeExpr()) {
                InvokeExpr ie = stmt.getInvokeExpr();

                Iterator<Edge> edges = cg.edgesOutOf(stmt);

                while (edges.hasNext()) {
                    SootMethod tgt = edges.next().tgt();

                    if (ie instanceof InstanceInvokeExpr) {
                        Value baseVal = ((InstanceInvokeExpr) ie).getBase();
                        if (baseVal instanceof Local) {
                            for (AllocSite a: state.get((Local) baseVal)) {
                                callLines.computeIfAbsent(a, k-> new HashSet<>()).add(line);
                                if (!tgt.getDeclaringClass().isApplicationClass()) {
                                    if (method!=mainMethod) modified.put(a, true);
                                } else if (methodModifiesThis(tgt));
                            }
                        }
                    }

                    Set<Integer> modifiedParams = tgt.getDeclaringClass().isApplicationClass() ? methodModifiedParams(tgt) : Collections.emptySet();
                
                    List<Value> args = ie.getArgs();
                    for (int i = 0; i < args.size(); i++) {
                        Value arg = args.get(i);
                        if (!(arg instanceof Local)) continue;

                        for (AllocSite a: state.get((Local) arg)) {
                            callLines.computeIfAbsent(a, k-> new HashSet<>()).add(line);
                            if (!tgt.getDeclaringClass().isApplicationClass()) {
                                modified.put(a, true);
                            } else if (modifiedParams.contains(i)) {
                                modified.put(a, true);
                            }
                        }
                    }
                }
            }
        }
    }

    PointsToState buildInitialStateForMethod(SootMethod m, AllocSite tgt) {
        PointsToState state = new PointsToState();
        if (!m.isConcrete()) return state;
        Body body = m.retrieveActiveBody();
        for (Unit u: body.getUnits()) {
            Stmt stmt = (Stmt) u;
            if (stmt instanceof AssignStmt) {
                AssignStmt as = (AssignStmt) stmt;
                Value lhs = as.getLeftOp();
                Value rhs = as.getRightOp();

                if (lhs instanceof Local && rhs instanceof NewExpr) {
                    int line = stmt.getJavaSourceStartLineNumber();
                    if (line == tgt.lineNumber && u.equals(tgt.unit)) {
                        Set<AllocSite> singleton = new HashSet<>();
                        singleton.add(tgt);
                        state.set((Local) lhs, singleton);
                    }
                }
                if (lhs instanceof Local && rhs instanceof Local) {
                    state.set((Local) lhs, state.get((Local) rhs));
                }
                if (lhs instanceof Local && rhs instanceof CastExpr) {
                    Value op = ((CastExpr) rhs).getOp();
                    if (op instanceof Local) state.set((Local) lhs, state.get((Local) op));
                }
                if (lhs instanceof Local && rhs instanceof InstanceFieldRef) {
                    InstanceFieldRef fr = (InstanceFieldRef) rhs;
                    Value baseVal = fr.getBase();
                    if (baseVal instanceof Local) {
                        Type fldType = fr.getField().getType();
                        if (fldType instanceof RefLikeType) {
                            state.set((Local) lhs, state.get((Local) baseVal));
                        } else {
                            state.set((Local) lhs, Collections.emptySet());
                        }

                    }
                }
            }
        }
        return state;
    }

    PointsToState buildCalleeState(SootMethod m, InvokeExpr ie, PointsToState callerState) {
        PointsToState calleeState = new PointsToState();
        if (!m.isConcrete()) return calleeState;
        Body body = m.retrieveActiveBody();

        if (ie instanceof InstanceInvokeExpr && !m.isStatic()) {
            Value base = ((InstanceInvokeExpr) ie).getBase();
            if (base instanceof Local) {
                try {
                    calleeState.set((Local) base, callerState.get((Local) base));
                } catch (RuntimeException ignored) {}
            }
        }

        List<Value> args = ie.getArgs();
        for (int i = 0; i< args.size() && i<m.getParameterCount(); i++) {
            Value arg = ie.getArg(i);
            if (arg instanceof Local) {
                Local paramLocal = body.getParameterLocal(i);
                calleeState.set(paramLocal, callerState.get((Local) arg));        
            }
        }
        return calleeState;
    }

    void collectCallLinesForAllocSite(SootMethod m,
                                      PointsToState state,
                                      AllocSite tgt, LinkedHashSet<Integer> path,
                                      Set<String> visited, boolean isTgtBase) {
        if (!m.isConcrete()) return;
        boolean isTagetBase = false;

        if (state.varPts.isEmpty()) state = buildInitialStateForMethod(m, tgt);
        // boolean print = false;
        // if (tgt.lineNumber == 47 || tgt.lineNumber == 46) {print = true;}
        // if (print) System.out.println(path);
        // List<String> holders = new ArrayList<>();
        // for (Map.Entry<Local, Set<AllocSite>> e : state.varPts.entrySet()) {
        //     if (e.getValue().contains(tgt)) {
        //         holders.add(e.getKey().getName());
        //     }
        // }
        // Collections.sort(holders);

        // String key = m.getSignature() + "|" + tgt.lineNumber + "|" + path + "|" +holders;
        String key = m.getSignature() + "|" + tgt.lineNumber + "|" + path;
        if (visited.contains(key)) return;
        visited.add(key);

        Body body = m.retrieveActiveBody();

        for (Unit u: body.getUnits()) {
            isTagetBase = false;
            Stmt stmt = (Stmt) u;
            int line = stmt.getJavaSourceStartLineNumber();

            if (stmt instanceof IdentityStmt) {
                IdentityStmt is = (IdentityStmt) stmt;
                Value lhs = is.getLeftOp();
                Value rhs = is.getRightOp();
                if (lhs instanceof Local) {
                    if (rhs instanceof ThisRef) {
                        try {
                            Local thisLocal = body.getThisLocal();
                            if (!thisLocal.equals(lhs)) {
                                state.set((Local) lhs, state.get(thisLocal));
                            }
                        } catch (RuntimeException ignored) {}
                    } else if (rhs instanceof ParameterRef) {
                        int id = ((ParameterRef) rhs).getIndex();
                        if (id < body.getParameterLocals().size()) {
                            Local formal = body.getParameterLocal(id);
                            if (!formal.equals(lhs)) {
                                state.set((Local) lhs, state.get(formal));                       
                            }
                        }
                    }
                }
                continue;
            }
            if (stmt instanceof AssignStmt) {
                // if (m.isConstructor() || m.getName().equals("<init>") || m.getName().equals("clinit")) continue;

                // String declClass = m.getDeclaringClass().getName();
                // if (declClass.equals("java.io.PrintStream")) continue;

                AssignStmt as = (AssignStmt) stmt;
                Value lhs = as.getLeftOp();
                Value rhs = as.getRightOp();

                if (lhs instanceof Local && rhs instanceof Local) {
                    state.set((Local) lhs, state.get((Local) rhs));
                } else if (lhs instanceof Local && rhs instanceof CastExpr) {
                    Value op = ((CastExpr) rhs).getOp();
                    if (op instanceof Local) {
                        state.set((Local) lhs, state.get((Local) op));
                    }
                } else if (lhs instanceof Local && rhs instanceof InstanceFieldRef) {
                    InstanceFieldRef ifr = (InstanceFieldRef) rhs;
                    Value base = ifr.getBase();
                    if (base instanceof Local) {
                        Type fldType = ifr.getField().getType();
                        if (fldType instanceof RefLikeType) {
                            state.set((Local) lhs, state.get((Local) base));
                        } else {
                            state.set((Local) lhs, Collections.emptySet());
                        }
                    }
                }
            }

            if (stmt.containsInvokeExpr()) {
                InvokeExpr ie = stmt.getInvokeExpr();
                Iterator<Edge> edges = cg.edgesOutOf(stmt);

                while (edges.hasNext()) {
                    SootMethod target = edges.next().tgt();
                    boolean flows = false;

                    // System.out.println("reached here"+line);
                  
                    if (target.isConstructor() || target.getName().equals("<init>") || target.getName().equals("<clinit>")) // {if (print) {System.out.println(line);}continue;}
                    {continue;}

                    String declClass = target.getDeclaringClass().getName();
                    if (declClass.equals("java.io.PrintStream")) // {if (print) {System.out.println("here ");System.out.println(line);}continue;}
                    {continue;}

                    // if (print) System.out.println("i was here too");

                    if (ie instanceof InstanceInvokeExpr) {
                        // if(print) System.out.println(ie + " " + line);
                        Value base = ((InstanceInvokeExpr) ie).getBase();
                        boolean case1 = (base instanceof Local && state.get((Local) base).contains(tgt));
                        // if (print) System.out.println(base);
                        // if (tgt.lineNumber == 47 || tgt.lineNumber == 46) {System.out.println(base instanceof Local); System.out.println(state.get((Local) base).contains(tgt));}
                        if (case1 || (base instanceof Local && isTgtBase)) {
                            flows = true;
                            // if (!case1) System.out.println("hello hello");
                            if (case1) isTagetBase = true;
                        }
                    }
                    
                    for (Value arg: ie.getArgs()) {
                        if (arg instanceof Local && state.get((Local) arg).contains(tgt)) {
                            flows = true;
                        }
                    }

                    if (!flows) // {if (print) System.out.println("Exiting bcoz flow is false: " + edges.next());continue;}
                    {continue;}
                    // if (print) System.out.println("got here");

                    LinkedHashSet<Integer> nextPath = new LinkedHashSet<>(path);
                    
                    nextPath.add(line);
                    callLines.computeIfAbsent(tgt, k-> new HashSet<>()).addAll(nextPath);

                    PointsToState calleeState = buildCalleeState(target, ie, state);

                    collectCallLinesForAllocSite(target, calleeState, tgt, nextPath, visited, isTagetBase);
                }
            }
        }
    }

    boolean methodModifiesThis(SootMethod method) {
        if (!method.isConcrete()) return false;

        Body b = method.retrieveActiveBody();
        Local thisLocal = b.getThisLocal();

        if (!(method.isStatic())) {
            try {
                thisLocal = b.getThisLocal();
            } catch (RuntimeException e){
                thisLocal = null;
            }
        }

        Map<Local, Set<String>> aliases = new HashMap<>();

        if (thisLocal!=null) {
            Set<String> s =new HashSet<>();
            s.add("THIS");
            aliases.put(thisLocal, s);
        }

        for (int i = 0; i<method.getParameterCount(); i++) {
            Local p = b.getParameterLocal(i);
            aliases.putIfAbsent(p, new HashSet<>());
        }

        for (Unit u: b.getUnits()) {
            Stmt s = (Stmt) u;
            if (s instanceof IdentityStmt) {
                IdentityStmt is = (IdentityStmt) s;
                Value lhs = is.getLeftOp();
                Value rhs = is.getRightOp();
                if (lhs instanceof Local) {
                    if (rhs instanceof ThisRef) {
                        Set<String> tags = new HashSet<>();
                        tags.add("THIS");
                        aliases.put((Local) lhs, tags);
                    } else if (rhs instanceof ParameterRef) {
                        int idx = ((ParameterRef) rhs).getIndex();
                        Set<String> tags = new HashSet<>();
                        tags.add("PARAM_"+idx);
                        aliases.put((Local) lhs, tags);
                    }
                }
            } else if (s instanceof AssignStmt) {
                AssignStmt as = (AssignStmt) s;
                Value lhs = as.getLeftOp();
                Value rhs = as.getRightOp();
                if (lhs instanceof Local && rhs instanceof Local) {
                    aliases.put((Local) lhs, new HashSet<>(aliases.getOrDefault((Local) rhs, Collections.emptySet())));
                } else if (lhs instanceof Local && rhs instanceof CastExpr) {
                    Value op = ((CastExpr) rhs).getOp();
                    if (op instanceof Local) aliases.put((Local) lhs, new HashSet<>(aliases.getOrDefault((Local) op, Collections.emptySet())));
                } else if (lhs instanceof InstanceFieldRef) {
                    Value base = ((InstanceFieldRef) lhs).getBase();
                    if (base instanceof Local) {
                        Set<String> tags = aliases.getOrDefault((Local) base, Collections.emptySet());
                        if (tags.contains("THIS")) return true;
                    }
                }
            }
        }
        return false;
    }

    Set<Integer> methodModifiedParams(SootMethod m) {
        Set<Integer> result = new HashSet<>();
        if (!m.isConcrete()) return result;

        Body b = m.retrieveActiveBody();

        Map<Local, Set<String>> aliases = new HashMap<>();

        if (!m.isStatic()) {
            try {
                Local thisLocal = b.getThisLocal();
                Set<String> tags = new HashSet<>();
                tags.add("THIS");
                aliases.put(thisLocal, tags);
            } catch (RuntimeException ignored) {}
        }
        // return new HashSet<>();

        for (int i = 0; i< m.getParameterCount(); i++) {
            Local p = b.getParameterLocal(i);
            Set<String> tags = new HashSet<>();
            tags.add("PARAM_"+i);
            aliases.put(p,tags);
        }
    

        for (Unit u:b.getUnits()) {
            Stmt s = (Stmt)u;

            if (s instanceof IdentityStmt) {
                IdentityStmt is = (IdentityStmt)s;
                Value lhs = is.getLeftOp();
                Value rhs = is.getRightOp();

                if (lhs instanceof Local) {
                    if (rhs instanceof ThisRef) {
                        Set<String> tags = new HashSet<>();
                        tags.add("THIS");
                        aliases.put((Local) lhs, tags);
                    } else if (rhs instanceof ParameterRef) {
                        int idx = ((ParameterRef) rhs).getIndex();
                        Set<String> tags = new HashSet<>();
                        tags.add("PARAM_"+idx);
                        aliases.put((Local) lhs, tags);
                    }
                }
            } else if (s instanceof AssignStmt) {
                AssignStmt as = (AssignStmt) s;
                Value lhs = as.getLeftOp();
                Value rhs = as.getRightOp();

                if (lhs instanceof Local && rhs instanceof Local) {
                    aliases.put((Local) lhs, new HashSet<>(aliases.getOrDefault((Local) rhs, Collections.emptySet())));
                } else if (lhs instanceof Local && rhs instanceof CastExpr) {
                    Value op = ((CastExpr) rhs).getOp();
                    if (op instanceof Local) {
                        aliases.put((Local) lhs, new HashSet<>(aliases.getOrDefault((Local) op, Collections.emptySet())));
                    }
                } else if (lhs instanceof InstanceFieldRef) {
                    Value base = ((InstanceFieldRef) lhs).getBase();
                    if (base instanceof Local) {
                        Set<String> tags = aliases.getOrDefault((Local) base, Collections.emptySet());
                        for (String tag: tags) {
                            if (tag.startsWith("PARAM_")) {
                                int idx = Integer.parseInt(tag.substring("PARAM_".length()));
                                result.add(idx);
                            }
                        }
                    }
                }
            }
        }
        // System.out.println("helllloooo");
        // System.out.println(result);
        return result;
    }
}