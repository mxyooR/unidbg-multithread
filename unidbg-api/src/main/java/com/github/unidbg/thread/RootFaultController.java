package com.github.unidbg.thread;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Freezes a run before publishing root-fault and dependent-wait evidence. */
public final class RootFaultController {

    public enum FaultKind {
        UNHANDLED_GUEST_FAULT,
        RUNTIME_INTEGRITY_FAULT,
        DEPENDENCY_CYCLE,
        HOST_WATCHDOG_EXPIRED
    }

    public static final class RootFaultPublication {
        private final long runId;
        private final RunWaitGraph.WaitNodeId rootInvocation;
        private final long guestThreadIncarnationId;
        private final FaultKind kind;
        private final Throwable cause;
        private final long sequence;
        private final RunWaitGraph.WaiterSnapshot dependentSnapshot;

        private RootFaultPublication(long runId,
                                     RunWaitGraph.WaitNodeId rootInvocation,
                                     long guestThreadIncarnationId,
                                     FaultKind kind, Throwable cause, long sequence,
                                     RunWaitGraph.WaiterSnapshot dependentSnapshot) {
            this.runId = runId;
            this.rootInvocation = rootInvocation;
            this.guestThreadIncarnationId = guestThreadIncarnationId;
            this.kind = kind;
            this.cause = cause;
            this.sequence = sequence;
            this.dependentSnapshot = dependentSnapshot;
        }

        public long getRunId() {
            return runId;
        }

        public RunWaitGraph.WaitNodeId getRootInvocation() {
            return rootInvocation;
        }

        public long getGuestThreadIncarnationId() {
            return guestThreadIncarnationId;
        }

        public FaultKind getKind() {
            return kind;
        }

        public Throwable getCause() {
            return cause;
        }

        public long getSequence() {
            return sequence;
        }

        public RunWaitGraph.WaiterSnapshot getDependentSnapshot() {
            return dependentSnapshot;
        }
    }

    private final RunContext runContext;
    private final RunWaitGraph waitGraph;
    private final List<RootFaultPublication> ledger = new ArrayList<>();
    private long nextSequence;

    RootFaultController(RunContext runContext, RunWaitGraph waitGraph) {
        this.runContext = runContext;
        this.waitGraph = waitGraph;
    }

    public synchronized RootFaultPublication publishRoot(
            InvocationRecord root, FaultKind kind, Throwable cause) {
        if (kind == null) {
            throw new NullPointerException("kind");
        }
        if (cause == null) {
            throw new NullPointerException("cause");
        }
        if (root != null && root.getRunContext() != runContext) {
            throw new IllegalArgumentException("root invocation belongs to another run");
        }
        runContext.quarantine(cause);
        RunWaitGraph.WaitNodeId rootNode = root == null
                ? null : RunWaitGraph.WaitNodeId.from(root);
        RunWaitGraph.WaiterSnapshot snapshot = rootNode == null
                ? new RunWaitGraph.WaiterSnapshot(waitGraph.getGraphEpoch(),
                Collections.<RunWaitGraph.WaitNodeId>emptySet(),
                Collections.<RunWaitGraph.WaitEdge>emptyList())
                : waitGraph.snapshotDependents(rootNode);
        RootFaultPublication publication = new RootFaultPublication(
                runContext.getRunId(), rootNode,
                root == null || root.getGuestThread() == null
                        ? 0L : root.getGuestThread().getIncarnationId(),
                kind, cause, ++nextSequence, snapshot);
        ledger.add(publication);
        return publication;
    }

    public synchronized List<RootFaultPublication> getLedgerSnapshot() {
        return Collections.unmodifiableList(new ArrayList<>(ledger));
    }
}
