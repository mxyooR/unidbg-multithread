package com.github.unidbg.thread;

/**
 * Guest-thread-owned logical bounds for one downward-growing native stack.
 * The canary lives immediately below {@link #getLowerBound()}.
 */
public final class StackRegion {

    private final long lowerBound;
    private final long top;
    private final long canaryAddress;
    private final long expectedCanary;
    private long highWaterMark;
    private boolean faulted;

    StackRegion(long lowerBound, long top, long canaryAddress,
                long expectedCanary) {
        if (lowerBound <= 0L || top <= lowerBound) {
            throw new IllegalArgumentException("invalid stack range");
        }
        if (canaryAddress <= 0L || canaryAddress >= lowerBound) {
            throw new IllegalArgumentException("invalid stack canary address");
        }
        if (expectedCanary == 0L) {
            throw new IllegalArgumentException("stack canary must be non-zero");
        }
        this.lowerBound = lowerBound;
        this.top = top;
        this.canaryAddress = canaryAddress;
        this.expectedCanary = expectedCanary;
        this.highWaterMark = top;
    }

    public long getLowerBound() {
        return lowerBound;
    }

    public long getTop() {
        return top;
    }

    public long getCanaryAddress() {
        return canaryAddress;
    }

    public long getExpectedCanary() {
        return expectedCanary;
    }

    public synchronized long getHighWaterMark() {
        return highWaterMark;
    }

    public synchronized boolean isFaulted() {
        return faulted;
    }

    public boolean contains(long stackPointer) {
        return stackPointer >= lowerBound && stackPointer <= top;
    }

    public boolean overlaps(StackRegion other) {
        return other != null
                && lowerBound < other.top
                && other.lowerBound < top;
    }

    public synchronized void requireContains(long stackPointer) {
        if (faulted || !contains(stackPointer)) {
            faulted = true;
            throw new StackIntegrityException(
                    "stack pointer is outside its guest-thread region");
        }
        if (stackPointer < highWaterMark) {
            highWaterMark = stackPointer;
        }
    }

    public synchronized void requireCanary(long observedCanary) {
        if (faulted || observedCanary != expectedCanary) {
            faulted = true;
            throw new StackIntegrityException("guest-thread stack canary mismatch");
        }
    }

    public synchronized void requireDisjoint(StackRegion other) {
        if (other == null) {
            throw new NullPointerException("other");
        }
        if (overlaps(other)) {
            faulted = true;
            throw new StackIntegrityException("guest-thread stack regions overlap");
        }
    }

    synchronized boolean matches(long lower, long stackTop,
                                 long guardAddress, long canary) {
        return lowerBound == lower && top == stackTop
                && canaryAddress == guardAddress
                && expectedCanary == canary;
    }
}
