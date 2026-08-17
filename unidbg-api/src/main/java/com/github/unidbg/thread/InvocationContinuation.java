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
    private final ReentryMode reentryMode;
    private final long parentContinuationId;

    InvocationContinuation(long invocationId, long generation,
                           GuestThreadIncarnation guestThread,
                           long bindingEpoch, int stackDepth, long contextEpoch) {
        this(invocationId, generation, guestThread, bindingEpoch, stackDepth,
                contextEpoch, ReentryMode.FRESH_ASYNC, 0L);
    }

    private InvocationContinuation(long invocationId, long generation,
                                   GuestThreadIncarnation guestThread,
                                   long bindingEpoch, int stackDepth, long contextEpoch,
                                   ReentryMode reentryMode, long parentContinuationId) {
        if (invocationId <= 0L || generation <= 0L || guestThread == null
                || bindingEpoch <= 0L || stackDepth <= 0 || contextEpoch <= 0L
                || reentryMode == null || parentContinuationId < 0L) {
            throw new IllegalArgumentException("invalid continuation identity");
        }
        this.continuationId = NEXT_ID.incrementAndGet();
        this.invocationId = invocationId;
        this.generation = generation;
        this.guestThread = guestThread;
        this.bindingEpoch = bindingEpoch;
        this.stackDepth = stackDepth;
        this.contextEpoch = contextEpoch;
        this.reentryMode = reentryMode;
        this.parentContinuationId = parentContinuationId;
    }

    static InvocationContinuation synchronousChild(long invocationId, long generation,
                                                    InvocationContinuation parent,
                                                    long contextEpoch) {
        if (parent == null) {
            throw new NullPointerException("parent");
        }
        int childDepth = parent.stackDepth == Integer.MAX_VALUE
                ? Integer.MAX_VALUE : parent.stackDepth + 1;
        return new InvocationContinuation(invocationId, generation, parent.guestThread,
                parent.bindingEpoch, childDepth, contextEpoch,
                ReentryMode.SAME_THREAD_SYNCHRONOUS, parent.continuationId);
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

    public ReentryMode getReentryMode() {
        return reentryMode;
    }

    public long getParentContinuationId() {
        return parentContinuationId;
    }
}
