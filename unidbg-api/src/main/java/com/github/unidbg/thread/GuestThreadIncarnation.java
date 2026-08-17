package com.github.unidbg.thread;

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

    public synchronized TaskThreadBinding bind(Task task) {
        if (task == null || retired) {
            throw new IllegalStateException("guest thread is not bindable");
        }
        if (activeBinding != null && activeBinding.isActive()) {
            if (activeBinding.getTask() != task) {
                throw new IllegalStateException("guest thread is already bound to another task");
            }
            return activeBinding;
        }
        long epoch = activeBinding == null ? 1L : activeBinding.getEpoch() + 1L;
        activeBinding = new TaskThreadBinding(this, task, epoch);
        return activeBinding;
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
    }

    public synchronized boolean isRetired() {
        return retired;
    }
}
