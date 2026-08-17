package com.github.unidbg.thread;

/** Immutable identity for one temporary backend driving lease. */
public final class CarrierLeaseId {

    private final long runId;
    private final long sequence;

    CarrierLeaseId(long runId, long sequence) {
        this.runId = runId;
        this.sequence = sequence;
    }

    public long getRunId() {
        return runId;
    }

    public long getSequence() {
        return sequence;
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof CarrierLeaseId)) {
            return false;
        }
        CarrierLeaseId that = (CarrierLeaseId) other;
        return runId == that.runId && sequence == that.sequence;
    }

    @Override
    public int hashCode() {
        return 31 * Long.hashCode(runId) + Long.hashCode(sequence);
    }

    @Override
    public String toString() {
        return runId + ":" + sequence;
    }
}
