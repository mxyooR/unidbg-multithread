package com.github.unidbg.thread;

import com.github.unidbg.memory.MemoryBlock;
import com.github.unidbg.pointer.UnidbgPointer;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Immutable physical evidence connecting a carrier entry SP to a real backend
 * stack allocation. Address-only callers cannot construct this evidence.
 */
public final class TaskStackEvidence {

    static final int CANARY_SIZE = 8;

    private static final AtomicLong NEXT_ALLOCATION_SEQUENCE = new AtomicLong();
    private static final long CANARY_SEED = 0x554e494442475354L;

    private final MemoryBlock backendAllocationIdentity;
    private final StackRegion region;
    private final long entrySp;
    private final long canaryReadBack;
    private final long allocationSequence;

    private TaskStackEvidence(MemoryBlock backendAllocationIdentity,
                              StackRegion region, long entrySp,
                              long canaryReadBack, long allocationSequence) {
        this.backendAllocationIdentity = backendAllocationIdentity;
        this.region = region;
        this.entrySp = entrySp;
        this.canaryReadBack = canaryReadBack;
        this.allocationSequence = allocationSequence;
    }

    static long nextAllocationSequence() {
        return NEXT_ALLOCATION_SEQUENCE.incrementAndGet();
    }

    static long canaryFor(long allocationSequence) {
        long value = CANARY_SEED
                ^ Long.rotateLeft(allocationSequence * 0x9e3779b97f4a7c15L, 17);
        return value == 0L ? CANARY_SEED : value;
    }

    static StackRegion initializeRegion(MemoryBlock allocation, int stackSize,
                                        long allocationSequence) {
        if (allocation == null) {
            throw new NullPointerException("allocation");
        }
        if (stackSize <= 0 || allocationSequence <= 0L) {
            throw new IllegalArgumentException("invalid stack allocation evidence");
        }
        UnidbgPointer pointer = allocation.getPointer();
        if (pointer == null || pointer.peer <= 0L) {
            throw new IllegalArgumentException("backend allocation has no address");
        }
        long canary = canaryFor(allocationSequence);
        pointer.setLong(0, canary);
        long observed = pointer.getLong(0);
        StackRegion region = new StackRegion(pointer.peer + CANARY_SIZE,
                pointer.peer + CANARY_SIZE + stackSize, pointer.peer, canary);
        region.requireCanary(observed);
        return region;
    }

    static TaskStackEvidence fromBackendAllocation(
            MemoryBlock allocation, StackRegion region, long entrySp,
            long allocationSequence) {
        if (allocation == null || region == null) {
            throw new NullPointerException("allocation and region");
        }
        UnidbgPointer pointer = allocation.getPointer();
        long expectedLower = pointer.peer + CANARY_SIZE;
        if (!region.matches(expectedLower, region.getTop(), pointer.peer,
                canaryFor(allocationSequence))) {
            throw new IllegalArgumentException("stack region does not match backend allocation");
        }
        long observed = pointer.getLong(0);
        region.requireCanary(observed);
        region.requireContains(entrySp);
        return new TaskStackEvidence(allocation, region, entrySp, observed,
                allocationSequence);
    }

    public Object getBackendAllocationIdentity() {
        return backendAllocationIdentity;
    }

    public StackRegion getRegion() {
        return region;
    }

    public long getEntrySp() {
        return entrySp;
    }

    public long getCanaryReadBack() {
        return canaryReadBack;
    }

    public long getAllocationSequence() {
        return allocationSequence;
    }

    public long readCanaryFromBackend() {
        return backendAllocationIdentity.getPointer().getLong(0);
    }

    public void requireCanaryIntact() {
        region.requireCanary(readCanaryFromBackend());
    }

    public void requireStackPointer(long stackPointer) {
        requireCanaryIntact();
        region.requireContains(stackPointer);
    }
}
