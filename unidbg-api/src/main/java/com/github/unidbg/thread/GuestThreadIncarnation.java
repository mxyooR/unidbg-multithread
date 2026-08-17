package com.github.unidbg.thread;

import com.github.unidbg.Emulator;
import com.github.unidbg.memory.MemoryBlock;

/**
 * Stable identity for one guest thread incarnation. A host Java thread is not
 * used as guest identity because a carrier may move between host threads.
 */
public final class GuestThreadIncarnation {

    private final RunContext runContext;
    private final long incarnationId;
    private final int guestTid;
    private final String birthReason;
    private final GuestThreadExecutionState executionState = new GuestThreadExecutionState();
    private final InvocationStack invocationStack = new InvocationStack();
    private TaskThreadBinding activeBinding;
    private MemoryBlock stackAllocation;
    private Emulator<?> stackEmulator;
    private StackRegion stackRegion;
    private long stackAllocationSequence;
    private boolean retired;

    GuestThreadIncarnation(RunContext runContext, long incarnationId, int guestTid,
                           String birthReason) {
        this.runContext = runContext;
        this.incarnationId = incarnationId;
        this.guestTid = guestTid;
        this.birthReason = birthReason == null ? "unspecified" : birthReason;
    }

    public RunContext getRunContext() {
        return runContext;
    }

    public long getIncarnationId() {
        return incarnationId;
    }

    public int getGuestTid() {
        return guestTid;
    }

    public String getBirthReason() {
        return birthReason;
    }

    public GuestThreadExecutionState getExecutionState() {
        return executionState;
    }

    public InvocationStack getInvocationStack() {
        return invocationStack;
    }

    /** Logical stack bounds owned by this guest thread after first entry. */
    public synchronized StackRegion getStackRegion() {
        return stackRegion;
    }

    synchronized MemoryBlock acquireStackAllocation(Emulator<?> emulator,
                                                    int stackSize) {
        if (emulator == null || retired) {
            throw new IllegalStateException("guest thread stack is not available");
        }
        if (stackAllocation != null) {
            if (stackEmulator != emulator) {
                throw new IllegalStateException("guest thread stack belongs to another emulator");
            }
            if (stackRegion.getTop() - stackRegion.getLowerBound() != stackSize) {
                throw new IllegalStateException("guest thread stack size changed");
            }
            stackRegion.requireCanary(stackAllocation.getPointer().getLong(0));
            return stackAllocation;
        }

        long sequence = TaskStackEvidence.nextAllocationSequence();
        MemoryBlock allocation = emulator.getMemory().malloc(
                stackSize + TaskStackEvidence.CANARY_SIZE, true);
        boolean installed = false;
        try {
            StackRegion region = TaskStackEvidence.initializeRegion(
                    allocation, stackSize, sequence);
            stackAllocation = allocation;
            stackEmulator = emulator;
            stackRegion = region;
            stackAllocationSequence = sequence;
            installed = true;
            return allocation;
        } finally {
            if (!installed) {
                allocation.free();
            }
        }
    }

    synchronized long getStackAllocationSequence() {
        if (stackAllocation == null) {
            throw new IllegalStateException("guest thread stack is not allocated");
        }
        return stackAllocationSequence;
    }

    public synchronized TaskThreadBinding bind(Task task) {
        return bind(task, TaskThreadBinding.BindingKind.PERSISTENT_GUEST_THREAD);
    }

    /**
     * Binds a short-lived carrier without changing the lifetime of this guest
     * thread incarnation. The dispatcher uses this for ordinary invocation
     * tasks.
     */
    public synchronized TaskThreadBinding bindCarrier(Task task) {
        return bind(task, TaskThreadBinding.BindingKind.TRANSIENT_CARRIER);
    }

    private synchronized TaskThreadBinding bind(Task task,
                                                TaskThreadBinding.BindingKind kind) {
        if (task == null || retired) {
            throw new IllegalStateException("guest thread is not bindable");
        }
        if (activeBinding != null && activeBinding.isActive()) {
            if (activeBinding.getTask() != task) {
                throw new IllegalStateException("guest thread is already bound to another task");
            }
            if (activeBinding.getKind() != kind) {
                throw new IllegalStateException("guest thread binding kind cannot change while active");
            }
            attachToTask(task, activeBinding);
            return activeBinding;
        }
        long epoch = activeBinding == null ? 1L : activeBinding.getEpoch() + 1L;
        TaskThreadBinding candidate = new TaskThreadBinding(this, task, epoch, kind);
        attachToTask(task, candidate);
        activeBinding = candidate;
        return candidate;
    }

    private static void attachToTask(Task task, TaskThreadBinding binding) {
        if (task instanceof AbstractTask) {
            ((AbstractTask) task).attachThreadBinding(binding);
        }
    }

    public synchronized TaskThreadBinding getActiveBinding() {
        return activeBinding != null && activeBinding.isActive() ? activeBinding : null;
    }

    synchronized void retire() {
        if (retired) {
            return;
        }
        retired = true;
        if (activeBinding != null) {
            activeBinding.detach();
        }
        executionState.beginRetirement();
        executionState.retire();
        invocationStack.clear();
        if (stackAllocation != null) {
            stackAllocation.free();
            stackAllocation = null;
            stackEmulator = null;
        }
    }

    public synchronized boolean isRetired() {
        return retired;
    }
}
