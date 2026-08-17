package com.github.unidbg.thread;

import java.util.concurrent.atomic.AtomicLong;

/** Immutable logical invocation frame independent of the temporary carrier. */
public final class InvocationContinuation {

    private static final AtomicLong NEXT_ID = new AtomicLong();

    private final long continuationId;
    private final long invocationId;
    private final long generation;
    private final GuestThreadIncarnation guestThread;
    private final long bindingEpoch;
    private final int stackDepth;
    private final long contextEpoch;

    InvocationContinuation(long invocationId, long generation,
                           GuestThreadIncarnation guestThread,
                           long bindingEpoch, int stackDepth, long contextEpoch) {
        this.continuationId = NEXT_ID.incrementAndGet();
        this.invocationId = invocationId;
        this.generation = generation;
        this.guestThread = guestThread;
        this.bindingEpoch = bindingEpoch;
        this.stackDepth = stackDepth;
        this.contextEpoch = contextEpoch;
    }

    public long getContinuationId() {
        return continuationId;
    }

    public long getInvocationId() {
        return invocationId;
    }

    public long getGeneration() {
        return generation;
    }

    public GuestThreadIncarnation getGuestThread() {
        return guestThread;
    }

    public long getBindingEpoch() {
        return bindingEpoch;
    }

    public int getStackDepth() {
        return stackDepth;
    }

    public long getContextEpoch() {
        return contextEpoch;
    }
}
