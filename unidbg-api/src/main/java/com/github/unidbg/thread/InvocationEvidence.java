package com.github.unidbg.thread;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Immutable run-ledger evidence for one terminal invocation. */
public final class InvocationEvidence {

    private final long runId;
    private final long guestThreadIncarnationId;
    private final long invocationId;
    private final long generation;
    private final InvocationResult terminal;
    private final List<AdmissionReceipt> admissions;
    private final List<CarrierRetirementReceipt> retirements;
    private final long recordedAtNanos;

    InvocationEvidence(InvocationRecord invocation) {
        this.runId = invocation.getRunContext().getRunId();
        this.guestThreadIncarnationId = invocation.getGuestThread() == null
                ? 0L : invocation.getGuestThread().getIncarnationId();
        this.invocationId = invocation.getInvocationId();
        this.generation = invocation.getGeneration();
        this.terminal = invocation.getTerminal();
        this.admissions = Collections.unmodifiableList(
                new ArrayList<>(invocation.getAdmissionReceipts()));
        this.retirements = Collections.unmodifiableList(
                new ArrayList<>(invocation.getRetirementReceipts()));
        this.recordedAtNanos = System.nanoTime();
    }

    public long getRunId() {
        return runId;
    }

    public long getGuestThreadIncarnationId() {
        return guestThreadIncarnationId;
    }

    public long getInvocationId() {
        return invocationId;
    }

    public long getGeneration() {
        return generation;
    }

    public InvocationResult getTerminal() {
        return terminal;
    }

    public List<AdmissionReceipt> getAdmissions() {
        return admissions;
    }

    public List<CarrierRetirementReceipt> getRetirements() {
        return retirements;
    }

    public long getRecordedAtNanos() {
        return recordedAtNanos;
    }
}
