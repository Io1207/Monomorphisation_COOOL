import java.util.*;

import soot.*;

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
        stack.values().removeIf(Objects::isNull); // remove any null set that may have snuck in
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

    void union(PTGraph Other) {
        if (Other == null) return;
        Set<Local> onlyInOther = new HashSet<>(Other.stack.keySet());

        for (Map.Entry<Local, Set<Integer>> s : stack.entrySet()) {
            Local l = s.getKey();
            if (Other.stack.containsKey(l)) {
                onlyInOther.remove(l);
                Set<Integer> otherSet = Other.stack.get(l);
                if (otherSet != null && s.getValue() != null)
                    s.getValue().addAll(otherSet);
            }
        }

        for (Local l : onlyInOther) {
            Set<Integer> otherSet = Other.stack.get(l);
            if (otherSet != null)
                stack.computeIfAbsent(l, k -> new HashSet<>()).addAll(otherSet);
        }

        for (Map.Entry<Integer, Map<SootField, Set<Integer>>> h : Other.heap.entrySet()) {
            if (h.getValue() == null) continue;
            for (Map.Entry<SootField, Set<Integer>> e : h.getValue().entrySet()) {
                if (e.getValue() == null) continue;
                heap.computeIfAbsent(h.getKey(), k -> new HashMap<>())
                    .computeIfAbsent(e.getKey(), k -> new HashSet<>())
                    .addAll(e.getValue());
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
