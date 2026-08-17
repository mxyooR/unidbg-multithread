package com.github.unidbg.thread;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Run-owned typed wait graph with atomic batch publication and cycle checks. */
public final class RunWaitGraph {

    public enum WaitReason {
        INVOCATION_TERMINAL,
        FUTEX,
        THREAD_JOIN,
        CALLBACK_MAILBOX,
        EXTERNAL_RESOURCE
    }

    public enum DependencyKind {
        INVOCATION,
        FUTEX,
        GUEST_THREAD,
        CALLBACK_MAILBOX,
        EXTERNAL_RESOURCE
    }

    public static final class WaitNodeId {
        private final long runId;
        private final long invocationId;
        private final long generation;

        public WaitNodeId(long runId, long invocationId, long generation) {
            if (runId <= 0L || invocationId <= 0L || generation <= 0L) {
                throw new IllegalArgumentException("invalid wait node identity");
            }
            this.runId = runId;
            this.invocationId = invocationId;
            this.generation = generation;
        }

        public static WaitNodeId from(InvocationRecord invocation) {
            return new WaitNodeId(invocation.getRunContext().getRunId(),
                    invocation.getInvocationId(), invocation.getGeneration());
        }

        public long getRunId() {
            return runId;
        }

        public long getInvocationId() {
            return invocationId;
        }

        public long getGeneration() {
            return generation;
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof WaitNodeId)) {
                return false;
            }
            WaitNodeId that = (WaitNodeId) other;
            return runId == that.runId && invocationId == that.invocationId
                    && generation == that.generation;
        }

        @Override
        public int hashCode() {
            int result = Long.hashCode(runId);
            result = 31 * result + Long.hashCode(invocationId);
            return 31 * result + Long.hashCode(generation);
        }
    }

    public static final class WaitDependency {
        private final long runId;
        private final DependencyKind kind;
        private final long primaryId;
        private final long qualifier;

        private WaitDependency(long runId, DependencyKind kind,
                               long primaryId, long qualifier) {
            if (runId <= 0L || kind == null) {
                throw new IllegalArgumentException("invalid wait dependency");
            }
            this.runId = runId;
            this.kind = kind;
            this.primaryId = primaryId;
            this.qualifier = qualifier;
        }

        public static WaitDependency invocation(WaitNodeId node) {
            return new WaitDependency(node.getRunId(), DependencyKind.INVOCATION,
                    node.getInvocationId(), node.getGeneration());
        }

        public static WaitDependency futex(long runId, long address, int expectedValue) {
            return new WaitDependency(runId, DependencyKind.FUTEX,
                    address, expectedValue & 0xffffffffL);
        }

        public static WaitDependency guestThread(long runId, long incarnationId) {
            return new WaitDependency(runId, DependencyKind.GUEST_THREAD,
                    incarnationId, 0L);
        }

        public static WaitDependency callbackMailbox(long runId, long mailboxId,
                                                       long publicationSequence) {
            return new WaitDependency(runId, DependencyKind.CALLBACK_MAILBOX,
                    mailboxId, publicationSequence);
        }

        public static WaitDependency externalResource(long runId, long resourceId,
                                                       long resourceVersion) {
            return new WaitDependency(runId, DependencyKind.EXTERNAL_RESOURCE,
                    resourceId, resourceVersion);
        }

        public long getRunId() {
            return runId;
        }

        public DependencyKind getKind() {
            return kind;
        }

        public long getPrimaryId() {
            return primaryId;
        }

        public long getQualifier() {
            return qualifier;
        }

        WaitNodeId asInvocationNode() {
            return kind == DependencyKind.INVOCATION
                    ? new WaitNodeId(runId, primaryId, qualifier) : null;
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof WaitDependency)) {
                return false;
            }
            WaitDependency that = (WaitDependency) other;
            return runId == that.runId && kind == that.kind
                    && primaryId == that.primaryId && qualifier == that.qualifier;
        }

        @Override
        public int hashCode() {
            int result = Long.hashCode(runId);
            result = 31 * result + kind.hashCode();
            result = 31 * result + Long.hashCode(primaryId);
            return 31 * result + Long.hashCode(qualifier);
        }
    }

    public static final class WaitEdgeDraft {
        private final WaitNodeId waiter;
        private final WaitDependency dependency;
        private final WaitReason reason;

        public WaitEdgeDraft(WaitNodeId waiter, WaitDependency dependency,
                             WaitReason reason) {
            if (waiter == null || dependency == null || reason == null) {
                throw new NullPointerException("wait edge fields must not be null");
            }
            this.waiter = waiter;
            this.dependency = dependency;
            this.reason = reason;
        }

        public WaitNodeId getWaiter() {
            return waiter;
        }

        public WaitDependency getDependency() {
            return dependency;
        }

        public WaitReason getReason() {
            return reason;
        }
    }

    public static final class WaitEdge {
        private final WaitNodeId waiter;
        private final WaitDependency dependency;
        private final WaitReason reason;
        private final long graphEpoch;

        private WaitEdge(WaitEdgeDraft draft, long graphEpoch) {
            this.waiter = draft.waiter;
            this.dependency = draft.dependency;
            this.reason = draft.reason;
            this.graphEpoch = graphEpoch;
        }

        public WaitNodeId getWaiter() {
            return waiter;
        }

        public WaitDependency getDependency() {
            return dependency;
        }

        public WaitReason getReason() {
            return reason;
        }

        public long getGraphEpoch() {
            return graphEpoch;
        }
    }

    public static final class WaiterSnapshot {
        private final long graphEpoch;
        private final Set<WaitNodeId> dependents;
        private final List<WaitEdge> edges;

        WaiterSnapshot(long graphEpoch, Set<WaitNodeId> dependents,
                       List<WaitEdge> edges) {
            this.graphEpoch = graphEpoch;
            this.dependents = Collections.unmodifiableSet(
                    new LinkedHashSet<>(dependents));
            this.edges = Collections.unmodifiableList(new ArrayList<>(edges));
        }

        public long getGraphEpoch() {
            return graphEpoch;
        }

        public Set<WaitNodeId> getDependents() {
            return dependents;
        }

        public List<WaitEdge> getEdges() {
            return edges;
        }
    }

    public static final class DependencyCycleException extends IllegalStateException {
        private static final long serialVersionUID = 1L;

        DependencyCycleException() {
            super("wait graph dependency cycle");
        }
    }

    private final long runId;
    private final Map<WaitNodeId, List<WaitEdge>> outgoing = new LinkedHashMap<>();
    private long graphEpoch;

    RunWaitGraph(long runId) {
        this.runId = runId;
    }

    public synchronized long getGraphEpoch() {
        return graphEpoch;
    }

    public synchronized List<WaitEdge> addBatch(List<WaitEdgeDraft> drafts) {
        if (drafts == null || drafts.isEmpty()) {
            return Collections.emptyList();
        }
        validateDrafts(drafts);
        Map<WaitNodeId, Set<WaitNodeId>> adjacency = invocationAdjacency();
        for (WaitEdgeDraft draft : drafts) {
            WaitNodeId dependency = draft.dependency.asInvocationNode();
            if (dependency != null) {
                adjacency.computeIfAbsent(draft.waiter, ignored -> new LinkedHashSet<>())
                        .add(dependency);
            }
        }
        if (containsCycle(adjacency)) {
            throw new DependencyCycleException();
        }
        long epoch = ++graphEpoch;
        List<WaitEdge> published = new ArrayList<>(drafts.size());
        for (WaitEdgeDraft draft : drafts) {
            WaitEdge edge = new WaitEdge(draft, epoch);
            outgoing.computeIfAbsent(draft.waiter, ignored -> new ArrayList<>()).add(edge);
            published.add(edge);
        }
        return Collections.unmodifiableList(published);
    }

    public synchronized void removeOutgoing(WaitNodeId waiter) {
        if (outgoing.remove(waiter) != null) {
            graphEpoch++;
        }
    }

    public synchronized void removeForTerminal(WaitNodeId terminal) {
        boolean changed = outgoing.remove(terminal) != null;
        for (Iterator<Map.Entry<WaitNodeId, List<WaitEdge>>> entries =
             outgoing.entrySet().iterator(); entries.hasNext(); ) {
            List<WaitEdge> edges = entries.next().getValue();
            for (int i = edges.size() - 1; i >= 0; i--) {
                if (terminal.equals(edges.get(i).dependency.asInvocationNode())) {
                    edges.remove(i);
                    changed = true;
                }
            }
            if (edges.isEmpty()) {
                entries.remove();
            }
        }
        if (changed) {
            graphEpoch++;
        }
    }

    public synchronized WaiterSnapshot snapshotDependents(WaitNodeId root) {
        Set<WaitNodeId> dependents = new LinkedHashSet<>();
        List<WaitEdge> evidence = new ArrayList<>();
        Deque<WaitNodeId> queue = new ArrayDeque<>();
        queue.add(root);
        while (!queue.isEmpty()) {
            WaitNodeId dependency = queue.removeFirst();
            for (List<WaitEdge> edges : outgoing.values()) {
                for (WaitEdge edge : edges) {
                    if (dependency.equals(edge.dependency.asInvocationNode())
                            && dependents.add(edge.waiter)) {
                        evidence.add(edge);
                        queue.addLast(edge.waiter);
                    }
                }
            }
        }
        dependents.remove(root);
        return new WaiterSnapshot(graphEpoch, dependents, evidence);
    }

    public synchronized List<WaitEdge> getEdgesSnapshot() {
        List<WaitEdge> snapshot = new ArrayList<>();
        for (List<WaitEdge> edges : outgoing.values()) {
            snapshot.addAll(edges);
        }
        return Collections.unmodifiableList(snapshot);
    }

    synchronized void clear() {
        if (!outgoing.isEmpty()) {
            outgoing.clear();
            graphEpoch++;
        }
    }

    private void validateDrafts(List<WaitEdgeDraft> drafts) {
        for (int i = 0; i < drafts.size(); i++) {
            WaitEdgeDraft draft = drafts.get(i);
            if (draft == null || draft.waiter.runId != runId
                    || draft.dependency.runId != runId) {
                throw new IllegalArgumentException("wait edge crosses run boundary");
            }
            if (containsEquivalent(draft)) {
                throw new IllegalArgumentException("duplicate wait edge");
            }
            for (int j = 0; j < i; j++) {
                WaitEdgeDraft previous = drafts.get(j);
                if (previous.waiter.equals(draft.waiter)
                        && previous.dependency.equals(draft.dependency)
                        && previous.reason == draft.reason) {
                    throw new IllegalArgumentException("duplicate wait edge");
                }
            }
        }
    }

    private boolean containsEquivalent(WaitEdgeDraft draft) {
        List<WaitEdge> edges = outgoing.get(draft.waiter);
        if (edges == null) {
            return false;
        }
        for (WaitEdge edge : edges) {
            if (edge.dependency.equals(draft.dependency) && edge.reason == draft.reason) {
                return true;
            }
        }
        return false;
    }

    private Map<WaitNodeId, Set<WaitNodeId>> invocationAdjacency() {
        Map<WaitNodeId, Set<WaitNodeId>> adjacency = new HashMap<>();
        for (Map.Entry<WaitNodeId, List<WaitEdge>> entry : outgoing.entrySet()) {
            for (WaitEdge edge : entry.getValue()) {
                WaitNodeId dependency = edge.dependency.asInvocationNode();
                if (dependency != null) {
                    adjacency.computeIfAbsent(entry.getKey(), ignored -> new LinkedHashSet<>())
                            .add(dependency);
                }
            }
        }
        return adjacency;
    }

    private static boolean containsCycle(Map<WaitNodeId, Set<WaitNodeId>> adjacency) {
        Set<WaitNodeId> visiting = new HashSet<>();
        Set<WaitNodeId> visited = new HashSet<>();
        for (WaitNodeId node : adjacency.keySet()) {
            if (hasCycle(node, adjacency, visiting, visited)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasCycle(WaitNodeId node,
                                    Map<WaitNodeId, Set<WaitNodeId>> adjacency,
                                    Set<WaitNodeId> visiting,
                                    Set<WaitNodeId> visited) {
        if (visiting.contains(node)) {
            return true;
        }
        if (!visited.add(node)) {
            return false;
        }
        visiting.add(node);
        Set<WaitNodeId> dependencies = adjacency.get(node);
        if (dependencies != null) {
            for (WaitNodeId dependency : dependencies) {
                if (hasCycle(dependency, adjacency, visiting, visited)) {
                    return true;
                }
            }
        }
        visiting.remove(node);
        return false;
    }
}
