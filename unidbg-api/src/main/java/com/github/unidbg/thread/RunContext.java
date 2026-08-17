package com.github.unidbg.thread;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Lifecycle owner for one emulator run. It contains only runtime identity and
 * ownership state; application policy belongs outside this class.
 */
public final class RunContext implements AutoCloseable {

    public enum State {
        ACTIVE,
        QUARANTINED,
        DISPOSED
    }

    private static final AtomicLong NEXT_RUN_ID = new AtomicLong();

    private final long runId;
    private final Map<Long, GuestThreadIncarnation> threads = new LinkedHashMap<>();
    private final Map<Long, InvocationRecord> activeInvocations = new LinkedHashMap<>();
    private final List<InvocationEvidence> terminalLedger = new ArrayList<>();
    private final RunWaitGraph waitGraph;
    private final RootFaultController rootFaultController;
    private long nextThreadSerial;
    private long nextCarrierLeaseId;
    private int nextSyntheticTid = 0x10000;
    private CarrierLease activeLease;
    private State state = State.ACTIVE;
    private Throwable quarantineCause;

    public RunContext() {
        this(NEXT_RUN_ID.incrementAndGet());
    }

    private RunContext(long runId) {
        if (runId <= 0L) {
            throw new IllegalArgumentException("runId must be positive");
        }
        this.runId = runId;
        this.waitGraph = new RunWaitGraph(runId);
        this.rootFaultController = new RootFaultController(this, waitGraph);
    }

    static RunContext detached() {
        return new RunContext(NEXT_RUN_ID.incrementAndGet());
    }

    public synchronized long getRunId() {
        return runId;
    }

    public synchronized State getState() {
        return state;
    }

    public synchronized Throwable getQuarantineCause() {
        return quarantineCause;
    }

    public synchronized boolean acceptsAdmission() {
        return state == State.ACTIVE && activeLease == null;
    }

    public RunWaitGraph getWaitGraph() {
        return waitGraph;
    }

    public RootFaultController getRootFaultController() {
        return rootFaultController;
    }

    synchronized void registerInvocation(InvocationRecord invocation) {
        ensureActive();
        if (invocation == null || invocation.getRunContext() != this
                || activeInvocations.containsKey(invocation.getInvocationId())) {
            throw new IllegalArgumentException("invalid or duplicate invocation");
        }
        activeInvocations.put(invocation.getInvocationId(), invocation);
    }

    synchronized void recordTerminal(InvocationRecord invocation) {
        if (invocation == null || invocation.getRunContext() != this
                || invocation.getTerminal() == null || !invocation.isCarrierRetired()) {
            throw new IllegalArgumentException("invocation lacks terminal retirement evidence");
        }
        if (activeInvocations.remove(invocation.getInvocationId()) != invocation) {
            throw new IllegalStateException("invocation is not active in this run");
        }
        waitGraph.removeForTerminal(RunWaitGraph.WaitNodeId.from(invocation));
        terminalLedger.add(new InvocationEvidence(invocation));
    }

    public synchronized List<InvocationRecord> getActiveInvocationsSnapshot() {
        return Collections.unmodifiableList(new ArrayList<>(activeInvocations.values()));
    }

    public synchronized List<InvocationEvidence> getTerminalEvidenceSnapshot() {
        return Collections.unmodifiableList(new ArrayList<>(terminalLedger));
    }

    public synchronized GuestThreadIncarnation registerGuestThread(int guestTid,
                                                                      String birthReason) {
        ensureActive();
        int actualTid = guestTid;
        if (actualTid <= 0 || isActiveTid(actualTid)) {
            do {
                actualTid = nextSyntheticTid++;
            } while (isActiveTid(actualTid));
        }
        long incarnationId = ++nextThreadSerial;
        GuestThreadIncarnation thread = new GuestThreadIncarnation(
                this, incarnationId, actualTid, birthReason);
        threads.put(incarnationId, thread);
        return thread;
    }

    private boolean isActiveTid(int guestTid) {
        for (GuestThreadIncarnation thread : threads.values()) {
            if (!thread.isRetired() && thread.getGuestTid() == guestTid) {
                return true;
            }
        }
        return false;
    }

    synchronized void retireGuestThread(GuestThreadIncarnation thread) {
        if (thread == null || thread.getRunContext() != this) {
            throw new IllegalArgumentException("guest thread does not belong to this run");
        }
        thread.retire();
    }

    public synchronized List<GuestThreadIncarnation> getGuestThreadsSnapshot() {
        return Collections.unmodifiableList(new ArrayList<>(threads.values()));
    }

    synchronized AdmissionReceipt admitCarrier(InvocationRecord invocation,
                                                TaskThreadBinding binding) {
        ensureActive();
        if (invocation == null || binding == null
                || binding.getRunContext() != this
                || binding.getGuestThread().getRunContext() != this
                || !binding.isActive()) {
            throw new IllegalArgumentException("invalid carrier binding");
        }
        if (activeLease != null && !activeLease.isRetired()) {
            throw new IllegalStateException("backend carrier lease is already active");
        }
        CarrierLease lease = new CarrierLease(
                new CarrierLeaseId(runId, ++nextCarrierLeaseId), this,
                invocation.getInvocationId(), invocation.getGeneration(), binding);
        activeLease = lease;
        return new AdmissionReceipt(lease, invocation, binding);
    }

    synchronized CarrierRetirementReceipt retireCarrier(AdmissionReceipt admission) {
        if (admission == null || admission.getLease().getRunContext() != this) {
            throw new IllegalArgumentException("admission does not belong to this run");
        }
        CarrierLease lease = admission.getLease();
        if (activeLease != lease || lease.isRetired()) {
            throw new IllegalStateException("carrier lease is not active");
        }
        lease.retire();
        activeLease = null;
        return new CarrierRetirementReceipt(admission);
    }

    public synchronized CarrierLease getActiveCarrierLease() {
        return activeLease;
    }

    public synchronized void quarantine(Throwable cause) {
        if (state == State.DISPOSED) {
            return;
        }
        state = State.QUARANTINED;
        quarantineCause = cause;
    }

    @Override
    public synchronized void close() {
        if (state == State.DISPOSED) {
            return;
        }
        if (activeLease != null) {
            activeLease.retire();
            activeLease = null;
        }
        for (GuestThreadIncarnation thread : threads.values()) {
            thread.retire();
        }
        activeInvocations.clear();
        waitGraph.clear();
        state = State.DISPOSED;
    }

    private void ensureActive() {
        if (state != State.ACTIVE) {
            throw new IllegalStateException("run context is " + state);
        }
    }
}
