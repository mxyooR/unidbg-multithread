package com.github.unidbg.thread;

/** Non-owning link between a carrier task and a guest thread incarnation. */
public final class TaskThreadBinding {

    /** Describes whether a binding represents a persistent guest thread or a carrier. */
    public enum BindingKind {
        /** A short-lived dispatcher carrier created for one invocation/task. */
        TRANSIENT_CARRIER,
        /** An explicit guest-thread binding that may be reused across invocations. */
        PERSISTENT_GUEST_THREAD
    }

    private static long nextBindingId;

    private final long bindingId;
    private final GuestThreadIncarnation guestThread;
    private final Task task;
    private final long epoch;
    private final BindingKind kind;
    private volatile boolean active = true;

    TaskThreadBinding(GuestThreadIncarnation guestThread, Task task, long epoch,
                      BindingKind kind) {
        synchronized (TaskThreadBinding.class) {
            bindingId = ++nextBindingId;
        }
        this.guestThread = guestThread;
        this.task = task;
        this.epoch = epoch;
        this.kind = kind == null ? BindingKind.TRANSIENT_CARRIER : kind;
    }

    public long getBindingId() {
        return bindingId;
    }

    public GuestThreadIncarnation getGuestThread() {
        return guestThread;
    }

    public RunContext getRunContext() {
        return guestThread.getRunContext();
    }

    public Task getTask() {
        return task;
    }

    public long getEpoch() {
        return epoch;
    }

    public BindingKind getKind() {
        return kind;
    }

    public boolean isPersistentGuestThread() {
        return kind == BindingKind.PERSISTENT_GUEST_THREAD;
    }

    public boolean isActive() {
        return active && !guestThread.isRetired();
    }

    void detach() {
        active = false;
    }
}
